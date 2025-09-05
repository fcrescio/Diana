package li.crescio.penates.diana.recorder

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.crescio.penates.diana.notes.RawRecording
import java.io.File

/** Records audio from the device microphone and stores it in a temporary file. */
class AndroidRecorder(private val context: Context) : Recorder {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** Releases any held [MediaRecorder] resources. */
    private fun cleanupRecorder() {
        recorder?.apply {
            try {
                release()
            } catch (_: Exception) {
                // Ignore release errors, we just want to free resources
            }
        }
        recorder = null
        outputFile = null
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        // Ensure any previous recorder is fully released before starting again
        cleanupRecorder()

        val outputDir = File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = File.createTempFile("rec_", ".m4a", outputDir)
        outputFile = file

        recorder = MediaRecorder(context).apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            } catch (e: Exception) {
                release()
                recorder = null
                outputFile = null
                throw e
            }
        }
    }

    override suspend fun stop(): RawRecording = withContext(Dispatchers.IO) {
        val file = outputFile ?: throw IllegalStateException("Output file missing")
        val activeRecorder = recorder ?: throw IllegalStateException("Recording has not started")
        try {
            activeRecorder.stop()
        } finally {
            cleanupRecorder()
        }
        outputFile = null
        RawRecording(file.absolutePath)
    }
}
