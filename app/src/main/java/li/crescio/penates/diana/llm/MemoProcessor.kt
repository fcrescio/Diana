package li.crescio.penates.diana.llm

import li.crescio.penates.diana.notes.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.collections.ArrayDeque

/**
 * Maintains running text buffers for to-dos, appointments and free-form
 * thoughts, updating each by calling an LLM with strict structured outputs.
 */
class MemoProcessor(private val apiKey: String, private val logger: LlmLogger, locale: Locale) {
    private val client = OkHttpClient()

    private var todo: String = ""
    private var appointments: String = ""
    private var thoughts: String = ""

    private val prompts = Prompts.forLocale(locale)

    /**
     * Update the running summaries based on [memo] and return the latest values.
     */
    suspend fun process(memo: Memo): MemoSummary {
        todo = updateBuffer(prompts.todo, todo, memo.text)
        appointments = updateBuffer(prompts.appointments, appointments, memo.text)
        thoughts = updateBuffer(prompts.thoughts, thoughts, memo.text)
        return MemoSummary(todo, appointments, thoughts)
    }

    private suspend fun updateBuffer(aspect: String, prior: String, memo: String): String {
        val schema = """{"type":"object","properties":{"updated":{"type":"string"}},"required":["updated"]}""""
        val system = prompts.systemTemplate.replace("{aspect}", aspect)
        val user = prompts.userTemplate
            .replace("{aspect}", aspect)
            .replace("{prior}", prior)
            .replace("{memo}", memo)
        val json = """
            {
              "model": "mistralai/mistral-nemo",
              "messages": [
                {"role":"system","content":"$system"},
                {"role":"user","content":"$user"}
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

        val (code, message, responseText) = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                Triple(response.code, response.message, response.body?.string().orEmpty())
            }
        }
        if (code !in 200..299) {
            logger.log(json, "HTTP $code $message: $responseText")
            return prior
        }
        logger.log(json, responseText)

        val messageObj = try {
            JSONObject(responseText)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
        } catch (_: Exception) {
            null
        } ?: return prior

        val rawContent = when (val content = messageObj.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    append(content.optJSONObject(i)?.optString("text").orEmpty())
                }
            }
            else -> ""
        }
        if (rawContent.isBlank()) return prior

        val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(rawContent)
            ?: return prior
        return try {
            JSONObject(jsonMatch.value).optString("updated", prior)
        } catch (_: Exception) {
            prior
        }
    }
}

data class MemoSummary(val todo: String, val appointments: String, val thoughts: String)

class LlmLogger(private val maxLogs: Int = 100) {
    private val logs = ArrayDeque<String>()
    private val _logFlow = MutableSharedFlow<String>()
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    fun log(request: String, response: String) {
        val entry = "REQUEST: $request\nRESPONSE: $response"
        if (logs.size == maxLogs) {
            logs.removeFirst()
        }
        logs.addLast(entry)
        _logFlow.tryEmit(entry)
    }

    fun entries(): List<String> = logs.toList()
}

/**
 * Holds localized strings for interacting with the LLM.
 */
data class Prompts(
    val todo: String,
    val appointments: String,
    val thoughts: String,
    val systemTemplate: String,
    val userTemplate: String
) {
    companion object {
        fun forLocale(locale: Locale): Prompts {
            return when (locale.language) {
                "it" -> Prompts(
                    todo = "lista di cose da fare",
                    appointments = "lista degli appuntamenti",
                    thoughts = "pensieri e note",
                    systemTemplate = "Gestisci un documento {aspect}. Restituisci solo JSON.",
                    userTemplate = "Stato attuale della {aspect}:\n{prior}\n\nNuovo memo:\n{memo}\n\nRestituisci la {aspect} aggiornata nel campo 'updated', nella stessa lingua del nuovo memo."
                )
                "fr" -> Prompts(
                    todo = "liste de tâches",
                    appointments = "liste des rendez-vous",
                    thoughts = "pensées et notes",
                    systemTemplate = "Vous maintenez un document de {aspect}. Retournez uniquement du JSON.",
                    userTemplate = "État actuel de la {aspect}:\n{prior}\n\nNouveau mémo:\n{memo}\n\nRetournez la {aspect} mise à jour dans le champ 'updated', dans la même langue que le nouveau mémo."
                )
                else -> Prompts(
                    todo = "to-do list",
                    appointments = "appointments list",
                    thoughts = "thoughts and notes",
                    systemTemplate = "You maintain a {aspect} document. Return only JSON.",
                    userTemplate = "Current {aspect}:\n{prior}\n\nNew memo:\n{memo}\n\nReturn the updated {aspect} in the field 'updated', in the same language as the new memo."
                )
            }
        }
    }
}
