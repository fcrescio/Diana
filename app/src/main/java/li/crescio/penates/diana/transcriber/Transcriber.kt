package li.crescio.penates.diana.transcriber

import li.crescio.penates.diana.notes.RawRecording
import li.crescio.penates.diana.notes.Transcript

interface Transcriber {
    suspend fun transcribe(recording: RawRecording): Transcript
}
