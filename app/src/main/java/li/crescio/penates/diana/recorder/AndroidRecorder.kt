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

    override suspend fun start() = withContext(Dispatchers.IO) {
        val outputDir = File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = File.createTempFile("rec_", ".m4a", outputDir)
        outputFile = file

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
    }

    override suspend fun stop(): RawRecording = withContext(Dispatchers.IO) {
        val activeRecorder = recorder ?: throw IllegalStateException("Recording has not started")
        activeRecorder.stop()
        activeRecorder.release()
        recorder = null

        val file = outputFile ?: throw IllegalStateException("Output file missing")
        RawRecording(file.absolutePath)
    }
}
