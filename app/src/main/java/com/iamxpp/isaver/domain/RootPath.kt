package com.iamxpp.isaver.domain

@JvmInline
value class RootPath private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<RootPath> {
            val normalized = raw.trim().replace(Regex("/{2,}"), "/")
            if (!normalized.startsWith('/')) {
                return Result.failure(IllegalArgumentException("必须使用绝对路径"))
            }
            if (normalized.contains('\u0000')) {
                return Result.failure(IllegalArgumentException("路径包含非法字符"))
            }
            return Result.success(RootPath(normalized.removeSuffix("/").ifEmpty { "/" }))
        }
    }
}
