package com.iamxpp.isaver.share

import android.content.Intent
import android.net.Uri
import com.iamxpp.isaver.domain.RootPath
import java.util.Locale

data class ShareSourceLocation(
    val directory: RootPath,
    val title: String,
)

object ShareSourceLocationResolver {
    fun resolve(intent: Intent): ShareSourceLocation? {
        val stream = ShareIntentStream.extract(intent) as? ShareIntentStreamExtra.Valid ?: return null
        val uri = stream.uri
        if (uri.scheme != "content") return null
        val filePath = resolveWeChatFilePath(uri) ?: return null
        val parent = filePath.parentDirectory() ?: return null
        return ShareSourceLocation(parent, "文件所在位置")
    }

    internal fun resolveWeChatFilePath(uri: Uri): RootPath? {
        if (!uri.isWeChatFileProvider()) return null
        val segments = uri.pathSegments.map { segment ->
            segment.takeIf(::isSafePathSegment) ?: return null
        }
        if (segments.isEmpty()) return null

        val rootName = segments.first().lowercase(Locale.US)
        WECHAT_PROVIDER_ROOTS[rootName]?.let { base ->
            return RootPath.parse(joinPath(base, segments.drop(1))).getOrNull()
        }

        findEmbeddedExternalPath(segments)?.let { embedded ->
            return RootPath.parse(joinPath(embedded.base, segments.drop(embedded.suffixStart))).getOrNull()
        }
        if (segments.take(3) == listOf("storage", "emulated", "0")) {
            return RootPath.parse(joinPath("/", segments)).getOrNull()
        }
        return null
    }

    private fun Uri.isWeChatFileProvider(): Boolean {
        val value = authority?.lowercase(Locale.US) ?: return false
        return value == "com.tencent.mm.external.fileprovider" ||
            value == "com.tencent.mm.fileprovider" ||
            value == "com.tencent.mm.sdk.fileprovider" ||
            value.startsWith("com.tencent.mm.") && value.endsWith(".fileprovider")
    }

    private fun isSafePathSegment(segment: String): Boolean =
        segment.isNotEmpty() && segment != "." && segment != ".." && '\u0000' !in segment && '/' !in segment

    private fun findEmbeddedExternalPath(segments: List<String>): EmbeddedExternalPath? {
        val tencentMicroMsg = segments.windowed(size = 2).indexOfFirst { pair ->
            pair[0].equals("tencent", ignoreCase = true) && pair[1].equals("MicroMsg", ignoreCase = true)
        }
        if (tencentMicroMsg >= 0) {
            return EmbeddedExternalPath(
                base = "$EXTERNAL_STORAGE_ROOT/tencent/MicroMsg",
                suffixStart = tencentMicroMsg + 2,
            )
        }

        val androidData = segments.windowed(size = 3).indexOfFirst { triple ->
            triple[0].equals("Android", ignoreCase = true) &&
                triple[1].equals("data", ignoreCase = true) &&
                triple[2].equals(WECHAT_PACKAGE, ignoreCase = true)
        }
        return androidData.takeIf { it >= 0 }?.let { start ->
            EmbeddedExternalPath(
                base = "$EXTERNAL_STORAGE_ROOT/Android/data/$WECHAT_PACKAGE",
                suffixStart = start + 3,
            )
        }
    }

    private fun RootPath.parentDirectory(): RootPath? {
        val trimmed = value.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val index = trimmed.lastIndexOf('/')
        if (index < 0) return null
        val parent = if (index == 0) "/" else trimmed.substring(0, index)
        return RootPath.parse(parent).getOrNull()
    }

    private fun joinPath(base: String, segments: List<String>): String {
        if (segments.isEmpty()) return base
        return if (base == "/") {
            "/" + segments.joinToString("/")
        } else {
            base.trimEnd('/') + "/" + segments.joinToString("/")
        }
    }

    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val EXTERNAL_STORAGE_ROOT = "/storage/emulated/0"
    private data class EmbeddedExternalPath(
        val base: String,
        val suffixStart: Int,
    )

    private val WECHAT_PROVIDER_ROOTS = mapOf(
        "external" to EXTERNAL_STORAGE_ROOT,
        "external_path" to EXTERNAL_STORAGE_ROOT,
        "external-path" to EXTERNAL_STORAGE_ROOT,
        "external_storage" to EXTERNAL_STORAGE_ROOT,
        "external_storage_root" to EXTERNAL_STORAGE_ROOT,
        "external_files" to "$EXTERNAL_STORAGE_ROOT/Android/data/$WECHAT_PACKAGE/files",
        "external-files" to "$EXTERNAL_STORAGE_ROOT/Android/data/$WECHAT_PACKAGE/files",
        "external_cache" to "$EXTERNAL_STORAGE_ROOT/Android/data/$WECHAT_PACKAGE/cache",
        "external-cache" to "$EXTERNAL_STORAGE_ROOT/Android/data/$WECHAT_PACKAGE/cache",
        "files" to "/data/data/$WECHAT_PACKAGE/files",
        "cache" to "/data/data/$WECHAT_PACKAGE/cache",
        "root" to "/",
        "root_path" to "/",
    )
}
