package li.crescio.penates.diana.transcriber

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import li.crescio.penates.diana.notes.RawRecording
import li.crescio.penates.diana.notes.Transcript
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocalTranscriber(private val context: Context) : Transcriber {
    override suspend fun transcribe(recording: RawRecording): Transcript =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val audioFile = File(recording.filePath)
                if (!audioFile.exists()) {
                    cont.resumeWithException(IllegalArgumentException("Audio file not found"))
                    return@suspendCancellableCoroutine
                }

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        cont.resume(Transcript(text))
                        recognizer.destroy()
                    }

                    override fun onError(error: Int) {
                        Log.e("LocalTranscriber", "Error: $error")
                        cont.resumeWithException(RuntimeException("Speech recognition error $error"))
                        recognizer.destroy()
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioFile.absolutePath)
                }

                recognizer.startListening(intent)
                cont.invokeOnCancellation {
                    recognizer.cancel()
                    recognizer.destroy()
                }
            }
        }
}
