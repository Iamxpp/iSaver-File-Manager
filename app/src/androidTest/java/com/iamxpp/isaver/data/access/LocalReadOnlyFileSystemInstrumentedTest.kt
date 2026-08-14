package com.iamxpp.isaver.data.access

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalReadOnlyFileSystemInstrumentedTest {
    @Test
    fun appReadableDirectoryCanBeReadButNotMutated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = context.cacheDir.resolve("local-read-only-instrumented").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val fixture = directory.resolve("fixture.txt").apply { writeText("local read only") }
        val fileSystem = LocalReadOnlyFileSystem()
        try {
            val listing = fileSystem.readDirectory(path(directory.absolutePath)) as OperationResult.Success
            val entry = listing.value.entries.single { it.name == fixture.name }
            val output = ByteArrayOutputStream()

            assertTrue(entry.readable)
            assertFalse(entry.writable)
            assertEquals(15L, (fileSystem.copyToOutput(entry.path, output) as OperationResult.Success).value)
            assertArrayEquals("local read only".toByteArray(), output.toByteArray())

            val create = fileSystem.createFileNoReplace(
                path(directory.absolutePath),
                EntryName.parse("blocked.txt").getOrThrow(),
            ) as OperationResult.Failure
            assertEquals(ErrorCode.NOT_WRITABLE, create.code)
            assertFalse(directory.resolve("blocked.txt").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun path(raw: String): RootPath = RootPath.parse(raw).getOrThrow()
}
