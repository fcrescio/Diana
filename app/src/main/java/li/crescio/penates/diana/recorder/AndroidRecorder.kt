package li.crescio.penates.diana.recorder

import li.crescio.penates.diana.notes.RawRecording

class AndroidRecorder : Recorder {
    override suspend fun record(): RawRecording {
        // Placeholder for audio recording implementation
        return RawRecording("")
    }
}
