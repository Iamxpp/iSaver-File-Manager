package com.iamxpp.isaver.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserSessionRepositoryTest {
    @Test
    fun `scoped stores keep pane sessions independent`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.newFile("scoped-session.preferences_pb")
        }
        val primary = BrowserSessionRepository(dataStore)
        val secondary = BrowserSessionRepository(dataStore, "secondary")
        val first = BrowserSession(path("/first"), "第一窗", path("/first"), emptyList(), emptyList())
        val second = BrowserSession(path("/second"), "第二窗", path("/second"), emptyList(), emptyList())

        primary.save(first)
        secondary.save(second)

        assertEquals(first, primary.session.first())
        assertEquals(second, secondary.session.first())
        secondary.clear()
        assertEquals(first, primary.session.first())
        assertNull(secondary.session.first())
    }
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `session survives repository recreation with ordered history`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temporaryFolder.newFile("session.preferences_pb") },
        )
        val expected = BrowserSession(
            rootPath = path("/storage/emulated/0"),
            rootTitle = "内部存储",
            currentPath = path("/storage/emulated/0/Documents/工作"),
            backStack = listOf(path("/storage/emulated/0"), path("/storage/emulated/0/Documents")),
            forwardStack = listOf(path("/storage/emulated/0/Download")),
        )
        BrowserSessionRepository(dataStore).save(expected)

        assertEquals(expected, BrowserSessionRepository(dataStore).session.first())
    }

    @Test fun `malformed session is ignored`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temporaryFolder.newFile("malformed.preferences_pb") },
        )
        dataStore.edit { it[stringPreferencesKey("session_root_path")] = "not-base64!" }

        assertNull(BrowserSessionRepository(dataStore).session.first())
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
}
