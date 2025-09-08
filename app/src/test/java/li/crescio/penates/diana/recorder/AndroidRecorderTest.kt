package li.crescio.penates.diana.recorder

import android.content.Context
import android.media.MediaRecorder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

private class TestMediaRecorder(private val id: Int, private val log: MutableList<String>) : MediaRecorder() {
    override fun setAudioSource(source: Int) {}
    override fun setOutputFormat(format: Int) {}
    override fun setAudioEncoder(encoder: Int) {}
    override fun setAudioEncodingBitRate(bitRate: Int) {}
    override fun setAudioSamplingRate(rate: Int) {}
    override fun setOutputFile(path: String?) {}
    override fun prepare() { log += "prepare-$id" }
    override fun start() { log += "start-$id" }
    override fun stop() {}
    override fun reset() { log += "reset-$id" }
    override fun release() { log += "release-$id" }
}

class AndroidRecorderTest {
    @Test
    fun startTwice_releasesPreviousRecorder() = runBlocking {
        val log = mutableListOf<String>()
        var nextId = 0
        val context = mockk<Context>()
        val cacheDir = createTempDir().also { it.deleteOnExit() }
        every { context.cacheDir } returns cacheDir

        val recorder = AndroidRecorder(context) {
            TestMediaRecorder(nextId++, log)
        }

        recorder.start()
        recorder.start()

        val resetIndex = log.indexOf("reset-0")
        val releaseIndex = log.indexOf("release-0")
        val secondStartIndex = log.indexOf("start-1")

        assertTrue(resetIndex >= 0)
        assertTrue(releaseIndex >= 0)
        assertTrue(secondStartIndex >= 0)
        assertTrue(resetIndex < secondStartIndex)
        assertTrue(releaseIndex < secondStartIndex)
    }
}
