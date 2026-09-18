package uk.co.fuelprices.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.co.fuelprices.data.api.FuelPricesApi
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.api.StationListResponse
import uk.co.fuelprices.data.db.FuelDatabase
import uk.co.fuelprices.data.db.FuelPriceEntity
import uk.co.fuelprices.data.db.StationDao
import uk.co.fuelprices.data.db.StationEntity
import uk.co.fuelprices.data.db.StationWithPrices

class FuelRepositoryTest {

    private lateinit var api: FuelPricesApi
    private lateinit var dao: StationDao
    private lateinit var db: FuelDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var repo: FuelRepository

    @Before
    fun setUp() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        db = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        every { db.stationDao() } returns dao
        repo = FuelRepository(api, db, tokenStore)
    }

    @Test
    fun `getNearbyStations returns cached stations without calling the API when the cache is fresh`() = runTest {
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns
            listOf(stationWithPrices(id = 1, name = "Cached Station"))

        val response = repo.getNearbyStations(lat = 51.5, lng = -0.1)

        assertEquals(1, response.stations.size)
        assertEquals("Cached Station", response.stations.first().name)
        coVerify(exactly = 0) { api.getNearbyStations(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getNearbyStations calls the API and caches the result when there is no fresh cache`() = runTest {
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { api.getNearbyStations(any(), any(), any(), any(), any()) } returns
            StationListResponse(count = 1, stations = listOf(stationDto(id = 2, name = "Fresh Station")))

        val response = repo.getNearbyStations(lat = 51.5, lng = -0.1)

        assertEquals("Fresh Station", response.stations.first().name)
        coVerify { dao.upsertStations(any()) }
    }

    @Test
    fun `getNearbyStations falls back to any cached stations when the API fails`() = runTest {
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { api.getNearbyStations(any(), any(), any(), any(), any()) } throws RuntimeException("network down")
        coEvery { dao.getAllStations(any()) } returns listOf(stationWithPrices(id = 3, name = "Stale Station"))

        val response = repo.getNearbyStations(lat = 51.5, lng = -0.1)

        assertEquals("Stale Station", response.stations.first().name)
        assertEquals(1, repo.apiFailureCount.value)
    }

    @Test
    fun `apiFailureCount resets to zero after a subsequent success`() = runTest {
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { api.getNearbyStations(any(), any(), any(), any(), any()) } throws RuntimeException("network down")
        coEvery { dao.getAllStations(any()) } returns emptyList()

        repo.getNearbyStations(lat = 51.5, lng = -0.1)
        assertEquals(1, repo.apiFailureCount.value)

        coEvery { api.getNearbyStations(any(), any(), any(), any(), any()) } returns
            StationListResponse(count = 0, stations = emptyList())

        repo.getNearbyStations(lat = 51.5, lng = -0.1)
        assertEquals(0, repo.apiFailureCount.value)
    }

    @Test
    fun `getStation falls back to the cached entity when the API fails`() = runTest {
        coEvery { api.getStation(4) } throws RuntimeException("network down")
        coEvery { dao.getStationById(4) } returns stationWithPrices(id = 4, name = "Cached Detail")

        val station = repo.getStation(4)

        assertEquals("Cached Detail", station.name)
    }

    @Test(expected = RuntimeException::class)
    fun `getStation rethrows when the API fails and there is no cached fallback`() = runTest {
        coEvery { api.getStation(5) } throws RuntimeException("network down")
        coEvery { dao.getStationById(5) } returns null

        repo.getStation(5)
    }

    private fun stationDto(id: Int, name: String) = StationDto(
        id = id,
        govId = "gov-$id",
        name = name,
        latitude = 51.5,
        longitude = -0.1,
    )

    private fun stationWithPrices(id: Int, name: String) = StationWithPrices(
        station = StationEntity(
            id = id,
            govId = "gov-$id",
            name = name,
            brand = null,
            operator = null,
            addressLine1 = null,
            addressLine2 = null,
            town = null,
            county = null,
            postcode = null,
            phone = null,
            latitude = 51.5,
            longitude = -0.1,
            amenitiesJson = null,
            openingHoursJson = null,
        ),
        prices = listOf(FuelPriceEntity(stationId = id, fuelType = "E10", pricePence = 140.0, reportedAt = "2026-01-01T00:00:00Z")),
    )
}
