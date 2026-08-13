package com.iamxpp.isaver.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.iamxpp.isaver.domain.RootPath
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

class BrowserSessionRepository(private val dataStore: DataStore<Preferences>) : BrowserSessionStore {
    override val session: Flow<BrowserSession?> = dataStore.data.map(::parse)

    override suspend fun save(session: BrowserSession) {
        dataStore.edit { values ->
            values[ROOT_PATH] = encode(session.rootPath.value)
            values[ROOT_TITLE] = encode(session.rootTitle.take(MAX_TITLE_LENGTH))
            values[CURRENT_PATH] = encode(session.currentPath.value)
            values[BACK_STACK] = encodePaths(session.backStack)
            values[FORWARD_STACK] = encodePaths(session.forwardStack)
        }
    }

    override suspend fun clear() {
        dataStore.edit { values -> KEYS.forEach(values::remove) }
    }

    private fun parse(values: Preferences): BrowserSession? = runCatching {
        val root = decodePath(values[ROOT_PATH] ?: return null)
        val title = decode(values[ROOT_TITLE] ?: return null).take(MAX_TITLE_LENGTH)
        val current = decodePath(values[CURRENT_PATH] ?: return null)
        BrowserSession(
            rootPath = root,
            rootTitle = title,
            currentPath = current,
            backStack = decodePaths(values[BACK_STACK].orEmpty()),
            forwardStack = decodePaths(values[FORWARD_STACK].orEmpty()),
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
        val ROOT_PATH = stringPreferencesKey("session_root_path")
        val ROOT_TITLE = stringPreferencesKey("session_root_title")
        val CURRENT_PATH = stringPreferencesKey("session_current_path")
        val BACK_STACK = stringPreferencesKey("session_back_stack")
        val FORWARD_STACK = stringPreferencesKey("session_forward_stack")
        val KEYS = listOf(ROOT_PATH, ROOT_TITLE, CURRENT_PATH, BACK_STACK, FORWARD_STACK)
    }
}
