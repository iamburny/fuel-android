package uk.co.fuelprices.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/** The ratings list asks for every rating behind the score, not just published comments. */
class StationRatingsRequestTest {

    private lateinit var server: MockWebServer
    private lateinit var api: FuelPricesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
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

    @Test
    fun `requests every rating and reads one without a published comment`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"items": [{"id": 1, "stars": 2, "price_matched": false, "fuel_type": "E10", "gap_pence": 5,
                       "comment": null, "created_at": "2026-10-03T10:00:00Z", "edited": false, "author_ref": "r"}],
                       "total": 1, "page": 1, "page_size": 20}""",
                ),
        )

        val res = api.getStationRatings(7)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/stations/7/ratings", url.encodedPath)
        assertEquals("1", url.queryParameter("page"))
        assertEquals("all", url.queryParameter("include"))
        assertNull(res.items.single().comment)
    }
}
