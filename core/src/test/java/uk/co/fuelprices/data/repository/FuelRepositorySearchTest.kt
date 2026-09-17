package uk.co.fuelprices.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.co.fuelprices.data.api.FuelPricesApi
import uk.co.fuelprices.data.api.StationListResponse
import uk.co.fuelprices.data.db.FuelDatabase
import uk.co.fuelprices.data.db.FuelPriceEntity
import uk.co.fuelprices.data.db.StationDao
import uk.co.fuelprices.data.db.StationEntity
import uk.co.fuelprices.data.db.StationWithPrices
import java.io.IOException

/**
 * Covers [FuelRepository.searchStations]: that a GPS fix is forwarded to the backend when there is
 * one and left off the request when there isn't, and that the offline (Room) fallback orders by
 * distance rather than by SQLite's arbitrary row order.
 */
class FuelRepositorySearchTest {

    private lateinit var api: FuelPricesApi
    private lateinit var dao: FakeStationDao
    private lateinit var repo: FuelRepository

    // FuelPricesApi.searchStations' own default, and the number of rows the user is shown both
    // online and offline. Mirrored here (the repository's copy is file-private) so a change to
    // either has to be made deliberately in both places.
    private val DISPLAY_LIMIT = 20

    // Oxford city centre — the origin every distance assertion below is relative to.
    private val originLat = 51.7520
    private val originLng = -1.2577

    @Before
    fun setUp() {
        api = mockk()
        dao = FakeStationDao()
        val db = mockk<FuelDatabase>()
        every { db.stationDao() } returns dao
        repo = FuelRepository(api, db, mockk(relaxed = true))
    }

    // ── Coordinate forwarding ────────────────────────────

    @Test
    fun `forwards the fix to the API when one is available`() = runTest {
        coEvery { api.searchStations(any(), any(), any(), any()) } returns emptyResponse()

        repo.searchStations("tesco", lat = originLat, lng = originLng)

        coVerify { api.searchStations("tesco", DISPLAY_LIMIT, originLat, originLng) }
    }

    @Test
    fun `passes null coordinates to the API when there is no fix`() = runTest {
        coEvery { api.searchStations(any(), any(), any(), any()) } returns emptyResponse()

        repo.searchStations("tesco")

        coVerify { api.searchStations("tesco", DISPLAY_LIMIT, null, null) }
    }

    /** A half-fix is meaningless as a query origin, so it's treated as no fix at all rather than
     *  sending one coordinate and letting the backend guess the other. */
    @Test
    fun `treats a half fix as no fix`() = runTest {
        coEvery { api.searchStations(any(), any(), any(), any()) } returns emptyResponse()

        repo.searchStations("tesco", lat = originLat, lng = null)

        coVerify { api.searchStations("tesco", DISPLAY_LIMIT, null, null) }
    }

    // ── Offline fallback ─────────────────────────────────

    @Test
    fun `offline fallback sorts by distance and fills in distanceMiles`() = runTest {
        coEvery { api.searchStations(any(), any(), any(), any()) } throws IOException("offline")
        dao.results = listOf(
            stationRow(id = 1, name = "Far Tesco", lat = 52.2, lng = -1.2),      // ~31 miles
            stationRow(id = 2, name = "Near Tesco", lat = 51.7530, lng = -1.2580), // ~0.07 miles
            stationRow(id = 3, name = "Middling Tesco", lat = 51.85, lng = -1.25),  // ~7 miles
        )

        val result = repo.searchStations("tesco", lat = originLat, lng = originLng)

        assertEquals(listOf(2, 3, 1), result.stations.map { it.id })
        assertEquals(3, result.count)
        val distances = result.stations.map { it.distanceMiles!! }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `offline fallback leaves cache order alone and omits distance when there is no fix`() =
        runTest {
            coEvery { api.searchStations(any(), any(), any(), any()) } throws IOException("offline")
            dao.results = listOf(
                stationRow(id = 1, name = "Far Tesco", lat = 52.2, lng = -1.2),
                stationRow(id = 2, name = "Near Tesco", lat = 51.7530, lng = -1.2580),
                stationRow(id = 3, name = "Middling Tesco", lat = 51.85, lng = -1.25),
            )

            val result = repo.searchStations("tesco")

            assertEquals(listOf(1, 2, 3), result.stations.map { it.id })
            result.stations.forEach { assertNull(it.distanceMiles) }
        }

    /**
     * The SQL can't sort by distance, so its LIMIT would otherwise decide which rows are eligible
     * to be sorted — the nearest station could sit just past the cut and never be seen. The
     * repository asks for a wider candidate pool and trims *after* sorting; this fakes a cache
     * where the nearest match is well past row 20 and asserts it still comes out first.
     */
    @Test
    fun `offline fallback sorts the whole candidate pool before trimming to the display limit`() =
        runTest {
            coEvery { api.searchStations(any(), any(), any(), any()) } throws IOException("offline")
            // 150 progressively-further stations, then the closest one of all at the very end.
            dao.results = (1..150).map { i ->
                stationRow(id = i, name = "Tesco $i", lat = originLat + i * 0.05, lng = originLng)
            } + stationRow(id = 999, name = "Tesco Next Door", lat = originLat, lng = originLng)

            val result = repo.searchStations("tesco", lat = originLat, lng = originLng)

            assertEquals(999, result.stations.first().id)
            assertEquals("trimmed to the display limit", DISPLAY_LIMIT, result.stations.size)
            assertEquals(DISPLAY_LIMIT, result.count)
            // The wider pool is what makes the above possible — assert it's actually requested.
            assertTrue("candidate limit must exceed the display limit", dao.lastLimit!! > DISPLAY_LIMIT)
        }

    /**
     * A search cancelled by the next keystroke isn't a network failure. If it fell through to the
     * catch-all it would run the (deliberately wide) cache scan for a query the user has already
     * typed past, and hand the ViewModel results for stale input.
     */
    @Test(expected = CancellationException::class)
    fun `a cancelled search propagates instead of falling back to the cache`() = runTest {
        coEvery {
            api.searchStations(any(), any(), any(), any())
        } throws CancellationException("superseded")
        dao.results = listOf(stationRow(id = 1, name = "Tesco", lat = originLat, lng = originLng))

        try {
            repo.searchStations("tesco", lat = originLat, lng = originLng)
        } finally {
            assertNull("the cache must not be queried at all", dao.lastLimit)
        }
    }

    // ── Helpers ──────────────────────────────────────────

    private fun emptyResponse() = StationListResponse(count = 0, stations = emptyList())

    private fun stationRow(id: Int, name: String, lat: Double, lng: Double) = StationWithPrices(
        station = StationEntity(
            id = id,
            govId = "gov-$id",
            name = name,
            brand = "Tesco",
            operator = null,
            addressLine1 = null,
            addressLine2 = null,
            town = "Oxford",
            county = null,
            postcode = "OX1 1AA",
            phone = null,
            latitude = lat,
            longitude = lng,
            amenitiesJson = null,
            openingHoursJson = null,
        ),
        prices = emptyList(),
    )

    /** Hand-written rather than mocked so the [StationDao.searchStations] limit the repository
     *  actually passes can be asserted on. */
    private class FakeStationDao : StationDao {
        var results: List<StationWithPrices> = emptyList()
        var lastLimit: Int? = null

        override suspend fun searchStations(query: String, limit: Int): List<StationWithPrices> {
            lastLimit = limit
            return results.take(limit)
        }

        override suspend fun getAllStations(limit: Int) = emptyList<StationWithPrices>()

        override suspend fun getFreshStationsNear(
            minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
            freshAfter: Long, limit: Int,
        ) = emptyList<StationWithPrices>()

        override suspend fun getStationById(id: Int): StationWithPrices? = null

        override suspend fun upsertStations(stations: List<StationEntity>) = Unit

        override suspend fun upsertPrices(prices: List<FuelPriceEntity>) = Unit

        override suspend fun deleteStale(before: Long) = Unit
    }
}
