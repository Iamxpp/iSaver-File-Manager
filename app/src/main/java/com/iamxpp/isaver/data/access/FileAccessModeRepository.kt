package com.iamxpp.isaver.data.access

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface FileAccessModeStore {
    suspend fun load(): FileAccessMode
    suspend fun save(mode: FileAccessMode)
}

class FileAccessModeRepository(
    private val dataStore: DataStore<Preferences>,
) : FileAccessModeStore {
    val mode: Flow<FileAccessMode> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            preferences[MODE_KEY]
                ?.let { stored -> FileAccessMode.entries.firstOrNull { it.name == stored } }
                ?: FileAccessMode.ROOT
        }

    override suspend fun load(): FileAccessMode = mode.first()

    override suspend fun save(mode: FileAccessMode) {
        dataStore.edit { preferences -> preferences[MODE_KEY] = mode.name }
    }

    suspend fun setMode(mode: FileAccessMode) = save(mode)

    private companion object {
        val MODE_KEY = stringPreferencesKey("file_access_mode")
    }
}
