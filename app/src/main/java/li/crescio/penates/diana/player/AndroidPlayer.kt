package li.crescio.penates.diana.player

import android.media.MediaPlayer

/** Plays audio files using [MediaPlayer]. */
class AndroidPlayer : Player {
    override fun play(filePath: String) {
        val player = MediaPlayer()
        var started = false
        try {
            player.setDataSource(filePath)
            player.setOnCompletionListener { it.release() }
            player.prepare()
            player.start()
            started = true
        } catch (e: Exception) {
            // Swallow exceptions for this simplified implementation
        } finally {
            if (!started) {
                player.release()
            }
        }
    }
}
