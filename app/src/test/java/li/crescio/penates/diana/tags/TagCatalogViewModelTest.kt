package li.crescio.penates.diana.tags

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagCatalogViewModelTest {

    @Test
    fun refresh_loadsCatalogIntoState() = runTest {
        val repository = FakeTagCatalogDataSource(
            catalog = TagCatalog(
                tags = listOf(
                    TagDefinition(
                        id = "home",
                        labels = listOf(LocalizedLabel.create("en", "Home"))
                    )
                )
            )
        )
        val viewModel = TagCatalogViewModel(repository, this)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.tags.size)
        assertEquals("home", state.tags.first().id)
    }

    @Test
    fun save_withoutEnglishFallback_setsValidationError() = runTest {
        val repository = FakeTagCatalogDataSource(
            catalog = TagCatalog(
                tags = listOf(
                    TagDefinition(
                        id = "voyage",
                        labels = listOf(LocalizedLabel.create("fr", "Voyage"))
                    )
                )
            )
        )
        val viewModel = TagCatalogViewModel(repository, this)

        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.tags.first().validation.englishFallbackMissing)
        assertNull(repository.savedCatalog)
    }

    @Test
    fun save_withEnglishFallback_persistsCatalog() = runTest {
        val repository = FakeTagCatalogDataSource()
        val viewModel = TagCatalogViewModel(repository, this)

        advanceUntilIdle()
        viewModel.addTag()
        val tag = viewModel.uiState.value.tags.first()
        viewModel.updateTagId(tag.key, "projects")
        val labelKey = viewModel.uiState.value.tags.first().labels.first().key
        viewModel.updateLabelValue(tag.key, labelKey, "Projects")

        viewModel.save()
        advanceUntilIdle()

        val saved = repository.savedCatalog
        assertNotNull(saved)
        saved!!
        assertEquals(1, saved.tags.size)
        val savedTag = saved.tags.first()
        assertEquals("projects", savedTag.id)
        assertEquals("Projects", savedTag.labels.first().value)
        assertTrue(viewModel.uiState.value.saveSuccess)
    }

    private class FakeTagCatalogDataSource(
        var catalog: TagCatalog = TagCatalog(emptyList()),
        private val shouldFailSave: Boolean = false,
    ) : TagCatalogDataSource {
        var savedCatalog: TagCatalog? = null

        override suspend fun loadCatalog(): TagCatalog = catalog

        override suspend fun saveCatalog(catalog: TagCatalog): TagCatalogSyncOutcome {
            return if (shouldFailSave) {
                TagCatalogSyncOutcome(catalog = catalog, error = RuntimeException("save failed"))
            } else {
                savedCatalog = catalog
                this.catalog = catalog
                TagCatalogSyncOutcome(catalog = catalog)
            }
        }
    }
}
