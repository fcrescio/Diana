package li.crescio.penates.diana

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.tasks.await
import li.crescio.penates.diana.llm.LlmLogger
import li.crescio.penates.diana.llm.LlmResources
import li.crescio.penates.diana.llm.MemoProcessor
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.llm.Appointment
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.llm.Thought
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.persistence.NoteRepository
import li.crescio.penates.diana.player.AndroidPlayer
import li.crescio.penates.diana.player.Player
import li.crescio.penates.diana.ui.*
import li.crescio.penates.diana.ui.theme.DianaTheme
import li.crescio.penates.diana.R
import java.util.Locale
import java.io.File
import java.io.IOException
import li.crescio.penates.diana.persistence.MemoRepository
import li.crescio.penates.diana.session.Session
import li.crescio.penates.diana.session.SessionRepository
import li.crescio.penates.diana.session.SessionSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionRepository = SessionRepository(filesDir)
        LlmResources.initialize(File(filesDir, "llm_resources"))
        val sessionInitialization = ensureInitialSession(sessionRepository)
        val permissionMessage =
            "Firestore PERMISSION_DENIED. Check security rules or authentication."
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                val firestore = FirebaseFirestore.getInstance()
                lifecycleScope.launch {
                    try {
                        LlmResources.refreshFromFirestore(firestore)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to update LLM resources", e)
                    }
                    if (sessionInitialization.createdDefaultSession) {
                        migrateLegacyNotesCollection(firestore, sessionInitialization.session.id)
                    }
                    val initialEnvironment = createSessionEnvironment(sessionInitialization.session, firestore)
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
                        var environment by remember { mutableStateOf(initialEnvironment) }
                        val sessionsState = remember {
                            mutableStateListOf<Session>().apply { addAll(sessionRepository.list()) }
                        }
                        val context = this@MainActivity

                        fun refreshSessions() {
                            sessionsState.clear()
                            sessionsState.addAll(sessionRepository.list())
                        }

                        val switchSession: (Session) -> Unit = { targetSession ->
                            sessionRepository.setSelected(targetSession.id)
                            val resolved = sessionRepository.getSelected() ?: targetSession
                            environment = createSessionEnvironment(resolved, firestore)
                        }

                        val addSession: () -> Unit = {
                            val name = context.generateSessionName(sessionsState.map { it.name })
                            val created = sessionRepository.create(name, SessionSettings())
                            refreshSessions()
                            switchSession(created)
                        }

                        val renameSession: (Session, String) -> Unit = label@ { session, newName ->
                            val trimmed = newName.trim()
                            if (trimmed.isEmpty() || trimmed == session.name) {
                                return@label
                            }
                            val persisted = sessionRepository.update(session.copy(name = trimmed))
                            refreshSessions()
                            if (environment.session.id == persisted.id) {
                                environment = environment.copy(session = persisted)
                            }
                        }

                        val deleteSession: (Session) -> Unit = { session ->
                            val wasSelected = environment.session.id == session.id
                            if (sessionRepository.delete(session.id)) {
                                refreshSessions()
                                val currentSelected = sessionRepository.getSelected()
                                if (currentSelected != null) {
                                    if (wasSelected) {
                                        switchSession(currentSelected)
                                    }
                                } else if (sessionsState.isNotEmpty()) {
                                    switchSession(sessionsState.first())
                                } else {
                                    val name = context.generateSessionName(sessionsState.map { it.name })
                                    val created = sessionRepository.create(name, SessionSettings())
                                    refreshSessions()
                                    switchSession(created)
                                }
                            }
                        }

                        DianaTheme {
                            DianaApp(
                                session = environment.session,
                                sessions = sessionsState,
                                repository = environment.noteRepository,
                                memoRepository = environment.memoRepository,
                                onUpdateSession = { updatedSession ->
                                    val persisted = sessionRepository.update(updatedSession)
                                    environment = environment.copy(session = persisted)
                                    refreshSessions()
                                    persisted
                                },
                                onSwitchSession = switchSession,
                                onAddSession = addSession,
                                onRenameSession = renameSession,
                                onDeleteSession = deleteSession,
                            )
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Firebase authentication failed", e)
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_LONG).show()
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

        val created = sessionRepository.create("Default Session", SessionSettings())
        sessionRepository.setSelected(created.id)
        migrateLegacyMemosToDefaultSession(created.id)
        return SessionInitialization(created, createdDefaultSession = true)
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

        return SessionEnvironment(
            session = session,
            noteRepository = NoteRepository(firestore, session.id, notesFile),
            memoRepository = MemoRepository(memoFile),
        )
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

private data class SessionEnvironment(
    val session: Session,
    val noteRepository: NoteRepository,
    val memoRepository: MemoRepository,
)

private data class SessionInitialization(
    val session: Session,
    val createdDefaultSession: Boolean,
)

@Composable
fun DianaApp(
    session: Session,
    sessions: List<Session>,
    repository: NoteRepository,
    memoRepository: MemoRepository,
    onUpdateSession: (Session) -> Session,
    onSwitchSession: (Session) -> Unit,
    onAddSession: () -> Unit,
    onRenameSession: (Session, String) -> Unit,
    onDeleteSession: (Session) -> Unit,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    val logs = remember { mutableStateListOf<String>() }

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
    val scope = rememberCoroutineScope()
    val player: Player = remember { AndroidPlayer() }
    val logRecorded = stringResource(R.string.log_recorded_memo)
    val logAdded = stringResource(R.string.log_added_memo)
    val processingText = stringResource(R.string.processing)
    val logLlmFailed = stringResource(R.string.log_llm_failed)
    val retryLabel = stringResource(R.string.retry)
    val logApiKeyMissing = stringResource(R.string.api_key_missing)
    val snackbarHostState = remember { SnackbarHostState() }

    fun sanitizeModel(model: String): String {
        return if (model in MemoProcessor.AVAILABLE_MODELS) {
            model
        } else {
            MemoProcessor.DEFAULT_MODEL
        }
    }

    val sanitizedModel = sanitizeModel(session.settings.model)
    var activeSession by remember(session.id) {
        mutableStateOf(session.copy(settings = session.settings.copy(model = sanitizedModel)))
    }
    var processTodos by remember(session.id) { mutableStateOf(activeSession.settings.processTodos) }
    var processAppointments by remember(session.id) { mutableStateOf(activeSession.settings.processAppointments) }
    var processThoughts by remember(session.id) { mutableStateOf(activeSession.settings.processThoughts) }
    var selectedModel by remember(session.id) { mutableStateOf(sanitizedModel) }
    val processor = remember(session.id) {
        MemoProcessor(
            BuildConfig.OPENROUTER_API_KEY,
            logger,
            Locale.getDefault(),
            initialModel = sanitizedModel
        )
    }
    val modelOptions = remember {
        listOf(
            LlmModelOption(MemoProcessor.DEFAULT_MODEL, R.string.model_mistral_nemo),
            LlmModelOption("openrouter/sonoma-sky-alpha", R.string.model_sonoma_sky_alpha),
            LlmModelOption("qwen/qwen3-30b-a3b", R.string.model_qwen_a3b),
            LlmModelOption("openai/gpt-oss-120b", R.string.model_gpt_oss_120b),
        )
    }

    LaunchedEffect(session) {
        val sanitized = sanitizeModel(session.settings.model)
        val sanitizedSession = session.copy(settings = session.settings.copy(model = sanitized))
        activeSession = sanitizedSession
        processTodos = sanitizedSession.settings.processTodos
        processAppointments = sanitizedSession.settings.processAppointments
        processThoughts = sanitizedSession.settings.processThoughts
        selectedModel = sanitized
        processor.model = sanitized
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
        selectedModel = finalSettings.model
        return finalSettings
    }

    fun syncProcessor() {
        val thoughtItems = thoughtNotes.map { note ->
            when (note) {
                is StructuredNote.Memo -> Thought(note.text, note.tags)
                is StructuredNote.Free -> Thought(note.text, note.tags)
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
            thoughtItems = thoughtItems
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
        todoItems = todoNotes.map { TodoItem(it.text, it.status, it.tags, it.dueDate, it.eventDate, it.id) }
        appointments = eventNotes.map { Appointment(it.text, it.datetime, it.location) }
        thoughtNotes = memoNotes + freeNotes
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
                val summary = processor.process(memo, processTodos, processAppointments, processThoughts)
                if (processTodos) {
                    todo = summary.todo
                    todoItems = summary.todoItems
                }
                if (processAppointments) {
                    appointments = summary.appointmentItems
                }
                if (processThoughts) {
                    thoughtNotes = summary.thoughtItems.map { StructuredNote.Memo(it.text, it.tags) }
                }
                val saved = repository.saveSummary(summary, processTodos, processAppointments, processThoughts)
                if (processTodos) {
                    todoItems = saved.todoItems
                }
                processor.initialize(saved)
                screen = Screen.List
            } catch (e: IOException) {
                Log.e("DianaApp", "Error processing memo: ${e.message}", e)
                addLog("LLM error: ${e.message ?: e}")
                val result = snackbarHostState.showSnackbar(
                    message = logLlmFailed,
                    actionLabel = retryLabel
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
                onSelectSession = onSwitchSession,
                onAddSession = onAddSession,
                onRenameSession = onRenameSession,
                onDeleteSession = onDeleteSession,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (screen == Screen.List) {
                NavigationBar {
                    NavigationBarItem(
                        selected = false,
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
                        selected = false,
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
                        selected = false,
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
                        selected = false,
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
        when (screen) {
            Screen.List -> NotesListScreen(
                todoItems,
                appointments,
                thoughtNotes,
                logs,
                processTodos,
                processAppointments,
                processThoughts,
                modifier = contentModifier,
                onTodoCheckedChange = { item, checked ->
                      val newStatus = if (checked) "done" else "not_started"
                    todoItems = todoItems.map {
                        if (it.id == item.id) it.copy(status = newStatus) else it
                    }
                    scope.launch {
                          val todoNotes = todoItems.map {
                              StructuredNote.ToDo(it.text, it.status, it.tags, it.dueDate, it.eventDate, id = it.id)
                          }
                        val apptNotes = if (processAppointments) {
                            appointments.map {
                                StructuredNote.Event(it.text, it.datetime, it.location)
                            }
                        } else emptyList()
                        val thoughtNoteList = if (processThoughts) thoughtNotes else emptyList()
                        repository.saveNotes(todoNotes + apptNotes + thoughtNoteList)
                        syncProcessor()
                    }
                },
                onTodoDelete = { item ->
                    todoItems = todoItems.filterNot { it.id == item.id }
                    scope.launch {
                        repository.deleteTodoItem(item.id)
                        syncProcessor()
                    }
                },
                onAppointmentDelete = { appt ->
                    appointments = appointments.filterNot { it == appt }
                    scope.launch {
                        repository.deleteAppointment(appt.text, appt.datetime, appt.location)
                        syncProcessor()
                    }
                }
            )
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
                onModelChange = { model ->
                    val persisted = persistSettings { it.copy(model = model) }
                    processor.model = persisted.model
                },
                onClearTodos = {
                    scope.launch {
                        repository.clearTodos()
                        todo = ""
                        todoItems = emptyList()
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
                        syncProcessor()
                    }
                },
                onBack = { screen = Screen.List },
                modifier = contentModifier
            )
        }
    }
}

@Composable
private fun SessionTabBar(
    sessions: List<Session>,
    selectedSessionId: String,
    onSelectSession: (Session) -> Unit,
    onAddSession: () -> Unit,
    onRenameSession: (Session, String) -> Unit,
    onDeleteSession: (Session) -> Unit,
) {
    val addLabel = stringResource(R.string.add_session)
    val emptyLabel = stringResource(R.string.no_sessions)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                        onDelete = { onDeleteSession(session) }
                    )
                }
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
    onDelete: () -> Unit,
) {
    val renameLabel = stringResource(R.string.rename_session)
    val deleteLabel = stringResource(R.string.delete_session)
    val actionsLabel = stringResource(R.string.session_actions)
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
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
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = { Text(stringResource(R.string.delete_session_message, session.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

sealed class Screen {
    data object List : Screen()
    data object Archive : Screen()
    data object Recorder : Screen()
    data object TextMemo : Screen()
    data object Processing : Screen()
    data object Settings : Screen()
}
