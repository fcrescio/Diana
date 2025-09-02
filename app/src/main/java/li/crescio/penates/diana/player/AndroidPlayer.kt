package li.crescio.penates.diana.player

import android.media.MediaPlayer

/** Plays audio files using [MediaPlayer]. */
class AndroidPlayer : Player {
    override fun play(filePath: String) {
        val player = MediaPlayer()
        try {
            player.setDataSource(filePath)
            player.prepare()
            player.start()
        } catch (e: Exception) {
            // Swallow exceptions for this simplified implementation
        }
    }
}
