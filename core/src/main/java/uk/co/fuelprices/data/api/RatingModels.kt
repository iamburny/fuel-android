package uk.co.fuelprices.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Station ratings ──────────────────────────────────────
// Driver reports on whether the pump price matched the published one. None of this is Fuel
// Finder data. Every field has a default so a response missing one still decodes.

/** Null on a station until at least three drivers have rated it in the last 12 months. */
@Serializable
data class RatingSummaryDto(
    @SerialName("rater_count") val raterCount: Int = 0,
    @SerialName("avg_stars") val avgStars: Double = 0.0,
    // Raters who checked a price; a driver who didn't buy fuel rates without one.
    @SerialName("price_check_count") val priceCheckCount: Int = 0,
    // Share of those checks where the pump matched, 0–100; null when nobody checked a price.
    @SerialName("price_match_pct") val priceMatchPct: Int? = null,
    // Mean of (price paid − price published) over mismatch reports that gave a price: positive
    // means drivers paid more than listed.
    @SerialName("avg_gap_pence") val avgGapPence: Double? = null,
)

/** A published comment as any viewer sees it. Reviewers are anonymous; [authorRef] is only a
 *  handle for hiding a reviewer. */
@Serializable
data class PublicRatingDto(
    val id: Int,
    val stars: Int = 0,
    // Both null when the driver didn't buy fuel, and so made no price check.
    @SerialName("price_matched") val priceMatched: Boolean? = null,
    @SerialName("fuel_type") val fuelType: String? = null,
    @SerialName("gap_pence") val gapPence: Double? = null,
    val comment: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val edited: Boolean = false,
    @SerialName("author_ref") val authorRef: String = "",
)

@Serializable
data class PublicRatingsResponse(
    val items: List<PublicRatingDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
)

/** The caller's own rating, including where its comment stands in moderation. */
@Serializable
data class OwnRatingDto(
    val id: Int,
    @SerialName("station_id") val stationId: Int = 0,
    val stars: Int = 0,
    @SerialName("price_matched") val priceMatched: Boolean? = null,
    @SerialName("fuel_type") val fuelType: String? = null,
    @SerialName("reported_price_pence") val reportedPricePence: Double? = null,
    @SerialName("published_price_pence") val publishedPricePence: Double? = null,
    @SerialName("gap_pence") val gapPence: Double? = null,
    val comment: String? = null,
    // none | pending | approved | held | rejected | hidden
    @SerialName("comment_status") val commentStatus: String = "none",
    @SerialName("moderation_reason") val moderationReason: String? = null,
    @SerialName("edits_remaining") val editsRemaining: Int = 0,
    @SerialName("editable_until") val editableUntil: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("edited_at") val editedAt: String? = null,
)

/** `GET /api/ratings/mine`. [blockers] are kept as raw codes so an unrecognised one still decodes. */
@Serializable
data class MyRatingResponse(
    val rating: OwnRatingDto? = null,
    @SerialName("can_rate_at") val canRateAt: String? = null,
    val blockers: List<String> = emptyList(),
    @SerialName("daily_cap_resets_at") val dailyCapResetsAt: String? = null,
    @SerialName("terms_version") val termsVersion: String = "",
)

/** Body for both creating and editing a rating. Every field is always sent, nulls included.
 *  A driver who didn't buy fuel sends a null [fuelType], and then [priceMatched] is null too. */
@Serializable
data class RatingInputRequest(
    @SerialName("fuel_type") val fuelType: String?,
    @SerialName("price_matched") val priceMatched: Boolean?,
    @SerialName("reported_price_pence") val reportedPricePence: Double?,
    val stars: Int,
    val comment: String?,
)

@Serializable
data class RatingSavedResponse(
    val rating: OwnRatingDto? = null,
    @SerialName("can_rate_at") val canRateAt: String? = null,
)

@Serializable
data class ReportRatingRequest(val reason: String?)

@Serializable
data class BlockAuthorResponse(@SerialName("author_ref") val authorRef: String = "")

@Serializable
data class BlockedReviewersResponse(@SerialName("author_refs") val authorRefs: List<String> = emptyList())

@Serializable
data class VerifyEmailResponse(
    val ok: Boolean = false,
    @SerialName("already_verified") val alreadyVerified: Boolean = false,
)

@Serializable
data class AcceptTermsRequest(val version: String)

/** The error body the ratings and verification routes answer with. A 409 cooldown also carries
 *  the rating already stored, which a retried submit treats as success. */
@Serializable
data class RatingErrorBody(
    val detail: String? = null,
    val reason: String? = null,
    @SerialName("can_rate_at") val canRateAt: String? = null,
    val rating: OwnRatingDto? = null,
)
