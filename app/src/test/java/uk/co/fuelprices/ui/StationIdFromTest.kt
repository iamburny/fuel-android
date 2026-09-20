package uk.co.fuelprices.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The station id out of an App Link path segment.
 *
 * fuel-web serves a station at both `/stations/4312` and a slug form carrying the same leading id,
 * and an installed build has to resolve either — a link that parses to nothing drops the user on
 * the app's default screen having tapped through for a specific station.
 *
 * Tested directly rather than through `DeepLinkTarget.fromUri`, which needs `android.net.Uri` and
 * so a Robolectric or instrumented runner; this module has neither, and the parsing is the part
 * with behaviour worth pinning.
 */
class StationIdFromTest {
    @Test
    fun `bare id parses`() {
        assertEquals(4312, stationIdFrom("4312"))
    }

    @Test
    fun `id followed by a slug parses to the same station`() {
        assertEquals(4312, stationIdFrom("4312-shell-high-street-guildford"))
    }

    @Test
    fun `a slug that merely contains digits is not a station`() {
        assertNull(stationIdFrom("shell-4312"))
        assertNull(stationIdFrom("guildford"))
    }

    @Test
    fun `digits have to end the segment or meet a hyphen`() {
        // Guards the looser "leading run of digits" reading, which would accept this.
        assertNull(stationIdFrom("4312abc"))
    }

    @Test
    fun `an empty or hyphen-led segment is not a station`() {
        assertNull(stationIdFrom(""))
        assertNull(stationIdFrom("-4312"))
    }
}
