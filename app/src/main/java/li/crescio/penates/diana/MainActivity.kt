package li.crescio.penates.diana

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import li.crescio.penates.diana.notes.RecordedNote
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.player.AndroidPlayer
import li.crescio.penates.diana.player.Player
import li.crescio.penates.diana.ui.*
import li.crescio.penates.diana.ui.theme.DianaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DianaTheme { DianaApp() } }
    }
}

@Composable
fun DianaApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    val notes = remember { mutableStateListOf<StructuredNote>() }
    val recordedNotes = remember { mutableStateListOf<RecordedNote>() }
    val logs = remember { mutableStateListOf<String>() }
    val player: Player = remember { AndroidPlayer() }

    when (screen) {
        Screen.List -> NotesListScreen(
            notes,
            logs,
            onRecord = { screen = Screen.Recorder },
            onViewRecordings = { screen = Screen.Recordings },
            onAddMemo = { screen = Screen.TextMemo }
        )
        Screen.Recordings -> RecordedNotesScreen(recordedNotes, player) { screen = Screen.List }
        Screen.Recorder -> RecorderScreen(logs) { note ->
            recordedNotes.add(note)
            logs.add("Recorded note")
            screen = Screen.Recordings
        }
        Screen.TextMemo -> TextMemoScreen(onSave = { text ->
            notes.add(StructuredNote.Memo(text))
            logs.add("Added memo")
            screen = Screen.List
        })
        Screen.Processing -> ProcessingScreen("Processing...") { screen = Screen.List }
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
