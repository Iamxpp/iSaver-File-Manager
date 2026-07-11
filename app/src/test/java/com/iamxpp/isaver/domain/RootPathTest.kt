package com.iamxpp.isaver.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPathTest {
    @Test
    fun `rejects relative paths`() {
        assertTrue(RootPath.parse("data/user/0").isFailure)
    }

    @Test
    fun `rejects paths with whitespace before root slash`() {
        assertTrue(RootPath.parse(" /data").isFailure)
    }

    @Test
    fun `preserves legal trailing spaces`() {
        assertEquals("/data ", RootPath.parse("/data ").getOrThrow().value)
    }

    @Test
    fun `preserves trailing slash`() {
        assertEquals("/data/", RootPath.parse("/data/").getOrThrow().value)
    }

    @Test
    fun `preserves repeated slashes`() {
        assertEquals("/data//user///0", RootPath.parse("/data//user///0").getOrThrow().value)
    }

    @Test
    fun `keeps root slash`() {
        assertEquals("/", RootPath.parse("/").getOrThrow().value)
    }

    @Test
    fun `rejects nul characters`() {
        assertTrue(RootPath.parse("/data/\u0000bad").isFailure)
    }

    @Test
    fun `does not resolve parent path segments`() {
        assertEquals("/data/../system", RootPath.parse("/data/../system").getOrThrow().value)
    }

    @Test
    fun `does not resolve current path segments`() {
        assertEquals("/data/./files", RootPath.parse("/data/./files").getOrThrow().value)
    }
}
