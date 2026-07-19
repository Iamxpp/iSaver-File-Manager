package com.iamxpp.isaver

import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseFeaturesTest {
    @Test
    fun remoteServersAreDisabledForInitialRelease() {
        assertFalse(ReleaseFeatures.remoteServers)
    }
}
