package li.crescio.penates.diana.recorder

import li.crescio.penates.diana.notes.RawRecording

interface Recorder {
    suspend fun record(): RawRecording
}
