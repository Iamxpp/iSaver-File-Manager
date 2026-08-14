package com.isaver.filemanager.data.access

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAccessModeRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `new preference defaults to root and persists local read only`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.newFile("access-mode.preferences_pb")
        }
        val repository = FileAccessModeRepository(dataStore)

        assertEquals(FileAccessMode.ROOT, repository.mode.first())

        repository.setMode(FileAccessMode.LOCAL_READ_ONLY)

        assertEquals(FileAccessMode.LOCAL_READ_ONLY, FileAccessModeRepository(dataStore).mode.first())
    }
}
