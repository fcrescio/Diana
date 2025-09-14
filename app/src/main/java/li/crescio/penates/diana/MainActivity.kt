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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import android.content.Context
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

class MainActivity : ComponentActivity() {
    private lateinit var repository: NoteRepository
    private lateinit var memoRepository: MemoRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(filesDir, "notes.txt")
        val memoFile = File(filesDir, "memos.txt")
        val permissionMessage =
            "Firestore PERMISSION_DENIED. Check security rules or authentication."
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                val firestore = FirebaseFirestore.getInstance()
                repository = NoteRepository(firestore, file)
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
                setContent { DianaTheme { DianaApp(repository, memoRepository) } }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Firebase authentication failed", e)
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_LONG).show()
            }
    }
}

@Composable
fun DianaApp(repository: NoteRepository, memoRepository: MemoRepository) {
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
    val processor = remember { MemoProcessor(BuildConfig.OPENROUTER_API_KEY, logger, Locale.getDefault()) }
    val player: Player = remember { AndroidPlayer() }
    val logRecorded = stringResource(R.string.log_recorded_memo)
    val logAdded = stringResource(R.string.log_added_memo)
    val processingText = stringResource(R.string.processing)
    val logLlmFailed = stringResource(R.string.log_llm_failed)
    val retryLabel = stringResource(R.string.retry)
    val logApiKeyMissing = stringResource(R.string.api_key_missing)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var processTodos by remember { mutableStateOf(prefs.getBoolean("process_todos", true)) }
    var processAppointments by remember { mutableStateOf(prefs.getBoolean("process_appointments", true)) }
    var processThoughts by remember { mutableStateOf(prefs.getBoolean("process_thoughts", true)) }

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

        todo = todoNotes.joinToString("\n") { it.text }
        todoItems = todoNotes.map { TodoItem(it.text, it.status, it.tags) }
        appointments = eventNotes.map { Appointment(it.text, it.datetime, it.location) }
        thoughtNotes = memoNotes + freeNotes
        val thoughtItems = (memoNotes + freeNotes).map { note ->
            when (note) {
                is StructuredNote.Memo -> Thought(note.text, note.tags)
                is StructuredNote.Free -> Thought(note.text, note.tags)
                else -> throw IllegalStateException("Unexpected note type")
            }
        }

        val summary = MemoSummary(
            todo = todo,
            appointments = appointments.joinToString("\n") { it.text },
            thoughts = thoughtItems.joinToString("\n") { it.text },
            todoItems = todoItems,
            appointmentItems = appointments,
            thoughtItems = thoughtItems
        )
        processor.initialize(summary)
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
                repository.saveSummary(summary, processTodos, processAppointments, processThoughts)
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
                    val newStatus = if (checked) "done" else "open"
                    todoItems = todoItems.map {
                        if (it.text == item.text) it.copy(status = newStatus) else it
                    }
                    scope.launch {
                        val todoNotes = todoItems.map {
                            StructuredNote.ToDo(it.text, it.status, it.tags)
                        }
                        val apptNotes = if (processAppointments) {
                            appointments.map {
                                StructuredNote.Event(it.text, it.datetime, it.location)
                            }
                        } else emptyList()
                        val thoughtNoteList = if (processThoughts) thoughtNotes else emptyList()
                        repository.saveNotes(todoNotes + apptNotes + thoughtNoteList)
                    }
                },
                onTodoDelete = { item ->
                    todoItems = todoItems.filterNot { it.text == item.text }
                    scope.launch {
                        repository.deleteTodoItem(item.text)
                    }
                },
                onAppointmentDelete = { appt ->
                    appointments = appointments.filterNot { it == appt }
                    scope.launch {
                        repository.deleteAppointment(appt.text, appt.datetime, appt.location)
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
                onProcessTodosChange = { enabled ->
                    processTodos = enabled
                    prefs.edit().putBoolean("process_todos", enabled).apply()
                },
                onProcessAppointmentsChange = { enabled ->
                    processAppointments = enabled
                    prefs.edit().putBoolean("process_appointments", enabled).apply()
                },
                onProcessThoughtsChange = { enabled ->
                    processThoughts = enabled
                    prefs.edit().putBoolean("process_thoughts", enabled).apply()
                },
                onClearTodos = {
                    scope.launch {
                        repository.clearTodos()
                        todo = ""
                        todoItems = emptyList()
                    }
                },
                onClearAppointments = {
                    scope.launch {
                        repository.clearAppointments()
                        appointments = emptyList()
                    }
                },
                onClearThoughts = {
                    scope.launch {
                        repository.clearThoughts()
                        thoughtNotes = emptyList()
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
