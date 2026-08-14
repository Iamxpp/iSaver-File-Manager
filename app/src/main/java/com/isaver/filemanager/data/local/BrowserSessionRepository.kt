package com.isaver.filemanager.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.isaver.filemanager.domain.RootPath
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BrowserSession(
    val rootPath: RootPath,
    val rootTitle: String,
    val currentPath: RootPath,
    val backStack: List<RootPath>,
    val forwardStack: List<RootPath>,
)

interface BrowserSessionStore {
    val session: Flow<BrowserSession?>
    suspend fun save(session: BrowserSession)
    suspend fun clear()
}

class BrowserSessionRepository(
    private val dataStore: DataStore<Preferences>,
    scope: String = "",
) : BrowserSessionStore {
    private val keys = Keys(scope)
    override val session: Flow<BrowserSession?> = dataStore.data.map(::parse)

    override suspend fun save(session: BrowserSession) {
        dataStore.edit { values ->
            values[keys.rootPath] = encode(session.rootPath.value)
            values[keys.rootTitle] = encode(session.rootTitle.take(MAX_TITLE_LENGTH))
            values[keys.currentPath] = encode(session.currentPath.value)
            values[keys.backStack] = encodePaths(session.backStack)
            values[keys.forwardStack] = encodePaths(session.forwardStack)
        }
    }

    override suspend fun clear() {
        dataStore.edit { values -> keys.all.forEach(values::remove) }
    }

    private fun parse(values: Preferences): BrowserSession? = runCatching {
        val root = decodePath(values[keys.rootPath] ?: return null)
        val title = decode(values[keys.rootTitle] ?: return null).take(MAX_TITLE_LENGTH)
        val current = decodePath(values[keys.currentPath] ?: return null)
        BrowserSession(
            rootPath = root,
            rootTitle = title,
            currentPath = current,
            backStack = decodePaths(values[keys.backStack].orEmpty()),
            forwardStack = decodePaths(values[keys.forwardStack].orEmpty()),
        )
    }.getOrNull()

    private fun encodePaths(paths: List<RootPath>): String =
        paths.takeLast(MAX_HISTORY).joinToString(",") { encode(it.value) }

    private fun decodePaths(value: String): List<RootPath> = if (value.isEmpty()) {
        emptyList()
    } else {
        value.split(',').also { require(it.size <= MAX_HISTORY) }.map(::decodePath)
    }

    private fun decodePath(value: String): RootPath = RootPath.parse(decode(value)).getOrThrow()
    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )

    private companion object {
        const val MAX_HISTORY = 100
        const val MAX_TITLE_LENGTH = 200
    }

    private class Keys(scope: String) {
        private val prefix = scope.trim().takeIf(String::isNotEmpty)?.let { "$it." }.orEmpty()
        val rootPath = stringPreferencesKey("${prefix}session_root_path")
        val rootTitle = stringPreferencesKey("${prefix}session_root_title")
        val currentPath = stringPreferencesKey("${prefix}session_current_path")
        val backStack = stringPreferencesKey("${prefix}session_back_stack")
        val forwardStack = stringPreferencesKey("${prefix}session_forward_stack")
        val all = listOf(rootPath, rootTitle, currentPath, backStack, forwardStack)
    }
}
