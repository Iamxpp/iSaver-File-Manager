package com.iamxpp.isaver.export

import android.os.SystemClock
import java.io.File
import java.security.SecureRandom

data class CachedExportFile(
    val file: File,
    val sizeBytes: Long,
    val device: Long,
    val inode: Long,
    val displayName: String,
    val mimeType: String,
)

data class ExternalFileGrant(
    val contentUri: String,
    val token: String,
    val displayName: String,
    val mimeType: String,
)

class ExternalFileRegistry internal constructor(
    private val authority: String,
    private val validate: (CachedExportFile) -> Boolean,
    private val onDiscard: (CachedExportFile) -> Unit,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val randomBytes: () -> ByteArray = {
        ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
    },
    private val scheduleExpiry: (String, Long, () -> Unit) -> Unit = { _, _, _ -> },
) {
    private data class Entry(
        val cached: CachedExportFile,
        val expiresAtMillis: Long,
    )

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun issue(
        cached: CachedExportFile,
        ttlMillis: Long = OPEN_TTL_MILLIS,
    ): Result<ExternalFileGrant> = runCatching {
        require(validate(cached))
        require(ttlMillis in 1L..MAX_TTL_MILLIS)
        val bytes = randomBytes()
        require(bytes.size == TOKEN_BYTES)
        val token = bytes.toLowerHex()
        val expiresAtMillis = Math.addExact(nowMillis(), ttlMillis)
        synchronized(lock) {
            check(entries.putIfAbsent(token, Entry(cached, expiresAtMillis)) == null)
        }
        try {
            scheduleExpiry(token, ttlMillis) { expire(token) }
        } catch (error: Exception) {
            synchronized(lock) { entries.remove(token) }?.cached?.let(onDiscard)
            throw error
        }
        ExternalFileGrant(
            contentUri = "content://$authority/$FILE_SEGMENT/$token",
            token = token,
            displayName = cached.displayName,
            mimeType = cached.mimeType,
        )
    }

    fun peek(token: String, nowMillis: Long = this.nowMillis()): CachedExportFile? =
        resolve(token, nowMillis, consume = false)

    fun consume(token: String, nowMillis: Long = this.nowMillis()): CachedExportFile? =
        resolve(token, nowMillis, consume = true)

    fun revoke(token: String) {
        if (!TOKEN.matches(token)) return
        synchronized(lock) { entries.remove(token) }?.cached?.let(onDiscard)
    }

    fun expire(token: String, nowMillis: Long = this.nowMillis()) {
        if (!TOKEN.matches(token)) return
        val expired = synchronized(lock) {
            val entry = entries[token] ?: return
            if (nowMillis < entry.expiresAtMillis) return
            entries.remove(token)
        }
        expired?.cached?.let(onDiscard)
    }

    private fun resolve(token: String, nowMillis: Long, consume: Boolean): CachedExportFile? {
        if (!TOKEN.matches(token)) return null
        val entry = synchronized(lock) {
            if (consume) entries.remove(token) else entries[token]
        } ?: return null
        if (nowMillis < entry.expiresAtMillis && validate(entry.cached)) return entry.cached
        if (!consume) {
            synchronized(lock) { entries.remove(token, entry) }
        }
        onDiscard(entry.cached)
        return null
    }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    companion object {
        const val OPEN_TTL_MILLIS = 60_000L
        const val SHARE_TTL_MILLIS = 30L * 60L * 1_000L
        const val MAX_TTL_MILLIS = 2L * 60L * 60L * 1_000L
        const val TTL_MILLIS = OPEN_TTL_MILLIS
        const val FILE_SEGMENT = "file"
        private const val TOKEN_BYTES = 32
        private const val HEX = "0123456789abcdef"
        private val TOKEN = Regex("[0-9a-f]{64}")
    }
}
