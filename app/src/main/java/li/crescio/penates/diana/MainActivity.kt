package li.crescio.penates.diana

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import li.crescio.penates.diana.llm.LlmLogger
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
    private lateinit var repository: NoteRepository
    private lateinit var memoRepository: MemoRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionRepository = SessionRepository(filesDir)
        val initialSession = sessionRepository.getSelected()
            ?: sessionRepository.list().firstOrNull()?.also { existing ->
                sessionRepository.setSelected(existing.id)
            }
            ?: sessionRepository.create("Default Session", SessionSettings()).also { created ->
                sessionRepository.setSelected(created.id)
            }
        val sessionId = initialSession.id
        val notesFile = File(filesDir, "notes.txt")
        val memoFile = File(filesDir, "memos_${sessionId}.txt")
        val collectionPath = "sessions/$sessionId/notes"
        val permissionMessage =
            "Firestore PERMISSION_DENIED. Check security rules or authentication."
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                val firestore = FirebaseFirestore.getInstance()
                repository = NoteRepository(firestore, collectionPath, notesFile)
                memoRepository = MemoRepository(memoFile)
                val testDoc = firestore.collection("test").document("init")
                testDoc.set(mapOf("ping" to "pong")).addOnSuccessListener {
                    testDoc.get().addOnFailureListener { e ->
                        if (e is FirebaseFirestoreException &&
                            e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                        ) {
                            Log.e("MainActivity", permissionMessage, e)
                            Toast.makeText(this, permissionMessage, Toast.LENGTH_LONG).show()
                        }
                    }
                }.addOnFailureListener { e ->
                    if (e is FirebaseFirestoreException &&
                        e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    ) {
                        Log.e("MainActivity", permissionMessage, e)
                        Toast.makeText(this, permissionMessage, Toast.LENGTH_LONG).show()
                    }
                }
                setContent {
                    DianaTheme {
                        DianaApp(
                            repository = repository,
                            memoRepository = memoRepository,
                            sessionRepository = sessionRepository,
                            initialSession = initialSession,
                        )
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Firebase authentication failed", e)
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_LONG).show()
            }
    }
}

@Composable
fun DianaApp(
    repository: NoteRepository,
    memoRepository: MemoRepository,
    sessionRepository: SessionRepository,
    initialSession: Session,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    val recordedMemos = remember { mutableStateListOf<Memo>() }
    val logs = remember { mutableStateListOf<String>() }

    fun addLog(message: String) {
        logs.add(message)
        if (logs.size > 100) {
            logs.removeAt(0)
        }
    }
    val logger = remember { LlmLogger() }
    var todo by remember { mutableStateOf("") }
    var todoItems by remember { mutableStateOf(listOf<TodoItem>()) }
    var appointments by remember { mutableStateOf(listOf<Appointment>()) }
    var thoughtNotes by remember { mutableStateOf(listOf<StructuredNote>()) }
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

    var activeSession by remember(initialSession.id) { mutableStateOf(initialSession) }
    var processTodos by remember(initialSession.id) { mutableStateOf(initialSession.settings.processTodos) }
    var processAppointments by remember(initialSession.id) { mutableStateOf(initialSession.settings.processAppointments) }
    var processThoughts by remember(initialSession.id) { mutableStateOf(initialSession.settings.processThoughts) }
    val initialModel = sanitizeModel(initialSession.settings.model)
    var selectedModel by remember(initialSession.id) { mutableStateOf(initialModel) }
    val processor = remember(initialSession.id) {
        MemoProcessor(
            BuildConfig.OPENROUTER_API_KEY,
            logger,
            Locale.getDefault(),
            initialModel = initialModel
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

    fun persistSettings(transform: (SessionSettings) -> SessionSettings): SessionSettings {
        val updatedSettings = transform(activeSession.settings)
        val sanitizedModel = sanitizeModel(updatedSettings.model)
        val sanitizedSettings = updatedSettings.copy(model = sanitizedModel)
        processTodos = sanitizedSettings.processTodos
        processAppointments = sanitizedSettings.processAppointments
        processThoughts = sanitizedSettings.processThoughts
        selectedModel = sanitizedSettings.model
        val updatedSession = activeSession.copy(settings = sanitizedSettings)
        activeSession = sessionRepository.update(updatedSession)
        return sanitizedSettings
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

    LaunchedEffect(memoRepository) {
        recordedMemos.addAll(memoRepository.loadMemos())
    }

    LaunchedEffect(repository) {
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
        when (screen) {
            Screen.List -> NotesListScreen(
                todoItems,
                appointments,
                thoughtNotes,
                logs,
                processTodos,
                processAppointments,
                processThoughts,
                modifier = Modifier.padding(innerPadding),
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
            Screen.Archive -> MemoArchiveScreen(recordedMemos, player, onBack = { screen = Screen.List }) { memo ->
                screen = Screen.Processing
                processMemo(memo)
            }
            Screen.Recorder -> RecorderScreen(
                logs,
                addLog = { addLog(it) },
                snackbarHostState = snackbarHostState
            ) { memo ->
                recordedMemos.add(memo)
                scope.launch { memoRepository.addMemo(memo) }
                addLog(logRecorded)
                screen = Screen.Processing
                processMemo(memo)
            }
            Screen.TextMemo -> TextMemoScreen(onSave = { text ->
                val memo = Memo(text)
                recordedMemos.add(memo)
                scope.launch { memoRepository.addMemo(memo) }
                addLog(logAdded)
                screen = Screen.Processing
                processMemo(memo)
            })
            Screen.Processing -> ProcessingScreen(processingText, logs)
            Screen.Settings -> SettingsScreen(
                processTodos = processTodos,
                processAppointments = processAppointments,
                processThoughts = processThoughts,
                selectedModel = selectedModel,
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
                onBack = { screen = Screen.List }
            )
        }
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
