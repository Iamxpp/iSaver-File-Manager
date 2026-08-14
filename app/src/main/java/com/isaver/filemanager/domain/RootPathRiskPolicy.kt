package com.isaver.filemanager.domain

object RootPathRiskPolicy {
    private val protectedRoots = setOf(
        "/system",
        "/vendor",
        "/product",
        "/boot",
        "/data/adb",
        "/apex",
        "/proc",
        "/sys",
        "/dev",
    )

    fun isProtected(path: RootPath): Boolean = protectedRoots.any { root ->
        path.value == root || path.value.startsWith("$root/")
    }
}
