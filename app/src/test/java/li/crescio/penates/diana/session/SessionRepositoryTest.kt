package li.crescio.penates.diana.session

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory

private class ImmediateDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        block.run()
    }
}

class SessionRepositoryTest {

    private lateinit var filesDir: File
    private val dispatcher = ImmediateDispatcher()

    @Before
    fun setup() {
        System.setProperty("net.bytebuddy.experimental", "true")
        filesDir = createTempDirectory(prefix = "session-repo-test").toFile()
    }

    @Test
    fun createSession_syncsDocumentWithSettings() {
        val (firestore, capturedSets) = mockFirestore()
        val repository = SessionRepository(filesDir, firestore, dispatcher)

        val session = repository.create(
            name = "New Session",
            settings = SessionSettings(
                processTodos = false,
                processAppointments = true,
                processThoughts = false,
                showTags = true,
                model = "gpt-4o",
            )
        )

        val recorded = capturedSets[session.id]
        assertNotNull(recorded)
        assertEquals(1, recorded!!.size)
        val payload = recorded.first()
        assertEquals("New Session", payload["name"])
        assertEquals("", payload["summaryGroup"])
        assertEquals(
            mapOf(
                "processTodos" to false,
                "processAppointments" to true,
                "processThoughts" to false,
                "showTags" to true,
                "model" to "gpt-4o",
            ),
            payload["settings"]
        )
        assertNull(payload["selectedSessionId"])
        assertEquals(false, payload["selected"])
    }

    @Test
    fun updateSession_whenSelected_syncsSelectionMetadata() {
        val (firestore, capturedSets) = mockFirestore()
        val repository = SessionRepository(filesDir, firestore, dispatcher)

        val original = repository.create("Original", SessionSettings())
        capturedSets[original.id]?.clear()

        repository.setSelected(original.id)
        capturedSets[original.id]?.clear()

        val updatedSettings = SessionSettings(
            processTodos = false,
            processAppointments = false,
            processThoughts = true,
            showTags = false,
            model = "sonoma",
        )
        val updated = repository.update(original.copy(name = "Renamed", settings = updatedSettings))

        val recorded = capturedSets[original.id]
        assertNotNull(recorded)
        assertEquals(1, recorded!!.size)
        val payload = recorded.single()
        assertEquals("Renamed", payload["name"])
        assertEquals("", payload["summaryGroup"])
        assertEquals(
            mapOf(
                "processTodos" to false,
                "processAppointments" to false,
                "processThoughts" to true,
                "showTags" to false,
                "model" to "sonoma",
            ),
            payload["settings"]
        )
        assertEquals(updated.id, payload["selectedSessionId"])
        assertEquals(true, payload["selected"])
    }

    @Test
    fun importRemoteSession_persistsAndSyncs() {
        val (firestore, capturedSets) = mockFirestore()
        val repository = SessionRepository(filesDir, firestore, dispatcher)

        val remote = Session(
            id = "remote-${UUID.randomUUID()}",
            name = "Remote Session",
            settings = SessionSettings(
                processTodos = true,
                processAppointments = false,
                processThoughts = true,
                showTags = true,
                model = "remote-model",
            ),
            summaryGroup = "Remote Group",
        )

        val persisted = repository.importRemoteSession(remote)

        val recorded = capturedSets[remote.id]
        assertNotNull(recorded)
        assertEquals(1, recorded!!.size)
        val payload = recorded.single()
        assertEquals("Remote Session", payload["name"])
        assertEquals("Remote Group", payload["summaryGroup"])
        assertEquals(
            mapOf(
                "processTodos" to true,
                "processAppointments" to false,
                "processThoughts" to true,
                "showTags" to true,
                "model" to "remote-model",
            ),
            payload["settings"]
        )
        assertNull(payload["selectedSessionId"])
        assertEquals(false, payload["selected"])
        assertEquals(remote, persisted)
    }

    @Test
    fun deleteSession_removesRemoteDocumentAndNotes() {
        val deletedSessions = mutableListOf<String>()
        val deletedNotes = mutableListOf<String>()

        val (firestore, capturedSets) = mockFirestore { id, document ->
            val notesCollection = mockk<CollectionReference>()
            val querySnapshot = mockk<QuerySnapshot>()
            val noteSnapshot = mockk<DocumentSnapshot>()
            val noteReference = mockk<DocumentReference>()

            every { noteSnapshot.reference } returns noteReference
            every { noteReference.delete() } answers {
                deletedNotes.add(id)
                Tasks.forResult(null)
            }
            every { querySnapshot.documents } returns listOf(noteSnapshot)
            every { notesCollection.get() } returns Tasks.forResult(querySnapshot)
            every { document.collection("notes") } returns notesCollection
            every { document.delete() } answers {
                deletedSessions.add(id)
                Tasks.forResult(null)
            }
        }

        val repository = SessionRepository(filesDir, firestore, dispatcher)
        val session = repository.create("To remove", SessionSettings())
        capturedSets[session.id]?.clear()

        val removed = repository.delete(session.id)

        assertTrue(removed)
        assertEquals(listOf(session.id), deletedSessions)
        assertEquals(listOf(session.id), deletedNotes)
        assertTrue(capturedSets[session.id]?.isEmpty() ?: true)
    }

    private fun mockFirestore(
        onDocument: (String, DocumentReference) -> Unit = { _, _ -> }
    ): Pair<FirebaseFirestore, MutableMap<String, MutableList<Map<String, Any?>>>> {
        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val capturedSets = mutableMapOf<String, MutableList<Map<String, Any?>>>()

        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(any()) } answers {
            val id = firstArg<String>()
            val document = mockk<DocumentReference>()
            every { document.set(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                val payload = firstArg<Map<String, Any?>>()
                capturedSets.getOrPut(id) { mutableListOf() }.add(payload)
                Tasks.forResult(null)
            }
            every { document.collection(any()) } returns mockk(relaxed = true)
            every { document.delete() } returns Tasks.forResult(null)
            onDocument(id, document)
            document
        }
        return firestore to capturedSets
    }
}
