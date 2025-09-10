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
import androidx.compose.ui.res.stringResource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import li.crescio.penates.diana.llm.LlmLogger
import li.crescio.penates.diana.llm.MemoProcessor
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

class MainActivity : ComponentActivity() {
    private lateinit var repository: NoteRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(filesDir, "notes.txt")
        val permissionMessage =
            "Firestore PERMISSION_DENIED. Check security rules or authentication."
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                val firestore = FirebaseFirestore.getInstance()
                repository = NoteRepository(firestore, file)
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
                setContent { DianaTheme { DianaApp(repository) } }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Firebase authentication failed", e)
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_LONG).show()
            }
    }
}

@Composable
fun DianaApp(repository: NoteRepository) {
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
    var appointments by remember { mutableStateOf("") }
    var thoughts by remember { mutableStateOf("") }
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

    LaunchedEffect(logger) {
        logger.logFlow.collect { addLog(it) }
    }

    LaunchedEffect(repository) {
        val notes = repository.loadNotes()
        todo = notes.filterIsInstance<StructuredNote.ToDo>().joinToString("\n") { it.text }
        appointments = notes.filterIsInstance<StructuredNote.Event>().joinToString("\n") { note ->
            if (note.datetime.isNotBlank()) "${note.datetime} ${note.text}" else note.text
        }
        thoughts = notes.filter { it is StructuredNote.Memo || it is StructuredNote.Free }
            .joinToString("\n") {
                when (it) {
                    is StructuredNote.Memo -> it.text
                    is StructuredNote.Free -> it.text
                    else -> ""
                }
            }
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
                val summary = processor.process(memo)
                todo = summary.todo
                appointments = summary.appointments
                thoughts = summary.thoughts
                repository.saveSummary(summary)
                screen = Screen.List
            } catch (e: Exception) {
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
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) {
        when (screen) {
            Screen.List -> NotesListScreen(
                todo,
                appointments,
                thoughts,
                logs,
                onRecord = { screen = Screen.Recorder },
                onViewRecordings = { screen = Screen.Recordings },
                onAddMemo = { screen = Screen.TextMemo }
            )
            Screen.Recordings -> RecordedMemosScreen(recordedMemos, player) { screen = Screen.List }
            Screen.Recorder -> RecorderScreen(
                logs,
                addLog = { addLog(it) },
                snackbarHostState = snackbarHostState
            ) { memo ->
                recordedMemos.add(memo)
                addLog(logRecorded)
                screen = Screen.Processing
                processMemo(memo)
            }
            Screen.TextMemo -> TextMemoScreen(onSave = { text ->
                val memo = Memo(text)
                addLog(logAdded)
                screen = Screen.Processing
                processMemo(memo)
            })
            Screen.Processing -> ProcessingScreen(processingText, logs)
            Screen.Settings -> SettingsScreen()
        }
    }
}

sealed class Screen {
    data object List : Screen()
    data object Recordings : Screen()
    data object Recorder : Screen()
    data object TextMemo : Screen()
    data object Processing : Screen()
    data object Settings : Screen()
}
