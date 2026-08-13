package com.iamxpp.isaver.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class BrowserPreferences(
    val displayMode: DisplayMode = DisplayMode.LIST,
    val sortSpec: SortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
)

interface BrowserPreferencesStore {
    val preferences: Flow<BrowserPreferences>
    suspend fun setDisplayMode(displayMode: DisplayMode)
    suspend fun setSort(sortSpec: SortSpec)
}

class BrowserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    scope: String = "",
) : BrowserPreferencesStore {
    private val keys = Keys(scope)
    override val preferences: Flow<BrowserPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map(::parsePreferences)

    override suspend fun setDisplayMode(displayMode: DisplayMode) {
        dataStore.edit { values ->
            values[keys.displayMode] = displayMode.name
        }
    }

    override suspend fun setSort(sortSpec: SortSpec) {
        dataStore.edit { values ->
            values[keys.sortField] = sortSpec.field.name
            values[keys.sortDirection] = sortSpec.direction.name
        }
    }

    private fun parsePreferences(values: Preferences): BrowserPreferences {
            val displayMode = values[keys.displayMode]
                ?.let { stored -> DisplayMode.entries.firstOrNull { it.name == stored } }
            val sortField = values[keys.sortField]
                ?.let { stored -> SortField.entries.firstOrNull { it.name == stored } }
            val sortDirection = values[keys.sortDirection]
                ?.let { stored -> SortDirection.entries.firstOrNull { it.name == stored } }

            val containsUnknownValue =
                values[keys.displayMode] != null && displayMode == null ||
                    values[keys.sortField] != null && sortField == null ||
                    values[keys.sortDirection] != null && sortDirection == null
            if (containsUnknownValue) return BrowserPreferences()

            return BrowserPreferences(
                displayMode = displayMode ?: DisplayMode.LIST,
                sortSpec = SortSpec(
                    field = sortField ?: SortField.DISPLAY_NAME,
                    direction = sortDirection ?: SortDirection.ASCENDING,
                ),
            )
    }

    private class Keys(scope: String) {
        private val prefix = scope.trim().takeIf(String::isNotEmpty)?.let { "$it." }.orEmpty()
        val displayMode = stringPreferencesKey("${prefix}display_mode")
        val sortField = stringPreferencesKey("${prefix}sort_field")
        val sortDirection = stringPreferencesKey("${prefix}sort_direction")
    }
}
