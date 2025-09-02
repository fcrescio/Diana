package li.crescio.penates.diana.player

/** Simple abstraction for playing back audio recordings. */
interface Player {
    fun play(filePath: String)
}
