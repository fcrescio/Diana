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
        val snapshot = firestore.collection("resources").get().await()
        if (snapshot.isEmpty) {
            Log.w(TAG, "No documents found in Firestore collection 'resources'")
            return
        }

        val entries = snapshot.documents.mapNotNull { doc ->
            val filename = doc.getString("filename")?.trim().orEmpty()
            val content = doc.getString("content")
            if (filename.isEmpty() || content == null) {
                Log.w(TAG, "Skipping resource document ${doc.id} due to missing fields")
                null
            } else {
                try {
                    normalizePath(filename) to content
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Skipping resource document ${doc.id}: ${e.message}")
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
                        file.delete()
                    }
                }

            for ((name, content) in orderedEntries) {
                val target = File(directory, name)
                target.parentFile?.mkdirs()
                target.writeText(content)
            }

            versionFile.writeText(newHash)
        }

        overrides = orderedEntries.toMap()
        Log.i(TAG, "LLM resources updated successfully (new version: $isNewVersion)")
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
