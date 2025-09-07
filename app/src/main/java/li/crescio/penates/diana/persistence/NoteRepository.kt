package li.crescio.penates.diana.persistence

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.notes.StructuredNote
import org.json.JSONObject
import java.io.File

class NoteRepository(
    private val firestore: FirebaseFirestore,
    private val file: File
) {
    suspend fun saveNotes(notes: List<StructuredNote>) {
        file.writeText(notes.joinToString("\n") { toJson(it) })
        for (note in notes) {
            firestore.collection("notes").add(noteToMap(note)).await()
        }
    }

    suspend fun saveSummary(summary: MemoSummary) {
        saveNotes(summaryToNotes(summary))
    }

    suspend fun loadNotes(): List<StructuredNote> {
        val local = if (file.exists()) {
            file.readLines().mapNotNull { parse(it) }
        } else emptyList()

        val remote = try {
            firestore.collection("notes").get().await().documents.mapNotNull { doc ->
                val type = doc.getString("type")
                val text = doc.getString("text")
                val datetime = doc.getString("datetime") ?: ""
                when (type) {
                    "todo" -> text?.let { StructuredNote.ToDo(it) }
                    "memo" -> text?.let { StructuredNote.Memo(it) }
                    "event" -> text?.let { StructuredNote.Event(it, datetime) }
                    "free" -> text?.let { StructuredNote.Free(it) }
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        return local + remote
    }

    private fun toJson(note: StructuredNote): String {
        return JSONObject(noteToMap(note)).toString()
    }

    private fun noteToMap(note: StructuredNote): Map<String, String> = when (note) {
        is StructuredNote.ToDo -> mapOf("type" to "todo", "text" to note.text, "datetime" to "")
        is StructuredNote.Memo -> mapOf("type" to "memo", "text" to note.text, "datetime" to "")
        is StructuredNote.Event -> mapOf("type" to "event", "text" to note.text, "datetime" to note.datetime)
        is StructuredNote.Free -> mapOf("type" to "free", "text" to note.text, "datetime" to "")
    }

    private fun parse(line: String): StructuredNote? {
        return try {
            val obj = JSONObject(line)
            val type = obj.getString("type")
            val text = obj.getString("text")
            val datetime = obj.optString("datetime", "")
            when (type) {
                "todo" -> StructuredNote.ToDo(text)
                "memo" -> StructuredNote.Memo(text)
                "event" -> StructuredNote.Event(text, datetime)
                "free" -> StructuredNote.Free(text)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun summaryToNotes(summary: MemoSummary): List<StructuredNote> {
        val notes = mutableListOf<StructuredNote>()
        notes += summary.todo.lines().filter { it.isNotBlank() }.map { StructuredNote.ToDo(it.trim()) }
        notes += summary.appointments.lines().filter { it.isNotBlank() }
            .map { StructuredNote.Event(it.trim(), "") }
        notes += summary.thoughts.lines().filter { it.isNotBlank() }.map { StructuredNote.Memo(it.trim()) }
        return notes
    }
}
