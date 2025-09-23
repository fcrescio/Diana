package li.crescio.penates.diana.tags

import org.json.JSONArray
import org.json.JSONObject
import java.util.Comparator
import java.util.Locale

private val LABEL_COMPARATOR = Comparator<LocalizedLabel> { first, second ->
    val firstTag = first.normalizedLocaleTag
    val secondTag = second.normalizedLocaleTag
    when {
        firstTag == secondTag -> 0
        firstTag == null -> 1
        secondTag == null -> -1
        firstTag.length != secondTag.length -> secondTag.length.compareTo(firstTag.length)
        else -> firstTag.compareTo(secondTag)
    }
}

internal fun normalizeLocaleTag(tag: String): String {
    return tag.replace('_', '-').lowercase(Locale.US)
}

data class LocalizedLabel(
    val localeTag: String?,
    val value: String,
) {
    init {
        if (localeTag != null && localeTag.isBlank()) {
            throw IllegalArgumentException("localeTag must be null or non-blank")
        }
    }

    val normalizedLocaleTag: String? = localeTag?.let { normalizeLocaleTag(it) }

    fun storageKey(): String {
        return normalizedLocaleTag ?: DEFAULT_STORAGE_KEY
    }

    companion object {
        const val DEFAULT_STORAGE_KEY = "default"

        fun create(localeTag: String?, value: String): LocalizedLabel {
            val normalized = localeTag?.takeUnless { it.isBlank() }?.let { normalizeLocaleTag(it) }
            return LocalizedLabel(normalized, value)
        }
    }
}

data class TagDefinition(
    val id: String,
    val labels: List<LocalizedLabel>,
    val color: String? = null,
) {
    fun labelForLocale(
        locale: Locale,
        fallbackLocales: List<Locale> = DEFAULT_FALLBACK_LOCALES,
    ): String? {
        if (labels.isEmpty()) {
            return null
        }
        val byLocale = mutableMapOf<String, LocalizedLabel>()
        var defaultLabel: LocalizedLabel? = null
        labels.forEach { label ->
            val normalized = label.normalizedLocaleTag
            if (normalized == null) {
                if (defaultLabel == null) {
                    defaultLabel = label
                }
            } else if (!byLocale.containsKey(normalized)) {
                byLocale[normalized] = label
            }
        }
        val visited = mutableSetOf<String>()
        LocaleFallback.candidates(locale).forEach { candidate ->
            if (visited.add(candidate)) {
                byLocale[candidate]?.let { return it.value }
            }
        }
        fallbackLocales.forEach { fallback ->
            LocaleFallback.candidates(fallback).forEach { candidate ->
                if (visited.add(candidate)) {
                    byLocale[candidate]?.let { return it.value }
                }
            }
        }
        defaultLabel?.let { return it.value }
        return labels.first().value
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        color?.takeUnless { it.isBlank() }?.let { obj.put("color", it) }
        val labelsObject = JSONObject()
        labels.sortedWith(LABEL_COMPARATOR).forEach { label ->
            val key = label.storageKey()
            if (!labelsObject.has(key)) {
                labelsObject.put(key, label.value)
            }
        }
        obj.put("labels", labelsObject)
        return obj
    }

    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>("id" to id)
        color?.takeUnless { it.isBlank() }?.let { map["color"] = it }
        val labelsMap = linkedMapOf<String, String>()
        labels.sortedWith(LABEL_COMPARATOR).forEach { label ->
            val key = label.storageKey()
            if (!labelsMap.containsKey(key)) {
                labelsMap[key] = label.value
            }
        }
        map["labels"] = labelsMap
        return map
    }

    companion object {
        val DEFAULT_FALLBACK_LOCALES: List<Locale> = listOf(Locale.ENGLISH)

        fun fromJson(obj: JSONObject?): TagDefinition? {
            if (obj == null) return null
            val id = obj.optString("id", "").takeUnless { it.isBlank() } ?: return null
            val color = obj.optString("color", "").takeUnless { it.isBlank() }
            val labelsObj = obj.optJSONObject("labels") ?: JSONObject()
            val labels = mutableListOf<LocalizedLabel>()
            val keys = labelsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val rawValue = labelsObj.opt(key)
                val raw = rawValue as? String ?: continue
                val localeTag = if (key == LocalizedLabel.DEFAULT_STORAGE_KEY) null else key
                labels += LocalizedLabel.create(localeTag, raw)
            }
            return TagDefinition(id, labels.sortedWith(LABEL_COMPARATOR), color)
        }

        fun fromMap(map: Map<*, *>?): TagDefinition? {
            if (map == null) return null
            val id = (map["id"] as? String)?.takeUnless { it.isBlank() } ?: return null
            val color = (map["color"] as? String)?.takeUnless { it.isBlank() }
            val labelsMap = map["labels"] as? Map<*, *>
            val labels = mutableListOf<LocalizedLabel>()
            labelsMap?.forEach { (key, value) ->
                val keyString = key as? String ?: return@forEach
                val labelValue = value as? String ?: return@forEach
                val localeTag = if (keyString == LocalizedLabel.DEFAULT_STORAGE_KEY) null else keyString
                labels += LocalizedLabel.create(localeTag, labelValue)
            }
            return TagDefinition(id, labels.sortedWith(LABEL_COMPARATOR), color)
        }
    }
}

data class TagCatalog(
    val tags: List<TagDefinition>,
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        val array = JSONArray()
        tags.forEach { array.put(it.toJson()) }
        obj.put("tags", array)
        return obj
    }

    fun toMap(): Map<String, Any> {
        return mapOf("tags" to tags.map { it.toMap() })
    }

    companion object {
        fun fromJson(obj: JSONObject?): TagCatalog {
            if (obj == null) return TagCatalog(emptyList())
            val array = obj.optJSONArray("tags") ?: JSONArray()
            val tags = mutableListOf<TagDefinition>()
            for (i in 0 until array.length()) {
                val element = array.optJSONObject(i) ?: continue
                TagDefinition.fromJson(element)?.let { tags += it }
            }
            return TagCatalog(tags)
        }

        fun fromMap(map: Map<*, *>?): TagCatalog {
            if (map == null) return TagCatalog(emptyList())
            val rawTags = map["tags"] as? List<*>
            val tags = mutableListOf<TagDefinition>()
            rawTags?.forEach { entry ->
                TagDefinition.fromMap(entry as? Map<*, *>)?.let { tags += it }
            }
            return TagCatalog(tags)
        }
    }
}

object LocaleFallback {
    fun candidates(locale: Locale): Sequence<String> = sequence {
        val normalizedBase = normalizeLocaleTag(locale.toLanguageTag())
        if (normalizedBase.isNotBlank()) {
            yield(normalizedBase)
        }
        val builder = Locale.Builder()
        val language = locale.language
        if (language.isNotEmpty()) {
            builder.setLanguage(language)
        }
        val script = locale.script
        val region = locale.country
        val variant = locale.variant
        if (script.isNotEmpty()) {
            builder.setScript(script)
        }
        if (region.isNotEmpty()) {
            builder.setRegion(region)
        }
        if (variant.isNotEmpty()) {
            builder.setVariant(variant)
            val withoutVariant = builder.build().toLanguageTag()
            val normalized = normalizeLocaleTag(withoutVariant)
            if (normalized.isNotBlank() && normalized != normalizedBase) {
                yield(normalized)
            }
            builder.setVariant("")
        }
        if (region.isNotEmpty()) {
            val withoutRegion = builder.build().toLanguageTag()
            val normalized = normalizeLocaleTag(withoutRegion)
            if (normalized.isNotBlank() && normalized != normalizedBase) {
                yield(normalized)
            }
            builder.setRegion("")
        }
        if (script.isNotEmpty()) {
            val withoutScript = builder.build().toLanguageTag()
            val normalized = normalizeLocaleTag(withoutScript)
            if (normalized.isNotBlank() && normalized != normalizedBase) {
                yield(normalized)
            }
            builder.setScript("")
        }
        if (language.isNotEmpty()) {
            val normalizedLanguage = normalizeLocaleTag(language)
            if (normalizedLanguage.isNotBlank() && normalizedLanguage != normalizedBase) {
                yield(normalizedLanguage)
            }
        }
        yield(LocalizedLabel.DEFAULT_STORAGE_KEY)
    }
}
