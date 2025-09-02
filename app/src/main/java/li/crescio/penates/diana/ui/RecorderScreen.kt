package li.crescio.penates.diana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import li.crescio.penates.diana.BuildConfig
import li.crescio.penates.diana.notes.RecordedNote
import li.crescio.penates.diana.recorder.AndroidRecorder
import li.crescio.penates.diana.transcriber.GroqTranscriber

@Composable
fun RecorderScreen(logs: List<String>, onFinish: (RecordedNote) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recorder = remember { AndroidRecorder(context) }
    val transcriber = remember { GroqTranscriber(BuildConfig.GROQ_API_KEY) }

    LaunchedEffect(Unit) { recorder.start() }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = {
                scope.launch {
                    val recording = recorder.stop()
                    val transcript = transcriber.transcribe(recording)
                    onFinish(RecordedNote(recording, transcript))
                }
            }) { Text("Finish Recording") }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.Black)
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(logs) { log ->
                    Text(
                        log,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Green
                    )
                }
            }
        }
    }
}
