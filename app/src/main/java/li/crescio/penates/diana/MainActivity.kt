package li.crescio.penates.diana

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import li.crescio.penates.diana.llm.LlmLogger
import li.crescio.penates.diana.llm.MemoProcessor
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.player.AndroidPlayer
import li.crescio.penates.diana.player.Player
import li.crescio.penates.diana.ui.*
import li.crescio.penates.diana.ui.theme.DianaTheme
import li.crescio.penates.diana.R
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DianaTheme { DianaApp() } }
    }
}

@Composable
fun DianaApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    val recordedMemos = remember { mutableStateListOf<Memo>() }
    val logs = remember { mutableStateListOf<String>() }
    var todo by remember { mutableStateOf("") }
    var appointments by remember { mutableStateOf("") }
    var thoughts by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val processor = remember { MemoProcessor(BuildConfig.OPENROUTER_API_KEY, LlmLogger(), Locale.getDefault()) }
    val player: Player = remember { AndroidPlayer() }
    val logRecorded = stringResource(R.string.log_recorded_memo)
    val logAdded = stringResource(R.string.log_added_memo)
    val processingText = stringResource(R.string.processing)

    fun processMemo(memo: Memo) {
        scope.launch {
            val summary = processor.process(memo)
            todo = summary.todo
            appointments = summary.appointments
            thoughts = summary.thoughts
        }
    }

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
        Screen.Recorder -> RecorderScreen(logs) { memo ->
            recordedMemos.add(memo)
            logs.add(logRecorded)
            processMemo(memo)
            screen = Screen.List
        }
        Screen.TextMemo -> TextMemoScreen(onSave = { text ->
            val memo = Memo(text)
            logs.add(logAdded)
            processMemo(memo)
            screen = Screen.List
        })
        Screen.Processing -> ProcessingScreen(processingText) { screen = Screen.List }
        Screen.Settings -> SettingsScreen()
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
