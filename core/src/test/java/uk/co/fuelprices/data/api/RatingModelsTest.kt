package uk.co.fuelprices.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decoding against the same Json configuration the Retrofit converter uses (AppModule). */
class RatingModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val stationWithoutRatings = """
        {"id": 7, "gov_id": "abc", "name": "Shell", "latitude": 51.5, "longitude": -0.1,
         "prices": [{"fuel_type": "E10", "price_pence": 139.9, "reported_at": "2026-09-30T10:00:00Z"}]}
    """

    @Test
    fun `a station from a backend without ratings decodes with no summary and no warning`() {
        val station = json.decodeFromString<StationDto>(stationWithoutRatings)

        assertNull(station.ratingSummary)
        assertFalse(station.priceAccuracyWarning)
    }

    @Test
    fun `a station carries its rating summary and accuracy warning`() {
        val station = json.decodeFromString<StationDto>(
            """
            {"id": 7, "gov_id": "abc", "name": "Shell", "latitude": 51.5, "longitude": -0.1,
             "rating_summary": {"rater_count": 7, "avg_stars": 2.4, "price_match_pct": 43, "avg_gap_pence": 3.1},
             "price_accuracy_warning": true}
            """,
        )

        assertEquals(RatingSummaryDto(raterCount = 7, avgStars = 2.4, priceMatchPct = 43, avgGapPence = 3.1), station.ratingSummary)
        assertTrue(station.priceAccuracyWarning)
    }

    @Test
    fun `a null summary and gap decode as null`() {
        val station = json.decodeFromString<StationDto>(
            """{"id": 7, "gov_id": "abc", "name": "Shell", "latitude": 51.5, "longitude": -0.1, "rating_summary": null}""",
        )
        assertNull(station.ratingSummary)

        val summary = json.decodeFromString<RatingSummaryDto>(
            """{"rater_count": 3, "avg_stars": 4.0, "price_match_pct": 100, "avg_gap_pence": null}""",
        )
        assertNull(summary.avgGapPence)
    }

    @Test
    fun `my rating decodes every field, keeping unknown blocker codes`() {
        val mine = json.decodeFromString<MyRatingResponse>(
            """
            {"rating": {"id": 5, "station_id": 7, "stars": 2, "price_matched": false, "fuel_type": "E10",
                        "reported_price_pence": 149.9, "published_price_pence": 145.9, "gap_pence": 4,
                        "comment": "Pump was dearer", "comment_status": "held", "moderation_reason": null,
                        "edits_remaining": 3, "editable_until": "2026-10-02T10:00:00.000Z",
                        "created_at": "2026-10-01T10:00:00.000Z", "edited_at": null, "something_new": 1},
             "can_rate_at": "2026-10-08T10:00:00.000Z",
             "blockers": ["terms", "brand_new_blocker"],
             "daily_cap_resets_at": null,
             "terms_version": "1"}
            """,
        )

        val rating = mine.rating!!
        assertEquals(5, rating.id)
        assertEquals(4.0, rating.gapPence!!, 0.0)
        assertEquals("held", rating.commentStatus)
        assertEquals(3, rating.editsRemaining)
        assertEquals("2026-10-08T10:00:00.000Z", mine.canRateAt)
        assertEquals(listOf("terms", "brand_new_blocker"), mine.blockers)
        assertEquals("1", mine.termsVersion)
    }

    @Test
    fun `my rating with nothing set decodes to empty defaults`() {
        val mine = json.decodeFromString<MyRatingResponse>("""{"rating": null, "can_rate_at": null}""")

        assertNull(mine.rating)
        assertNull(mine.canRateAt)
        assertEquals(emptyList<String>(), mine.blockers)
    }

    @Test
    fun `public ratings page decodes`() {
        val page = json.decodeFromString<PublicRatingsResponse>(
            """
            {"items": [{"id": 1, "stars": 4, "price_matched": true, "fuel_type": "B7_STANDARD", "gap_pence": null,
                        "comment": "Fine", "created_at": "2026-09-01T08:00:00.000Z", "edited": true, "author_ref": "r_abc"}],
             "total": 21, "page": 1, "page_size": 20}
            """,
        )

        assertEquals(21, page.total)
        assertEquals("r_abc", page.items.single().authorRef)
        assertTrue(page.items.single().edited)
    }

    @Test
    fun `a cooldown error body carries the stored rating`() {
        val body = json.decodeFromString<RatingErrorBody>(
            """
            {"detail": "You can rate this station once every 7 days.", "reason": "cooldown",
             "can_rate_at": "2026-10-08T10:00:00.000Z", "rating": {"id": 9, "comment_status": "approved"}}
            """,
        )

        assertEquals("cooldown", body.reason)
        assertEquals(9, body.rating!!.id)
    }

    @Test
    fun `rating input always sends nullable fields, as explicit nulls`() {
        val encoded = json.encodeToJsonElement(
            RatingInputRequest.serializer(),
            RatingInputRequest(fuelType = "E10", priceMatched = true, reportedPricePence = null, stars = 5, comment = null),
        ).jsonObject

        assertEquals(JsonNull, encoded["reported_price_pence"])
        assertEquals(JsonNull, encoded["comment"])
        assertEquals("E10", encoded["fuel_type"].toString().trim('"'))
    }
}
