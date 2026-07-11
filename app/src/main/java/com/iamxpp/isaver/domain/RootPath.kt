package com.iamxpp.isaver.domain

/**
 * An Android POSIX path that is syntactically absolute and contains no NUL character.
 *
 * The original spelling is preserved, including spaces, repeated or trailing slashes, and `.`
 * or `..` segments. It is neither a canonical path nor a security boundary. Canonical-path,
 * file-type, permission, and symbolic-link checks belong to `RootFileSystem` and must be performed
 * against the live file system.
 */
@JvmInline
value class RootPath private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<RootPath> {
            if (!raw.startsWith('/')) {
                return Result.failure(IllegalArgumentException("必须使用绝对路径"))
            }
            if (raw.contains('\u0000')) {
                return Result.failure(IllegalArgumentException("路径包含非法字符"))
            }
            return Result.success(RootPath(raw))
        }
    }
}
