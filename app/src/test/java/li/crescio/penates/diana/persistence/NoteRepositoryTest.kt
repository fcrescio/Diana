package li.crescio.penates.diana.persistence

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.llm.Appointment
import li.crescio.penates.diana.llm.Thought
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.ThoughtDocument
import li.crescio.penates.diana.notes.ThoughtOutline
import li.crescio.penates.diana.notes.ThoughtOutlineSection
import li.crescio.penates.diana.tags.LocalizedLabel
import li.crescio.penates.diana.tags.TagCatalog
import li.crescio.penates.diana.tags.TagCatalogRepository
import li.crescio.penates.diana.tags.TagDefinition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

private const val SESSION_ID = "test-session"

class NoteRepositoryTest {

    @Test
    fun saveNotes_writesJsonLines_andSavesToFirestore() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()
        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val document = mockk<DocumentReference>()
        val existingDocument = mockk<DocumentReference>()

        val capturedAdds = mutableListOf<Map<String, Any>>()
        val capturedSet = mutableListOf<Map<String, Any>>()
        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.add(any()) } answers {
            capturedAdds.add(firstArg())
            Tasks.forResult(document)
        }
        every { collection.document("id1") } returns existingDocument
        every { existingDocument.set(any()) } answers {
            capturedSet.add(firstArg())
            Tasks.forResult(null)
        }

        val repo = NoteRepository(firestore, SESSION_ID, file)
        val notes = listOf(
            StructuredNote.ToDo(
                "task",
                status = "not_started",
                tagIds = listOf("home"),
                createdAt = 1L,
                id = "id1",
            ),
            StructuredNote.Memo("memo", tagIds = listOf("x"), createdAt = 2L),
            StructuredNote.Event("meet", "2024-05-01", "office", createdAt = 3L),
            StructuredNote.Free("free", tagIds = listOf("y"), createdAt = 4L)
        )

        repo.saveNotes(notes)

        val lines = file.readLines()
        assertEquals(notes.size, lines.size)
        lines.forEachIndexed { idx, line ->
            assertEquals(expectedMap(notes[idx]), lineToMap(line))
        }

        verify(exactly = notes.size - 1) { collection.add(any()) }
        verify(exactly = 1) { existingDocument.set(any()) }
        assertEquals(listOf(expectedMap(notes[0])), capturedSet)
        assertEquals(notes.drop(1).map { expectedMap(it) }, capturedAdds)
    }

    @Test
    fun saveSummary_generatesIdsForNewTodos() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()
        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val document = mockk<DocumentReference>()

        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.add(any()) } returns Tasks.forResult(document)
        every { document.id } returns "generated"

        val repo = NoteRepository(firestore, SESSION_ID, file)
        val summary = MemoSummary(
            todo = "",
            appointments = "",
            thoughts = "",
            todoItems = listOf(TodoItem("task", "not_started", emptyList())),
            appointmentItems = emptyList(),
            thoughtItems = emptyList()
        )

        val result = repo.saveSummary(summary)

        assertEquals("generated", result.todoItems[0].id)
    }

    @Test
    fun saveSummary_persistsThoughtDocument() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val notesFile = createTempFile().toFile()
        val parent = notesFile.absoluteFile.parentFile
        val markdownFile = File(parent, "thoughts.md")
        val outlineFile = File(parent, "thought_outline.json")

        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val docRef = mockk<DocumentReference>()

        val capturedDoc = mutableListOf<Map<String, Any>>()
        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.add(any()) } returns Tasks.forResult(mockk())
        every { collection.document(any()) } returns docRef
        every { docRef.set(any()) } answers {
            capturedDoc.add(firstArg())
            Tasks.forResult(null)
        }

        val repo = NoteRepository(firestore, SESSION_ID, notesFile)
        val document = ThoughtDocument(
            markdownBody = "# Heading\n\n## Sub\n\nDetails.",
            outline = ThoughtOutline(
                listOf(
                    ThoughtOutlineSection(
                        title = "Heading",
                        level = 1,
                        anchor = "heading",
                        children = listOf(
                            ThoughtOutlineSection("Sub", 2, "sub")
                        )
                    )
                )
            ),
        )
        val summary = MemoSummary(
            todo = "",
            appointments = "",
            thoughts = "",
            todoItems = emptyList(),
            appointmentItems = emptyList(),
            thoughtItems = emptyList(),
            thoughtDocument = document,
        )

        repo.saveSummary(summary, saveTodos = false, saveAppointments = false, saveThoughts = true)

        assertTrue(markdownFile.exists())
        assertEquals(document.markdownBody, markdownFile.readText())
        assertTrue(outlineFile.exists())
        val outlineJson = JSONObject(outlineFile.readText())
        val sections = outlineJson.getJSONArray("sections")
        assertEquals(1, sections.length())
        val root = sections.getJSONObject(0)
        assertEquals("Heading", root.getString("title"))
        val children = root.getJSONArray("children")
        assertEquals(1, children.length())
        assertEquals("Sub", children.getJSONObject(0).getString("title"))

        assertEquals(1, capturedDoc.size)
        val remote = capturedDoc.first()
        assertEquals("thought_document", remote["type"])
        assertEquals(document.markdownBody, remote["markdown"])
        val remoteOutline = remote["outline"] as List<*>
        assertEquals(1, remoteOutline.size)
        val remoteRoot = remoteOutline.first() as Map<*, *>
        assertEquals("Heading", remoteRoot["title"])
        val remoteChildren = remoteRoot["children"] as List<*>
        assertEquals(1, remoteChildren.size)
        val remoteChild = remoteChildren.first() as Map<*, *>
        assertEquals("Sub", remoteChild["title"])
    }

    @Test
    fun loadThoughtDocument_prefersLocalArtifacts() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val notesFile = createTempFile().toFile()
        val parent = notesFile.absoluteFile.parentFile
        val markdownFile = File(parent, "thoughts.md")
        markdownFile.writeText("# Local Heading\n\nDetails.")
        val outlineFile = File(parent, "thought_outline.json")
        val outlineJson = JSONObject()
        val sections = JSONObject()
            .put("title", "Local Heading")
            .put("level", 1)
            .put("anchor", "local-heading")
            .put("children", org.json.JSONArray())
        outlineJson.put("sections", org.json.JSONArray().put(sections))
        outlineFile.writeText(outlineJson.toString())

        val repo = NoteRepository(mockk(relaxed = true), SESSION_ID, notesFile)

        val document = repo.loadThoughtDocument()

        assertNotNull(document)
        val result = requireNotNull(document)
        assertEquals("# Local Heading\n\nDetails.", result.markdownBody)
        assertEquals(1, result.outline.sections.size)
        assertEquals("local-heading", result.outline.sections.first().anchor)
    }

    @Test
    fun loadThoughtDocument_fetchesRemoteWhenMissingLocal() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val notesFile = createTempFile().toFile()
        val parent = notesFile.absoluteFile.parentFile
        val markdownFile = File(parent, "thoughts.md")
        val outlineFile = File(parent, "thought_outline.json")

        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val docRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()

        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.document(any()) } returns docRef
        every { docRef.get() } returns Tasks.forResult(snapshot)
        every { snapshot.data } returns mutableMapOf(
            "type" to "thought_document",
            "markdown" to "# Remote Heading",
            "outline" to listOf(
                mapOf(
                    "title" to "Remote Heading",
                    "level" to 1,
                    "anchor" to "remote-heading",
                    "children" to emptyList<Map<String, Any>>()
                )
            )
        )

        val repo = NoteRepository(firestore, SESSION_ID, notesFile)

        val document = repo.loadThoughtDocument()

        assertNotNull(document)
        val result = requireNotNull(document)
        assertEquals("# Remote Heading", result.markdownBody)
        assertEquals(1, result.outline.sections.size)
        assertEquals("remote-heading", result.outline.sections.first().anchor)

        assertTrue(markdownFile.exists())
        assertTrue(outlineFile.exists())
    }

    @Test
    fun clearThoughts_removesThoughtDocumentArtifacts() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val notesFile = createTempFile().toFile()
        val parent = notesFile.absoluteFile.parentFile
        val markdownFile = File(parent, "thoughts.md").apply { writeText("content") }
        val outlineFile = File(parent, "thought_outline.json").apply {
            writeText(JSONObject().put("sections", org.json.JSONArray()).toString())
        }
        val memo = StructuredNote.Memo("note", tagIds = emptyList(), createdAt = 1L)
        notesFile.writeText(JSONObject(expectedMap(memo)).toString())

        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        val docRef = mockk<DocumentReference>()

        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.whereEqualTo(any<String>(), any()) } returns collection
        every { collection.get() } returns Tasks.forResult(querySnapshot)
        every { querySnapshot.documents } returns emptyList()
        every { collection.document(any()) } returns docRef
        every { docRef.delete() } returns Tasks.forResult(null)

        val repo = NoteRepository(firestore, SESSION_ID, notesFile)

        repo.clearThoughts()

        assertTrue(!markdownFile.exists())
        assertTrue(!outlineFile.exists())
        assertTrue(notesFile.readText().isBlank())
        verify { collection.document("__thought_document__") }
        verify { docRef.delete() }
    }

    @Test
    fun loadNotes_mergesLocalAndRemote_removingDuplicates_andSortsDescending() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()

        val localNotes = listOf(
            StructuredNote.ToDo("task", status = "not_started", tagIds = listOf(), createdAt = 100L, id = "id1"),
            StructuredNote.Memo("memo", tagIds = emptyList(), createdAt = 200L)
        )
        file.writeText(localNotes.joinToString("\n") { JSONObject(expectedMap(it)).toString() })

        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<DocumentSnapshot>(relaxed = true)
        val doc2 = mockk<DocumentSnapshot>(relaxed = true)
        val doc3 = mockk<DocumentSnapshot>(relaxed = true)

        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.get() } returns Tasks.forResult(querySnapshot)
        every { querySnapshot.documents } returns listOf(doc1, doc2, doc3)

        every { doc1.getString("type") } returns "todo"
        every { doc1.getString("text") } returns "task"
        every { doc1.getString("datetime") } returns ""
        every { doc1.getString("location") } returns ""
        every { doc1.getString("status") } returns "done"
        every { doc1.get("tagIds") } returns listOf("work")
        every { doc1.get("tagLabels") } returns null
        every { doc1.get("tags") } returns null
        every { doc1.getLong("createdAt") } returns 150L
        every { doc1.id } returns "id1"

        every { doc2.getString("type") } returns "memo"
        every { doc2.getString("text") } returns "memo"
        every { doc2.getString("datetime") } returns ""
        every { doc2.getString("location") } returns ""
        every { doc2.getLong("createdAt") } returns 250L
        every { doc2.get("tagIds") } returns listOf("remote")
        every { doc2.get("tagLabels") } returns null
        every { doc2.get("tags") } returns null
        every { doc2.id } returns "id2"

        every { doc3.getString("type") } returns "event"
        every { doc3.getString("text") } returns "meet"
        every { doc3.getString("datetime") } returns "2024-05-01"
        every { doc3.getString("location") } returns "office"
        every { doc3.getLong("createdAt") } returns 300L
        every { doc3.id } returns "id3"

        val repo = NoteRepository(firestore, SESSION_ID, file)

        val result = repo.loadNotes()

        val expected = listOf(
            StructuredNote.Event("meet", "2024-05-01", "office", createdAt = 300L),
            StructuredNote.Memo("memo", tagIds = emptyList(), createdAt = 200L),
            StructuredNote.ToDo("task", status = "not_started", tagIds = listOf(), createdAt = 100L, id = "id1")
        )

        assertEquals(expected, result)
    }

    @Test
    fun loadNotes_migratesLegacyTags_usingCatalog() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()
        val legacy = JSONObject()
            .put("type", "memo")
            .put("text", "note")
            .put("tags", JSONArray().put("Focus"))
            .put("datetime", "")
            .put("location", "")
            .put("createdAt", 10L)
        file.writeText(legacy.toString())

        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.get() } returns Tasks.forResult(querySnapshot)
        every { querySnapshot.documents } returns emptyList()

        val tagCatalogRepository = mockk<TagCatalogRepository>()
        coEvery { tagCatalogRepository.loadCatalog() } returns TagCatalog(
            listOf(
                TagDefinition(
                    id = "focus",
                    labels = listOf(LocalizedLabel.create(null, "Focus")),
                ),
            ),
        )

        val repo = NoteRepository(
            firestore = firestore,
            sessionId = SESSION_ID,
            notesFile = file,
            tagCatalogRepository = tagCatalogRepository,
        )

        val notes = repo.loadNotes()

        val memo = notes.single() as StructuredNote.Memo
        assertEquals(listOf("focus"), memo.tagIds)
        assertTrue(memo.tagLabels.isEmpty())
    }

    @Test
    fun loadNotes_flagsLegacyTags_withoutCatalogMatch() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()
        val legacy = JSONObject()
            .put("type", "free")
            .put("text", "idea")
            .put("tags", JSONArray().put("Mystery"))
            .put("datetime", "")
            .put("location", "")
            .put("createdAt", 5L)
        file.writeText(legacy.toString())

        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.get() } returns Tasks.forResult(querySnapshot)
        every { querySnapshot.documents } returns emptyList()

        val tagCatalogRepository = mockk<TagCatalogRepository>()
        coEvery { tagCatalogRepository.loadCatalog() } returns TagCatalog(emptyList())

        val repo = NoteRepository(
            firestore = firestore,
            sessionId = SESSION_ID,
            notesFile = file,
            tagCatalogRepository = tagCatalogRepository,
        )

        val notes = repo.loadNotes()

        val free = notes.single() as StructuredNote.Free
        assertTrue(free.tagIds.isEmpty())
        assertEquals(listOf("Mystery"), free.tagLabels)
    }

    @Test
    fun summaryToNotes_mapsThoughtItems() {
        System.setProperty("net.bytebuddy.experimental", "true")
        val repo = NoteRepository(mockk(), SESSION_ID, createTempFile().toFile())

        val summary = MemoSummary(
            todo = "",
            appointments = "",
            thoughts = "",
            todoItems = listOf(
                TodoItem("task one", "not_started", emptyList(), id = "id1"),
                TodoItem("task two", "done", listOf("tag"), id = "id2")
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
            .getDeclaredMethod(
                "summaryToNotes",
                MemoSummary::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }

        val result = method.invoke(repo, summary, true, true, true) as List<StructuredNote>

        val expected = listOf(
            StructuredNote.ToDo("task one", status = "not_started", tagIds = emptyList(), createdAt = (result[0] as StructuredNote.ToDo).createdAt, id = "id1"),
            StructuredNote.ToDo("task two", status = "done", tagIds = listOf("tag"), createdAt = (result[1] as StructuredNote.ToDo).createdAt, id = "id2"),
            StructuredNote.Event("meet Alice", "", "", createdAt = (result[2] as StructuredNote.Event).createdAt),
            StructuredNote.Event("meet Bob", "", "", createdAt = (result[3] as StructuredNote.Event).createdAt),
            StructuredNote.Memo("idea one", tagIds = listOf("a"), createdAt = (result[4] as StructuredNote.Memo).createdAt),
            StructuredNote.Memo("idea two", tagIds = emptyList(), createdAt = (result[5] as StructuredNote.Memo).createdAt)
        )

        assertEquals(expected, result)
    }

    @Test
    fun toJsonAndParse_roundTrip_returnsOriginalNote() {
        System.setProperty("net.bytebuddy.experimental", "true")
        val repo = NoteRepository(mockk(), SESSION_ID, createTempFile().toFile())

        val toJson = NoteRepository::class.java.getDeclaredMethod("toJson", StructuredNote::class.java)
            .apply { isAccessible = true }
        val tagContextClass = Class.forName(
            "li.crescio.penates.diana.persistence.NoteRepository\$TagMappingContext"
        )
        val companionField = tagContextClass.getDeclaredField("Companion").apply { isAccessible = true }
        val companion = companionField.get(null)
        val emptyContext = companion.javaClass.getDeclaredMethod("getEMPTY")
            .apply { isAccessible = true }
            .invoke(companion)
        val parse = NoteRepository::class.java
            .getDeclaredMethod("parse", String::class.java, tagContextClass)
            .apply { isAccessible = true }

        val notes = listOf(
            StructuredNote.ToDo("task", status = "not_started", tagIds = listOf("x"), createdAt = 1L, id = "id1"),
            StructuredNote.Memo("memo", tagIds = listOf("t"), createdAt = 2L),
            StructuredNote.Event("meet", "2024-05-01", "home", createdAt = 3L),
            StructuredNote.Free("free", tagIds = listOf("z"), createdAt = 4L)
        )

        notes.forEach { note ->
            val json = toJson.invoke(repo, note) as String
            val parsed = parse.invoke(repo, json, emptyContext) as StructuredNote
            assertEquals(note, parsed)
        }
    }

    @Test
    fun deleteTodoItem_removesFromFileAndFirestore() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()
        val todo1 = StructuredNote.ToDo("task1", status = "not_started", tagIds = emptyList(), createdAt = 1L, id = "id1")
        val todo2 = StructuredNote.ToDo("task2", status = "not_started", tagIds = emptyList(), createdAt = 2L, id = "id2")
        val memo = StructuredNote.Memo("memo", tagIds = emptyList(), createdAt = 3L)
        file.writeText(listOf(todo1, todo2, memo).joinToString("\n") { JSONObject(expectedMap(it)).toString() })

        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val docRef = mockk<DocumentReference>()

        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.document("id1") } returns docRef
        every { docRef.delete() } returns Tasks.forResult(null)

        val repo = NoteRepository(firestore, SESSION_ID, file)

        repo.deleteTodoItem("id1")

        val remaining = file.readLines().map { lineToMap(it) }
        val expected = listOf(expectedMap(todo2), expectedMap(memo))
        assertEquals(expected, remaining)
        verify { docRef.delete() }
    }

    @Test
    fun deleteAppointment_removesFromFileAndFirestore() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val file = createTempFile().toFile()
        val appt1 = StructuredNote.Event("meet", "2024-05-01T10:00:00Z", "office", createdAt = 1L)
        val appt2 = StructuredNote.Event("call", "2024-05-02T11:00:00Z", "home", createdAt = 2L)
        val memo = StructuredNote.Memo("memo", tagIds = emptyList(), createdAt = 3L)
        file.writeText(listOf(appt1, appt2, memo).joinToString("\n") { JSONObject(expectedMap(it)).toString() })

        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val collection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        val doc = mockk<DocumentSnapshot>()
        val docRef = mockk<DocumentReference>()

        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(SESSION_ID) } returns sessionDocument
        every { sessionDocument.collection("notes") } returns collection
        every { collection.whereEqualTo("type", "event") } returns collection
        every { collection.whereEqualTo("text", "meet") } returns collection
        every { collection.whereEqualTo("datetime", "2024-05-01T10:00:00Z") } returns collection
        every { collection.whereEqualTo("location", "office") } returns collection
        every { collection.get() } returns Tasks.forResult(querySnapshot)
        every { querySnapshot.documents } returns listOf(doc)
        every { doc.reference } returns docRef
        every { docRef.delete() } returns Tasks.forResult(null)

        val repo = NoteRepository(firestore, SESSION_ID, file)

        repo.deleteAppointment("meet", "2024-05-01T10:00:00Z", "office")

        val remaining = file.readLines().map { lineToMap(it) }
        val expected = listOf(expectedMap(appt2), expectedMap(memo))
        assertEquals(expected, remaining)
        verify { docRef.delete() }
    }

    private fun expectedMap(note: StructuredNote): Map<String, Any> = when (note) {
        is StructuredNote.ToDo -> buildMap {
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
        is StructuredNote.Memo -> buildMap {
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
        is StructuredNote.Event -> mapOf<String, Any>(
            "type" to "event",
            "text" to note.text,
            "datetime" to note.datetime,
            "location" to note.location,
            "createdAt" to note.createdAt
        )
        is StructuredNote.Free -> buildMap {
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
        obj.optJSONArray("tagIds")?.let { arr ->
            map["tagIds"] = (0 until arr.length()).map { arr.getString(it) }
        }
        obj.optJSONArray("tagLabels")?.takeIf { it.length() > 0 }?.let { arr ->
            map["tagLabels"] = (0 until arr.length()).map { arr.getString(it) }
        }
        obj.optJSONArray("tags")?.let { arr ->
            map["tags"] = (0 until arr.length()).map { arr.getString(it) }
        }
        obj.optString("dueDate", "").takeIf { it.isNotBlank() }?.let { map["dueDate"] = it }
        obj.optString("eventDate", "").takeIf { it.isNotBlank() }?.let { map["eventDate"] = it }
        obj.optString("id", null)?.let { map["id"] = it }
        obj.optString("sectionAnchor", null)?.takeIf { it.isNotBlank() }?.let { map["sectionAnchor"] = it }
        obj.optString("sectionTitle", null)?.takeIf { it.isNotBlank() }?.let { map["sectionTitle"] = it }
        return map
    }
}
