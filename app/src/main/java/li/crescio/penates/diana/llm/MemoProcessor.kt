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
import com.fasterxml.jackson.core.io.JsonStringEncoder
import java.io.IOException
import java.util.Locale
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayDeque
import android.util.Log
import li.crescio.penates.diana.notes.ThoughtDocument
import li.crescio.penates.diana.notes.ThoughtOutline
import li.crescio.penates.diana.notes.ThoughtOutlineSection

private fun loadResource(path: String): String = LlmResources.load(path)

/**
 * Maintains running text buffers for to-dos, appointments and free-form
 * thoughts, updating each by calling an LLM with strict structured outputs.
 */
class MemoProcessor(
    private val apiKey: String,
    private val logger: LlmLogger,
    locale: Locale,
    initialModel: String = DEFAULT_MODEL,
    private val baseUrl: String = loadResource("llm/base_url.txt").trim(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        const val DEFAULT_MODEL = "mistralai/mistral-nemo"
    }

    private var todo: String = ""
    private var todoItems: List<TodoItem> = emptyList()
    private var appointments: String = ""
    private var appointmentItems: List<Appointment> = emptyList()
    private var thoughts: String = ""
    private var thoughtItems: List<Thought> = emptyList()
    private var thoughtDocument: ThoughtDocument? = null

    private val prompts = Prompts.forLocale(locale)

    var model: String = normalizeModel(initialModel)
        set(value) {
            field = normalizeModel(value)
        }

    private fun normalizeModel(value: String): String {
        val available = LlmModelCatalog.availableModelIds()
        if (available.isEmpty()) {
            return DEFAULT_MODEL
        }
        return when {
            available.contains(value) -> value
            available.contains(DEFAULT_MODEL) -> DEFAULT_MODEL
            else -> available.first()
        }
    }

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
        thoughts = summary.thoughtDocument?.markdownBody ?: summary.thoughts
        thoughtItems = summary.thoughtItems
        thoughtDocument = summary.thoughtDocument
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
        return MemoSummary(
            todo,
            appointments,
            thoughts,
            todoItems,
            appointmentItems,
            thoughtItems,
            thoughtDocument,
        )
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
            if (item.dueDate.isNotBlank()) itemObj.put("due_date", item.dueDate)
            if (item.eventDate.isNotBlank()) itemObj.put("event_date", item.eventDate)
            if (item.id.isNotBlank()) itemObj.put("id", item.id)
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
        obj.put("markdown_body", thoughtDocument?.markdownBody ?: thoughts)
        val outlineSections = thoughtDocument?.outline?.sections ?: emptyList()
        obj.put("sections", JSONArray().apply {
            outlineSections.forEach { put(outlineSectionToJson(it)) }
        })
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

    private fun outlineSectionToJson(section: ThoughtOutlineSection): JSONObject {
        val obj = JSONObject()
        obj.put("title", section.title)
        obj.put("level", section.level)
        obj.put("anchor", section.anchor)
        val children = JSONArray()
        section.children.forEach { child ->
            children.put(outlineSectionToJson(child))
        }
        obj.put("children", children)
        return obj
    }

    private fun parseOutlineSections(array: JSONArray?): List<ThoughtOutlineSection> {
        if (array == null) return emptyList()
        val sections = mutableListOf<ThoughtOutlineSection>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val title = obj.optString("title").trim()
            if (title.isBlank()) continue
            val level = obj.optInt("level", 1)
            val anchorValue = obj.optString("anchor").trim()
            val anchor = if (anchorValue.isNotBlank()) anchorValue else defaultAnchor(title)
            val children = parseOutlineSections(obj.optJSONArray("children"))
            sections.add(ThoughtOutlineSection(title, level, anchor, children))
        }
        return sections
    }

    private fun defaultAnchor(title: String): String {
        val slug = title.lowercase(Locale.ROOT)
            .replace("[^a-z0-9\\s-]".toRegex(), "")
            .trim()
            .replace("\\s+".toRegex(), "-")
        return if (slug.isNotBlank()) slug else "section-${Integer.toHexString(title.hashCode())}"
    }

    private fun buildRequest(system: String, user: String, schema: String): String {
        val encoder = JsonStringEncoder.getInstance()
        val escapedModel = String(encoder.quoteAsString(model))
        val escapedSystem = String(encoder.quoteAsString(system))
        val escapedUser = String(encoder.quoteAsString(user))
        return requestTemplate
            .replace("{model}", escapedModel)
            .replace("{system}", escapedSystem)
            .replace("{user}", escapedUser)
            .replace("{schema}", schema)
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
        val json = buildRequest(system, user, schema)

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
        } else if (aspect == prompts.thoughts) {
            val props = innerSchema.optJSONObject("properties")
            val markdownProp = props?.optJSONObject("updated_markdown")
            check(markdownProp?.optString("type") == "string") {
                "Schema 'updated_markdown' must be string"
            }
            val sectionsProp = props?.optJSONObject("sections")
            check(sectionsProp?.optString("type") == "array") { "Schema 'sections' must be array" }
            val requiredFields = (0 until (required?.length() ?: 0)).map { required!!.optString(it) }
            check(requiredFields.contains("updated_markdown")) { "Schema missing 'updated_markdown'" }
            check(requiredFields.contains("sections")) { "Schema missing 'sections'" }
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
                val updates = (0 until (itemsArr?.length() ?: 0)).mapNotNull { idx ->
                    val itemObj = itemsArr?.optJSONObject(idx) ?: return@mapNotNull null
                    val op = itemObj.optString("op")
                    val text = itemObj.optString("text")
                    val status = itemObj.optString("status")
                    val tagsArr = itemObj.optJSONArray("tags")
                    val tags = (0 until (tagsArr?.length() ?: 0)).map { tagsArr.optString(it) }
                    val dueDate = itemObj.optString("due_date", "")
                    val eventDate = itemObj.optString("event_date", "")
                    val id = itemObj.optString("id", "")
                    if (text.isBlank()) null else op to TodoItem(text, status, tags, dueDate, eventDate, id)
                }
                val merged = todoItems.associateBy { it.id.ifBlank { it.text } }.toMutableMap()
                for ((op, item) in updates) {
                    when (op) {
                        "add", "update" -> merged[item.id.ifBlank { item.text }] = item
                    }
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
                val updatedMarkdown = obj.optString("updated_markdown", thoughts)
                val sections = parseOutlineSections(obj.optJSONArray("sections"))
                thoughtDocument = ThoughtDocument(updatedMarkdown, ThoughtOutline(sections))
                updatedMarkdown
            }
            else -> obj.optString("updated", "")
        }
    } catch (e: Exception) {
        throw IOException("Invalid JSON", e)
    }
}
}

data class TodoItem(
    val text: String,
    val status: String,
    val tags: List<String>,
    val dueDate: String = "",
    val eventDate: String = "",
    val id: String = "",
)

data class Appointment(val text: String, val datetime: String, val location: String)

data class MemoSummary(
    val todo: String,
    val appointments: String,
    val thoughts: String,
    val todoItems: List<TodoItem>,
    val appointmentItems: List<Appointment>,
    val thoughtItems: List<Thought>,
    val thoughtDocument: ThoughtDocument? = null,
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
