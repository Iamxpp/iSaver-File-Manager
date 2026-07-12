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

class BrowserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<BrowserPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map(::parsePreferences)

    suspend fun setDisplayMode(displayMode: DisplayMode) {
        dataStore.edit { values ->
            values[DISPLAY_MODE_KEY] = displayMode.name
        }
    }

    suspend fun setSort(sortSpec: SortSpec) {
        dataStore.edit { values ->
            values[SORT_FIELD_KEY] = sortSpec.field.name
            values[SORT_DIRECTION_KEY] = sortSpec.direction.name
        }
    }

    private companion object {
        val DISPLAY_MODE_KEY = stringPreferencesKey("display_mode")
        val SORT_FIELD_KEY = stringPreferencesKey("sort_field")
        val SORT_DIRECTION_KEY = stringPreferencesKey("sort_direction")

        fun parsePreferences(values: Preferences): BrowserPreferences {
            val displayMode = values[DISPLAY_MODE_KEY]
                ?.let { stored -> DisplayMode.entries.firstOrNull { it.name == stored } }
            val sortField = values[SORT_FIELD_KEY]
                ?.let { stored -> SortField.entries.firstOrNull { it.name == stored } }
            val sortDirection = values[SORT_DIRECTION_KEY]
                ?.let { stored -> SortDirection.entries.firstOrNull { it.name == stored } }

            val containsUnknownValue =
                values[DISPLAY_MODE_KEY] != null && displayMode == null ||
                    values[SORT_FIELD_KEY] != null && sortField == null ||
                    values[SORT_DIRECTION_KEY] != null && sortDirection == null
            if (containsUnknownValue) return BrowserPreferences()

            return BrowserPreferences(
                displayMode = displayMode ?: DisplayMode.LIST,
                sortSpec = SortSpec(
                    field = sortField ?: SortField.DISPLAY_NAME,
                    direction = sortDirection ?: SortDirection.ASCENDING,
                ),
            )
        }
    }
}
