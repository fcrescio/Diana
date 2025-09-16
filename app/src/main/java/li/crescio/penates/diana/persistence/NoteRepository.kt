package li.crescio.penates.diana.persistence

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.notes.StructuredNote
import org.json.JSONObject
import java.io.File

class NoteRepository(
    private val firestore: FirebaseFirestore,
    private val collectionPath: String,
    private val file: File
) {
    suspend fun saveNotes(notes: List<StructuredNote>): List<StructuredNote> {
        val saved = mutableListOf<StructuredNote>()
        val collection = notesCollection()
        for (note in notes) {
            val updated = if (note is StructuredNote.ToDo && note.id.isNotBlank()) {
                collection.document(note.id).set(noteToMap(note)).await()
                note
            } else {
                val doc = collection.add(noteToMap(note)).await()
                if (note is StructuredNote.ToDo && note.id.isBlank()) {
                    note.copy(id = doc.id)
                } else {
                    note
                }
            }
            saved += updated
        }
        file.writeText(saved.joinToString("\n") { toJson(it) })
        return saved
    }

    suspend fun saveSummary(
        summary: MemoSummary,
        saveTodos: Boolean = true,
        saveAppointments: Boolean = true,
        saveThoughts: Boolean = true,
    ): MemoSummary {
        val savedNotes = saveNotes(summaryToNotes(summary, saveTodos, saveAppointments, saveThoughts))
        val updatedTodos = if (saveTodos) {
            savedNotes.filterIsInstance<StructuredNote.ToDo>().map {
                TodoItem(it.text, it.status, it.tags, it.dueDate, it.eventDate, it.id)
            }
        } else summary.todoItems
        return summary.copy(todoItems = updatedTodos)
    }

    suspend fun loadNotes(): List<StructuredNote> {
        val local = if (file.exists()) {
            file.readLines().mapNotNull { parse(it) }
        } else emptyList()

        val remote = try {
            notesCollection().get().await().documents.mapNotNull { doc ->
                val type = doc.getString("type")
                val text = doc.getString("text")
                val datetime = doc.getString("datetime") ?: ""
                val location = doc.getString("location") ?: ""
                val createdAt = doc.getLong("createdAt") ?: 0L
                when (type) {
                    "todo" -> text?.let {
                        val status = doc.getString("status") ?: ""
                        val tags = (doc.get("tags") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                        val dueDate = doc.getString("dueDate") ?: ""
                        val eventDate = doc.getString("eventDate") ?: ""
                        StructuredNote.ToDo(it, status, tags, dueDate, eventDate, createdAt, doc.id)
                    }
                    "memo" -> text?.let {
                        val tags = (doc.get("tags") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                        StructuredNote.Memo(it, tags, createdAt)
                    }
                    "event" -> text?.let { StructuredNote.Event(it, datetime, location, createdAt) }
                    "free" -> text?.let {
                        val tags = (doc.get("tags") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                        StructuredNote.Free(it, tags, createdAt)
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val combined = local + remote
        return combined.distinctBy { noteKey(it) }.sortedByDescending { it.createdAt }
    }

    suspend fun clearTodos() = clearTypes("todo")

    suspend fun clearAppointments() = clearTypes("event")

    suspend fun clearThoughts() = clearTypes("memo", "free")

    suspend fun deleteTodoItem(id: String) {
        if (file.exists()) {
            val remaining = file.readLines().mapNotNull { parse(it) }
                .filterNot { note -> note is StructuredNote.ToDo && note.id == id }
            file.writeText(remaining.joinToString("\n") { toJson(it) })
        }

        try {
            notesCollection().document(id).delete().await()
        } catch (_: Exception) {
            // ignore failures
        }
    }

    suspend fun deleteAppointment(text: String, datetime: String, location: String) {
        if (file.exists()) {
            val remaining = file.readLines().mapNotNull { parse(it) }
                .filterNot { note ->
                    note is StructuredNote.Event &&
                        note.text == text &&
                        note.datetime == datetime &&
                        note.location == location
                }
            file.writeText(remaining.joinToString("\n") { toJson(it) })
        }

        try {
            val snapshot = notesCollection()
                .whereEqualTo("type", "event")
                .whereEqualTo("text", text)
                .whereEqualTo("datetime", datetime)
                .whereEqualTo("location", location)
                .get()
                .await()
            for (doc in snapshot.documents) {
                doc.reference.delete().await()
            }
        } catch (_: Exception) {
            // ignore failures
        }
    }

    private suspend fun clearTypes(vararg types: String) {
        if (file.exists()) {
            val remaining = file.readLines().mapNotNull { parse(it) }.filterNot { note ->
                when (note) {
                    is StructuredNote.ToDo -> "todo" in types
                    is StructuredNote.Event -> "event" in types
                    is StructuredNote.Memo -> "memo" in types
                    is StructuredNote.Free -> "free" in types
                }
            }
            file.writeText(remaining.joinToString("\n") { toJson(it) })
        }

        for (type in types) {
            try {
                val snapshot = notesCollection()
                    .whereEqualTo("type", type)
                    .get()
                    .await()
                for (doc in snapshot.documents) {
                    doc.reference.delete().await()
                }
            } catch (_: Exception) {
                // ignore failures
            }
        }
    }

    private fun notesCollection() = firestore.collection(collectionPath)

    private fun noteKey(note: StructuredNote): String = when (note) {
        is StructuredNote.ToDo -> "todo:${note.id.ifBlank { note.text }}"
        is StructuredNote.Memo -> "memo:${note.text}"
        is StructuredNote.Event -> "event:${note.text}|${note.datetime}|${note.location}"
        is StructuredNote.Free -> "free:${note.text}"
    }

    private fun toJson(note: StructuredNote): String {
        return JSONObject(noteToMap(note)).toString()
    }

    private fun noteToMap(note: StructuredNote): Map<String, Any> = when (note) {
        is StructuredNote.ToDo -> mapOf(
            "type" to "todo",
            "text" to note.text,
            "status" to note.status,
            "tags" to note.tags,
            "dueDate" to note.dueDate,
            "eventDate" to note.eventDate,
            "datetime" to "",
            "location" to "",
            "createdAt" to note.createdAt,
            "id" to note.id
        )
        is StructuredNote.Memo -> mapOf(
            "type" to "memo",
            "text" to note.text,
            "tags" to note.tags,
            "datetime" to "",
            "location" to "",
            "createdAt" to note.createdAt
        )
        is StructuredNote.Event -> mapOf(
            "type" to "event",
            "text" to note.text,
            "datetime" to note.datetime,
            "location" to note.location,
            "createdAt" to note.createdAt
        )
        is StructuredNote.Free -> mapOf(
            "type" to "free",
            "text" to note.text,
            "tags" to note.tags,
            "datetime" to "",
            "location" to "",
            "createdAt" to note.createdAt
        )
    }

    private fun parse(line: String): StructuredNote? {
        return try {
            val obj = JSONObject(line)
            val type = obj.getString("type")
            val text = obj.getString("text")
            val datetime = obj.optString("datetime", "")
            val location = obj.optString("location", "")
            val createdAt = obj.optLong("createdAt", 0L)
            when (type) {
                "todo" -> {
                    val status = obj.optString("status", "")
                    val tagsArr = obj.optJSONArray("tags")
                    val tags = (0 until (tagsArr?.length() ?: 0)).map { tagsArr.optString(it) }
                    val dueDate = obj.optString("dueDate", "")
                    val eventDate = obj.optString("eventDate", "")
                    val id = obj.optString("id", "")
                    StructuredNote.ToDo(text, status, tags, dueDate, eventDate, createdAt, id)
                }
                "memo" -> {
                    val tagsArr = obj.optJSONArray("tags")
                    val tags = (0 until (tagsArr?.length() ?: 0)).map { tagsArr.optString(it) }
                    StructuredNote.Memo(text, tags, createdAt)
                }
                "event" -> StructuredNote.Event(text, datetime, location, createdAt)
                "free" -> {
                    val tagsArr = obj.optJSONArray("tags")
                    val tags = (0 until (tagsArr?.length() ?: 0)).map { tagsArr.optString(it) }
                    StructuredNote.Free(text, tags, createdAt)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun summaryToNotes(
        summary: MemoSummary,
        saveTodos: Boolean,
        saveAppointments: Boolean,
        saveThoughts: Boolean,
    ): List<StructuredNote> {
        val notes = mutableListOf<StructuredNote>()
        if (saveTodos) {
            notes += summary.todoItems.map {
                StructuredNote.ToDo(it.text, it.status, it.tags, it.dueDate, it.eventDate, id = it.id)
            }
        }
        if (saveAppointments) {
            notes += summary.appointmentItems.map { StructuredNote.Event(it.text, it.datetime, it.location) }
        }
        if (saveThoughts) {
            notes += summary.thoughtItems.map { StructuredNote.Memo(it.text, it.tags) }
        }
        return notes
    }
}
