package li.crescio.penates.diana.llm

import li.crescio.penates.diana.notes.Memo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Maintains running text buffers for to-dos, appointments and free-form
 * thoughts, updating each by calling an LLM with strict structured outputs.
 */
class MemoProcessor(private val apiKey: String, private val logger: LlmLogger) {
    private val client = OkHttpClient()

    private var todo: String = ""
    private var appointments: String = ""
    private var thoughts: String = ""

    /**
     * Update the running summaries based on [memo] and return the latest values.
     */
    suspend fun process(memo: Memo): MemoSummary {
        todo = updateBuffer("to-do list", todo, memo.text)
        appointments = updateBuffer("appointments list", appointments, memo.text)
        thoughts = updateBuffer("thoughts and notes", thoughts, memo.text)
        return MemoSummary(todo, appointments, thoughts)
    }

    private suspend fun updateBuffer(aspect: String, prior: String, memo: String): String {
        val schema = """{"type":"object","properties":{"updated":{"type":"string"}},"required":["updated"]}"""
        val json = """
            {
              "model": "mistralai/mistral-nemo",
              "messages": [
                {"role":"system","content":"You maintain a $aspect document. Return only JSON."},
                {"role":"user","content":"Current $aspect:\n$prior\n\nNew memo:\n$memo\n\nReturn the updated $aspect in the field 'updated'."}
              ],
              "response_format": {
                "type":"json_schema",
                "json_schema":{
                  "name":"update",
                  "schema":$schema
                }
              }
            }
        """.trimIndent()

        val requestBody = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        val responseText = client.newCall(request).execute().use { it.body?.string().orEmpty() }
        logger.log(json, responseText)

        val content = JSONObject(responseText)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: return prior
        return try {
            JSONObject(content).optString("updated", prior)
        } catch (_: Exception) {
            prior
        }
    }
}

data class MemoSummary(val todo: String, val appointments: String, val thoughts: String)

class LlmLogger {
    private val logs = mutableListOf<String>()
    fun log(request: String, response: String) {
        logs += "REQUEST: $request\nRESPONSE: $response"
    }
    fun entries(): List<String> = logs
}

