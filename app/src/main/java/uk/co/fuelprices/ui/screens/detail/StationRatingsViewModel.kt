package uk.co.fuelprices.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.MyRatingResponse
import uk.co.fuelprices.data.api.OwnRatingDto
import uk.co.fuelprices.data.api.PublicRatingDto
import uk.co.fuelprices.data.api.RatingInputRequest
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.RatingException
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.AppAnalytics
import uk.co.fuelprices.util.FeatureFlags
import javax.inject.Inject

data class RateFormState(
    val fuelTypes: List<String> = emptyList(),
    val fuelType: String? = null,
    val priceMatched: Boolean? = null,
    val paidText: String = "",
    val stars: Int? = null,
    val comment: String = "",
    val termsAccepted: Boolean = false,
    // Set once the user changes anything, so a late /ratings/mine reload doesn't reset their input.
    val touched: Boolean = false,
) {
    val paidValue: Double? get() = paidText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val paidValid: Boolean
        get() = paidText.isBlank() || paidValue?.let { it in MIN_REPORTED_PENCE..MAX_REPORTED_PENCE } == true
}

enum class VerifyEmailStatus { IDLE, SENDING, SENT, ERROR }

data class RateSheetUiState(
    val form: RateFormState = RateFormState(),
    val saved: OwnRatingDto? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    val verifyStatus: VerifyEmailStatus = VerifyEmailStatus.IDLE,
    val verifyError: String? = null,
)

data class StationRatingsUiState(
    val enabled: Boolean = false,
    val isLoggedIn: Boolean = false,
    val mine: MyRatingResponse? = null,
    val mineLoading: Boolean = false,
    val comments: List<PublicRatingDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val loadingMore: Boolean = false,
    val blockedAuthorRefs: Set<String> = emptySet(),
    // Per-comment outcome of a report or hide, shown under that comment.
    val commentMessages: Map<Int, String> = emptyMap(),
    // Comments with a report/hide in flight, so a double tap can't send it twice.
    val pendingCommentIds: Set<Int> = emptySet(),
    val reportingCommentId: Int? = null,
    val sheet: RateSheetUiState? = null,
    // One-shot: the screen routes to sign-in, then calls consumeSignInRequest().
    val signInRequested: Boolean = false,
) {
    val visibleComments: List<PublicRatingDto> get() = comments.filter { it.authorRef !in blockedAuthorRefs }
    val hiddenComments: List<PublicRatingDto> get() = comments.filter { it.authorRef in blockedAuthorRefs }
    val canEditOwn: Boolean get() = mine?.rating?.let { isEditable(it) } == true
    val sheetMode: RateSheetMode? get() = sheet?.let { rateSheetMode(mine, mineLoading, it.saved) }
}

/**
 * Driver reports on the Detail screen: the public comments, the signed-in user's own rating and
 * blocked reviewers, and the rate sheet. Scoped to the Detail nav entry alongside
 * [DetailViewModel]; everything stays empty while the shared.station-ratings flag is off.
 */
@HiltViewModel
class StationRatingsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: FuelRepository,
    private val featureFlags: FeatureFlags,
    private val preferencesStore: UserPreferencesStore,
    private val analytics: AppAnalytics,
) : ViewModel() {

    private val stationId: Int = savedState.get<Int>("stationId") ?: 0

    private val _state = MutableStateFlow(StationRatingsUiState())
    val state: StateFlow<StationRatingsUiState> = _state.asStateFlow()

    private var stationFuelTypes: List<String> = emptyList()
    private var preferredFuelType: String? = null
    private var openAfterSignIn = false
    private var userJob: Job? = null

    init {
        viewModelScope.launch {
            preferredFuelType = try {
                preferencesStore.get().fuelType
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        // StateFlow, so this also runs once straight away with the current flag state.
        viewModelScope.launch {
            featureFlags.version.collect {
                val on = featureFlags.isEnabled(STATION_RATINGS_FLAG, default = false)
                val wasOn = _state.value.enabled
                if (on && !wasOn) {
                    _state.update { it.copy(enabled = true) }
                    loadFirstPage()
                    refreshUserState()
                } else if (!on && wasOn) {
                    _state.value = StationRatingsUiState()
                }
            }
        }
    }

    /** The fuels this station lists, which are the only ones a driver can rate. */
    fun setStationFuelTypes(fuelTypes: List<String>) {
        stationFuelTypes = fuelTypes
    }

    /**
     * Re-reads sign-in state and the user's rating. Called whenever the screen resumes, which
     * covers coming back from sign-in and coming back from the browser after verifying an email.
     */
    fun refreshUserState() {
        if (!_state.value.enabled) return
        userJob?.cancel()
        userJob = viewModelScope.launch {
            val loggedIn = repo.isLoggedIn()
            _state.update { it.copy(isLoggedIn = loggedIn) }
            if (!loggedIn) {
                openAfterSignIn = false
                _state.update { it.copy(mine = null, mineLoading = false, blockedAuthorRefs = emptySet(), sheet = null) }
                return@launch
            }
            launch { loadBlocked() }
            reloadMine()
            if (openAfterSignIn) {
                openAfterSignIn = false
                openSheet()
            }
        }
    }

    private suspend fun reloadMine() {
        _state.update { it.copy(mineLoading = true) }
        val mine = try {
            repo.getMyRating(stationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ratings switched off server-side (404) or a transient failure: no rating action.
            null
        }
        val loggedIn = mine != null || repo.isLoggedIn()
        _state.update { s ->
            val sheet = s.sheet
            val refreshedSheet = if (sheet != null && sheet.saved == null && !sheet.form.touched) {
                sheet.copy(form = initialForm(mine))
            } else sheet
            s.copy(mine = mine, mineLoading = false, isLoggedIn = loggedIn, sheet = refreshedSheet)
        }
    }

    private suspend fun loadBlocked() {
        val refs = try {
            repo.getBlockedReviewers()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        _state.update { it.copy(blockedAuthorRefs = refs) }
    }

    private fun loadFirstPage() {
        viewModelScope.launch {
            try {
                val res = repo.getStationRatings(stationId, 1)
                _state.update { it.copy(comments = res.items, total = res.total, page = 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || s.comments.size >= s.total) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val next = repo.getStationRatings(stationId, s.page + 1)
                _state.update { cur ->
                    val known = cur.comments.map { it.id }.toSet()
                    cur.copy(
                        comments = cur.comments + next.items.filter { it.id !in known },
                        total = next.total,
                        page = s.page + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // What's shown stays in place and the button stays for another try.
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    fun consumeSignInRequest() {
        _state.update { it.copy(signInRequested = false) }
    }

    private fun requestSignIn() {
        _state.update { it.copy(signInRequested = true) }
    }

    // ── Rate sheet ───────────────────────────────────────

    /** Runs [action] when signed in, otherwise asks for sign-in. Reads the session itself rather
     *  than the state, which is still false until the first refresh lands. */
    private fun whenSignedIn(onSignedOut: () -> Unit = {}, action: () -> Unit) {
        if (_state.value.isLoggedIn) {
            action()
            return
        }
        viewModelScope.launch {
            if (repo.isLoggedIn()) {
                _state.update { it.copy(isLoggedIn = true) }
                action()
            } else {
                onSignedOut()
                requestSignIn()
            }
        }
    }

    fun onRateClicked() {
        if (!_state.value.enabled) return
        whenSignedIn(onSignedOut = { openAfterSignIn = true }) {
            analytics.trackEvent("open_rating_form", mapOf("station_id" to stationId))
            openSheet()
            if (_state.value.mine == null && !_state.value.mineLoading) {
                viewModelScope.launch { reloadMine() }
            }
        }
    }

    private fun openSheet() {
        _state.update { it.copy(sheet = RateSheetUiState(form = initialForm(it.mine))) }
    }

    fun closeSheet() {
        _state.update { it.copy(sheet = null) }
    }

    private fun initialForm(mine: MyRatingResponse?): RateFormState {
        val existing = mine?.rating?.takeIf { isEditable(it) }
        val fuels = stationFuelTypes
        val fuel = when {
            existing != null && existing.fuelType in fuels -> existing.fuelType
            preferredFuelType != null && preferredFuelType in fuels -> preferredFuelType
            else -> fuels.firstOrNull()
        }
        return RateFormState(
            fuelTypes = fuels,
            fuelType = fuel,
            priceMatched = existing?.priceMatched,
            paidText = existing?.reportedPricePence?.let(::formatPence) ?: "",
            stars = existing?.stars,
            comment = existing?.comment ?: "",
        )
    }

    private fun editForm(change: (RateFormState) -> RateFormState) {
        _state.update { s ->
            val sheet = s.sheet ?: return@update s
            s.copy(sheet = sheet.copy(form = change(sheet.form).copy(touched = true), error = null))
        }
    }

    fun setFuelType(fuelType: String) = editForm { it.copy(fuelType = fuelType) }
    fun setPriceMatched(matched: Boolean) = editForm { it.copy(priceMatched = matched) }
    fun setPaidText(text: String) = editForm { it.copy(paidText = text.filter { c -> c.isDigit() || c == '.' }.take(6)) }
    fun setStars(stars: Int) = editForm { it.copy(stars = stars.coerceIn(1, 5)) }
    fun setComment(text: String) = editForm { it.copy(comment = text.take(RATING_COMMENT_MAX)) }
    fun setTermsAccepted(accepted: Boolean) = editForm { it.copy(termsAccepted = accepted) }

    fun canSubmit(): Boolean {
        val s = _state.value
        val sheet = s.sheet ?: return false
        val mode = s.sheetMode as? RateSheetMode.Form ?: return false
        val f = sheet.form
        return !sheet.submitting && f.fuelTypes.isNotEmpty() && f.fuelType != null &&
            f.priceMatched != null && f.stars != null && f.paidValid &&
            (!mode.needsTerms || f.termsAccepted)
    }

    fun submit() {
        if (!canSubmit()) return
        val s = _state.value
        val mode = s.sheetMode as RateSheetMode.Form
        val f = s.sheet!!.form
        val matched = f.priceMatched!!
        val input = RatingInputRequest(
            fuelType = f.fuelType!!,
            priceMatched = matched,
            reportedPricePence = if (matched) null else f.paidValue,
            stars = f.stars!!,
            comment = f.comment.trim().ifEmpty { null },
        )
        val existing = mode.existing
        _state.update { it.copy(sheet = it.sheet?.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            try {
                // Acceptance first: the rating itself is refused until the policy is accepted.
                if (mode.needsTerms) repo.acceptTerms(mode.termsVersion)
                val saved = if (existing != null) {
                    repo.updateRating(existing.id, input).rating
                } else {
                    repo.createRating(stationId, input).rating
                }
                if (saved == null) {
                    setSheetError(GENERIC_ERROR)
                } else {
                    analytics.trackEvent(
                        if (existing != null) "edit_rating" else "submit_rating",
                        mapOf(
                            "station_id" to stationId,
                            "price_matched" to matched,
                            "stars" to input.stars,
                            "has_comment" to (input.comment != null),
                        ),
                    )
                    onSaved(saved)
                }
            } catch (e: RatingException) {
                // A retried submit that already landed answers as a cooldown carrying the stored
                // rating. Only a rating matching this input counts: a different one means the user
                // rated elsewhere since the screen loaded, and this input was not saved.
                val stored = e.rating
                if (e.reason == "cooldown" && stored != null && existing == null && stored.matches(input)) {
                    onSaved(stored)
                } else {
                    setSheetError(errorMessage(e))
                    // A blocker, cooldown or closed edit window means the form no longer applies;
                    // re-reading moves the sheet on to the matching message.
                    if (e.status == 403 || e.status == 409) reloadMine()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setSheetError(GENERIC_ERROR)
            } finally {
                _state.update { it.copy(sheet = it.sheet?.copy(submitting = false)) }
            }
        }
    }

    private suspend fun onSaved(rating: OwnRatingDto) {
        _state.update { it.copy(sheet = it.sheet?.copy(saved = rating, error = null)) }
        reloadMine()
        loadFirstPage()
    }

    private fun setSheetError(message: String) {
        _state.update { it.copy(sheet = it.sheet?.copy(error = message)) }
    }

    fun sendVerificationEmail() {
        val sheet = _state.value.sheet ?: return
        if (sheet.verifyStatus == VerifyEmailStatus.SENDING || sheet.verifyStatus == VerifyEmailStatus.SENT) return
        _state.update { it.copy(sheet = it.sheet?.copy(verifyStatus = VerifyEmailStatus.SENDING, verifyError = null)) }
        viewModelScope.launch {
            try {
                val res = repo.requestEmailVerification()
                _state.update { it.copy(sheet = it.sheet?.copy(verifyStatus = VerifyEmailStatus.SENT)) }
                // Already verified elsewhere: reloading moves the sheet straight on to the form.
                if (res.alreadyVerified) reloadMine()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(sheet = it.sheet?.copy(verifyStatus = VerifyEmailStatus.ERROR, verifyError = errorMessage(e)))
                }
            }
        }
    }

    // ── Comments ─────────────────────────────────────────

    fun startReport(ratingId: Int) = whenSignedIn {
        _state.update { it.copy(reportingCommentId = ratingId) }
    }

    fun cancelReport() {
        _state.update { it.copy(reportingCommentId = null) }
    }

    fun report(ratingId: Int, reason: String) {
        if (ratingId in _state.value.pendingCommentIds) return
        _state.update { it.copy(reportingCommentId = null, pendingCommentIds = it.pendingCommentIds + ratingId) }
        viewModelScope.launch {
            val message = try {
                repo.reportRating(ratingId, reason)
                analytics.trackEvent("report_rating", mapOf("rating_id" to ratingId))
                "Thanks. We'll review this comment."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage(e)
            }
            _state.update {
                it.copy(
                    commentMessages = it.commentMessages + (ratingId to message),
                    pendingCommentIds = it.pendingCommentIds - ratingId,
                )
            }
        }
    }

    fun hideReviewer(ratingId: Int) = whenSignedIn { hideReviewerNow(ratingId) }

    private fun hideReviewerNow(ratingId: Int) {
        if (ratingId in _state.value.pendingCommentIds) return
        _state.update { it.copy(pendingCommentIds = it.pendingCommentIds + ratingId) }
        viewModelScope.launch {
            try {
                val ref = repo.blockRatingAuthor(ratingId)
                _state.update { it.copy(blockedAuthorRefs = it.blockedAuthorRefs + ref) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(commentMessages = it.commentMessages + (ratingId to errorMessage(e))) }
            } finally {
                _state.update { it.copy(pendingCommentIds = it.pendingCommentIds - ratingId) }
            }
        }
    }

    /** Unhides every reviewer whose comments are currently hidden on this station. */
    fun showHiddenReviewers() {
        val refs = _state.value.hiddenComments.map { it.authorRef }.toSet()
        refs.forEach { ref ->
            viewModelScope.launch {
                try {
                    repo.unblockReviewer(ref)
                    _state.update { it.copy(blockedAuthorRefs = it.blockedAuthorRefs - ref) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun errorMessage(e: Exception): String {
        if (e is RatingException) {
            if (e.status == 401) {
                // TokenAuthenticator's silent refresh already failed and cleared the session.
                _state.update { it.copy(isLoggedIn = repo.isLoggedIn()) }
                return "Your session has expired. Sign in again to continue."
            }
            if (e.status == 429) return e.detail ?: "Too many attempts. Try again in a little while."
            e.detail?.let { return it }
        }
        return GENERIC_ERROR
    }

    /** Compares as the server stores it: comment whitespace tidied, price rounded to 0.1p. */
    private fun OwnRatingDto.matches(input: RatingInputRequest): Boolean =
        fuelType == input.fuelType && priceMatched == input.priceMatched && stars == input.stars &&
            reportedPricePence == input.reportedPricePence?.let { Math.round(it * 10) / 10.0 } &&
            comment == input.comment?.let(::normaliseComment)

    private companion object {
        const val GENERIC_ERROR = "Something went wrong. Please try again."

        private val CONTROL_CHARS = Regex("[\u0000-\u001f\u007f]")
        private val INVISIBLE_CHARS = Regex("[\u00ad\u180e\u200b-\u200f\u202a-\u202e\u2060-\u2069\ufeff]")
        private val WHITESPACE = Regex("\\s+")

        /** The same tidying fuel-api applies before storing a comment; empty becomes null. */
        fun normaliseComment(value: String): String? =
            value.replace(CONTROL_CHARS, " ").replace(INVISIBLE_CHARS, "").replace(WHITESPACE, " ").trim()
                .ifEmpty { null }

        fun formatPence(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
