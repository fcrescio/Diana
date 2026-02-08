package li.crescio.penates.diana

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import li.crescio.penates.diana.llm.LlmLogger
import li.crescio.penates.diana.llm.LlmModelCatalog
import li.crescio.penates.diana.llm.LlmResources
import li.crescio.penates.diana.llm.MemoProcessor
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.llm.Appointment
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.llm.Thought
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.ThoughtDocument
import li.crescio.penates.diana.persistence.NoteRepository
import li.crescio.penates.diana.player.AndroidPlayer
import li.crescio.penates.diana.player.Player
import li.crescio.penates.diana.ui.*
import li.crescio.penates.diana.ui.theme.DianaTheme
import li.crescio.penates.diana.R
import java.util.Locale
import java.io.File
import java.io.IOException
import li.crescio.penates.diana.onboarding.InviteValidationException
import li.crescio.penates.diana.onboarding.OnboardingScreen
import li.crescio.penates.diana.onboarding.redeemInvite
import li.crescio.penates.diana.persistence.MemoRepository
import li.crescio.penates.diana.session.Session
import li.crescio.penates.diana.session.SessionRepository
import li.crescio.penates.diana.session.SessionSettings
import li.crescio.penates.diana.tags.TagCatalogRepository
import li.crescio.penates.diana.tags.TagCatalogViewModel
import li.crescio.penates.diana.tags.toTagCatalog
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var applicationLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val firestore = FirebaseFirestore.getInstance()
        val sessionRepository = SessionRepository(filesDir, firestore)
        LlmResources.initialize(File(filesDir, "llm_resources"))
        val sessionInitialization = ensureInitialSession(sessionRepository)
        val permissionMessage =
            "Firestore PERMISSION_DENIED. Check security rules or authentication."
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            Log.i("MainActivity", "User already authenticated; launching application.")
            launchApplication(
                canRefreshOverrides = true,
                authException = null,
                firestore = firestore,
                sessionRepository = sessionRepository,
                sessionInitialization = sessionInitialization,
                permissionMessage = permissionMessage,
            )
        } else {
            setContent {
                DianaTheme {
                    OnboardingScreen(
                        validateAndSignIn = { encodedPayload ->
                            try {
                                val result = redeemInvite(encodedPayload, firestore, auth)
                                Log.i(
                                    "MainActivity",
                                    "Invite ${result.inviteId} redeemed; launching application.",
                                )
                            } catch (validationError: InviteValidationException) {
                                Log.w(
                                    "MainActivity",
                                    "Invite validation failed: ${validationError.message}",
                                )
                                throw validationError
                            } catch (error: Exception) {
                                Log.e(
                                    "MainActivity",
                                    "Unexpected error while redeeming invite",
                                    error,
                                )
                                throw error
                            }
                        },
                        onAuthenticated = {
                            launchApplication(
                                canRefreshOverrides = true,
                                authException = null,
                                firestore = firestore,
                                sessionRepository = sessionRepository,
                                sessionInitialization = sessionInitialization,
                                permissionMessage = permissionMessage,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun launchApplication(
        canRefreshOverrides: Boolean,
        authException: Exception?,
        firestore: FirebaseFirestore,
        sessionRepository: SessionRepository,
        sessionInitialization: SessionInitialization,
        permissionMessage: String,
    ) {
        if (applicationLaunched) {
            Log.w("MainActivity", "launchApplication invoked more than once; ignoring subsequent call.")
            return
        }
        applicationLaunched = true
        lifecycleScope.launch {
            if (canRefreshOverrides) {
                Log.i("MainActivity", "LLM refresh: start (remote overrides enabled)")
                var refreshResult = "completed successfully"
                try {
                    LlmResources.refreshFromFirestore(firestore)
                } catch (e: Exception) {
                    refreshResult = "failed: ${e.message ?: "no message"}"
                    Log.e("MainActivity", "Failed to update LLM resources", e)
                }
                Log.i("MainActivity", "LLM refresh: end ($refreshResult)")
            } else {
                val authCode = (authException as? FirebaseAuthException)?.errorCode
                val message = authException?.message ?: "unknown error"
                val codeInfo = authCode?.let { "code=$it, " } ?: ""
                Log.w(
                    "MainActivity",
                    "LLM refresh: start (skipped due to auth failure: ${codeInfo}message=$message)",
                )
                Log.w(
                    "MainActivity",
                    "LLM refresh: end (skipped due to auth failure: ${codeInfo}message=$message)",
                )
            }
            if (sessionInitialization.createdDefaultSession && sessionInitialization.session != null) {
                migrateLegacyNotesCollection(firestore, sessionInitialization.session.id)
            }
            val initialEnvironment = sessionInitialization.session?.let {
                createSessionEnvironment(it, firestore)
            }
            val initialImportSessions = try {
                sessionRepository.fetchRemoteSessions()
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to fetch remote sessions", e)
                emptyList()
            }
            val testDoc = firestore.collection("test").document("init")
            testDoc.set(mapOf("ping" to "pong")).addOnSuccessListener {
                testDoc.get().addOnFailureListener { e ->
                    if (e is FirebaseFirestoreException &&
                        e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    ) {
                        Log.e("MainActivity", permissionMessage, e)
                        Toast.makeText(this@MainActivity, permissionMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }.addOnFailureListener { e ->
                if (e is FirebaseFirestoreException &&
                    e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                ) {
                    Log.e("MainActivity", permissionMessage, e)
                    Toast.makeText(this@MainActivity, permissionMessage, Toast.LENGTH_LONG).show()
                }
            }
            setContent {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(1200)
                    showSplash = false
                }
                var environment by remember { mutableStateOf<SessionEnvironment?>(initialEnvironment) }
                val sessionsState = remember {
                    mutableStateListOf<Session>().apply { addAll(sessionRepository.list()) }
                }
                val sessionTodoProgress = remember { mutableStateMapOf<String, SessionTodoProgress>() }
                val importableSessions = remember {
                    mutableStateListOf<Session>().apply { addAll(initialImportSessions) }
                }
                val coroutineScope = rememberCoroutineScope()
                val context = this@MainActivity
                var showSessionSelection by remember { mutableStateOf(true) }

                fun refreshSessionTodoProgress(sessionId: String, items: List<TodoItem>? = null) {
                    if (items != null) {
                        val progress = SessionTodoProgress.fromTodoItems(items)
                        if (progress == null) {
                            sessionTodoProgress.remove(sessionId)
                        } else {
                            sessionTodoProgress[sessionId] = progress
                        }
                        return
                    }
                    coroutineScope.launch {
                        val progress = loadSessionTodoProgress(sessionId)
                        if (progress == null) {
                            sessionTodoProgress.remove(sessionId)
                        } else {
                            sessionTodoProgress[sessionId] = progress
                        }
                    }
                }

                fun refreshTodoProgressForSessions(sessions: List<Session>) {
                    val validIds = sessions.map { it.id }.toSet()
                    sessionTodoProgress.keys.retainAll(validIds)
                    sessions.forEach { session ->
                        refreshSessionTodoProgress(session.id)
                    }
                }

                LaunchedEffect(Unit) {
                    refreshTodoProgressForSessions(sessionsState)
                }

                fun refreshSessions() {
                    sessionsState.clear()
                    sessionsState.addAll(sessionRepository.list())
                    refreshTodoProgressForSessions(sessionsState)
                }

                fun refreshRemoteSessionsList() {
                    coroutineScope.launch {
                        try {
                            val remoteSessions = sessionRepository.fetchRemoteSessions()
                            importableSessions.clear()
                            importableSessions.addAll(remoteSessions)
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Failed to refresh remote sessions", e)
                        }
                    }
                }

                val switchSession: (Session) -> Unit = { targetSession ->
                    sessionRepository.setSelected(targetSession.id)
                    val resolved = sessionRepository.getSelected() ?: targetSession
                    environment = createSessionEnvironment(resolved, firestore)
                    refreshSessionTodoProgress(resolved.id)
                }

                val openSession: (Session) -> Unit = { targetSession ->
                    showSessionSelection = false
                    switchSession(targetSession)
                }

                val addSession: () -> Unit = {
                    coroutineScope.launch {
                        val wasEmpty = sessionsState.isEmpty()
                        val name = context.generateSessionName(sessionsState.map { it.name })
                        val created = sessionRepository.create(name, SessionSettings())
                        if (wasEmpty) {
                            migrateLegacyMemosToDefaultSession(created.id)
                            migrateLegacyNotesCollection(firestore, created.id)
                        }
                        refreshSessions()
                        openSession(created)
                        try {
                            sessionRepository.syncSessionRemote(created)
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Failed to sync session ${created.id} to Firestore", e)
                        }
                    }
                }

                val renameSession: (Session, String) -> Unit = { session, newName ->
                    coroutineScope.launch {
                        val trimmed = newName.trim()
                        if (trimmed.isEmpty() || trimmed == session.name) {
                            return@launch
                        }
                        val persisted = sessionRepository.update(session.copy(name = trimmed))
                        refreshSessions()
                        if (environment?.session?.id == persisted.id) {
                            environment = environment?.copy(session = persisted)
                        }
                        try {
                            sessionRepository.syncSessionRemote(persisted)
                        } catch (e: Exception) {
                            Log.w(
                                "MainActivity",
                                "Failed to sync session ${persisted.id} to Firestore",
                                e,
                            )
                        }
                    }
                }

                val updateSummaryGroup: (Session, String) -> Unit = { session, newSummaryGroup ->
                    coroutineScope.launch {
                        val trimmed = newSummaryGroup.trim()
                        if (trimmed == session.summaryGroup) {
                            return@launch
                        }
                        val persisted = sessionRepository.update(session.copy(summaryGroup = trimmed))
                        refreshSessions()
                        if (environment?.session?.id == persisted.id) {
                            environment = environment?.copy(session = persisted)
                        }
                        try {
                            sessionRepository.syncSessionRemote(persisted)
                        } catch (e: Exception) {
                            Log.w(
                                "MainActivity",
                                "Failed to sync session ${persisted.id} to Firestore",
                                e,
                            )
                        }
                    }
                }

                fun handleDeleteSession(
                    session: Session,
                    deleteAction: (String) -> Boolean,
                ) {
                    val wasSelected = environment?.session?.id == session.id
                    if (deleteAction(session.id)) {
                        sessionTodoProgress.remove(session.id)
                        refreshSessions()
                        val currentSelected = sessionRepository.getSelected()
                        when {
                            currentSelected != null && wasSelected -> switchSession(currentSelected)
                            currentSelected != null -> Unit
                            sessionsState.isNotEmpty() -> switchSession(sessionsState.first())
                            else -> {
                                environment = null
                            }
                        }
                    }
                }

                val deleteSessionLocal: (Session) -> Unit = { session ->
                    handleDeleteSession(session) { id -> sessionRepository.deleteLocal(id) }
                }

                val deleteSessionRemote: (Session) -> Unit = { session ->
                    handleDeleteSession(session) { id -> sessionRepository.deleteLocalAndRemote(id) }
                }

                val importRemoteSession: (Session) -> Unit = { remoteSession ->
                    val imported = sessionRepository.importRemoteSession(remoteSession)
                    refreshSessions()
                    openSession(imported)
                    importableSessions.removeAll { it.id == imported.id }
                    refreshRemoteSessionsList()
                }

                DianaTheme {
                    if (showSplash) {
                        SplashScreen(modifier = Modifier.fillMaxSize())
                    } else {
                        val activeEnvironment = environment
                        if (showSessionSelection || activeEnvironment == null) {
                            SessionSelectionScreen(
                                sessions = sessionsState,
                                selectedSessionId = activeEnvironment?.session?.id,
                                importableSessions = importableSessions,
                                sessionTodoProgress = sessionTodoProgress,
                                onSelectSession = openSession,
                                onAddSession = addSession,
                                onRenameSession = renameSession,
                                onUpdateSummaryGroup = updateSummaryGroup,
                                onDeleteSessionLocal = deleteSessionLocal,
                                onDeleteSessionRemote = deleteSessionRemote,
                                onImportRemoteSession = importRemoteSession,
                                onRefreshImportableSessions = { refreshRemoteSessionsList() },
                            )
                        } else {
                            DianaApp(
                                session = activeEnvironment.session,
                                sessions = sessionsState,
                                importableSessions = importableSessions,
                                repository = activeEnvironment.noteRepository,
                                memoRepository = activeEnvironment.memoRepository,
                                tagCatalogRepository = activeEnvironment.tagCatalogRepository,
                                createNoteRepository = { target ->
                                    createSessionEnvironment(target, firestore).noteRepository
                                },
                                onSessionTodosChanged = { sessionId, items ->
                                    refreshSessionTodoProgress(sessionId, items)
                                },
                                onUpdateSession = { updatedSession ->
                                    val persisted = sessionRepository.update(updatedSession)
                                    environment = environment?.copy(session = persisted)
                                    refreshSessions()
                                    coroutineScope.launch {
                                        try {
                                            sessionRepository.syncSessionRemote(persisted)
                                        } catch (e: Exception) {
                                            Log.w(
                                                "MainActivity",
                                                "Failed to sync session ${persisted.id} to Firestore",
                                                e,
                                            )
                                        }
                                    }
                                    persisted
                                },
                                onSwitchSession = switchSession,
                                onAddSession = addSession,
                                onRenameSession = renameSession,
                                onUpdateSummaryGroup = updateSummaryGroup,
                                onDeleteSessionLocal = deleteSessionLocal,
                                onDeleteSessionRemote = deleteSessionRemote,
                                onImportRemoteSession = importRemoteSession,
                                onRefreshImportableSessions = { refreshRemoteSessionsList() },
                                onNavigateBackToSessionList = { showSessionSelection = true },
                            )
                        }
                    }
                }
            }
        }

    }

    private fun ensureInitialSession(sessionRepository: SessionRepository): SessionInitialization {
        val selected = sessionRepository.getSelected()
        if (selected != null) {
            return SessionInitialization(selected, createdDefaultSession = false)
        }

        val existing = sessionRepository.list().firstOrNull()
        if (existing != null) {
            sessionRepository.setSelected(existing.id)
            return SessionInitialization(existing, createdDefaultSession = false)
        }

        sessionRepository.setSelected(null)
        return SessionInitialization(session = null, createdDefaultSession = false)
    }

    private fun createSessionEnvironment(
        session: Session,
        firestore: FirebaseFirestore,
    ): SessionEnvironment {
        val sessionDir = File(filesDir, "sessions/${session.id}")
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }

        val notesFile = File(sessionDir, "notes.txt")
        val memoFile = File(filesDir, "memos_${session.id}.txt")

        migrateLegacyFile(File(filesDir, "notes.txt"), notesFile)
        migrateLegacyFile(File(sessionDir, "memos.txt"), memoFile)

        val tagCatalogRepository = TagCatalogRepository(
            sessionId = session.id,
            sessionDir = sessionDir,
            firestore = firestore,
        )
        val noteRepository = NoteRepository(
            firestore = firestore,
            sessionId = session.id,
            notesFile = notesFile,
            tagCatalogRepository = tagCatalogRepository,
        )
        return SessionEnvironment(
            session = session,
            noteRepository = noteRepository,
            memoRepository = MemoRepository(memoFile),
            tagCatalogRepository = tagCatalogRepository,
        )
    }

    private suspend fun loadSessionTodoProgress(sessionId: String): SessionTodoProgress? {
        val sessionDir = File(filesDir, "sessions/$sessionId")
        val notesFile = File(sessionDir, "notes.txt")
        if (!notesFile.exists()) {
            return null
        }

        return withContext(Dispatchers.IO) {
            if (!notesFile.exists()) {
                return@withContext null
            }
            var total = 0
            var completed = 0
            try {
                notesFile.useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        try {
                            val obj = JSONObject(line)
                            if (obj.optString("type") == "todo") {
                                total++
                                if (SessionTodoProgress.isCompletedStatus(obj.optString("status", ""))) {
                                    completed++
                                }
                            }
                        } catch (_: Exception) {
                            // ignore malformed lines
                        }
                    }
                }
            } catch (_: Exception) {
                return@withContext null
            }
            SessionTodoProgress.fromCounts(total, completed)
        }
    }

    private fun migrateLegacyMemosToDefaultSession(defaultSessionId: String) {
        val legacyMemos = File(filesDir, "memos.txt")
        val defaultSessionMemos = File(filesDir, "memos_${defaultSessionId}.txt")
        migrateLegacyFile(legacyMemos, defaultSessionMemos)
    }

    private suspend fun migrateLegacyNotesCollection(
        firestore: FirebaseFirestore,
        sessionId: String,
    ) {
        try {
            val legacyCollection = firestore.collection("notes")
            val snapshot = legacyCollection.get().await()
            if (snapshot.isEmpty) {
                return
            }
            val sessionCollection = firestore
                .collection("sessions")
                .document(sessionId)
                .collection("notes")
            for (doc in snapshot.documents) {
                val data = doc.data ?: continue
                try {
                    sessionCollection.document(doc.id).set(data).await()
                    doc.reference.delete().await()
                } catch (e: Exception) {
                    Log.w(
                        "MainActivity",
                        "Failed to migrate document ${doc.id} from legacy notes collection",
                        e,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to migrate legacy notes collection", e)
        }
    }

    private fun migrateLegacyFile(source: File, target: File) {
        if (!source.exists() || target.exists()) {
            return
        }
        try {
            source.copyTo(target, overwrite = false)
            source.delete()
        } catch (e: IOException) {
            Log.w("MainActivity", "Failed to migrate legacy file: ${source.absolutePath}", e)
        }
    }
}

private fun Context.generateSessionName(existingNames: Collection<String>): String {
    var index = existingNames.size + 1
    while (true) {
        val candidate = getString(R.string.session_default_name, index)
        if (existingNames.none { it.equals(candidate, ignoreCase = false) }) {
            return candidate
        }
        index++
    }
}

private data class SessionTodoProgress(
    val completed: Int,
    val total: Int,
) {
    val open: Int get() = (total - completed).coerceAtLeast(0)
    val completedFraction: Float get() = if (total <= 0) 0f else completed.toFloat() / total

    companion object {
        private val COMPLETED_STATUSES = setOf("done", "cancelled", "not_required")

        fun fromCounts(total: Int, completed: Int): SessionTodoProgress? {
            if (total <= 0) return null
            val sanitized = completed.coerceIn(0, total)
            return SessionTodoProgress(sanitized, total)
        }

        fun fromTodoItems(items: List<TodoItem>): SessionTodoProgress? {
            if (items.isEmpty()) return null
            val completed = items.count { isCompletedStatus(it.status) }
            return fromCounts(items.size, completed)
        }

        fun isCompletedStatus(status: String): Boolean {
            if (status.isBlank()) return false
            val normalized = status.lowercase(Locale.getDefault())
            return normalized in COMPLETED_STATUSES
        }
    }
}

private data class SessionEnvironment(
    val session: Session,
    val noteRepository: NoteRepository,
    val memoRepository: MemoRepository,
    val tagCatalogRepository: TagCatalogRepository,
)

private data class SessionInitialization(
    val session: Session?,
    val createdDefaultSession: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSelectionScreen(
    sessions: List<Session>,
    selectedSessionId: String?,
    importableSessions: List<Session>,
    sessionTodoProgress: Map<String, SessionTodoProgress>,
    onSelectSession: (Session) -> Unit,
    onAddSession: () -> Unit,
    onRenameSession: (Session, String) -> Unit,
    onUpdateSummaryGroup: (Session, String) -> Unit,
    onDeleteSessionLocal: (Session) -> Unit,
    onDeleteSessionRemote: (Session) -> Unit,
    onImportRemoteSession: (Session) -> Unit,
    onRefreshImportableSessions: () -> Unit,
) {
    var showImportDialog by remember { mutableStateOf(false) }
    val title = stringResource(R.string.session_list_title)
    val addLabel = stringResource(R.string.add_session)
    val importLabel = stringResource(R.string.import_remote_sessions)
    val emptyTitle = stringResource(R.string.no_sessions)
    val emptyMessage = stringResource(R.string.no_active_session_message)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                actions = {
                    IconButton(
                        onClick = {
                            onRefreshImportableSessions()
                            showImportDialog = true
                        }
                    ) {
                        BadgedBox(
                            badge = {
                                if (importableSessions.isNotEmpty()) {
                                    val badgeText = if (importableSessions.size > 99) {
                                        "99+"
                                    } else {
                                        importableSessions.size.toString()
                                    }
                                    Badge { Text(badgeText) }
                                }
                            }
                        ) {
                            Icon(imageVector = Icons.Filled.Cloud, contentDescription = importLabel)
                        }
                    }
                    IconButton(onClick = onAddSession) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = addLabel)
                    }
                }
            )
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = emptyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onAddSession) {
                    Text(addLabel)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionListItem(
                        session = session,
                        selected = session.id == selectedSessionId,
                        todoProgress = sessionTodoProgress[session.id],
                        onSelect = { onSelectSession(session) },
                        onRename = { newName -> onRenameSession(session, newName) },
                        onUpdateSummaryGroup = { newGroup -> onUpdateSummaryGroup(session, newGroup) },
                        onDeleteLocal = { onDeleteSessionLocal(session) },
                        onDeleteRemote = { onDeleteSessionRemote(session) },
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        ImportSessionsDialog(
            sessions = importableSessions,
            onDismiss = { showImportDialog = false },
            onImportSession = { remoteSession ->
                onImportRemoteSession(remoteSession)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun SessionListItem(
    session: Session,
    selected: Boolean,
    todoProgress: SessionTodoProgress?,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onUpdateSummaryGroup: (String) -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteRemote: () -> Unit,
) {
    val renameLabel = stringResource(R.string.rename_session)
    val editSummaryGroupLabel = stringResource(R.string.edit_summary_group)
    val summaryGroupLabel = stringResource(R.string.session_summary_group)
    val deleteLabel = stringResource(R.string.delete_session)
    val actionsLabel = stringResource(R.string.session_actions)
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSummaryGroupDialog by remember { mutableStateOf(false) }

    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (selected) 6.dp else 1.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            todoProgress?.let {
                SessionTodoProgressBackground(progress = it, selected = selected)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = Icons.Filled.MoreVert, contentDescription = actionsLabel)
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(renameLabel) },
                onClick = {
                    menuExpanded = false
                    showRenameDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text(editSummaryGroupLabel) },
                onClick = {
                    menuExpanded = false
                    showSummaryGroupDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text(deleteLabel) },
                onClick = {
                    menuExpanded = false
                    showDeleteDialog = true
                }
            )
        }
    }

    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(session.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(renameLabel) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.session_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            showRenameDialog = false
                            onRename(trimmed)
                        }
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (showSummaryGroupDialog) {
        var summaryGroupText by remember { mutableStateOf(session.summaryGroup) }
        AlertDialog(
            onDismissRequest = { showSummaryGroupDialog = false },
            title = { Text(editSummaryGroupLabel) },
            text = {
                OutlinedTextField(
                    value = summaryGroupText,
                    onValueChange = { summaryGroupText = it },
                    label = { Text(summaryGroupLabel) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSummaryGroupDialog = false
                        onUpdateSummaryGroup(summaryGroupText.trim())
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSummaryGroupDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (showDeleteDialog) {
        DeleteSessionDialog(
            sessionName = session.name,
            onDismiss = { showDeleteDialog = false },
            onDeleteLocal = onDeleteLocal,
            onDeleteRemote = onDeleteRemote
        )
    }
}

@Composable
private fun SessionTodoProgressBackground(progress: SessionTodoProgress, selected: Boolean) {
    val openAlpha = if (selected) 0.35f else 0.2f
    val doneAlpha = if (selected) 0.45f else 0.3f
    val openColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = openAlpha)
    val doneColor = MaterialTheme.colorScheme.primary.copy(alpha = doneAlpha)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
    ) {
        if (progress.open > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(openColor)
            )
        }
        if (progress.completed > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.completedFraction)
                    .background(doneColor)
            )
        }
    }
}

@Composable
fun DianaApp(
    session: Session,
    sessions: List<Session>,
    importableSessions: List<Session>,
    repository: NoteRepository,
    memoRepository: MemoRepository,
    tagCatalogRepository: TagCatalogRepository,
    createNoteRepository: (Session) -> NoteRepository,
    onSessionTodosChanged: (String, List<TodoItem>?) -> Unit,
    onUpdateSession: (Session) -> Session,
    onSwitchSession: (Session) -> Unit,
    onAddSession: () -> Unit,
    onRenameSession: (Session, String) -> Unit,
    onUpdateSummaryGroup: (Session, String) -> Unit,
    onDeleteSessionLocal: (Session) -> Unit,
    onDeleteSessionRemote: (Session) -> Unit,
    onImportRemoteSession: (Session) -> Unit,
    onRefreshImportableSessions: () -> Unit,
    onNavigateBackToSessionList: () -> Unit,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    val logs = remember { mutableStateListOf<String>() }
    var showImportDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (screen != Screen.List) {
            screen = Screen.List
        } else {
            onNavigateBackToSessionList()
        }
    }

    fun addLog(message: String) {
        logs.add(message)
        if (logs.size > 100) {
            logs.removeAt(0)
        }
    }
    val logger = remember { LlmLogger() }
    var todo by remember(session.id) { mutableStateOf("") }
    var todoItems by remember(session.id) { mutableStateOf(listOf<TodoItem>()) }
    var appointments by remember(session.id) { mutableStateOf(listOf<Appointment>()) }
    var thoughtNotes by remember(session.id) { mutableStateOf(listOf<StructuredNote>()) }
    var thoughtDocument by remember(session.id) { mutableStateOf<ThoughtDocument?>(null) }
    var pendingMoveRequest by remember(session.id) { mutableStateOf<SessionItemMoveRequest?>(null) }
    val scope = rememberCoroutineScope()
    val player: Player = remember { AndroidPlayer() }
    val logRecorded = stringResource(R.string.log_recorded_memo)
    val logAdded = stringResource(R.string.log_added_memo)
    val processingText = stringResource(R.string.processing)
    val logLlmFailed = stringResource(R.string.log_llm_failed)
    val retryLabel = stringResource(R.string.retry)
    val cancelLabel = stringResource(R.string.cancel)
    val logApiKeyMissing = stringResource(R.string.api_key_missing)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val modelOptions = remember(context) {
        val resources = context.resources
        val packageName = context.packageName
        val options = LlmModelCatalog.availableModels().mapNotNull { definition ->
            val resId = resources.getIdentifier(definition.labelResourceName, "string", packageName)
            if (resId == 0) {
                Log.w("MainActivity", "Missing string resource for model label ${definition.labelResourceName}")
                null
            } else {
                LlmModelOption(definition.id, resId)
            }
        }
        if (options.isEmpty()) {
            listOf(LlmModelOption(MemoProcessor.DEFAULT_MODEL, R.string.model_mistral_nemo))
        } else {
            options
        }
    }
    val availableModelIds = remember(modelOptions) { modelOptions.map { it.id } }

    fun sanitizeModel(model: String): String {
        return when {
            availableModelIds.isEmpty() -> MemoProcessor.DEFAULT_MODEL
            availableModelIds.contains(model) -> model
            availableModelIds.contains(MemoProcessor.DEFAULT_MODEL) -> MemoProcessor.DEFAULT_MODEL
            else -> availableModelIds.first()
        }
    }

    val sanitizedModel = sanitizeModel(session.settings.model)
    var activeSession by remember(session.id) {
        mutableStateOf(session.copy(settings = session.settings.copy(model = sanitizedModel)))
    }
    var processTodos by remember(session.id) { mutableStateOf(activeSession.settings.processTodos) }
    var processAppointments by remember(session.id) { mutableStateOf(activeSession.settings.processAppointments) }
    var processThoughts by remember(session.id) { mutableStateOf(activeSession.settings.processThoughts) }
    var showTags by remember(session.id) { mutableStateOf(activeSession.settings.showTags) }
    var selectedModel by remember(session.id) { mutableStateOf(sanitizedModel) }
    val processor = remember(session.id) {
        MemoProcessor(
            BuildConfig.OPENROUTER_API_KEY,
            logger,
            locale,
            initialModel = sanitizedModel,
            tagCatalogRepository = tagCatalogRepository,
        )
    }
    val tagCatalogViewModel = remember(session.id) {
        TagCatalogViewModel(tagCatalogRepository)
    }
    DisposableEffect(tagCatalogViewModel) {
        onDispose { tagCatalogViewModel.dispose() }
    }
    val tagCatalogState by tagCatalogViewModel.uiState.collectAsState()
    val currentTagCatalog = remember(tagCatalogState.tags) { tagCatalogState.toTagCatalog() }
    LaunchedEffect(session) {
        val sanitized = sanitizeModel(session.settings.model)
        val sanitizedSession = session.copy(settings = session.settings.copy(model = sanitized))
        activeSession = sanitizedSession
        processTodos = sanitizedSession.settings.processTodos
        processAppointments = sanitizedSession.settings.processAppointments
        processThoughts = sanitizedSession.settings.processThoughts
        showTags = sanitizedSession.settings.showTags
        selectedModel = sanitized
        processor.model = sanitized
    }

    LaunchedEffect(currentTagCatalog) {
        processor.updateTagCatalog(currentTagCatalog)
    }

    fun persistSettings(transform: (SessionSettings) -> SessionSettings): SessionSettings {
        val updatedSettings = transform(activeSession.settings)
        val sanitizedSettings = updatedSettings.copy(model = sanitizeModel(updatedSettings.model))
        val updatedSession = activeSession.copy(settings = sanitizedSettings)
        val persistedSession = onUpdateSession(updatedSession)
        activeSession = persistedSession
        val finalSettings = persistedSession.settings
        processTodos = finalSettings.processTodos
        processAppointments = finalSettings.processAppointments
        processThoughts = finalSettings.processThoughts
        showTags = finalSettings.showTags
        selectedModel = finalSettings.model
        return finalSettings
    }

    fun syncProcessor() {
        val thoughtItems = thoughtNotes.map { note ->
            when (note) {
                is StructuredNote.Memo -> Thought(
                    text = note.text,
                    tagIds = note.tagIds,
                    tagLabels = note.resolvedTagLabels(currentTagCatalog, locale),
                )
                is StructuredNote.Free -> Thought(
                    text = note.text,
                    tagIds = note.tagIds,
                    tagLabels = note.resolvedTagLabels(currentTagCatalog, locale),
                )
                else -> throw IllegalStateException("Unexpected note type")
            }
        }
        val todoText = todoItems.joinToString("\n") { it.text }
        val summary = MemoSummary(
            todo = todoText,
            appointments = appointments.joinToString("\n") { it.text },
            thoughts = thoughtItems.joinToString("\n") { it.text },
            todoItems = todoItems,
            appointmentItems = appointments,
            thoughtItems = thoughtItems,
            thoughtDocument = thoughtDocument,
        )
        todo = todoText
        scope.launch { processor.initialize(summary) }
    }

    LaunchedEffect(logger) {
        logger.logFlow.collect { addLog(it) }
    }

    LaunchedEffect(session, repository) {
        val notes = repository.loadNotes()
        val todoNotes = notes.filterIsInstance<StructuredNote.ToDo>()
        val eventNotes = notes.filterIsInstance<StructuredNote.Event>()
        val memoNotes = notes.filterIsInstance<StructuredNote.Memo>()
        val freeNotes = notes.filterIsInstance<StructuredNote.Free>()
        val updatedTodos = todoNotes.map {
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
        todoItems = updatedTodos
        appointments = eventNotes.map { Appointment(it.text, it.datetime, it.location) }
        thoughtDocument = repository.loadThoughtDocument()
        thoughtNotes = memoNotes + freeNotes
        onSessionTodosChanged(session.id, updatedTodos)
        syncProcessor()
    }

    fun processMemo(memo: Memo) {
        if (BuildConfig.OPENROUTER_API_KEY.isBlank()) {
            addLog(logApiKeyMissing)
            scope.launch {
                snackbarHostState.showSnackbar(logApiKeyMissing)
                screen = Screen.List
            }
            return
        }
        scope.launch {
            try {
                val summary = processor.process(
                    memo,
                    processTodos,
                    processAppointments,
                    processThoughts,
                    sessionId = session.id,
                )
                if (processTodos) {
                    todo = summary.todo
                    todoItems = summary.todoItems
                }
                if (processAppointments) {
                    appointments = summary.appointmentItems
                }
                if (processThoughts) {
                    thoughtNotes = summary.thoughtItems.map {
                        StructuredNote.Memo(
                            text = it.text,
                            tagIds = it.tagIds,
                            tagLabels = it.tagLabels,
                        )
                    }
                    thoughtDocument = summary.thoughtDocument ?: thoughtDocument
                }
                val saved = repository.saveSummary(
                    summary,
                    processTodos,
                    processAppointments,
                    processThoughts,
                    changeSet = summary.todoChangeSet,
                )
                if (processTodos) {
                    todoItems = saved.todoItems
                    onSessionTodosChanged(session.id, saved.todoItems)
                }
                if (processThoughts) {
                    thoughtDocument = saved.thoughtDocument ?: thoughtDocument
                }
                processor.initialize(saved)
                screen = Screen.List
            } catch (e: IOException) {
                Log.e("DianaApp", "Error processing memo: ${e.message}", e)
                addLog("LLM error: ${e.message ?: e}")
                val result = snackbarHostState.showSnackbar(
                    message = logLlmFailed,
                    actionLabel = retryLabel,
                    withDismissAction = true
                )
                if (result == SnackbarResult.ActionPerformed) {
                    processMemo(memo)
                } else {
                    screen = Screen.List
                }
            } catch (e: Exception) {
                Log.e("DianaApp", "Unexpected error processing memo: ${e.message}", e)
                addLog("LLM error: ${e.message ?: e}")
                snackbarHostState.showSnackbar(logLlmFailed)
                screen = Screen.List
            }
        }
    }

    Scaffold(
        topBar = {
            SessionTabBar(
                sessions = sessions,
                selectedSessionId = session.id,
                importableSessions = importableSessions,
                onSelectSession = onSwitchSession,
                onAddSession = onAddSession,
                onRenameSession = onRenameSession,
                onUpdateSummaryGroup = onUpdateSummaryGroup,
                onDeleteSessionLocal = onDeleteSessionLocal,
                onDeleteSessionRemote = onDeleteSessionRemote,
                onShowImportSessions = {
                    onRefreshImportableSessions()
                    showImportDialog = true
                },
                onNavigateBack = onNavigateBackToSessionList,
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    action = {
                        data.visuals.actionLabel?.let { label ->
                            TextButton(onClick = { data.performAction() }) {
                                Text(label)
                            }
                        }
                    },
                    dismissAction = if (data.visuals.withDismissAction) {
                        {
                            TextButton(onClick = { data.dismiss() }) {
                                Text(cancelLabel)
                            }
                        }
                    } else {
                        null
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        },
        bottomBar = {
            if (screen == Screen.List) {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen == Screen.Recorder,
                        onClick = { screen = Screen.Recorder },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.record)
                            )
                        },
                        label = { Text(stringResource(R.string.record)) }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.TextMemo,
                        onClick = { screen = Screen.TextMemo },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.text_memo)
                            )
                        },
                        label = { Text(stringResource(R.string.text_memo)) }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.Archive,
                        onClick = { screen = Screen.Archive },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.LibraryMusic,
                                contentDescription = stringResource(R.string.memo_archive)
                            )
                        },
                        label = { Text(stringResource(R.string.memo_archive)) }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.Settings,
                        onClick = { screen = Screen.Settings },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        },
                        label = { Text(stringResource(R.string.settings)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        fun persistNotes(currentTodos: List<TodoItem>) {
            val currentAppointments = appointments
            val includeAppointments = processAppointments
            val includeThoughts = processThoughts
            val currentThoughts = thoughtNotes
            scope.launch {
                val todoNotes = currentTodos.map {
                    StructuredNote.ToDo(
                        text = it.text,
                        status = it.status,
                        tagIds = it.tagIds,
                        tagLabels = it.tagLabels,
                        dueDate = it.dueDate,
                        eventDate = it.eventDate,
                        id = it.id,
                    )
                }
                val apptNotes = if (includeAppointments) {
                    currentAppointments.map {
                        StructuredNote.Event(it.text, it.datetime, it.location)
                    }
                } else emptyList()
                val thoughtNoteList = if (includeThoughts) currentThoughts else emptyList()
                repository.saveNotes(todoNotes + apptNotes + thoughtNoteList)
                onSessionTodosChanged(session.id, currentTodos)
                syncProcessor()
            }
        }

        when (screen) {
            Screen.List -> {
                NotesListScreen(
                    todoItems,
                    appointments,
                    thoughtNotes,
                    thoughtDocument,
                    currentTagCatalog,
                    locale,
                    logs,
                    processTodos,
                    processAppointments,
                    processThoughts,
                    showTags,
                    modifier = contentModifier,
                    onTodoCheckedChange = { item, checked ->
                        val newStatus = if (checked) "done" else "not_started"
                        todoItems = todoItems.map {
                            if (it.id == item.id) it.copy(status = newStatus) else it
                        }
                        persistNotes(todoItems)
                    },
                    onTodoEdit = { updated ->
                        todoItems = todoItems.map { existing ->
                            if (existing.id == updated.id) updated else existing
                        }
                        persistNotes(todoItems)
                    },
                    onTodoDelete = { item ->
                        val updatedTodos = todoItems.filterNot { it.id == item.id }
                        todoItems = updatedTodos
                        scope.launch {
                            repository.deleteTodoItem(item.id)
                            onSessionTodosChanged(session.id, updatedTodos)
                            syncProcessor()
                        }
                    },
                    onAppointmentDelete = { appt ->
                        appointments = appointments.filterNot { it == appt }
                        scope.launch {
                            repository.deleteAppointment(appt.text, appt.datetime, appt.location)
                            syncProcessor()
                        }
                    },
                    onTodoMove = { item ->
                        pendingMoveRequest = SessionItemMoveRequest.Todo(item)
                    },
                    onAppointmentMove = { appt ->
                        pendingMoveRequest = SessionItemMoveRequest.Appointment(appt)
                    }
                )
                pendingMoveRequest?.let { moveRequest ->
                    val targetSessions = sessions.filter { it.id != session.id }
                    MoveSessionItemDialog(
                        itemLabel = when (moveRequest) {
                            is SessionItemMoveRequest.Todo -> moveRequest.item.text
                            is SessionItemMoveRequest.Appointment -> moveRequest.item.text
                        },
                        sessions = targetSessions,
                        onDismiss = { pendingMoveRequest = null },
                        onMove = { target ->
                            when (moveRequest) {
                                is SessionItemMoveRequest.Todo -> {
                                    val item = moveRequest.item
                                    val updatedTodos = todoItems.filterNot { it.id == item.id }
                                    todoItems = updatedTodos
                                    scope.launch {
                                        repository.deleteTodoItem(item.id)
                                        onSessionTodosChanged(session.id, updatedTodos)
                                        createNoteRepository(target).saveNotes(
                                            listOf(
                                                StructuredNote.ToDo(
                                                    text = item.text,
                                                    status = item.status,
                                                    tagIds = item.tagIds,
                                                    tagLabels = item.tagLabels,
                                                    dueDate = item.dueDate,
                                                    eventDate = item.eventDate,
                                                )
                                            )
                                        )
                                        onSessionTodosChanged(target.id, null)
                                        syncProcessor()
                                    }
                                }
                                is SessionItemMoveRequest.Appointment -> {
                                    val appt = moveRequest.item
                                    appointments = appointments.filterNot { it == appt }
                                    scope.launch {
                                        repository.deleteAppointment(appt.text, appt.datetime, appt.location)
                                        createNoteRepository(target).saveNotes(
                                            listOf(
                                                StructuredNote.Event(
                                                    appt.text,
                                                    appt.datetime,
                                                    appt.location,
                                                )
                                            )
                                        )
                                        syncProcessor()
                                    }
                                }
                            }
                            pendingMoveRequest = null
                        }
                    )
                }
            }
            Screen.Archive -> MemoArchiveScreen(
                memoRepository = memoRepository,
                player = player,
                onBack = { screen = Screen.List },
                modifier = contentModifier,
                onReprocess = { memo ->
                    screen = Screen.Processing
                    processMemo(memo)
                }
            )
            Screen.Recorder -> RecorderScreen(
                memoRepository = memoRepository,
                logs = logs,
                addLog = { addLog(it) },
                snackbarHostState = snackbarHostState,
                modifier = contentModifier
            ) { memo ->
                addLog(logRecorded)
                screen = Screen.Processing
                processMemo(memo)
            }
            Screen.TextMemo -> TextMemoScreen(
                modifier = contentModifier,
                onSave = { text ->
                    val memo = Memo(text)
                    scope.launch { memoRepository.addMemo(memo) }
                    addLog(logAdded)
                    screen = Screen.Processing
                    processMemo(memo)
                }
            )
            Screen.Processing -> ProcessingScreen(
                status = processingText,
                messages = logs,
                modifier = contentModifier
            )
            Screen.Settings -> SettingsScreen(
                sessionName = activeSession.name,
                settings = activeSession.settings,
                llmModels = modelOptions,
                onProcessTodosChange = { enabled ->
                    persistSettings { it.copy(processTodos = enabled) }
                },
                onProcessAppointmentsChange = { enabled ->
                    persistSettings { it.copy(processAppointments = enabled) }
                },
                onProcessThoughtsChange = { enabled ->
                    persistSettings { it.copy(processThoughts = enabled) }
                },
                onShowTagsChange = { enabled ->
                    persistSettings { it.copy(showTags = enabled) }
                },
                onModelChange = { model ->
                    val persisted = persistSettings { it.copy(model = model) }
                    processor.model = persisted.model
                },
                onManageTags = { screen = Screen.TagCatalog },
                onClearTodos = {
                    scope.launch {
                        repository.clearTodos()
                        todo = ""
                        todoItems = emptyList()
                        onSessionTodosChanged(session.id, emptyList())
                        syncProcessor()
                    }
                },
                onClearAppointments = {
                    scope.launch {
                        repository.clearAppointments()
                        appointments = emptyList()
                        syncProcessor()
                    }
                },
                onClearThoughts = {
                    scope.launch {
                        repository.clearThoughts()
                        thoughtNotes = emptyList()
                        thoughtDocument = null
                        syncProcessor()
                    }
                },
                onBack = { screen = Screen.List },
                modifier = contentModifier
            )
            Screen.TagCatalog -> TagCatalogScreen(
                state = tagCatalogState,
                onBack = { screen = Screen.Settings },
                onRetry = { tagCatalogViewModel.refresh() },
                onAddTag = { tagCatalogViewModel.addTag() },
                onDeleteTag = { tagCatalogViewModel.deleteTag(it) },
                onTagIdChange = { tagKey, value -> tagCatalogViewModel.updateTagId(tagKey, value) },
                onTagColorChange = { tagKey, value -> tagCatalogViewModel.updateTagColor(tagKey, value) },
                onAddLabel = { tagCatalogViewModel.addLabel(it) },
                onDeleteLabel = { tagKey, labelKey ->
                    tagCatalogViewModel.removeLabel(tagKey, labelKey)
                },
                onLabelLocaleChange = { tagKey, labelKey, value ->
                    tagCatalogViewModel.updateLabelLocale(tagKey, labelKey, value)
                },
                onLabelValueChange = { tagKey, labelKey, value ->
                    tagCatalogViewModel.updateLabelValue(tagKey, labelKey, value)
                },
                onSave = { tagCatalogViewModel.save() },
                modifier = contentModifier
            )
        }
    }

    if (showImportDialog) {
        ImportSessionsDialog(
            sessions = importableSessions,
            onDismiss = { showImportDialog = false },
            onImportSession = { remoteSession ->
                onImportRemoteSession(remoteSession)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun SessionTabBar(
    sessions: List<Session>,
    selectedSessionId: String?,
    importableSessions: List<Session>,
    onSelectSession: (Session) -> Unit,
    onAddSession: () -> Unit,
    onRenameSession: (Session, String) -> Unit,
    onUpdateSummaryGroup: (Session, String) -> Unit,
    onDeleteSessionLocal: (Session) -> Unit,
    onDeleteSessionRemote: (Session) -> Unit,
    onShowImportSessions: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
) {
    val addLabel = stringResource(R.string.add_session)
    val emptyLabel = stringResource(R.string.no_sessions)
    val importLabel = stringResource(R.string.import_remote_sessions)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (onNavigateBack != null) {
            IconButton(onClick = onNavigateBack) {
                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        }
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(emptyLabel)
            }
        } else {
            val selectedIndex = sessions.indexOfFirst { it.id == selectedSessionId }.let { index ->
                if (index >= 0) index else 0
            }
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.weight(1f),
                edgePadding = 0.dp
            ) {
                sessions.forEachIndexed { index, session ->
                    SessionTab(
                        session = session,
                        selected = index == selectedIndex,
                        onSelect = { onSelectSession(session) },
                        onRename = { newName -> onRenameSession(session, newName) },
                        onUpdateSummaryGroup = { newGroup -> onUpdateSummaryGroup(session, newGroup) },
                        onDeleteLocal = { onDeleteSessionLocal(session) },
                        onDeleteRemote = { onDeleteSessionRemote(session) },
                    )
                }
            }
        }
        IconButton(onClick = onShowImportSessions) {
            BadgedBox(
                badge = {
                    if (importableSessions.isNotEmpty()) {
                        val badgeText = if (importableSessions.size > 99) "99+" else importableSessions.size.toString()
                        Badge { Text(badgeText) }
                    }
                }
            ) {
                Icon(imageVector = Icons.Filled.Cloud, contentDescription = importLabel)
            }
        }
        IconButton(onClick = onAddSession) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = addLabel)
        }
    }
}

@Composable
private fun SessionTab(
    session: Session,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onUpdateSummaryGroup: (String) -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteRemote: () -> Unit,
) {
    val renameLabel = stringResource(R.string.rename_session)
    val editSummaryGroupLabel = stringResource(R.string.edit_summary_group)
    val summaryGroupLabel = stringResource(R.string.session_summary_group)
    val deleteLabel = stringResource(R.string.delete_session)
    val actionsLabel = stringResource(R.string.session_actions)
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSummaryGroupDialog by remember { mutableStateOf(false) }
    Tab(selected = selected, onClick = onSelect) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = session.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = actionsLabel
                    )
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(renameLabel) },
                    onClick = {
                        menuExpanded = false
                        showRenameDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(editSummaryGroupLabel) },
                    onClick = {
                        menuExpanded = false
                        showSummaryGroupDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    onClick = {
                        menuExpanded = false
                        showDeleteDialog = true
                    }
                )
            }
        }
    }
    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(session.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(renameLabel) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.session_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            showRenameDialog = false
                            onRename(trimmed)
                        }
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (showSummaryGroupDialog) {
        var summaryGroupText by remember { mutableStateOf(session.summaryGroup) }
        AlertDialog(
            onDismissRequest = { showSummaryGroupDialog = false },
            title = { Text(editSummaryGroupLabel) },
            text = {
                OutlinedTextField(
                    value = summaryGroupText,
                    onValueChange = { summaryGroupText = it },
                    label = { Text(summaryGroupLabel) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSummaryGroupDialog = false
                        onUpdateSummaryGroup(summaryGroupText.trim())
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSummaryGroupDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (showDeleteDialog) {
        DeleteSessionDialog(
            sessionName = session.name,
            onDismiss = { showDeleteDialog = false },
            onDeleteLocal = onDeleteLocal,
            onDeleteRemote = onDeleteRemote
        )
    }
}

private sealed interface SessionItemMoveRequest {
    data class Todo(val item: TodoItem) : SessionItemMoveRequest
    data class Appointment(val item: li.crescio.penates.diana.llm.Appointment) : SessionItemMoveRequest
}

@Composable
private fun MoveSessionItemDialog(
    itemLabel: String,
    sessions: List<Session>,
    onDismiss: () -> Unit,
    onMove: (Session) -> Unit,
) {
    val title = stringResource(R.string.move_item_title)
    val description = stringResource(R.string.move_item_message, itemLabel)
    val noSessionsMessage = stringResource(R.string.move_item_no_sessions)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (sessions.isEmpty()) {
                Text(noSessionsMessage)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(description)
                    sessions.forEach { target ->
                        TextButton(
                            onClick = { onMove(target) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(target.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (sessions.isEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            if (sessions.isNotEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
private fun DeleteSessionDialog(
    sessionName: String,
    onDismiss: () -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteRemote: () -> Unit,
) {
    val deleteLocalLabel = stringResource(R.string.delete_session_local_action)
    val deleteRemoteLabel = stringResource(R.string.delete_session_remote_action)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_session_title)) },
        text = { Text(stringResource(R.string.delete_session_message, sessionName)) },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onDismiss()
                        onDeleteLocal()
                    }
                ) {
                    Text(deleteLocalLabel)
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    onClick = {
                        onDismiss()
                        onDeleteRemote()
                    }
                ) {
                    Text(deleteRemoteLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Preview(name = "Delete session dialog – phone", widthDp = 320, showBackground = true)
@Preview(name = "Delete session dialog – tablet", widthDp = 600, showBackground = true)
@Composable
private fun DeleteSessionDialogPreview() {
    DianaTheme {
        DeleteSessionDialog(
            sessionName = "Strategy Sync",
            onDismiss = {},
            onDeleteLocal = {},
            onDeleteRemote = {}
        )
    }
}

@Composable
private fun ImportSessionsDialog(
    sessions: List<Session>,
    onDismiss: () -> Unit,
    onImportSession: (Session) -> Unit,
) {
    val title = stringResource(R.string.import_remote_sessions_title)
    val emptyMessage = stringResource(R.string.no_remote_sessions)
    val closeLabel = stringResource(R.string.close)
    val importLabel = stringResource(R.string.import_session_action)
    val enabledLabel = stringResource(R.string.setting_enabled)
    val disabledLabel = stringResource(R.string.setting_disabled)
    val defaultModelLabel = stringResource(R.string.remote_session_model_default)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (sessions.isEmpty()) {
                Text(emptyMessage)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sessions.forEach { session ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = session.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    R.string.remote_session_settings,
                                    if (session.settings.processTodos) enabledLabel else disabledLabel,
                                    if (session.settings.processAppointments) enabledLabel else disabledLabel,
                                    if (session.settings.processThoughts) enabledLabel else disabledLabel,
                                    if (session.settings.showTags) enabledLabel else disabledLabel,
                                    session.settings.model.ifBlank { defaultModelLabel },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { onImportSession(session) }) {
                                Text(importLabel)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(closeLabel)
            }
        }
    )
}

sealed class Screen {
    data object List : Screen()
    data object Archive : Screen()
    data object Recorder : Screen()
    data object TextMemo : Screen()
    data object Processing : Screen()
    data object Settings : Screen()
    data object TagCatalog : Screen()
}
