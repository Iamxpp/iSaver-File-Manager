package com.isaver.filemanager

import androidx.test.core.app.ApplicationProvider
import androidx.room.RoomDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ISaverApplication::class, sdk = [34])
class ISaverApplicationTest {
    private val application: ISaverApplication = ApplicationProvider.getApplicationContext()

    @Test
    fun applicationOwnsSingleLocationGraph() {
        assertSame(application.database, application.database)
        assertSame(application.customLocationRepository, application.customLocationRepository)
        assertSame(application.recentRepository, application.recentRepository)
        assertSame(application.locationResolver, application.locationResolver)
        assertSame(application.locationHomeAppResolver, application.locationHomeAppResolver)
        assertSame(application.locationHomeCustomStore, application.locationHomeCustomStore)
        assertSame(application.browserPreferencesStore, application.browserPreferencesStore)
        assertSame(application.fileAccessModeStore, application.fileAccessModeStore)
        assertSame(application.fileAccessController, application.fileAccessController)
        assertSame(application.shareIntentParser, application.shareIntentParser)
        assertSame(application.incomingFileCache, application.incomingFileCache)
        assertSame(application.incomingStreamRegistry, application.incomingStreamRegistry)
        assertSame(application.transferRepository, application.transferRepository)
        assertSame(application.archiveRepository, application.archiveRepository)
        assertSame(application.transferDependencies, application.transferDependencies)
    }

    @Test
    fun productionRoomDoesNotEnableMainThreadQueries() {
        val allowMainThreadQueries = RoomDatabase::class.java
            .getDeclaredField("allowMainThreadQueries")
            .apply { isAccessible = true }
            .getBoolean(application.database)

        assertFalse(allowMainThreadQueries)
    }

    @After
    fun closeDatabase() {
        application.database.close()
    }
}
