package li.crescio.penates.diana.llm

import android.util.Log
import org.json.JSONArray
import java.io.IOException
import java.util.LinkedHashSet

/**
 * Loads the list of supported LLM models from bundled resources with optional
 * overrides provided via [LlmResources].
 */
object LlmModelCatalog {
    private const val TAG = "LlmModelCatalog"
    private const val MODELS_PATH = "llm/models.json"

    data class ModelDefinition(val id: String, val labelResourceName: String)

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedModels: List<ModelDefinition> = emptyList()

    /**
     * Returns the ordered list of available models described by the catalog
     * resource. Parsed definitions are cached and refreshed whenever the
     * underlying resource content changes.
     */
    fun availableModels(): List<ModelDefinition> {
        val raw = try {
            LlmResources.load(MODELS_PATH)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load model catalog", e)
            return cachedModels
        }

        if (cachedRaw == raw) {
            return cachedModels
        }

        val parsed = parseModels(raw)
        if (parsed == null) {
            return cachedModels
        }

        cachedRaw = raw
        cachedModels = parsed
        return parsed
    }

    private fun parseModels(raw: String): List<ModelDefinition>? {
        return try {
            val result = mutableListOf<ModelDefinition>()
            val seen = LinkedHashSet<String>()
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index)
                if (obj == null) {
                    Log.w(TAG, "Skipping model entry at index $index: not a JSON object")
                    continue
                }
                val id = obj.optString("id").trim()
                val label = obj.optString("label").trim()
                if (id.isEmpty() || label.isEmpty()) {
                    Log.w(TAG, "Skipping model entry at index $index: missing id or label")
                    continue
                }
                if (!seen.add(id)) {
                    Log.w(TAG, "Skipping duplicate model id '$id'")
                    continue
                }
                result.add(ModelDefinition(id, label))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse model catalog", e)
            null
        }
    }

    fun availableModelIds(): List<String> = availableModels().map { it.id }

    fun isModelAvailable(id: String): Boolean = availableModels().any { it.id == id }
}
