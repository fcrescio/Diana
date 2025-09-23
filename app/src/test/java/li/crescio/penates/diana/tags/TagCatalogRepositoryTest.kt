package li.crescio.penates.diana.tags

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.io.path.createTempDirectory

class TagCatalogRepositoryTest {

    @Test
    fun serialization_roundTrip() {
        val catalog = TagCatalog(
            tags = listOf(
                TagDefinition(
                    id = "home",
                    labels = listOf(
                        LocalizedLabel.create("en-US", "Home"),
                        LocalizedLabel.create("en", "Home (general)"),
                        LocalizedLabel.create(null, "Casa"),
                    ),
                    color = "#ff0000",
                ),
                TagDefinition(
                    id = "work",
                    labels = listOf(
                        LocalizedLabel.create("fr", "Travail"),
                        LocalizedLabel.create(null, "Work"),
                    ),
                ),
            ),
        )

        val json = catalog.toJson().toString()
        val parsedFromJson = TagCatalog.fromJson(JSONObject(json))
        assertEquals(catalog, parsedFromJson)

        val map = catalog.toMap()
        val parsedFromMap = TagCatalog.fromMap(map)
        assertEquals(catalog, parsedFromMap)
    }

    @Test
    fun labelForLocale_appliesFallbackOrdering() {
        val definition = TagDefinition(
            id = "travel",
            labels = listOf(
                LocalizedLabel.create("en-GB", "Holiday"),
                LocalizedLabel.create("en", "Vacation"),
                LocalizedLabel.create("fr", "Vacances"),
                LocalizedLabel.create(null, "Trip"),
            ),
        )

        assertEquals("Holiday", definition.labelForLocale(Locale.UK))
        assertEquals("Vacation", definition.labelForLocale(Locale.US))
        assertEquals("Vacances", definition.labelForLocale(Locale.CANADA_FRENCH))
        assertEquals("Vacation", definition.labelForLocale(Locale("es", "ES")))

        val fallbackToDefault = TagDefinition(
            id = "task",
            labels = listOf(
                LocalizedLabel.create("it", "Compito"),
                LocalizedLabel.create(null, "Task"),
            ),
        )

        assertEquals("Task", fallbackToDefault.labelForLocale(Locale("es", "MX")))
        assertNull(TagDefinition("empty", emptyList()).labelForLocale(Locale.US))
    }

    @Test
    fun saveCatalog_remoteFailureReturnsErrorAndPersistsLocal() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")
        val sessionDir = createTempDirectory().toFile()
        val firestore = mockk<FirebaseFirestore>()
        val sessionsCollection = mockk<CollectionReference>()
        val sessionDocument = mockk<DocumentReference>()
        val settingsCollection = mockk<CollectionReference>()
        val tagDocument = mockk<DocumentReference>()

        every { firestore.collection("sessions") } returns sessionsCollection
        every { sessionsCollection.document("session") } returns sessionDocument
        every { sessionDocument.collection("settings") } returns settingsCollection
        every { settingsCollection.document("tagCatalog") } returns tagDocument
        every { tagDocument.set(any()) } returns Tasks.forException(RuntimeException("boom"))

        val repository = TagCatalogRepository(
            sessionId = "session",
            sessionDir = sessionDir,
            firestore = firestore,
        )
        val catalog = TagCatalog(
            tags = listOf(
                TagDefinition(
                    id = "home",
                    labels = listOf(LocalizedLabel.create(null, "Home")),
                ),
            ),
        )

        val result = repository.saveCatalog(catalog)

        assertFalse(result.isSuccessful)
        assertNotNull(result.error)
        assertEquals(catalog, result.catalog)

        val file = File(sessionDir, "tags.json")
        assertTrue(file.exists())
        val parsed = TagCatalog.fromJson(JSONObject(file.readText()))
        assertEquals(catalog, parsed)

        verify(exactly = 1) { tagDocument.set(any()) }
    }
}
