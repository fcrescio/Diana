package li.crescio.penates.diana.llm

import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.Transcript
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class NoteInterpreter(private val apiKey: String, private val logger: LlmLogger) {
    private val client = OkHttpClient()

    suspend fun interpret(transcript: Transcript): List<StructuredNote> {
        val json = """{"input": "${transcript.text}"}"""
        val requestBody = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        val responseText = client.newCall(request).execute().use { it.body?.string() ?: "" }
        logger.log(transcript.text, responseText)
        return listOf(StructuredNote.Memo(transcript.text))
    }
}

class LlmLogger {
    private val logs = mutableListOf<String>()
    fun log(request: String, response: String) {
        logs += "REQUEST: $request\nRESPONSE: $response"
    }
    fun entries(): List<String> = logs
}
