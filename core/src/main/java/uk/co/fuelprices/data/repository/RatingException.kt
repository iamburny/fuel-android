package uk.co.fuelprices.data.repository

import kotlinx.serialization.json.Json
import retrofit2.HttpException
import uk.co.fuelprices.data.api.OwnRatingDto
import uk.co.fuelprices.data.api.RatingErrorBody

/**
 * A failed ratings or email-verification call, carrying the API's own error fields so the app
 * layer can show [detail] and branch on [reason] without Retrofit on its classpath.
 */
class RatingException(
    val status: Int,
    val detail: String?,
    val reason: String?,
    val canRateAt: String? = null,
    val rating: OwnRatingDto? = null,
) : Exception(detail ?: "HTTP $status") {

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun from(e: HttpException): RatingException {
            val body = try {
                e.response()?.errorBody()?.string()?.let { json.decodeFromString(RatingErrorBody.serializer(), it) }
            } catch (_: Exception) {
                null
            }
            return RatingException(
                status = e.code(),
                detail = body?.detail,
                reason = body?.reason,
                canRateAt = body?.canRateAt,
                rating = body?.rating,
            )
        }
    }
}
