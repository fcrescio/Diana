package li.crescio.penates.diana.persistence

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
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
