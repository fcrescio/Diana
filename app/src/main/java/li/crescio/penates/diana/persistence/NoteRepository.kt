package li.crescio.penates.diana.persistence

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.ThoughtDocument
import li.crescio.penates.diana.notes.ThoughtOutline
import li.crescio.penates.diana.notes.ThoughtOutlineSection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NoteRepository(
    private val firestore: FirebaseFirestore,
    private val sessionId: String,
    private val notesFile: File,
    private val thoughtMarkdownFile: File = defaultThoughtMarkdownFile(notesFile),
    private val thoughtOutlineFile: File = defaultThoughtOutlineFile(notesFile),
) {
    companion object {
        private const val THOUGHT_DOCUMENT_TYPE = "thought_document"
        private const val THOUGHT_DOCUMENT_DOC_ID = "__thought_document__"

        private fun defaultThoughtMarkdownFile(notesFile: File): File {
            val parent = notesFile.absoluteFile.parentFile
                ?: throw IllegalArgumentException("notesFile must have a parent directory")
            return File(parent, "thoughts.md")
        }

        private fun defaultThoughtOutlineFile(notesFile: File): File {
            val parent = notesFile.absoluteFile.parentFile
                ?: throw IllegalArgumentException("notesFile must have a parent directory")
            return File(parent, "thought_outline.json")
        }
    }
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
        notesFile.writeText(saved.joinToString("\n") { toJson(it) })
        return saved
    }

    suspend fun saveSummary(
        summary: MemoSummary,
        saveTodos: Boolean = true,
        saveAppointments: Boolean = true,
        saveThoughts: Boolean = true,
    ): MemoSummary {
        val savedNotes = saveNotes(summaryToNotes(summary, saveTodos, saveAppointments, saveThoughts))
        if (saveThoughts) {
            summary.thoughtDocument?.let { saveThoughtDocument(it) }
        }
        val updatedTodos = if (saveTodos) {
            savedNotes.filterIsInstance<StructuredNote.ToDo>().map {
                TodoItem(it.text, it.status, it.tags, it.dueDate, it.eventDate, it.id)
            }
        } else summary.todoItems
        return summary.copy(todoItems = updatedTodos)
    }

    suspend fun loadNotes(): List<StructuredNote> {
        val local = if (notesFile.exists()) {
            notesFile.readLines().mapNotNull { parse(it) }
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
                        val sectionAnchor = doc.getString("sectionAnchor")?.takeUnless { it.isBlank() }
                        val sectionTitle = doc.getString("sectionTitle")?.takeUnless { it.isBlank() }
                        StructuredNote.Memo(it, tags, sectionAnchor, sectionTitle, createdAt)
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

    suspend fun saveThoughtDocument(document: ThoughtDocument) {
        writeThoughtDocumentLocal(document)
        try {
            notesCollection()
                .document(THOUGHT_DOCUMENT_DOC_ID)
                .set(thoughtDocumentToMap(document))
                .await()
        } catch (_: Exception) {
            // ignore failures
        }
    }

    suspend fun loadThoughtDocument(): ThoughtDocument? {
        readThoughtDocumentLocal()?.let { return it }

        val remote = try {
            val snapshot = notesCollection()
                .document(THOUGHT_DOCUMENT_DOC_ID)
                .get()
                .await()
            val data = snapshot.data ?: return null
            parseThoughtDocumentMap(data)
        } catch (_: Exception) {
            null
        }

        if (remote != null) {
            writeThoughtDocumentLocal(remote)
        }
        return remote
    }

    suspend fun clearTodos() = clearTypes("todo")

    suspend fun clearAppointments() = clearTypes("event")

    suspend fun clearThoughts() {
        clearTypes("memo", "free")
        clearThoughtDocument()
    }

    suspend fun deleteTodoItem(id: String) {
        if (notesFile.exists()) {
            val remaining = notesFile.readLines().mapNotNull { parse(it) }
                .filterNot { note -> note is StructuredNote.ToDo && note.id == id }
            notesFile.writeText(remaining.joinToString("\n") { toJson(it) })
        }

        try {
            notesCollection().document(id).delete().await()
        } catch (_: Exception) {
            // ignore failures
        }
    }

    suspend fun deleteAppointment(text: String, datetime: String, location: String) {
        if (notesFile.exists()) {
            val remaining = notesFile.readLines().mapNotNull { parse(it) }
                .filterNot { note ->
                    note is StructuredNote.Event &&
                        note.text == text &&
                        note.datetime == datetime &&
                        note.location == location
                }
            notesFile.writeText(remaining.joinToString("\n") { toJson(it) })
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
        if (notesFile.exists()) {
            val remaining = notesFile.readLines().mapNotNull { parse(it) }.filterNot { note ->
                when (note) {
                    is StructuredNote.ToDo -> "todo" in types
                    is StructuredNote.Event -> "event" in types
                    is StructuredNote.Memo -> "memo" in types
                    is StructuredNote.Free -> "free" in types
                }
            }
            notesFile.writeText(remaining.joinToString("\n") { toJson(it) })
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

    private fun notesCollection() = firestore
        .collection("sessions")
        .document(sessionId)
        .collection("notes")

    private fun writeThoughtDocumentLocal(document: ThoughtDocument) {
        ensureParentExists(thoughtMarkdownFile)
        thoughtMarkdownFile.writeText(document.markdownBody)
        ensureParentExists(thoughtOutlineFile)
        val outlineJson = JSONObject().apply {
            put("sections", JSONArray().apply {
                document.outline.sections.forEach { put(outlineSectionToJson(it)) }
            })
        }
        thoughtOutlineFile.writeText(outlineJson.toString())
    }

    private fun readThoughtDocumentLocal(): ThoughtDocument? {
        if (!thoughtMarkdownFile.exists()) {
            return null
        }
        val markdown = thoughtMarkdownFile.readText()
        val outline = if (thoughtOutlineFile.exists()) {
            try {
                val obj = JSONObject(thoughtOutlineFile.readText())
                val sections = parseOutlineSections(obj.optJSONArray("sections"))
                ThoughtOutline(sections)
            } catch (_: Exception) {
                ThoughtOutline.EMPTY
            }
        } else {
            ThoughtOutline.EMPTY
        }
        return ThoughtDocument(markdown, outline)
    }

    private suspend fun clearThoughtDocument() {
        if (thoughtMarkdownFile.exists()) {
            thoughtMarkdownFile.delete()
        }
        if (thoughtOutlineFile.exists()) {
            thoughtOutlineFile.delete()
        }
        try {
            notesCollection().document(THOUGHT_DOCUMENT_DOC_ID).delete().await()
        } catch (_: Exception) {
            // ignore failures
        }
    }

    private fun ensureParentExists(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
    }

    private fun outlineSectionToJson(section: ThoughtOutlineSection): JSONObject {
        val obj = JSONObject()
        obj.put("title", section.title)
        obj.put("level", section.level)
        obj.put("anchor", section.anchor)
        val children = JSONArray()
        section.children.forEach { child -> children.put(outlineSectionToJson(child)) }
        obj.put("children", children)
        return obj
    }

    private fun parseOutlineSections(array: JSONArray?): List<ThoughtOutlineSection> {
        if (array == null) return emptyList()
        val sections = mutableListOf<ThoughtOutlineSection>()
        for (idx in 0 until array.length()) {
            val obj = array.optJSONObject(idx) ?: continue
            val title = obj.optString("title")
            if (title.isBlank()) continue
            val level = obj.optInt("level", 1)
            val anchor = obj.optString("anchor")
            val children = parseOutlineSections(obj.optJSONArray("children"))
            sections.add(ThoughtOutlineSection(title, level, anchor, children))
        }
        return sections
    }

    private fun parseOutlineSections(list: List<*>?): List<ThoughtOutlineSection> {
        if (list == null) return emptyList()
        val sections = mutableListOf<ThoughtOutlineSection>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val title = map["title"] as? String ?: continue
            val levelValue = map["level"]
            val level = when (levelValue) {
                is Number -> levelValue.toInt()
                else -> 1
            }
            val anchor = (map["anchor"] as? String).orEmpty()
            val children = parseOutlineSections(map["children"] as? List<*>)
            sections.add(ThoughtOutlineSection(title, level, anchor, children))
        }
        return sections
    }

    private fun thoughtDocumentToMap(document: ThoughtDocument): Map<String, Any> {
        return mapOf(
            "type" to THOUGHT_DOCUMENT_TYPE,
            "markdown" to document.markdownBody,
            "outline" to document.outline.sections.map { outlineSectionToMap(it) }
        )
    }

    private fun outlineSectionToMap(section: ThoughtOutlineSection): Map<String, Any> {
        return mapOf(
            "title" to section.title,
            "level" to section.level,
            "anchor" to section.anchor,
            "children" to section.children.map { outlineSectionToMap(it) },
        )
    }

    private fun parseThoughtDocumentMap(data: Map<String, Any>): ThoughtDocument? {
        val type = data["type"] as? String ?: return null
        if (type != THOUGHT_DOCUMENT_TYPE) return null
        val markdown = data["markdown"] as? String ?: return null
        val outlineSections = parseOutlineSections(data["outline"] as? List<*>)
        return ThoughtDocument(markdown, ThoughtOutline(outlineSections))
    }

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
        ).let { base ->
            val map = base.toMutableMap()
            note.sectionAnchor?.takeIf { it.isNotBlank() }?.let { map["sectionAnchor"] = it }
            note.sectionTitle?.takeIf { it.isNotBlank() }?.let { map["sectionTitle"] = it }
            map
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
                    val sectionAnchor = obj.optString("sectionAnchor", "").takeUnless { it.isBlank() }
                    val sectionTitle = obj.optString("sectionTitle", "").takeUnless { it.isBlank() }
                    StructuredNote.Memo(text, tags, sectionAnchor, sectionTitle, createdAt)
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
        saveThoughts: Boolean
    ): List<StructuredNote> {
        val notes = mutableListOf<StructuredNote>()
        if (saveTodos) {
            summary.todoItems.forEach { item ->
                notes.add(
                    StructuredNote.ToDo(
                        text = item.text,
                        status = item.status,
                        tags = item.tags,
                        dueDate = item.dueDate,
                        eventDate = item.eventDate,
                        id = item.id
                    )
                )
            }
        }
        if (saveAppointments) {
            summary.appointmentItems.forEach { item ->
                notes.add(
                    StructuredNote.Event(
                        text = item.text,
                        datetime = item.datetime,
                        location = item.location
                    )
                )
            }
        }
        if (saveThoughts) {
            summary.thoughtItems.forEach { item ->
                notes.add(
                    StructuredNote.Memo(
                        text = item.text,
                        tags = item.tags
                    )
                )
            }
        }
        return notes
    }
}
