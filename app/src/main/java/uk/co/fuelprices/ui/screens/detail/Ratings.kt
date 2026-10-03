package uk.co.fuelprices.ui.screens.detail

import uk.co.fuelprices.data.api.MyRatingResponse
import uk.co.fuelprices.data.api.OwnRatingDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val STATION_RATINGS_FLAG = "shared.station-ratings"

const val RATING_COMMENT_MAX = 280
const val MIN_REPORTED_PENCE = 50.0
const val MAX_REPORTED_PENCE = 400.0

const val REVIEWS_TERMS_URL = "https://fueltracker.uk/terms#reviews"

val REPORT_REASONS = listOf(
    "Offensive or abusive",
    "Contains personal information",
    "Spam or advertising",
    "Not about this station",
    "Something else",
)

val BLOCKER_COPY = mapOf(
    "suspended" to "Your account can no longer leave ratings.",
    "email_unverified" to "Verify your email address to leave a rating.",
    "account_too_new" to "Accounts need to be 3 days old before they can leave a rating.",
    "terms" to "Please accept the reviews content policy first.",
    "daily_cap" to "You've reached today's limit of 5 ratings.",
)

private val LONDON: ZoneId = ZoneId.of("Europe/London")
private val UK_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(LONDON)
private val UK_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withZone(LONDON)

private fun parseInstant(iso: String?): Instant? =
    try {
        iso?.let(Instant::parse)
    } catch (_: Exception) {
        null
    }

/** "1 Oct 2026" in UK time; the raw value if it isn't a timestamp. */
fun formatRatingDate(iso: String?): String = parseInstant(iso)?.let(UK_DATE::format) ?: iso.orEmpty()

fun formatRatingTime(iso: String?): String = parseInstant(iso)?.let(UK_TIME::format) ?: iso.orEmpty()

private fun penceAmount(value: Double): String {
    val amount = abs(value)
    return if (amount == amount.roundToInt().toDouble()) "${amount.roundToInt()}p" else "%.1fp".format(Locale.UK, amount)
}

/** What the ratings section says before a station has a score, given the API's threshold. */
fun noScoreMessage(minRaters: Int?): String = when {
    minRaters == null -> "Not enough reports yet."
    minRaters <= 1 -> "No score yet. Be the first to rate this station."
    else -> "Not enough reports yet. A score appears once $minRaters drivers have rated this station."
}

/** "1 driver" / "7 drivers". */
fun driverCount(count: Int): String = "$count driver${if (count == 1) "" else "s"}"

/** "3.1p more than listed" / "2p less than listed". */
fun gapPhrase(gapPence: Double): String = when {
    gapPence == 0.0 -> "the listed price"
    gapPence > 0 -> "${penceAmount(gapPence)} more than listed"
    else -> "${penceAmount(gapPence)} less than listed"
}

/** "+3.1p" / "−2p": the sign says whether drivers paid more or less than listed. */
fun signedPence(gapPence: Double): String = (if (gapPence > 0) "+" else "−") + penceAmount(gapPence)

/** Five characters, filled up to the rounded value: 2.3 → "★★☆☆☆". */
fun starString(value: Double): String {
    val filled = value.roundToInt().coerceIn(0, 5)
    return "★".repeat(filled) + "☆".repeat(5 - filled)
}

fun isEditable(rating: OwnRatingDto, now: Instant = Instant.now()): Boolean {
    val until = parseInstant(rating.editableUntil) ?: return false
    return rating.editsRemaining > 0 && until.isAfter(now)
}

/** The line under the rate button telling the user where their own rating stands. */
fun ownRatingStatus(mine: MyRatingResponse?, now: Instant = Instant.now()): String? {
    val own = mine?.rating ?: return null
    val text = StringBuilder("You rated this station on ${formatRatingDate(own.createdAt)}.")
    when (own.commentStatus) {
        "pending", "held" -> text.append(" Your rating counts now; your comment will appear once it's been checked.")
        "rejected" -> text.append(
            " Your comment wasn't published" + moderationSuffix(own.moderationReason),
        )
        "hidden" -> text.append(" Your comment is hidden while it's reviewed.")
    }
    if (mine.canRateAt != null && !isEditable(own, now)) {
        text.append(" You can rate it again from ${formatRatingDate(mine.canRateAt)}.")
    }
    return text.toString()
}

/** What the rate sheet shows, resolved from `/ratings/mine` in the order the API asks. */
sealed interface RateSheetMode {
    data object Loading : RateSheetMode
    data object Unavailable : RateSheetMode
    data class Cooldown(val ratedOn: String?, val canRateAt: String) : RateSheetMode
    data object VerifyEmail : RateSheetMode
    data class Blocked(val message: String) : RateSheetMode
    data class Form(val existing: OwnRatingDto?, val needsTerms: Boolean, val termsVersion: String) : RateSheetMode
    data class Saved(val rating: OwnRatingDto) : RateSheetMode
}

fun rateSheetMode(
    mine: MyRatingResponse?,
    loading: Boolean,
    saved: OwnRatingDto?,
    now: Instant = Instant.now(),
): RateSheetMode {
    if (saved != null) return RateSheetMode.Saved(saved)
    if (mine == null) return if (loading) RateSheetMode.Loading else RateSheetMode.Unavailable
    val own = mine.rating
    val editing = own != null && isEditable(own, now)
    val canRateAt = mine.canRateAt
    if (!editing && canRateAt != null) {
        return RateSheetMode.Cooldown(own?.createdAt, canRateAt)
    }
    // Suspension comes first: verifying an email wouldn't let a suspended account rate.
    if (!editing && "suspended" in mine.blockers) return RateSheetMode.Blocked(BLOCKER_COPY.getValue("suspended"))
    if (!editing && "email_unverified" in mine.blockers) return RateSheetMode.VerifyEmail
    val blocker = mine.blockers.firstOrNull { it != "terms" }
    if (!editing && blocker != null) {
        var message = BLOCKER_COPY[blocker] ?: "You can't leave a rating right now."
        val resetsAt = mine.dailyCapResetsAt
        if (blocker == "daily_cap" && resetsAt != null) {
            message += " You can rate again from ${formatRatingTime(resetsAt)}."
        }
        return RateSheetMode.Blocked(message)
    }
    return RateSheetMode.Form(
        existing = if (editing) own else null,
        needsTerms = !editing && "terms" in mine.blockers,
        termsVersion = mine.termsVersion,
    )
}

private fun moderationSuffix(reason: String?): String =
    if (reason.isNullOrBlank()) "." else ": ${reason.trim().trimEnd('.')}."

/** Confirmation once a rating is saved, depending on where its comment landed in moderation. */
fun savedMessage(rating: OwnRatingDto): String = when (rating.commentStatus) {
    "approved", "none" -> "Thanks — your rating is live."
    "rejected" -> "Your rating counts, but your comment wasn't published" +
        moderationSuffix(rating.moderationReason) + " You can edit it within 24 hours."
    else -> "Thanks — your rating counts now. Your comment will appear once it's been checked."
}

/** Chip text on a published comment. */
fun priceMatchLabel(priceMatched: Boolean, gapPence: Double?): String = when {
    priceMatched -> "Price matched"
    gapPence != null && gapPence != 0.0 -> "Charged ${gapPhrase(gapPence)}"
    else -> "Price didn't match"
}
