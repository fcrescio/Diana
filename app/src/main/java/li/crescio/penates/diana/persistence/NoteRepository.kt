package li.crescio.penates.diana.persistence

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.llm.TodoChangeSet
import li.crescio.penates.diana.llm.TodoDiff
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.ThoughtDocument
import li.crescio.penates.diana.notes.ThoughtOutline
import li.crescio.penates.diana.notes.ThoughtOutlineSection
import li.crescio.penates.diana.tags.TagCatalog
import li.crescio.penates.diana.tags.TagCatalogRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashSet
import java.util.Locale

enum class TodoChangeSetMode {
    APPLY,
    UNDO,
}

class NoteRepository(
    private val firestore: FirebaseFirestore,
    private val sessionId: String,
    private val notesFile: File,
    private val thoughtMarkdownFile: File = defaultThoughtMarkdownFile(notesFile),
    private val thoughtOutlineFile: File = defaultThoughtOutlineFile(notesFile),
    private val todoChangeSetFile: File = defaultTodoChangeSetFile(notesFile),
    private val tagCatalogRepository: TagCatalogRepository? = null,
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

        private fun defaultTodoChangeSetFile(notesFile: File): File {
            val parent = notesFile.absoluteFile.parentFile
                ?: throw IllegalArgumentException("notesFile must have a parent directory")
            return File(parent, "todo_change_sets.jsonl")
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

    private suspend fun saveNotesWithChangeSet(
        notes: List<StructuredNote>,
        changeSet: TodoChangeSet,
    ): List<StructuredNote> {
        val saved = mutableListOf<StructuredNote>()
        val collection = notesCollection()
        val batch = firestore.batch()
        for (note in notes) {
            val (docRef, updated) = if (note is StructuredNote.ToDo) {
                if (note.id.isBlank()) {
                    val doc = collection.document()
                    doc to note.copy(id = doc.id)
                } else {
                    collection.document(note.id) to note
                }
            } else {
                collection.document() to note
            }
            batch.set(docRef, noteToMap(updated))
            saved += updated
        }
        val resolvedChangeSet = normalizeChangeSet(changeSet)
        batch.set(todoChangeSetsCollection().document(resolvedChangeSet.changeSetId), resolvedChangeSet)
        batch.commit().await()
        notesFile.writeText(saved.joinToString("\n") { toJson(it) })
        writeTodoChangeSetLocal(resolvedChangeSet)
        return saved
    }

    suspend fun saveSummary(
        summary: MemoSummary,
        saveTodos: Boolean = true,
        saveAppointments: Boolean = true,
        saveThoughts: Boolean = true,
        changeSet: TodoChangeSet? = null,
    ): MemoSummary {
        val notesToSave = summaryToNotes(summary, saveTodos, saveAppointments, saveThoughts)
        val savedNotes = if (changeSet == null) {
            saveNotes(notesToSave)
        } else {
            saveNotesWithChangeSet(notesToSave, changeSet)
        }
        if (saveThoughts) {
            summary.thoughtDocument?.let { saveThoughtDocument(it) }
        }
        val updatedTodos = if (saveTodos) {
            savedNotes.filterIsInstance<StructuredNote.ToDo>().map {
                TodoItem(
                    text = it.text,
                    status = it.status,
                    tagIds = it.tagIds,
                    tagLabels = it.tagLabels,
                    dueDate = it.dueDate,
                    eventDate = it.eventDate,
                    id = it.id,
                )
            }
        } else summary.todoItems
        return summary.copy(todoItems = updatedTodos)
    }

    suspend fun applyTodoChangeSet(
        changeSet: TodoChangeSet,
        mode: TodoChangeSetMode = TodoChangeSetMode.APPLY,
    ): List<StructuredNote> {
        val tagContext = loadTagContext()
        val existingNotes = if (notesFile.exists()) {
            notesFile.readLines().mapNotNull { parse(it, tagContext) }
        } else {
            emptyList()
        }
        val existingTodos = existingNotes.filterIsInstance<StructuredNote.ToDo>()
        val otherNotes = existingNotes.filterNot { it is StructuredNote.ToDo }
        val todosByKey = LinkedHashMap<String, StructuredNote.ToDo>()
        existingTodos.forEach { todo ->
            todosByKey[todoStableKey(todo)] = todo
        }

        val actionsToApply = if (mode == TodoChangeSetMode.UNDO) {
            changeSet.actions.map { invertTodoAction(it) }
        } else {
            changeSet.actions
        }

        val now = System.currentTimeMillis()
        val collection = notesCollection()
        val batch = firestore.batch()
        for (action in actionsToApply) {
            when (action.op) {
                "add" -> {
                    val item = action.after ?: continue
                    val resolvedId = item.id.ifBlank { collection.document().id }
                    val note = todoItemToNote(item, createdAt = now, idOverride = resolvedId)
                    todosByKey[todoStableKey(note)] = note
                    batch.set(collection.document(resolvedId), noteToMap(note))
                }
                "update" -> {
                    val item = action.after ?: continue
                    val beforeKey = action.before?.let { todoStableKey(it) }
                    val afterKey = todoStableKey(item)
                    val existing = beforeKey?.let { todosByKey[it] } ?: todosByKey[afterKey]
                    val createdAt = existing?.createdAt ?: now
                    val resolvedId = item.id.ifBlank { existing?.id.orEmpty() }
                    val updatedNote = todoItemToNote(item, createdAt = createdAt, idOverride = resolvedId)
                    if (beforeKey != null && beforeKey != afterKey) {
                        todosByKey.remove(beforeKey)
                    } else if (existing != null && beforeKey == null && todoStableKey(existing) != afterKey) {
                        todosByKey.remove(todoStableKey(existing))
                    }
                    todosByKey[afterKey] = updatedNote
                    if (resolvedId.isNotBlank()) {
                        batch.set(collection.document(resolvedId), noteToMap(updatedNote))
                    }
                }
                "delete" -> {
                    val item = action.before ?: continue
                    val key = todoStableKey(item)
                    val existing = todosByKey.remove(key)
                    val resolvedId = item.id.ifBlank { existing?.id.orEmpty() }
                    if (resolvedId.isNotBlank()) {
                        batch.delete(collection.document(resolvedId))
                    }
                }
            }
        }

        val recordedChangeSet = if (mode == TodoChangeSetMode.UNDO) {
            val inverted = changeSet.actions.map { invertTodoAction(it) }
            val undoChangeSet = changeSet.copy(
                changeSetId = java.util.UUID.randomUUID().toString(),
                timestamp = now,
                actions = inverted,
                type = "undo",
            )
            normalizeChangeSet(undoChangeSet)
        } else {
            normalizeChangeSet(changeSet)
        }

        batch.set(todoChangeSetsCollection().document(recordedChangeSet.changeSetId), recordedChangeSet)
        batch.commit().await()

        val updatedNotes = otherNotes + todosByKey.values
        notesFile.writeText(updatedNotes.joinToString("\n") { toJson(it) })
        writeTodoChangeSetLocal(recordedChangeSet)
        return updatedNotes
    }

    suspend fun loadNotes(): List<StructuredNote> {
        val tagContext = loadTagContext()
        val local = if (notesFile.exists()) {
            notesFile.readLines().mapNotNull { parse(it, tagContext) }
        } else emptyList()

        val remote = try {
            notesCollection().get().await().documents.mapNotNull { doc ->
                parseRemoteDocument(doc, tagContext)
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

    private fun todoChangeSetsCollection() = firestore
        .collection("sessions")
        .document(sessionId)
        .collection("todo_change_sets")

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

    private fun normalizeChangeSet(changeSet: TodoChangeSet): TodoChangeSet {
        val resolvedSessionId = if (changeSet.sessionId.isBlank()) sessionId else changeSet.sessionId
        val resolvedChangeSetId = if (changeSet.changeSetId.isBlank()) {
            java.util.UUID.randomUUID().toString()
        } else {
            changeSet.changeSetId
        }
        val resolvedType = changeSet.type.ifBlank { "apply" }
        return if (
            resolvedSessionId == changeSet.sessionId &&
            resolvedChangeSetId == changeSet.changeSetId &&
            resolvedType == changeSet.type
        ) {
            changeSet
        } else {
            changeSet.copy(
                sessionId = resolvedSessionId,
                changeSetId = resolvedChangeSetId,
                type = resolvedType,
            )
        }
    }

    private fun writeTodoChangeSetLocal(changeSet: TodoChangeSet) {
        val json = todoChangeSetToJson(changeSet)
        appendJsonLine(todoChangeSetFile, json)
    }

    private fun appendJsonLine(file: File, line: String) {
        ensureParentExists(file)
        if (file.exists() && file.length() > 0L) {
            file.appendText("\n$line")
        } else {
            file.writeText(line)
        }
    }

    private fun todoChangeSetToJson(changeSet: TodoChangeSet): String {
        val obj = JSONObject()
        obj.put("changeSetId", changeSet.changeSetId)
        obj.put("sessionId", changeSet.sessionId)
        obj.put("memoId", changeSet.memoId)
        obj.put("timestamp", changeSet.timestamp)
        obj.put("model", changeSet.model)
        obj.put("promptVersion", changeSet.promptVersion)
        obj.put("type", changeSet.type)
        val actions = JSONArray()
        changeSet.actions.forEach { action ->
            val actionObj = JSONObject()
            actionObj.put("op", action.op)
            actionObj.put(
                "before",
                action.before?.let { JSONObject(todoItemToMap(it)) } ?: JSONObject.NULL,
            )
            actionObj.put(
                "after",
                action.after?.let { JSONObject(todoItemToMap(it)) } ?: JSONObject.NULL,
            )
            actions.put(actionObj)
        }
        obj.put("actions", actions)
        return obj.toString()
    }

    private fun invertTodoAction(action: li.crescio.penates.diana.llm.TodoAction): li.crescio.penates.diana.llm.TodoAction {
        return when (action.op) {
            "add" -> action.after?.let { item ->
                li.crescio.penates.diana.llm.TodoAction(op = "delete", before = item, after = null)
            } ?: action
            "delete" -> action.before?.let { item ->
                li.crescio.penates.diana.llm.TodoAction(op = "add", before = null, after = item)
            } ?: action
            "update" -> li.crescio.penates.diana.llm.TodoAction(
                op = "update",
                before = action.after,
                after = action.before,
            )
            else -> action
        }
    }

    private fun todoItemToNote(
        item: TodoItem,
        createdAt: Long = System.currentTimeMillis(),
        idOverride: String = item.id,
    ): StructuredNote.ToDo {
        return StructuredNote.ToDo(
            text = item.text,
            status = item.status,
            tagIds = item.tagIds,
            tagLabels = item.tagLabels,
            dueDate = item.dueDate,
            eventDate = item.eventDate,
            createdAt = createdAt,
            id = idOverride,
        )
    }

    private fun todoStableKey(item: TodoItem): String = TodoDiff.stableKey(item)

    private fun todoStableKey(note: StructuredNote.ToDo): String {
        return todoStableKey(
            TodoItem(
                text = note.text,
                status = note.status,
                tagIds = note.tagIds,
                tagLabels = note.tagLabels,
                dueDate = note.dueDate,
                eventDate = note.eventDate,
                id = note.id,
            )
        )
    }

    private fun todoItemToMap(item: TodoItem): Map<String, Any> = buildMap {
        put("text", item.text)
        put("status", item.status)
        put("tagIds", item.tagIds)
        if (item.tagLabels.isNotEmpty()) put("tagLabels", item.tagLabels)
        if (item.dueDate.isNotBlank()) put("dueDate", item.dueDate)
        if (item.eventDate.isNotBlank()) put("eventDate", item.eventDate)
        if (item.id.isNotBlank()) put("id", item.id)
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

        is StructuredNote.ToDo -> buildMap<String, Any> {
            put("type", "todo")
            put("text", note.text)
            put("status", note.status)
            put("tagIds", note.tagIds)
            if (note.tagLabels.isNotEmpty()) put("tagLabels", note.tagLabels)
            put("tags", note.tagIds)
            put("datetime", "")
            put("location", "")
            put("createdAt", note.createdAt)
            put("id", note.id)
            if (note.dueDate.isNotBlank()) put("dueDate", note.dueDate)
            if (note.eventDate.isNotBlank()) put("eventDate", note.eventDate)
        }

        is StructuredNote.Memo -> buildMap<String, Any> {
            put("type", "memo")
            put("text", note.text)
            put("tagIds", note.tagIds)
            if (note.tagLabels.isNotEmpty()) put("tagLabels", note.tagLabels)
            put("tags", note.tagIds)
            put("datetime", "")
            put("location", "")
            put("createdAt", note.createdAt)
            note.sectionAnchor?.takeIf { it.isNotBlank() }?.let { put("sectionAnchor", it) }
            note.sectionTitle?.takeIf { it.isNotBlank() }?.let { put("sectionTitle", it) }
        }

        is StructuredNote.Event -> buildMap<String, Any> {
            put("type", "event")
            put("text", note.text)
            put("createdAt", note.createdAt)
            note.datetime?.let { put("datetime", it) }
            note.location?.let { put("location", it) }
        }

        is StructuredNote.Free -> buildMap<String, Any> {
            put("type", "free")
            put("text", note.text)
            put("tagIds", note.tagIds)
            if (note.tagLabels.isNotEmpty()) put("tagLabels", note.tagLabels)
            put("tags", note.tagIds)
            put("datetime", "")
            put("location", "")
            put("createdAt", note.createdAt)
        }
    }

    private fun parse(line: String, tagContext: TagMappingContext = TagMappingContext.EMPTY): StructuredNote? {
        return try {
            val obj = JSONObject(line)
            val type = obj.getString("type")
            val text = obj.getString("text")
            val datetime = obj.optString("datetime", "")
            val location = obj.optString("location", "")
            val createdAt = obj.optLong("createdAt", 0L)
            val tagData = resolveTagData(
                obj.optJSONArray("tagIds").toStringList(),
                obj.optJSONArray("tagLabels").toStringList(),
                obj.optJSONArray("tags").toStringList(),
                tagContext,
            )
            when (type) {
                "todo" -> {
                    val status = obj.optString("status", "")
                    val dueDate = obj.optString("dueDate", "")
                    val eventDate = obj.optString("eventDate", "")
                    val id = obj.optString("id", "")
                    StructuredNote.ToDo(
                        text = text,
                        status = status,
                        tagIds = tagData.tagIds,
                        tagLabels = tagData.tagLabels,
                        dueDate = dueDate,
                        eventDate = eventDate,
                        createdAt = createdAt,
                        id = id,
                    )
                }
                "memo" -> {
                    val sectionAnchor = obj.optString("sectionAnchor", "").takeUnless { it.isBlank() }
                    val sectionTitle = obj.optString("sectionTitle", "").takeUnless { it.isBlank() }
                    StructuredNote.Memo(
                        text = text,
                        tagIds = tagData.tagIds,
                        tagLabels = tagData.tagLabels,
                        sectionAnchor = sectionAnchor,
                        sectionTitle = sectionTitle,
                        createdAt = createdAt,
                    )
                }
                "event" -> StructuredNote.Event(text, datetime, location, createdAt)
                "free" -> StructuredNote.Free(
                    text = text,
                    tagIds = tagData.tagIds,
                    tagLabels = tagData.tagLabels,
                    createdAt = createdAt,
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadTagContext(): TagMappingContext {
        val catalog = if (tagCatalogRepository == null) {
            null
        } else {
            try {
                tagCatalogRepository.loadCatalog()
            } catch (_: Exception) {
                null
            }
        }
        return TagMappingContext(catalog, Locale.getDefault())
    }

    private fun parseRemoteDocument(
        doc: DocumentSnapshot,
        tagContext: TagMappingContext,
    ): StructuredNote? {
        val type = doc.getString("type") ?: return null
        val text = doc.getString("text")
        val datetime = doc.getString("datetime") ?: ""
        val location = doc.getString("location") ?: ""
        val createdAt = doc.getLong("createdAt") ?: 0L
        val tagData = resolveTagData(
            (doc.get("tagIds") as? List<*>)?.toStringList().orEmpty(),
            (doc.get("tagLabels") as? List<*>)?.toStringList().orEmpty(),
            (doc.get("tags") as? List<*>)?.toStringList().orEmpty(),
            tagContext,
        )
        return when (type) {
            "todo" -> text?.let {
                val status = doc.getString("status") ?: ""
                val dueDate = doc.getString("dueDate") ?: ""
                val eventDate = doc.getString("eventDate") ?: ""
                StructuredNote.ToDo(
                    text = it,
                    status = status,
                    tagIds = tagData.tagIds,
                    tagLabels = tagData.tagLabels,
                    dueDate = dueDate,
                    eventDate = eventDate,
                    createdAt = createdAt,
                    id = doc.id,
                )
            }
            "memo" -> text?.let {
                val sectionAnchor = doc.getString("sectionAnchor")?.takeUnless { anchor -> anchor.isBlank() }
                val sectionTitle = doc.getString("sectionTitle")?.takeUnless { title -> title.isBlank() }
                StructuredNote.Memo(
                    text = it,
                    tagIds = tagData.tagIds,
                    tagLabels = tagData.tagLabels,
                    sectionAnchor = sectionAnchor,
                    sectionTitle = sectionTitle,
                    createdAt = createdAt,
                )
            }
            "event" -> text?.let { StructuredNote.Event(it, datetime, location, createdAt) }
            "free" -> text?.let {
                StructuredNote.Free(
                    text = it,
                    tagIds = tagData.tagIds,
                    tagLabels = tagData.tagLabels,
                    createdAt = createdAt,
                )
            }
            else -> null
        }
    }

    private fun resolveTagData(
        explicitIds: List<String>,
        explicitLabels: List<String>,
        legacyTags: List<String>,
        tagContext: TagMappingContext,
    ): TagData {
        var ids = sanitizeStrings(explicitIds)
        val labels = LinkedHashSet<String>()
        sanitizeStrings(explicitLabels).forEach { labels.add(it) }
        val legacy = sanitizeStrings(legacyTags)
        if (ids.isEmpty() && legacy.isNotEmpty()) {
            val migrated = tagContext.mapLegacy(legacy)
            ids = migrated.tagIds
            migrated.unresolvedLabels.forEach { labels.add(it) }
        } else if (legacy.isNotEmpty()) {
            val migrated = tagContext.mapLegacy(legacy)
            migrated.tagIds.forEach { id ->
                if (ids.none { existing -> existing == id }) {
                    ids = ids + id
                }
            }
            migrated.unresolvedLabels.forEach { labels.add(it) }
        }
        return TagData(ids, labels.toList())
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val values = mutableListOf<String>()
        for (idx in 0 until length()) {
            val value = optString(idx)
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) {
                values.add(trimmed)
            }
        }
        return sanitizeStrings(values)
    }

    private fun List<*>?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val values = mutableListOf<String>()
        for (element in this) {
            val value = (element as? String)?.trim()
            if (!value.isNullOrEmpty()) {
                values.add(value)
            }
        }
        return sanitizeStrings(values)
    }

    private fun sanitizeStrings(values: List<String>): List<String> {
        if (values.isEmpty()) return emptyList()
        val normalized = LinkedHashSet<String>()
        values.forEach { value ->
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) {
                normalized.add(trimmed)
            }
        }
        return normalized.toList()
    }

    private data class TagData(
        val tagIds: List<String>,
        val tagLabels: List<String>,
    )

    private data class TagMigrationResult(
        val tagIds: List<String>,
        val unresolvedLabels: List<String>,
    )

    private class TagMappingContext(
        catalog: TagCatalog?,
        private val locale: Locale,
    ) {
        private val canonicalIds: Set<String>
        private val idLookup: Map<String, String>
        private val labelLookup: Map<String, String>

        init {
            if (catalog == null) {
                canonicalIds = emptySet()
                idLookup = emptyMap()
                labelLookup = emptyMap()
            } else {
                val ids = LinkedHashSet<String>()
                val idsByLower = mutableMapOf<String, String>()
                val labelsByLower = mutableMapOf<String, String>()
                catalog.tags.forEach { definition ->
                    val id = definition.id.trim()
                    if (id.isEmpty()) return@forEach
                    val added = ids.add(id)
                    idsByLower.putIfAbsent(id.lowercase(Locale.US), id)
                    if (added) {
                        val preferred = definition.labelForLocale(locale)
                            ?: definition.labels.firstOrNull()?.value
                        preferred?.let { label ->
                            val normalized = label.trim().lowercase(Locale.US)
                            if (normalized.isNotEmpty()) {
                                labelsByLower.putIfAbsent(normalized, id)
                            }
                        }
                    }
                    definition.labels.forEach { localized ->
                        val normalized = localized.value.trim().lowercase(Locale.US)
                        if (normalized.isNotEmpty()) {
                            labelsByLower.putIfAbsent(normalized, id)
                        }
                    }
                }
                canonicalIds = ids
                idLookup = idsByLower
                labelLookup = labelsByLower
            }
        }

        fun mapLegacy(values: List<String>): TagMigrationResult {
            if (values.isEmpty()) return TagMigrationResult(emptyList(), emptyList())
            val resolved = LinkedHashSet<String>()
            val unresolved = mutableListOf<String>()
            values.forEach { raw ->
                val candidate = resolve(raw)
                if (candidate != null) {
                    resolved.add(candidate)
                } else {
                    unresolved.add(raw)
                }
            }
            return TagMigrationResult(resolved.toList(), unresolved)
        }

        private fun resolve(value: String): String? {
            if (value.isBlank()) return null
            if (canonicalIds.contains(value)) return value
            val lower = value.lowercase(Locale.US)
            idLookup[lower]?.let { return it }
            labelLookup[lower]?.let { return it }
            return null
        }

        companion object {
            val EMPTY = TagMappingContext(null, Locale.getDefault())
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
                        tagIds = item.tagIds,
                        tagLabels = item.tagLabels,
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
                        tagIds = item.tagIds,
                        tagLabels = item.tagLabels,
                    )
                )
            }
        }
        return notes
    }
}
