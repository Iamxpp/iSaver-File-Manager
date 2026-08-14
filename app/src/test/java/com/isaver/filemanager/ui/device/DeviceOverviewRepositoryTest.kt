package com.isaver.filemanager.ui.device

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceOverviewRepositoryTest {
    @Test
    fun `storage usage exposes bounded used values`() {
        val usage = DeviceStorageUsage(totalBytes = 1_000, availableBytes = 250)

        assertEquals(750L, usage.usedBytes)
        assertEquals(0.75f, usage.usedFraction)
    }

    @Test
    fun `repository rejects impossible platform statistics`() = runTest {
        val repository = DeviceOverviewRepository(
            readStats = { DeviceStorageUsage(totalBytes = 100, availableBytes = 101) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.load()

        assertTrue(result.isFailure)
    }
}
