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
            StructuredNote.ToDo("task", createdAt = 1L),
            StructuredNote.Memo("memo", createdAt = 2L),
            StructuredNote.Event("meet", "2024-05-01", createdAt = 3L),
            StructuredNote.Free("free", createdAt = 4L)
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
            StructuredNote.ToDo("task", createdAt = 100L),
            StructuredNote.Memo("memo", createdAt = 200L)
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
        every { doc1.getLong("createdAt") } returns 150L

        every { doc2.getString("type") } returns "memo"
        every { doc2.getString("text") } returns "memo"
        every { doc2.getString("datetime") } returns ""
        every { doc2.getLong("createdAt") } returns 250L

        every { doc3.getString("type") } returns "event"
        every { doc3.getString("text") } returns "meet"
        every { doc3.getString("datetime") } returns "2024-05-01"
        every { doc3.getLong("createdAt") } returns 300L

        val repo = NoteRepository(firestore, file)

        val result = repo.loadNotes()

        val expected = listOf(
            StructuredNote.Event("meet", "2024-05-01", createdAt = 300L),
            StructuredNote.Memo("memo", createdAt = 200L),
            StructuredNote.ToDo("task", createdAt = 100L)
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
            StructuredNote.ToDo("task", createdAt = 1L),
            StructuredNote.Memo("memo", createdAt = 2L),
            StructuredNote.Event("meet", "2024-05-01", createdAt = 3L),
            StructuredNote.Free("free", createdAt = 4L)
        )

        notes.forEach { note ->
            val json = toJson.invoke(repo, note) as String
            val parsed = parse.invoke(repo, json) as StructuredNote
            assertEquals(note, parsed)
        }
    }

    private fun expectedMap(note: StructuredNote): Map<String, Any> = when (note) {
        is StructuredNote.ToDo -> mapOf(
            "type" to "todo",
            "text" to note.text,
            "datetime" to "",
            "createdAt" to note.createdAt
        )
        is StructuredNote.Memo -> mapOf(
            "type" to "memo",
            "text" to note.text,
            "datetime" to "",
            "createdAt" to note.createdAt
        )
        is StructuredNote.Event -> mapOf(
            "type" to "event",
            "text" to note.text,
            "datetime" to note.datetime,
            "createdAt" to note.createdAt
        )
        is StructuredNote.Free -> mapOf(
            "type" to "free",
            "text" to note.text,
            "datetime" to "",
            "createdAt" to note.createdAt
        )
    }

    private fun lineToMap(line: String): Map<String, Any> {
        val obj = JSONObject(line)
        return mapOf(
            "type" to obj.getString("type"),
            "text" to obj.getString("text"),
            "datetime" to obj.getString("datetime"),
            "createdAt" to obj.getLong("createdAt")
        )
    }
}
