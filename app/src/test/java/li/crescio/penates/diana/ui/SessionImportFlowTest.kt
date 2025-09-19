package li.crescio.penates.diana.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import li.crescio.penates.diana.session.Session
import li.crescio.penates.diana.session.SessionRepository
import li.crescio.penates.diana.session.SessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.io.path.createTempDirectory

private class ImmediateDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        block.run()
    }
}

class SessionImportFlowTest {

    private lateinit var filesDir: File
    private val dispatcher = ImmediateDispatcher()

    @Before
    fun setup() {
        System.setProperty("net.bytebuddy.experimental", "true")
        filesDir = createTempDirectory(prefix = "session-import-flow").toFile()
    }

    @Test
    fun importRemoteSession_updatesTabsAndEnvironment() {
        val firestore = mockFirestore()
        val repository = SessionRepository(filesDir, firestore, dispatcher)
        val local = repository.create("Local", SessionSettings())
        repository.setSelected(local.id)

        val sessionsState = mutableStateListOf<Session>().apply { addAll(repository.list()) }
        val importableSessions = mutableStateListOf<Session>()
        val environmentSessionId = mutableStateOf(local.id)

        fun refreshSessions() {
            sessionsState.clear()
            sessionsState.addAll(repository.list())
        }

        fun switchSession(session: Session) {
            repository.setSelected(session.id)
            environmentSessionId.value = session.id
        }

        val remote = Session(
            id = "remote-123",
            name = "Remote",
            settings = SessionSettings(processTodos = false, processAppointments = true, processThoughts = true, model = "remote-model")
        )
        importableSessions += remote

        val importRemoteSession = { remoteSession: Session ->
            val imported = repository.importRemoteSession(remoteSession)
            refreshSessions()
            switchSession(imported)
            importableSessions.removeAll { it.id == imported.id }
        }

        importRemoteSession(remote)

        assertTrue(sessionsState.any { it.id == remote.id })
        assertEquals(remote.id, environmentSessionId.value)
        assertTrue(importableSessions.isEmpty())
        assertEquals(remote.id, repository.getSelected()?.id)
    }

    private fun mockFirestore(): FirebaseFirestore {
        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document(any()) } answers {
            val document = mockk<DocumentReference>()
            every { document.set(any()) } returns Tasks.forResult(null)
            every { document.collection(any()) } returns mockk(relaxed = true)
            every { document.delete() } returns Tasks.forResult(null)
            document
        }
        return firestore
    }
}
