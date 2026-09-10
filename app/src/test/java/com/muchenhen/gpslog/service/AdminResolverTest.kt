package com.muchenhen.gpslog.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AdminResolverTest {
    @Test fun nearbyLocationsShareCacheCellAndDistantOnesDoNot() {
        val first = AdminResolver.gridKey(31.23040, 121.47370)
        assertEquals(first, AdminResolver.gridKey(31.23041, 121.47371))
        assertNotEquals(first, AdminResolver.gridKey(31.33040, 121.57370))
    }
}
