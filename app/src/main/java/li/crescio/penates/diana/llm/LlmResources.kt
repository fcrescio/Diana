package li.crescio.penates.diana.llm

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Loads LLM resources from the app bundle and allows overriding them with
 * remotely managed versions stored in Firestore.
 */
object LlmResources {
    private const val TAG = "LlmResources"
    private const val VERSION_FILE_NAME = "_version.txt"

    @Volatile
    private var storageDir: File? = null

    @Volatile
    private var overrides: Map<String, String> = emptyMap()

    /**
     * Prepare the loader by pointing it to the directory used to cache
     * downloaded resources and hydrating any previously cached values.
     */
    fun initialize(root: File) {
        storageDir = root
        if (!root.exists() && !root.mkdirs()) {
            Log.w(TAG, "Unable to create cache directory: ${root.absolutePath}")
        }
        overrides = loadOverridesFromDisk(root)
    }

    /**
     * Load the textual content of [path], checking cached overrides first and
     * falling back to the bundled resource when no override exists.
     */
    @Throws(IOException::class)
    fun load(path: String): String {
        val normalized = normalizePath(path)
        overrides[normalized]?.let { return it }
        val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(normalized)
            ?: throw IOException("Resource $normalized not found")
        return stream.bufferedReader().use { it.readText() }
    }

    /**
     * Download the latest resource documents from Firestore and persist them to
     * disk so that they can be used to override bundled assets.
     */
    suspend fun refreshFromFirestore(firestore: FirebaseFirestore) {
        val directory = storageDir ?: throw IllegalStateException("LlmResources not initialized")
        Log.i(TAG, "Starting LLM resource refresh into ${directory.absolutePath}")
        val snapshot = firestore.collection("resources").get().await()
        Log.i(TAG, "Fetched ${snapshot.size()} resource documents from Firestore collection 'resources'")
        if (snapshot.isEmpty) {
            Log.w(TAG, "No documents found in Firestore collection 'resources'")
            return
        }

        val entries = snapshot.documents.mapNotNull { doc ->
            val rawFilename = doc.getString("filename")
            val filename = rawFilename?.trim().orEmpty()
            val content = doc.getString("content")
            if (filename.isEmpty() || content == null) {
                val reason = when {
                    rawFilename.isNullOrBlank() -> "missing or blank filename"
                    content == null -> "missing content"
                    else -> "unknown validation failure"
                }
                Log.w(
                    TAG,
                    "Skipping resource document id=${doc.id} filename='${rawFilename.orEmpty()}': $reason"
                )
                null
            } else {
                try {
                    normalizePath(filename) to content
                } catch (e: IllegalArgumentException) {
                    Log.w(
                        TAG,
                        "Skipping resource document id=${doc.id} filename='${filename}': ${e.message}"
                    )
                    null
                }
            }
        }

        if (entries.isEmpty()) {
            Log.w(TAG, "No valid documents found in Firestore collection 'resources'")
            return
        }

        val orderedEntries = java.util.TreeMap<String, String>()
        for ((name, content) in entries) {
            orderedEntries[name] = content
        }

        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, content) in orderedEntries) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(content.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        val newHash = digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }

        val versionFile = File(directory, VERSION_FILE_NAME)
        val previousHash = if (versionFile.exists()) {
            runCatching { versionFile.readText() }.getOrNull()
        } else {
            null
        }
        val isNewVersion = previousHash != newHash

        withContext(Dispatchers.IO) {
            val keep = orderedEntries.keys
            directory.walkTopDown()
                .filter { it.isFile && it.name != VERSION_FILE_NAME }
                .forEach { file ->
                    val relative = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    if (relative !in keep) {
                        try {
                            if (file.delete()) {
                                Log.i(TAG, "Deleted stale resource ${file.absolutePath}")
                            } else if (file.exists()) {
                                Log.e(TAG, "Failed to delete stale resource ${file.absolutePath}")
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Error deleting stale resource ${file.absolutePath}", e)
                            throw e
                        }
                    }
                }

            for ((name, content) in orderedEntries) {
                val target = File(directory, name)
                val parent = target.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    Log.w(TAG, "Unable to create directory ${parent.absolutePath} for resource ${target.absolutePath}")
                }
                val existed = target.exists()
                try {
                    target.writeText(content)
                    val operation = if (existed) "Updated" else "Created"
                    Log.i(TAG, "$operation resource ${target.absolutePath}")
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing resource ${target.absolutePath}", e)
                    throw e
                }
            }

            try {
                versionFile.writeText(newHash)
            } catch (e: IOException) {
                Log.e(TAG, "Error writing version hash to ${versionFile.absolutePath}", e)
                throw e
            }
        }

        overrides = orderedEntries.toMap()
        Log.i(
            TAG,
            "LLM resources refresh complete for ${directory.absolutePath} (versionHash=$newHash, newVersion=$isNewVersion)"
        )
    }

    private fun loadOverridesFromDisk(root: File): Map<String, String> {
        if (!root.exists()) {
            return emptyMap()
        }
        val loaded = mutableMapOf<String, String>()
        root.walkTopDown()
            .filter { it.isFile && it.name != VERSION_FILE_NAME }
            .forEach { file ->
                val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                try {
                    loaded[relative] = file.readText()
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to read cached resource ${file.absolutePath}", e)
                }
            }
        if (loaded.isNotEmpty()) {
            Log.i(TAG, "Loaded ${loaded.size} cached LLM resources")
        }
        return loaded.toMap()
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().trimStart('/')
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Empty path")
        }
        if (trimmed.contains("..")) {
            throw IllegalArgumentException("Invalid path: $path")
        }
        val normalized = trimmed.replace('\\', '/')
        return if (normalized.startsWith("llm/")) normalized else "llm/$normalized"
    }
}
