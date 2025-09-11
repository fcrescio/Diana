package li.crescio.penates.diana.persistence

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.llm.Appointment
import li.crescio.penates.diana.llm.Thought
import li.crescio.penates.diana.notes.StructuredNote
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.io.path.createTempFile

class NoteRepositoryTest {

    @Test
    fun saveNotes_writesJsonLines_andAddsToFirestore() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()
        val firestore = mockk<FirebaseFirestore>()
        val collection = mockk<CollectionReference>()
        val document = mockk<DocumentReference>()

        val captured = mutableListOf<Map<String, Any>>()
        every { firestore.collection("notes") } returns collection
        every { collection.add(any()) } answers {
            captured.add(firstArg())
            Tasks.forResult(document)
        }

        val repo = NoteRepository(firestore, file)
        val notes = listOf(
            StructuredNote.ToDo("task", status = "open", tags = listOf("home"), createdAt = 1L),
            StructuredNote.Memo("memo", tags = listOf("x"), createdAt = 2L),
            StructuredNote.Event("meet", "2024-05-01", "office", createdAt = 3L),
            StructuredNote.Free("free", tags = listOf("y"), createdAt = 4L)
        )

        repo.saveNotes(notes)

        val lines = file.readLines()
        assertEquals(notes.size, lines.size)
        lines.forEachIndexed { idx, line ->
            assertEquals(expectedMap(notes[idx]), lineToMap(line))
        }

        verify(exactly = notes.size) { collection.add(any()) }
        assertEquals(notes.map { expectedMap(it) }, captured)
    }

    @Test
    fun loadNotes_mergesLocalAndRemote_removingDuplicates_andSortsDescending() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()

        val localNotes = listOf(
            StructuredNote.ToDo("task", status = "open", tags = listOf(), createdAt = 100L),
            StructuredNote.Memo("memo", tags = emptyList(), createdAt = 200L)
        )
        file.writeText(localNotes.joinToString("\n") { JSONObject(expectedMap(it)).toString() })

        val firestore = mockk<FirebaseFirestore>()
        val collection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<DocumentSnapshot>()
        val doc2 = mockk<DocumentSnapshot>()
        val doc3 = mockk<DocumentSnapshot>()

        every { firestore.collection("notes") } returns collection
        every { collection.get() } returns Tasks.forResult(querySnapshot)
        every { querySnapshot.documents } returns listOf(doc1, doc2, doc3)

        every { doc1.getString("type") } returns "todo"
        every { doc1.getString("text") } returns "task"
        every { doc1.getString("datetime") } returns ""
        every { doc1.getString("location") } returns ""
        every { doc1.getString("status") } returns "done"
        every { doc1.get("tags") } returns listOf("work")
        every { doc1.getLong("createdAt") } returns 150L

        every { doc2.getString("type") } returns "memo"
        every { doc2.getString("text") } returns "memo"
        every { doc2.getString("datetime") } returns ""
        every { doc2.getString("location") } returns ""
        every { doc2.getLong("createdAt") } returns 250L
        every { doc2.get("tags") } returns listOf("remote")

        every { doc3.getString("type") } returns "event"
        every { doc3.getString("text") } returns "meet"
        every { doc3.getString("datetime") } returns "2024-05-01"
        every { doc3.getString("location") } returns "office"
        every { doc3.getLong("createdAt") } returns 300L

        val repo = NoteRepository(firestore, file)

        val result = repo.loadNotes()

        val expected = listOf(
            StructuredNote.Event("meet", "2024-05-01", "office", createdAt = 300L),
            StructuredNote.Memo("memo", tags = emptyList(), createdAt = 200L),
            StructuredNote.ToDo("task", status = "open", tags = listOf(), createdAt = 100L)
        )

        assertEquals(expected, result)
    }

    @Test
    fun summaryToNotes_mapsThoughtItems() {
        System.setProperty("net.bytebuddy.experimental", "true")
        val repo = NoteRepository(mockk(), createTempFile().toFile())

        val summary = MemoSummary(
            todo = "",
            appointments = "",
            thoughts = "",
            todoItems = listOf(
                TodoItem("task one", "open", emptyList()),
                TodoItem("task two", "done", listOf("tag"))
            ),
            appointmentItems = listOf(
                Appointment("meet Alice", "", ""),
                Appointment("meet Bob", "", "")
            ),
            thoughtItems = listOf(
                Thought("idea one", listOf("a")),
                Thought("idea two", emptyList())
            )
        )

        val method = NoteRepository::class.java
            .getDeclaredMethod("summaryToNotes", MemoSummary::class.java)
            .apply { isAccessible = true }

        val result = method.invoke(repo, summary) as List<StructuredNote>

        val expected = listOf(
            StructuredNote.ToDo("task one", status = "open", tags = emptyList(), createdAt = (result[0] as StructuredNote.ToDo).createdAt),
            StructuredNote.ToDo("task two", status = "done", tags = listOf("tag"), createdAt = (result[1] as StructuredNote.ToDo).createdAt),
            StructuredNote.Event("meet Alice", "", "", createdAt = (result[2] as StructuredNote.Event).createdAt),
            StructuredNote.Event("meet Bob", "", "", createdAt = (result[3] as StructuredNote.Event).createdAt),
            StructuredNote.Memo("idea one", tags = listOf("a"), createdAt = (result[4] as StructuredNote.Memo).createdAt),
            StructuredNote.Memo("idea two", tags = emptyList(), createdAt = (result[5] as StructuredNote.Memo).createdAt)
        )

        assertEquals(expected, result)
    }

    @Test
    fun toJsonAndParse_roundTrip_returnsOriginalNote() {
        System.setProperty("net.bytebuddy.experimental", "true")
        val repo = NoteRepository(mockk(), createTempFile().toFile())

        val toJson = NoteRepository::class.java.getDeclaredMethod("toJson", StructuredNote::class.java)
            .apply { isAccessible = true }
        val parse = NoteRepository::class.java.getDeclaredMethod("parse", String::class.java)
            .apply { isAccessible = true }

        val notes = listOf(
            StructuredNote.ToDo("task", status = "open", tags = listOf("x"), createdAt = 1L),
            StructuredNote.Memo("memo", tags = listOf("t"), createdAt = 2L),
            StructuredNote.Event("meet", "2024-05-01", "home", createdAt = 3L),
            StructuredNote.Free("free", tags = listOf("z"), createdAt = 4L)
        )

        notes.forEach { note ->
            val json = toJson.invoke(repo, note) as String
            val parsed = parse.invoke(repo, json) as StructuredNote
            assertEquals(note, parsed)
        }
    }

    private fun expectedMap(note: StructuredNote): Map<String, Any> = when (note) {
        is StructuredNote.ToDo -> mapOf<String, Any>(
            "type" to "todo",
            "text" to note.text,
            "status" to note.status,
            "tags" to note.tags,
            "datetime" to "",
            "location" to "",
            "createdAt" to note.createdAt
        )
        is StructuredNote.Memo -> mapOf<String, Any>(
            "type" to "memo",
            "text" to note.text,
            "tags" to note.tags,
            "datetime" to "",
            "location" to "",
            "createdAt" to note.createdAt
        )
        is StructuredNote.Event -> mapOf<String, Any>(
            "type" to "event",
            "text" to note.text,
            "datetime" to note.datetime,
            "location" to note.location,
            "createdAt" to note.createdAt
        )
        is StructuredNote.Free -> mapOf<String, Any>(
            "type" to "free",
            "text" to note.text,
            "tags" to note.tags,
            "datetime" to "",
            "location" to "",
            "createdAt" to note.createdAt
        )
    }

    private fun lineToMap(line: String): Map<String, Any> {
        val obj = JSONObject(line)
        val map = mutableMapOf<String, Any>(
            "type" to obj.getString("type"),
            "text" to obj.getString("text"),
            "datetime" to obj.getString("datetime"),
            "location" to obj.optString("location"),
            "createdAt" to obj.getLong("createdAt")
        )
        obj.optString("status", null)?.let { map["status"] = it }
        obj.optJSONArray("tags")?.let { arr ->
            map["tags"] = (0 until arr.length()).map { arr.getString(it) }
        }
        return map
    }
}
