package li.crescio.penates.diana.llm

import li.crescio.penates.diana.notes.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayDeque
import android.util.Log

private fun loadResource(path: String): String {
    val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        ?: throw IOException("Resource $path not found")
    return stream.bufferedReader().use { it.readText() }
}

/**
 * Maintains running text buffers for to-dos, appointments and free-form
 * thoughts, updating each by calling an LLM with strict structured outputs.
 */
class MemoProcessor(
    private val apiKey: String,
    private val logger: LlmLogger,
    locale: Locale,
    private val baseUrl: String = loadResource("llm/base_url.txt").trim(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    private var todo: String = ""
    private var todoItems: List<TodoItem> = emptyList()
    private var appointments: String = ""
    private var appointmentItems: List<Appointment> = emptyList()
    private var thoughts: String = ""
    private var thoughtItems: List<Thought> = emptyList()

    private val prompts = Prompts.forLocale(locale)

    private val requestTemplate = loadResource("llm/request.json")
    private val baseSchema = loadResource("llm/schema/base.json")
    private val todoSchema = loadResource("llm/schema/todo.json")
    private val appointmentSchema = loadResource("llm/schema/appointment.json")
    private val thoughtSchema = loadResource("llm/schema/thought.json")

    /** Seed the processor with an existing summary. */
    fun initialize(summary: MemoSummary) {
        todo = summary.todo
        todoItems = summary.todoItems
        appointments = summary.appointments
        appointmentItems = summary.appointmentItems
        thoughts = summary.thoughts
        thoughtItems = summary.thoughtItems
    }

    /**
     * Update the running summaries based on [memo] and return the latest values.
     */
    suspend fun process(
        memo: Memo,
        processTodos: Boolean = true,
        processAppointments: Boolean = true,
        processThoughts: Boolean = true,
    ): MemoSummary {
        if (processTodos) {
            todo = updateBuffer(prompts.todo, todoPriorJson(), memo.text)
        }
        if (processAppointments) {
            appointments = updateBuffer(prompts.appointments, appointmentPriorJson(), memo.text)
        }
        if (processThoughts) {
            thoughts = updateBuffer(prompts.thoughts, thoughtPriorJson(), memo.text)
        }
        return MemoSummary(todo, appointments, thoughts, todoItems, appointmentItems, thoughtItems)
    }

    private fun todoPriorJson(): String {
        val obj = JSONObject()
        val itemsArr = JSONArray()
        for (item in todoItems) {
            val itemObj = JSONObject()
            itemObj.put("text", item.text)
            itemObj.put("status", item.status)
            val tagsArr = JSONArray()
            item.tags.forEach { tagsArr.put(it) }
            itemObj.put("tags", tagsArr)
            itemsArr.put(itemObj)
        }
        obj.put("items", itemsArr)
        return obj.toString()
    }

    private fun appointmentPriorJson(): String {
        val obj = JSONObject()
        obj.put("updated", appointments)
        val itemsArr = JSONArray()
        for (item in appointmentItems) {
            val itemObj = JSONObject()
            itemObj.put("text", item.text)
            itemObj.put("datetime", item.datetime)
            itemObj.put("location", item.location)
            itemsArr.put(itemObj)
        }
        obj.put("items", itemsArr)
        return obj.toString()
    }

    private fun thoughtPriorJson(): String {
        val obj = JSONObject()
        obj.put("updated", thoughts)
        val itemsArr = JSONArray()
        for (item in thoughtItems) {
            val itemObj = JSONObject()
            itemObj.put("text", item.text)
            val tagsArr = JSONArray()
            item.tags.forEach { tagsArr.put(it) }
            itemObj.put("tags", tagsArr)
            itemsArr.put(itemObj)
        }
        obj.put("items", itemsArr)
        return obj.toString()
    }

    private suspend fun updateBuffer(aspect: String, priorJson: String, memo: String): String {
        val schema = when (aspect) {
            prompts.todo -> todoSchema
            prompts.appointments -> appointmentSchema
            prompts.thoughts -> thoughtSchema
            else -> baseSchema
        }
        val system = prompts.systemTemplate.replace("{aspect}", aspect)
        val user = prompts.userTemplate
            .replace("{aspect}", aspect)
            .replace("{prior}", priorJson)
            .replace("{memo}", memo)
            .replace("{today}", LocalDate.now().toString())
        val json = requestTemplate
            .replace("{system}", system)
            .replace("{user}", user)
            .replace("{schema}", schema)

        if (apiKey.isBlank()) throw IOException("Missing API key")
        check(json.contains("\"model\"")) { "Invalid model" }
        val schemaObject = try {
            JSONObject(schema)
        } catch (e: Exception) {
            throw IOException("Invalid JSON schema", e)
        }
        val innerSchema = schemaObject.optJSONObject("schema") ?: schemaObject
        val required = innerSchema.optJSONArray("required")
        if (aspect == prompts.todo) {
            val itemsProp = innerSchema.optJSONObject("properties")?.optJSONObject("items")
            check(itemsProp?.optString("type") == "array") { "Schema 'items' must be array" }
            check((0 until (required?.length() ?: 0)).any { required!!.optString(it) == "items" }) {
                "Schema missing 'items'"
            }
        } else {
            val updatedProp = innerSchema.optJSONObject("properties")?.optJSONObject("updated")
            check(updatedProp?.optString("type") == "string") { "Schema 'updated' must be string" }
            check((0 until (required?.length() ?: 0)).any { required!!.optString(it) == "updated" }) {
                "Schema missing 'updated'"
            }
        }
    
        val requestBody = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
    
        var attempt = 0
        var responseText: String
        while (true) {
            val (code, message, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    Triple(response.code, response.message, response.body?.string().orEmpty())
                }
        }
        responseText = body
        logger.log(json, responseText)
        if (code in 200..299) break
        logger.log(json, "HTTP $code $message")
        if (attempt >= 2) {
            throw IOException("HTTP $code $message")
        }
        delay((1L shl attempt) * 1000L)
        attempt++
    }

    val messageObj = try {
        JSONObject(responseText)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
    } catch (e: Exception) {
        throw IOException("Invalid response", e)
    } ?: throw IOException("Empty response")

    val rawContent = when (val content = messageObj.opt("content")) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                append(content.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        else -> ""
    }
    if (rawContent.isBlank()) throw IOException("Blank content")

    val start = rawContent.indexOf('{')
    val end = rawContent.lastIndexOf('}')
    if (start == -1 || end == -1 || end <= start) throw IOException("No JSON found")
    return try {
        val obj = JSONObject(rawContent.substring(start, end + 1))
        val itemsArr = obj.optJSONArray("items")
        when (aspect) {
            prompts.todo -> {
                val newItems = (0 until (itemsArr?.length() ?: 0)).mapNotNull { idx ->
                    val itemObj = itemsArr?.optJSONObject(idx) ?: return@mapNotNull null
                    val text = itemObj.optString("text")
                    val status = itemObj.optString("status")
                    val tagsArr = itemObj.optJSONArray("tags")
                    val tags = (0 until (tagsArr?.length() ?: 0)).map { tagsArr.optString(it) }
                    if (text.isBlank()) null else TodoItem(text, status, tags)
                }
                val merged = todoItems.associateBy { it.text }.toMutableMap()
                for (item in newItems) {
                    merged[item.text] = item
                }
                todoItems = merged.values.toList()
                todoItems.joinToString("\n") { it.text }
            }
            prompts.appointments -> {
                appointmentItems = (0 until (itemsArr?.length() ?: 0)).mapNotNull { idx ->
                    val itemObj = itemsArr?.optJSONObject(idx) ?: return@mapNotNull null
                    val text = itemObj.optString("text")
                    val datetime = itemObj.optString("datetime")
                    val location = itemObj.optString("location")
                    if (text.isBlank()) null else Appointment(text, datetime, location)
                }
                obj.optString("updated", appointments)
            }
            prompts.thoughts -> {
                thoughtItems = (0 until (itemsArr?.length() ?: 0)).mapNotNull { idx ->
                    val itemObj = itemsArr?.optJSONObject(idx) ?: return@mapNotNull null
                    val text = itemObj.optString("text")
                    val tagsArr = itemObj.optJSONArray("tags")
                    val tags = (0 until (tagsArr?.length() ?: 0)).map { tagsArr.optString(it) }
                    if (text.isBlank()) null else Thought(text, tags)
                }
                obj.optString("updated", thoughts)
            }
            else -> obj.optString("updated", "")
        }
    } catch (e: Exception) {
        throw IOException("Invalid JSON", e)
    }
}
}

data class TodoItem(val text: String, val status: String, val tags: List<String>)

data class Appointment(val text: String, val datetime: String, val location: String)

data class MemoSummary(
    val todo: String,
    val appointments: String,
    val thoughts: String,
    val todoItems: List<TodoItem>,
    val appointmentItems: List<Appointment>,
    val thoughtItems: List<Thought>
)

data class Thought(val text: String, val tags: List<String>)

class LlmLogger(private val maxLogs: Int = 100) {
    private val logs = ArrayDeque<String>()
    private val _logFlow = MutableSharedFlow<String>()
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    companion object {
        private const val TAG = "LlmLogger"
    }

    fun log(request: String, response: String) {
        val entry = "REQUEST: $request\nRESPONSE: $response"
        Log.d(TAG, entry)
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
        private fun load(locale: String, name: String): String =
            loadResource("llm/prompts/$locale/$name.txt").trim()

        fun forLocale(locale: Locale): Prompts {
            val lang = when (locale.language) {
                "it" -> "it"
                "fr" -> "fr"
                else -> "en"
            }
            return Prompts(
                todo = load(lang, "todo"),
                appointments = load(lang, "appointments"),
                thoughts = load(lang, "thoughts"),
                systemTemplate = load(lang, "system"),
                userTemplate = load(lang, "user"),
            )
        }
    }
}
