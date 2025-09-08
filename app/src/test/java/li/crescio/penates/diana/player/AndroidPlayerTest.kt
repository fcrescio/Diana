package li.crescio.penates.diana.player

import android.media.MediaPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class AndroidPlayerTest {
    @Test
    fun release_is_called_when_setDataSource_throws() {
        val mediaPlayer = mockk<MediaPlayer>(relaxed = true)
        every { mediaPlayer.setDataSource(any<String>()) } throws RuntimeException("boom")

        val player = AndroidPlayer(mediaPlayerFactory = { mediaPlayer })

        player.play("file")

        verify { mediaPlayer.release() }
    }
}
