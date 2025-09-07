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
                val createdAt = doc.getLong("createdAt") ?: 0L
                when (type) {
                    "todo" -> text?.let { StructuredNote.ToDo(it, createdAt) }
                    "memo" -> text?.let { StructuredNote.Memo(it, createdAt) }
                    "event" -> text?.let { StructuredNote.Event(it, datetime, createdAt) }
                    "free" -> text?.let { StructuredNote.Free(it, createdAt) }
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val combined = local + remote
        return combined.distinctBy { noteKey(it) }.sortedByDescending { it.createdAt }
    }

    private fun noteKey(note: StructuredNote): String = when (note) {
        is StructuredNote.ToDo -> "todo:${note.text}"
        is StructuredNote.Memo -> "memo:${note.text}"
        is StructuredNote.Event -> "event:${note.text}|${note.datetime}"
        is StructuredNote.Free -> "free:${note.text}"
    }

    private fun toJson(note: StructuredNote): String {
        return JSONObject(noteToMap(note)).toString()
    }

    private fun noteToMap(note: StructuredNote): Map<String, Any> = when (note) {
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

    private fun parse(line: String): StructuredNote? {
        return try {
            val obj = JSONObject(line)
            val type = obj.getString("type")
            val text = obj.getString("text")
            val datetime = obj.optString("datetime", "")
            val createdAt = obj.optLong("createdAt", 0L)
            when (type) {
                "todo" -> StructuredNote.ToDo(text, createdAt)
                "memo" -> StructuredNote.Memo(text, createdAt)
                "event" -> StructuredNote.Event(text, datetime, createdAt)
                "free" -> StructuredNote.Free(text, createdAt)
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
