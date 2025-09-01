package li.crescio.penates.diana.transcriber

import li.crescio.penates.diana.notes.RawRecording
import li.crescio.penates.diana.notes.Transcript
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GroqTranscriber(private val apiKey: String) : Transcriber {
    private val client = OkHttpClient()

    override suspend fun transcribe(recording: RawRecording): Transcript {
        // Simplified network request placeholder
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post("".toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).execute().use { }
        return Transcript("")
    }
}
