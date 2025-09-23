package li.crescio.penates.diana.tags

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException

class TagCatalogRepository(
    private val sessionId: String,
    private val sessionDir: File,
    private val firestore: FirebaseFirestore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val catalogFile = File(sessionDir, "tags.json")
    private val mutex = Mutex()

    suspend fun loadCatalog(): TagCatalog = withContext(dispatcher) {
        val local = mutex.withLock { readLocalLocked() }
        if (local != null) {
            return@withContext local
        }
        val remote = fetchRemote()
        val catalog = remote.catalog ?: TagCatalog(emptyList())
        if (remote.error == null && remote.catalog != null) {
            mutex.withLock { writeLocalLocked(catalog) }
        }
        catalog
    }

    suspend fun saveCatalog(catalog: TagCatalog): TagCatalogSyncOutcome = withContext(dispatcher) {
        mutex.withLock { writeLocalLocked(catalog) }
        val error = try {
            settingsDocument().set(catalog.toMap()).await()
            null
        } catch (e: Exception) {
            e
        }
        TagCatalogSyncOutcome(catalog = catalog, error = error)
    }

    suspend fun syncFromRemote(): TagCatalogSyncOutcome = withContext(dispatcher) {
        val remote = fetchRemote()
        if (remote.error == null && remote.catalog != null) {
            mutex.withLock { writeLocalLocked(remote.catalog) }
        }
        remote
    }

    private suspend fun fetchRemote(): TagCatalogSyncOutcome {
        return try {
            val snapshot = settingsDocument().get().await()
            val data = snapshot.data
            val catalog = if (data == null) {
                TagCatalog(emptyList())
            } else {
                TagCatalog.fromMap(data)
            }
            TagCatalogSyncOutcome(catalog = catalog)
        } catch (e: Exception) {
            TagCatalogSyncOutcome(catalog = TagCatalog(emptyList()), error = e)
        }
    }

    private fun readLocalLocked(): TagCatalog? {
        if (!catalogFile.exists()) {
            return null
        }
        return try {
            val contents = catalogFile.readText()
            if (contents.isBlank()) {
                TagCatalog(emptyList())
            } else {
                TagCatalog.fromJson(JSONObject(contents))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLocalLocked(catalog: TagCatalog) {
        val json = catalog.toJson().toString()
        writeAtomically(json)
    }

    private fun writeAtomically(contents: String) {
        val parent = catalogFile.parentFile ?: sessionDir
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create directory: ${parent.absolutePath}")
        }
        val tmp = File.createTempFile("tags", ".json.tmp", parent)
        try {
            tmp.writeText(contents, Charsets.UTF_8)
            if (catalogFile.exists()) {
                if (!tmp.renameTo(catalogFile)) {
                    if (!catalogFile.delete() || !tmp.renameTo(catalogFile)) {
                        tmp.copyTo(catalogFile, overwrite = true)
                        tmp.delete()
                    }
                }
            } else {
                if (!tmp.renameTo(catalogFile)) {
                    tmp.copyTo(catalogFile, overwrite = true)
                    tmp.delete()
                }
            }
        } finally {
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    private fun settingsDocument() = firestore
        .collection("sessions")
        .document(sessionId)
        .collection("settings")
        .document("tagCatalog")
}

data class TagCatalogSyncOutcome(
    val catalog: TagCatalog?,
    val error: Exception? = null,
) {
    val isSuccessful: Boolean get() = error == null
}
