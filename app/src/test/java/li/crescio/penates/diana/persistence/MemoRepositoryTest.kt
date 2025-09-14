package li.crescio.penates.diana.persistence

import kotlinx.coroutines.runBlocking
import li.crescio.penates.diana.notes.Memo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class MemoRepositoryTest {
    private lateinit var file: File
    private lateinit var repository: MemoRepository

    @Before
    fun setup() {
        file = File.createTempFile("memos", ".txt")
        repository = MemoRepository(file)
    }

    @After
    fun teardown() {
        file.delete()
    }

    @Test
    fun addAndLoadMemos() = runBlocking {
        repository.addMemo(Memo("one"))
        repository.addMemo(Memo("two", "audio"))
        val memos = repository.loadMemos()
        assertEquals(listOf(Memo("one"), Memo("two", "audio")), memos)
    }
}

