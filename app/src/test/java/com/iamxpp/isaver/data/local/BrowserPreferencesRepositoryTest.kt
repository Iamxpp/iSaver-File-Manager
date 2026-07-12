package com.iamxpp.isaver.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec

class BrowserPreferencesRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `defaults to list display name ascending`() = runTest {
        val repository = BrowserPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { temporaryFolder.newFile("browser.preferences_pb") },
            ),
        )

        assertEquals(
            BrowserPreferences(
                displayMode = DisplayMode.LIST,
                sortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
            ),
            repository.preferences.first(),
        )
    }

    @Test
    fun `set display mode persists without changing sort`() = runTest {
        val repository = BrowserPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { temporaryFolder.newFile("display-mode.preferences_pb") },
            ),
        )

        repository.setDisplayMode(DisplayMode.GRID)

        assertEquals(
            BrowserPreferences(
                displayMode = DisplayMode.GRID,
                sortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
            ),
            repository.preferences.first(),
        )
    }

    @Test
    fun `set sort persists field and direction atomically`() = runTest {
        val repository = BrowserPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { temporaryFolder.newFile("sort.preferences_pb") },
            ),
        )
        val firstChangedValue = async(start = CoroutineStart.UNDISPATCHED) {
            repository.preferences.drop(1).first()
        }

        repository.setSort(SortSpec(SortField.MODIFIED_AT, SortDirection.DESCENDING))

        assertEquals(
            BrowserPreferences(
                displayMode = DisplayMode.LIST,
                sortSpec = SortSpec(SortField.MODIFIED_AT, SortDirection.DESCENDING),
            ),
            firstChangedValue.await(),
        )
    }

    @Test
    fun `preferences survive repository recreation`() = runTest {
        val dataStore = dataStore("recreation.preferences_pb", backgroundScope)
        val first = BrowserPreferencesRepository(dataStore)
        first.setDisplayMode(DisplayMode.GRID)
        first.setSort(SortSpec(SortField.SIZE, SortDirection.DESCENDING))

        val recreated = BrowserPreferencesRepository(dataStore)

        assertEquals(
            BrowserPreferences(
                displayMode = DisplayMode.GRID,
                sortSpec = SortSpec(SortField.SIZE, SortDirection.DESCENDING),
            ),
            recreated.preferences.first(),
        )
    }

    @Test
    fun `unknown stored enum resets the complete preference to defaults`() = runTest {
        val dataStore = dataStore("unknown.preferences_pb", backgroundScope)
        dataStore.edit { values ->
            values[stringPreferencesKey("display_mode")] = DisplayMode.GRID.name
            values[stringPreferencesKey("sort_field")] = "REMOVED_SORT_FIELD"
            values[stringPreferencesKey("sort_direction")] = SortDirection.DESCENDING.name
        }

        val repository = BrowserPreferencesRepository(dataStore)

        assertEquals(BrowserPreferences(), repository.preferences.first())
    }

    @Test
    fun `io exception while reading recovers with defaults`() = runTest {
        val repository = BrowserPreferencesRepository(ThrowingDataStore(IOException("disk unavailable")))

        assertEquals(BrowserPreferences(), repository.preferences.first())
    }

    @Test
    fun `non io exception while reading propagates`() = runTest {
        val expected = IllegalStateException("programming failure")
        val repository = BrowserPreferencesRepository(ThrowingDataStore(expected))

        try {
            repository.preferences.first()
            fail("Expected IllegalStateException")
        } catch (actual: IllegalStateException) {
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `concurrent display and sort updates do not lose either field`() = runTest {
        val repository = BrowserPreferencesRepository(dataStore("concurrent.preferences_pb", backgroundScope))

        coroutineScope {
            launch { repository.setDisplayMode(DisplayMode.GRID) }
            launch { repository.setSort(SortSpec(SortField.TYPE, SortDirection.DESCENDING)) }
        }

        assertEquals(
            BrowserPreferences(
                displayMode = DisplayMode.GRID,
                sortSpec = SortSpec(SortField.TYPE, SortDirection.DESCENDING),
            ),
            repository.preferences.first(),
        )
    }

    private fun dataStore(fileName: String, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { temporaryFolder.root.resolve(fileName) },
        )

    private class ThrowingDataStore(
        private val throwable: Throwable,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw throwable }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            emptyPreferences()
    }
}
