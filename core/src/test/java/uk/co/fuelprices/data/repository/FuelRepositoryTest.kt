package uk.co.fuelprices.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import uk.co.fuelprices.data.api.FuelPricesApi
import uk.co.fuelprices.data.api.RatingInputRequest
import uk.co.fuelprices.data.api.StationListResponse
import uk.co.fuelprices.data.db.FuelDatabase
import uk.co.fuelprices.data.db.StationDao
import uk.co.fuelprices.testutil.testStationDto
import uk.co.fuelprices.testutil.testStationWithPrices

class FuelRepositoryTest {

    private lateinit var api: FuelPricesApi
    private lateinit var dao: StationDao
    private lateinit var db: FuelDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var authenticator: TokenAuthenticator
    private lateinit var repo: FuelRepository

    @Before
    fun setUp() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        db = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        authenticator = mockk(relaxed = true)
        every { db.stationDao() } returns dao
        repo = FuelRepository(api, db, tokenStore, authenticator)
    }

    @Test
    fun `getNearbyStations returns cached stations without calling the API when the cache is fresh`() = runTest {
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns
            listOf(testStationWithPrices(id = 1, name = "Cached Station"))

        val response = repo.getNearbyStations(lat = 51.5, lng = -0.1)

        assertEquals(1, response.stations.size)
        assertEquals("Cached Station", response.stations.first().name)
        coVerify(exactly = 0) { api.getNearbyStations(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getNearbyStations calls the API and caches the result when there is no fresh cache`() = runTest {
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { api.getNearbyStations(any(), any(), any(), any(), any()) } returns
            StationListResponse(count = 1, stations = listOf(testStationDto(id = 2, name = "Fresh Station")))

        val response = repo.getNearbyStations(lat = 51.5, lng = -0.1)

        assertEquals("Fresh Station", response.stations.first().name)
        coVerify { dao.upsertStations(any()) }
    }

    @Test
    fun `getNearbyStations falls back to any cached stations when the API fails`() = runTest {
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { api.getNearbyStations(any(), any(), any(), any(), any()) } throws RuntimeException("network down")
        coEvery { dao.getAllStations(any()) } returns listOf(testStationWithPrices(id = 3, name = "Stale Station"))

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
        coEvery { dao.getStationById(4) } returns testStationWithPrices(id = 4, name = "Cached Detail")

        val station = repo.getStation(4)

        assertEquals("Cached Detail", station.name)
    }

    @Test(expected = RuntimeException::class)
    fun `getStation rethrows when the API fails and there is no cached fallback`() = runTest {
        coEvery { api.getStation(5) } throws RuntimeException("network down")
        coEvery { dao.getStationById(5) } returns null

        repo.getStation(5)
    }

    private fun httpError(code: Int, body: String) = retrofit2.HttpException(
        retrofit2.Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `a cooldown conflict surfaces as a RatingException carrying the stored rating`() = runTest {
        coEvery { api.createRating(7, any()) } throws httpError(
            409,
            """{"detail": "You can rate this station once every 7 days.", "reason": "cooldown",
               "can_rate_at": "2026-10-08T10:00:00.000Z", "rating": {"id": 9, "comment_status": "approved"}}""",
        )

        val error = try {
            repo.createRating(7, RatingInputRequest("E10", true, null, 5, null))
            null
        } catch (e: RatingException) {
            e
        }

        assertEquals(409, error!!.status)
        assertEquals("cooldown", error.reason)
        assertEquals(9, error.rating!!.id)
        assertEquals("2026-10-08T10:00:00.000Z", error.canRateAt)
    }

    @Test
    fun `an error with a body that isn't JSON still surfaces its status`() = runTest {
        coEvery { api.getMyRating(7) } throws httpError(502, "<html>Bad gateway</html>")

        val error = try {
            repo.getMyRating(7)
            null
        } catch (e: RatingException) {
            e
        }

        assertEquals(502, error!!.status)
        assertEquals(null, error.detail)
    }

    @Test
    fun `deleteAccount signs out locally only after the server deletes the account`() = runTest {
        repo.deleteAccount()
        coVerify { api.deleteAccount() }
        coVerify { authenticator.signOut(revoke = false) }
    }

    @Test
    fun `deleteAccount keeps the session when the server refuses`() = runTest {
        coEvery { api.deleteAccount() } throws httpError(500, """{"detail": "Internal error"}""")

        try {
            repo.deleteAccount()
        } catch (_: RatingException) {
        }

        coVerify(exactly = 0) { authenticator.signOut(any()) }
    }

    @Test
    fun `logout signs out through the authenticator so the refresh token is revoked`() = runTest {
        repo.logout()
        coVerify { authenticator.signOut(revoke = true) }
    }

    @Test
    fun `the accuracy warning is cached with the station and served back from the cache`() = runTest {
        val upserted = io.mockk.slot<List<uk.co.fuelprices.data.db.StationEntity>>()
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { api.getNearbyStations(any(), any(), any(), any(), any()) } returns
            StationListResponse(count = 1, stations = listOf(testStationDto(id = 2).copy(priceAccuracyWarning = true)))
        coEvery { dao.upsertStations(capture(upserted)) } returns Unit

        repo.getNearbyStations(lat = 51.5, lng = -0.1)
        assertEquals(true, upserted.captured.single().priceAccuracyWarning)

        val cached = testStationWithPrices(id = 2).let { it.copy(station = it.station.copy(priceAccuracyWarning = true)) }
        coEvery { dao.getFreshStationsNear(any(), any(), any(), any(), any(), any()) } returns listOf(cached)
        assertEquals(true, repo.getNearbyStations(lat = 51.5, lng = -0.1).stations.single().priceAccuracyWarning)
    }
}
