package com.isaver.filemanager.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.isaver.filemanager.domain.RootPath
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserSessionInstrumentedTest {
    @Test
    fun sessionSurvivesStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "browser-session-test.preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val expected = BrowserSession(
                rootPath = path("/storage/emulated/0"),
                rootTitle = "内部存储",
                currentPath = path("/storage/emulated/0/Documents"),
                backStack = listOf(path("/storage/emulated/0")),
                forwardStack = listOf(path("/storage/emulated/0/Download")),
            )
            BrowserSessionRepository(dataStore).save(expected)

            assertEquals(expected, BrowserSessionRepository(dataStore).session.first())
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    private fun path(value: String): RootPath = RootPath.parse(value).getOrThrow()
}
