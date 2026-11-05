package uk.co.fuelprices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which station a price-drop notification tap opens. Tested directly rather than through
 * `MainActivity.targetFromIntent`, which needs a real `Intent` and so a Robolectric or
 * instrumented runner.
 */
class NotificationStationIdTest {
    @Test
    fun `a tray notification's String station_id opens that station`() {
        assertEquals(4312, notificationStationId(-1, "4312"))
    }

    @Test
    fun `the Int extra from a foreground notification opens that station`() {
        assertEquals(4312, notificationStationId(4312, null))
    }

    @Test
    fun `the Int extra wins when both are present`() {
        assertEquals(4312, notificationStationId(4312, "99"))
    }

    @Test
    fun `no usable id means the tap is not for a station`() {
        assertNull(notificationStationId(-1, null))
        assertNull(notificationStationId(-1, "abc"))
        assertNull(notificationStationId(-1, "0"))
        assertNull(notificationStationId(0, null))
    }
}
