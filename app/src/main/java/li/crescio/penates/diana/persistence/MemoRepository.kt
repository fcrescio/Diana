package li.crescio.penates.diana.persistence

import li.crescio.penates.diana.notes.Memo
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MemoRepository(private val file: File) {
    suspend fun addMemo(memo: Memo) {
        if (!file.exists()) {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs() && !parent.exists()) {
                    throw IOException("Unable to create directory: ${parent.absolutePath}")
                }
            }
            if (!file.exists()) {
                file.createNewFile()
            }
        }
        file.appendText(toJson(memo) + "\n")
    }

    suspend fun loadMemos(): List<Memo> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { parse(it) }
    }

    suspend fun deleteMemo(memo: Memo) {
        if (!file.exists()) return

        val remaining = mutableListOf<String>()
        var removed = false

        file.readLines().forEach { line ->
            val parsed = parse(line)
            if (!removed && parsed != null && parsed.text == memo.text && parsed.audioPath == memo.audioPath) {
                removed = true
            } else {
                remaining += line
            }
        }

        if (remaining.isEmpty()) {
            file.writeText("")
        } else {
            file.writeText(remaining.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private fun toJson(memo: Memo): String {
        val obj = JSONObject()
        obj.put("text", memo.text)
        memo.audioPath?.let { obj.put("audioPath", it) }
        return obj.toString()
    }

    private fun parse(line: String): Memo? {
        return try {
            val obj = JSONObject(line)
            val text = obj.getString("text")
            val audioPath = obj.optString("audioPath", null)
            val path = if (audioPath.isNullOrEmpty()) null else audioPath
            Memo(text, path)
        } catch (_: Exception) {
            null
        }
    }
}

