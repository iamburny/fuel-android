package uk.co.fuelprices.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Asserts the *wire shape* of `GET /api/stations/search` — in particular that a missing GPS fix
 * leaves `lat`/`lng` off the URL entirely rather than sending `0`, which the backend would read as
 * a real position off the coast of Africa and rank every result against. Retrofit's documented
 * behaviour is to skip null `@Query` values, but "the coordinates are absent, not zero" is the
 * whole point of the nullable parameters, so it's verified here against a real HTTP request rather
 * than assumed.
 */
class SearchStationsRequestTest {

    private lateinit var server: MockWebServer
    private lateinit var api: FuelPricesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FuelPricesApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueEmptyResult() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"count":0,"stations":[]}""")
        )
    }

    @Test
    fun `coordinates appear in the URL when supplied`() = runTest {
        enqueueEmptyResult()

        api.searchStations("tesco", lat = 51.75, lng = -1.26)

        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("q=tesco"))
        assertTrue(path, path.contains("lat=51.75"))
        assertTrue(path, path.contains("lng=-1.26"))
    }

    @Test
    fun `coordinates are absent from the URL entirely when null - never sent as zero`() = runTest {
        enqueueEmptyResult()

        api.searchStations("tesco", lat = null, lng = null)

        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("q=tesco"))
        // Matching on "lat=" rather than "lat" so a query term that happens to contain those
        // letters (a search for "Slate", say) can't fail the test spuriously.
        assertFalse("lat must not appear at all: $path", path.contains("lat="))
        assertFalse("lng must not appear at all: $path", path.contains("lng="))
    }

    @Test
    fun `coordinates default to absent when the caller omits them`() = runTest {
        enqueueEmptyResult()

        api.searchStations("tesco")

        val path = server.takeRequest().path!!
        assertEquals("/api/stations/search/?q=tesco&limit=20", path)
    }
}
