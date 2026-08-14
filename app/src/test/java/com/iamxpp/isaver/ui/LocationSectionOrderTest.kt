package com.iamxpp.isaver.ui

import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationSectionOrderTest {
    @Test
    fun virtualViewsAppearAboveCommonLocations() {
        assertEquals(
            listOf(LocationSection.APP, LocationSection.CUSTOM, LocationSection.COMMON),
            locationSectionOrder(
                hasApps = true,
                virtualMode = true,
                sortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
            ),
        )
    }

    @Test
    fun legacyCustomLocationsKeepExistingOrder() {
        assertEquals(
            listOf(LocationSection.APP, LocationSection.COMMON, LocationSection.CUSTOM),
            locationSectionOrder(
                hasApps = true,
                virtualMode = false,
                sortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
            ),
        )
    }

    @Test
    fun virtualViewsStayAboveCommonLocationsWhenTypeSortIsDescending() {
        assertEquals(
            listOf(LocationSection.CUSTOM, LocationSection.COMMON),
            locationSectionOrder(
                hasApps = false,
                virtualMode = true,
                sortSpec = SortSpec(SortField.TYPE, SortDirection.DESCENDING),
            ),
        )
    }
}
