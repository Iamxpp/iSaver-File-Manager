package com.iamxpp.isaver.data.root

internal object RootCommandCodec {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
