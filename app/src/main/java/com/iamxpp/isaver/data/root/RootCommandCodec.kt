package com.iamxpp.isaver.data.root

internal object RootCommandCodec {
    /**
     * Quotes [value] as one argument for a single POSIX shell parsing layer.
     *
     * Quoting does not prevent option injection. Callers must still use `--` or place arguments
     * only in fixed positions where the invoked command cannot interpret them as options.
     */
    fun quote(value: String): String {
        require(!value.contains('\u0000')) { "Shell arguments cannot contain NUL characters" }
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
