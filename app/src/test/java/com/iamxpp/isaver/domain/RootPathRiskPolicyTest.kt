package com.iamxpp.isaver.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPathRiskPolicyTest {
    @Test
    fun `system roots and descendants are protected`() {
        listOf("/system", "/system/bin", "/vendor", "/product/app", "/boot").forEach { value ->
            assertTrue(value, RootPathRiskPolicy.isProtected(path(value)))
        }
    }

    @Test
    fun `similar prefixes and user storage are not protected`() {
        listOf("/system2", "/vendor_backup", "/products", "/bootable", "/storage/emulated/0").forEach { value ->
            assertFalse(value, RootPathRiskPolicy.isProtected(path(value)))
        }
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
}
