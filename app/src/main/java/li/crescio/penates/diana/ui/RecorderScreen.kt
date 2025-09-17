package li.crescio.penates.diana.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import li.crescio.penates.diana.BuildConfig
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.notes.Transcript
import li.crescio.penates.diana.recorder.AndroidRecorder
import li.crescio.penates.diana.transcriber.GroqTranscriber
import li.crescio.penates.diana.R
import li.crescio.penates.diana.persistence.MemoRepository

@Composable
fun RecorderScreen(
    memoRepository: MemoRepository,
    logs: List<String>,
    addLog: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    onFinish: (Memo) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recorder = remember { AndroidRecorder(context) }
    val transcriber = remember { GroqTranscriber(BuildConfig.GROQ_API_KEY) }

    val logStart = stringResource(R.string.log_start_recording)
    val logStarted = stringResource(R.string.log_recording_started)
    val logStop = stringResource(R.string.log_stop_recording)
    val logTransComplete = stringResource(R.string.log_transcription_complete)
    val logTransFail = stringResource(R.string.log_transcription_failed)
    val retryLabel = stringResource(R.string.retry)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isRecording by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && !isRecording) {
            addLog(logStart)
            recorder.start()
            isRecording = true
            addLog(logStarted)
        } else if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    scope.launch {
                        addLog(logStop)
                        val recording = recorder.stop()
                        var transcript: Transcript? = null
                        while (transcript == null) {
                            try {
                                transcript = transcriber.transcribe(recording)
                                addLog(logTransComplete)
                            } catch (e: Exception) {
                                addLog("$logTransFail: ${e.message}")
                                val result = snackbarHostState.showSnackbar(
                                    message = "$logTransFail: ${e.message}",
                                    actionLabel = retryLabel
                                )
                                if (result != SnackbarResult.ActionPerformed) {
                                    break
                                }
                            }
                        }
                        val memo = Memo(transcript?.text.orEmpty(), recording.filePath)
                        memoRepository.addMemo(memo)
                        onFinish(memo)
                    }
                },
                enabled = isRecording
            ) { Text(stringResource(R.string.finish_recording)) }
        }

        LogSection(logs)
    }
}
