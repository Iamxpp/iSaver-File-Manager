package com.iamxpp.isaver.domain

object RootPathRiskPolicy {
    private val protectedRoots = setOf("/system", "/vendor", "/product", "/boot")

    fun isProtected(path: RootPath): Boolean = protectedRoots.any { root ->
        path.value == root || path.value.startsWith("$root/")
    }
}
