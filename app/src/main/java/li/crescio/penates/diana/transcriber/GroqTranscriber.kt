package li.crescio.penates.diana.transcriber

import android.util.Log
import li.crescio.penates.diana.notes.RawRecording
import li.crescio.penates.diana.notes.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GroqTranscriber(private val apiKey: String) : Transcriber {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(recording: RawRecording): Transcript =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                throw IllegalStateException("GROQ API key is missing")
            }
            val audioFile = File(recording.filePath)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/m4a".toMediaType())
                )
                // Groq's API is compatible with OpenAI's Whisper model names.
                .addFormDataPart("model", "whisper-large-v3")
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedError = runCatching {
                            val json = JSONObject(rawBody)
                            when {
                                json.has("error") -> {
                                    json.optJSONObject("error")?.optString("message")
                                }
                                json.has("message") -> json.optString("message")
                                else -> null
                            }
                        }.getOrNull()
                        val errorMessage = parsedError?.takeIf { it.isNotBlank() }
                            ?: "HTTP ${response.code}"
                        throw IOException("Groq transcription failed: $errorMessage")
                    }
                    val json = JSONObject(rawBody)
                    val text = json.optString("text", "")
                    Transcript(text)
                }
            } catch (e: Exception) {
                Log.e("GroqTranscriber", "Transcription failed", e)
                throw e
            }
        }
}
