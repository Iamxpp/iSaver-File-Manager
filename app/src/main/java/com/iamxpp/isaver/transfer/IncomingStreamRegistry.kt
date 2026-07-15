package com.iamxpp.isaver.transfer

import android.os.SystemClock
import com.iamxpp.isaver.data.root.RootTransferSource
import java.security.SecureRandom

class IncomingStreamRegistry internal constructor(
    private val authority: String,
    private val validate: (CachedIncomingFile) -> Boolean,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val randomBytes: () -> ByteArray = {
        ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
    },
) {
    private data class Entry(
        val cached: CachedIncomingFile,
        val expiresAtMillis: Long,
    )

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun issue(cached: CachedIncomingFile): Result<RootTransferSource> = runCatching {
        require(validate(cached))
        val bytes = randomBytes()
        require(bytes.size == TOKEN_BYTES)
        val token = bytes.toLowerHex()
        val expiresAt = Math.addExact(nowMillis(), TTL_MILLIS)
        synchronized(lock) {
            check(entries.putIfAbsent(token, Entry(cached, expiresAt)) == null)
        }
        RootTransferSource(
            contentUri = "content://$authority/incoming/$token",
            expectedSizeBytes = cached.sizeBytes,
            token = token,
        )
    }

    fun consume(
        token: String,
        nowMillis: Long = this.nowMillis(),
    ): CachedIncomingFile? {
        if (!TOKEN.matches(token)) return null
        val entry = synchronized(lock) { entries.remove(token) } ?: return null
        return entry.cached.takeIf {
            nowMillis < entry.expiresAtMillis && validate(it)
        }
    }

    fun revoke(source: RootTransferSource) {
        synchronized(lock) {
            entries.remove(source.token)
        }
    }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    companion object {
        const val TTL_MILLIS = 60_000L
        private const val TOKEN_BYTES = 32
        private const val HEX = "0123456789abcdef"
        private val TOKEN = Regex("[0-9a-f]{64}")
    }
}
