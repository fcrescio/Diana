package li.crescio.penates.diana.transcriber

import li.crescio.penates.diana.notes.RawRecording
import li.crescio.penates.diana.notes.Transcript

class LocalTranscriber : Transcriber {
    override suspend fun transcribe(recording: RawRecording): Transcript {
        // Placeholder for on-device SpeechRecognizer usage
        return Transcript("")
    }
}
