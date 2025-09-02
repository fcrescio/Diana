package li.crescio.penates.diana.recorder

import li.crescio.penates.diana.notes.RawRecording

interface Recorder {
    /** Starts capturing audio from the microphone. */
    suspend fun start()

    /** Stops recording and returns a [RawRecording] pointing to the captured file. */
    suspend fun stop(): RawRecording
}
