package uk.co.fuelprices.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uk.co.fuelprices.data.api.MyRatingResponse
import uk.co.fuelprices.data.api.OwnRatingDto
import uk.co.fuelprices.data.api.PublicRatingDto
import uk.co.fuelprices.data.api.PublicRatingsResponse
import uk.co.fuelprices.data.api.RatingSavedResponse
import uk.co.fuelprices.data.api.VerifyEmailResponse
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.RatingException
import uk.co.fuelprices.data.repository.UserPreferences
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.testutil.MainDispatcherRule
import uk.co.fuelprices.util.AppAnalytics
import uk.co.fuelprices.util.FeatureFlags

private const val FUTURE = "2099-01-01T00:00:00Z"
private const val PAST = "2000-01-01T00:00:00Z"

private fun ownRating(
    id: Int = 5,
    editableUntil: String = FUTURE,
    editsRemaining: Int = 3,
    commentStatus: String = "approved",
    fuelType: String = "E5",
) = OwnRatingDto(
    id = id,
    stationId = 1,
    stars = 2,
    priceMatched = false,
    fuelType = fuelType,
    reportedPricePence = 152.9,
    comment = "Dearer than listed",
    commentStatus = commentStatus,
    editsRemaining = editsRemaining,
    editableUntil = editableUntil,
    createdAt = "2026-10-01T09:00:00Z",
)

private fun publicRating(id: Int, authorRef: String) = PublicRatingDto(
    id = id, stars = 4, priceMatched = true, fuelType = "E10", comment = "Fine", createdAt = "2026-09-01T08:00:00Z",
    authorRef = authorRef,
)

class StationRatingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: FuelRepository
    private lateinit var featureFlags: FeatureFlags
    private lateinit var analytics: AppAnalytics

    private fun buildViewModel(
        flagOn: Boolean = true,
        loggedIn: Boolean = true,
        mine: MyRatingResponse = MyRatingResponse(termsVersion = "1"),
        comments: List<PublicRatingDto> = emptyList(),
        blocked: Set<String> = emptySet(),
    ): StationRatingsViewModel {
        repo = mockk(relaxed = true)
        featureFlags = mockk(relaxed = true)
        analytics = mockk(relaxed = true)
        val preferencesStore: UserPreferencesStore = mockk(relaxed = true)

        every { featureFlags.version } returns MutableStateFlow(0)
        every { featureFlags.isEnabled(STATION_RATINGS_FLAG, false) } returns flagOn
        coEvery { preferencesStore.get() } returns UserPreferences(fuelType = "E10")
        coEvery { repo.isLoggedIn() } returns loggedIn
        coEvery { repo.getMyRating(1) } returns mine
        coEvery { repo.getBlockedReviewers() } returns blocked
        coEvery { repo.getStationRatings(1, 1) } returns PublicRatingsResponse(items = comments, total = comments.size)

        val vm = StationRatingsViewModel(SavedStateHandle(mapOf("stationId" to 1)), repo, featureFlags, preferencesStore, analytics)
        vm.setStationFuelTypes(listOf("E10", "E5"))
        return vm
    }

    private fun StationRatingsViewModel.fillValidForm() {
        setPriceMatched(true)
        setStars(4)
    }

    @Test
    fun `nothing loads and the rate button does nothing while the flag is off`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(flagOn = false)
        advanceUntilIdle()

        vm.onRateClicked()
        advanceUntilIdle()

        assertFalse(vm.state.value.enabled)
        assertNull(vm.state.value.sheet)
        coVerify(exactly = 0) { repo.getStationRatings(any(), any()) }
        coVerify(exactly = 0) { repo.getMyRating(any()) }
    }

    @Test
    fun `signed out, rating routes to sign-in and the sheet opens once signed in`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(loggedIn = false)
        advanceUntilIdle()

        vm.onRateClicked()
        advanceUntilIdle()
        assertTrue(vm.state.value.signInRequested)
        assertNull(vm.state.value.sheet)
        vm.consumeSignInRequest()

        coEvery { repo.isLoggedIn() } returns true
        vm.refreshUserState()
        advanceUntilIdle()

        assertTrue(vm.state.value.isLoggedIn)
        assertTrue(vm.state.value.sheetMode is RateSheetMode.Form)
        coVerify { repo.getMyRating(1) }
    }

    @Test
    fun `backing out of sign-in doesn't reopen the sheet later`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(loggedIn = false)
        advanceUntilIdle()

        vm.onRateClicked()
        advanceUntilIdle()
        vm.refreshUserState()
        advanceUntilIdle()
        coEvery { repo.isLoggedIn() } returns true
        vm.refreshUserState()
        advanceUntilIdle()

        assertNull(vm.state.value.sheet)
    }

    @Test
    fun `a rating that can no longer be edited, inside the cooldown, shows when the user can rate again`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(
            mine = MyRatingResponse(rating = ownRating(editableUntil = PAST), canRateAt = "2026-10-08T09:00:00Z", termsVersion = "1"),
        )
        advanceUntilIdle()

        vm.onRateClicked()

        assertEquals(RateSheetMode.Cooldown("2026-10-01T09:00:00Z", "2026-10-08T09:00:00Z"), vm.state.value.sheetMode)
        assertFalse(vm.state.value.canEditOwn)
    }

    @Test
    fun `an unverified email asks for verification ahead of any later blocker`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(
            mine = MyRatingResponse(blockers = listOf("email_unverified", "account_too_new", "terms"), termsVersion = "1"),
        )
        advanceUntilIdle()
        coEvery { repo.requestEmailVerification() } returns VerifyEmailResponse(ok = true)

        vm.onRateClicked()
        assertEquals(RateSheetMode.VerifyEmail, vm.state.value.sheetMode)

        vm.sendVerificationEmail()
        vm.sendVerificationEmail()
        advanceUntilIdle()

        assertEquals(VerifyEmailStatus.SENT, vm.state.value.sheet!!.verifyStatus)
        coVerify(exactly = 1) { repo.requestEmailVerification() }
    }

    @Test
    fun `a rate-limited verification request shows the API's message`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(mine = MyRatingResponse(blockers = listOf("email_unverified"), termsVersion = "1"))
        advanceUntilIdle()
        coEvery { repo.requestEmailVerification() } throws
            RatingException(429, "A verification email was sent recently. Check your inbox, or try again later.", null)

        vm.onRateClicked()
        vm.sendVerificationEmail()
        advanceUntilIdle()

        assertEquals(VerifyEmailStatus.ERROR, vm.state.value.sheet!!.verifyStatus)
        assertEquals(
            "A verification email was sent recently. Check your inbox, or try again later.",
            vm.state.value.sheet!!.verifyError,
        )
    }

    @Test
    fun `a blocker other than terms is explained, with the daily cap's reset time`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(
            mine = MyRatingResponse(blockers = listOf("terms", "daily_cap"), dailyCapResetsAt = "2026-10-01T16:30:00Z", termsVersion = "1"),
        )
        advanceUntilIdle()

        vm.onRateClicked()

        // 16:30 UTC is 17:30 in London during BST.
        assertEquals(
            RateSheetMode.Blocked("You've reached today's limit of 5 ratings. You can rate again from 17:30."),
            vm.state.value.sheetMode,
        )
    }

    @Test
    fun `an editable rating opens prefilled in edit mode and saves with PATCH`() = runTest(mainDispatcherRule.dispatcher) {
        val own = ownRating()
        val vm = buildViewModel(mine = MyRatingResponse(rating = own, canRateAt = "2026-10-08T09:00:00Z", termsVersion = "1"))
        advanceUntilIdle()
        coEvery { repo.updateRating(5, any()) } returns RatingSavedResponse(rating = own.copy(stars = 3))

        assertTrue(vm.state.value.canEditOwn)
        vm.onRateClicked()

        val mode = vm.state.value.sheetMode as RateSheetMode.Form
        assertEquals(own, mode.existing)
        val form = vm.state.value.sheet!!.form
        assertEquals("E5", form.fuelType)
        assertEquals(false, form.priceMatched)
        assertEquals("152.9", form.paidText)
        assertEquals(2, form.stars)
        assertEquals("Dearer than listed", form.comment)

        vm.setStars(3)
        vm.submit()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.updateRating(5, match { it.stars == 3 && it.reportedPricePence == 152.9 }) }
        coVerify(exactly = 0) { repo.createRating(any(), any()) }
        verify { analytics.trackEvent("edit_rating", any()) }
        assertTrue(vm.state.value.sheetMode is RateSheetMode.Saved)
    }

    @Test
    fun `a new rating defaults to the preferred fuel and drops the paid price once matched`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        coEvery { repo.createRating(1, any()) } returns RatingSavedResponse(rating = ownRating(commentStatus = "none"))

        vm.onRateClicked()
        assertEquals("E10", vm.state.value.sheet!!.form.fuelType)

        vm.setPriceMatched(false)
        vm.setPaidText("151.9")
        vm.setPriceMatched(true)
        vm.setStars(5)
        vm.setComment("   ")
        vm.submit()
        advanceUntilIdle()

        coVerify {
            repo.createRating(1, match { it.fuelType == "E10" && it.priceMatched == true && it.reportedPricePence == null && it.comment == null })
        }
        verify { analytics.trackEvent("submit_rating", any()) }
    }

    @Test
    fun `choosing no fuel skips the price check and sends nulls`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        coEvery { repo.createRating(1, any()) } returns RatingSavedResponse(rating = ownRating(commentStatus = "none"))

        vm.onRateClicked()
        vm.setPriceMatched(false)
        vm.setPaidText("151.9")
        vm.setFuelType(null)
        vm.setStars(3)
        assertNull(vm.state.value.sheet!!.form.priceMatched)
        assertTrue(vm.canSubmit())
        vm.submit()
        advanceUntilIdle()

        coVerify {
            repo.createRating(1, match { it.fuelType == null && it.priceMatched == null && it.reportedPricePence == null })
        }
    }

    @Test
    fun `a half-typed paid price stops blocking once the driver switches to no fuel`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onRateClicked()
        vm.setPriceMatched(false)
        vm.setPaidText("12")
        vm.setStars(4)
        assertFalse(vm.canSubmit())
        vm.setFuelType(null)
        assertTrue(vm.canSubmit())
    }

    @Test
    fun `an edited rating keeps a fuel the station no longer lists`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(mine = MyRatingResponse(rating = ownRating(fuelType = "SDV"), termsVersion = "1"))
        advanceUntilIdle()

        vm.onRateClicked()
        val form = vm.state.value.sheet!!.form
        assertEquals("SDV", form.fuelType)
        assertTrue("SDV" in form.fuelTypes)
        assertEquals(false, form.priceMatched)
    }

    @Test
    fun `a station listing no prices can still be rated without fuel`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        vm.setStationFuelTypes(emptyList())
        advanceUntilIdle()

        vm.onRateClicked()
        assertNull(vm.state.value.sheet!!.form.fuelType)
        assertFalse(vm.canSubmit())
        vm.setStars(4)
        assertTrue(vm.canSubmit())
    }

    @Test
    fun `an edited rating made without fuel reopens with no fuel chosen`() = runTest(mainDispatcherRule.dispatcher) {
        val noFuel = ownRating().copy(fuelType = null, priceMatched = null, reportedPricePence = null)
        val vm = buildViewModel(mine = MyRatingResponse(rating = noFuel, termsVersion = "1"))
        advanceUntilIdle()

        vm.onRateClicked()
        val form = vm.state.value.sheet!!.form
        assertNull(form.fuelType)
        assertNull(form.priceMatched)
    }

    @Test
    fun `a paid price outside 50 to 400 pence blocks submission`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onRateClicked()
        vm.setPriceMatched(false)
        vm.setStars(1)
        vm.setPaidText("1499")
        assertFalse(vm.canSubmit())

        vm.setPaidText("149.9")
        assertTrue(vm.canSubmit())
    }

    @Test
    fun `a retried submit answered with a cooldown that carries the rating counts as saved`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        val stored = ownRating(commentStatus = "held").copy(
            fuelType = "E10", priceMatched = true, reportedPricePence = null, stars = 4, comment = null,
        )
        coEvery { repo.createRating(1, any()) } throws
            RatingException(409, "You can rate this station once every 7 days.", "cooldown", rating = stored)

        vm.onRateClicked()
        vm.fillValidForm()
        vm.submit()
        advanceUntilIdle()

        assertEquals(RateSheetMode.Saved(stored), vm.state.value.sheetMode)
        assertNull(vm.state.value.sheet!!.error)
    }

    @Test
    fun `a retried submit still counts as saved after the server tidied the comment`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        val stored = ownRating(commentStatus = "held").copy(
            fuelType = "E10", priceMatched = true, reportedPricePence = null, stars = 4, comment = "Pump was dearer",
        )
        coEvery { repo.createRating(1, any()) } throws
            RatingException(409, "You can rate this station once every 7 days.", "cooldown", rating = stored)

        vm.onRateClicked()
        vm.fillValidForm()
        vm.setComment("Pump  was\n dearer\u200B ")
        vm.submit()
        advanceUntilIdle()

        assertEquals(RateSheetMode.Saved(stored), vm.state.value.sheetMode)
    }

    @Test
    fun `a refused submit re-reads the user's state so the sheet shows why`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        coEvery { repo.getMyRating(1) } returns
            MyRatingResponse(blockers = listOf("daily_cap"), dailyCapResetsAt = FUTURE, termsVersion = "1")
        coEvery { repo.createRating(1, any()) } throws
            RatingException(403, "You can leave up to 5 ratings a day.", "daily_cap")

        vm.onRateClicked()
        vm.fillValidForm()
        vm.submit()
        advanceUntilIdle()

        assertTrue(vm.state.value.sheetMode is RateSheetMode.Blocked)
    }

    @Test
    fun `a cooldown carrying a different rating is an error, not a silent success`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        coEvery { repo.createRating(1, any()) } throws
            RatingException(409, "You can rate this station once every 7 days.", "cooldown", rating = ownRating())

        vm.onRateClicked()
        vm.fillValidForm()
        vm.submit()
        advanceUntilIdle()

        assertEquals("You can rate this station once every 7 days.", vm.state.value.sheet!!.error)
    }

    @Test
    fun `a suspended account is told so before being asked to verify its email`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(mine = MyRatingResponse(blockers = listOf("suspended", "email_unverified"), termsVersion = "1"))
        advanceUntilIdle()

        vm.onRateClicked()

        assertEquals(RateSheetMode.Blocked("Your account can no longer leave ratings."), vm.state.value.sheetMode)
    }

    @Test
    fun `a tap before the session has been read still opens the sheet for a signed-in user`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(flagOn = true)
        // Only the flag collection has run; the session read in refreshUserState is still pending.
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        vm.onRateClicked()
        advanceUntilIdle()

        assertFalse(vm.state.value.signInRequested)
        assertTrue(vm.state.value.sheet != null)
    }

    @Test
    fun `a cooldown without a stored rating is shown as an error`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        coEvery { repo.createRating(1, any()) } throws
            RatingException(409, "You can rate this station once every 7 days.", "cooldown")

        vm.onRateClicked()
        vm.fillValidForm()
        vm.submit()
        advanceUntilIdle()

        assertEquals("You can rate this station once every 7 days.", vm.state.value.sheet!!.error)
    }

    @Test
    fun `terms must be ticked, and are accepted before the rating is sent`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(mine = MyRatingResponse(blockers = listOf("terms"), termsVersion = "2"))
        advanceUntilIdle()
        coEvery { repo.createRating(1, any()) } returns RatingSavedResponse(rating = ownRating())

        vm.onRateClicked()
        assertEquals(RateSheetMode.Form(existing = null, needsTerms = true, termsVersion = "2"), vm.state.value.sheetMode)
        vm.fillValidForm()
        assertFalse(vm.canSubmit())

        vm.setTermsAccepted(true)
        vm.submit()
        advanceUntilIdle()

        coVerifyOrder {
            repo.acceptTerms("2")
            repo.createRating(1, any())
        }
    }

    @Test
    fun `a double submit sends one rating`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        coEvery { repo.createRating(1, any()) } coAnswers {
            delay(1_000)
            RatingSavedResponse(rating = ownRating())
        }

        vm.onRateClicked()
        vm.fillValidForm()
        vm.submit()
        vm.submit()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.createRating(1, any()) }
    }

    @Test
    fun `an expired session says so and marks the user signed out`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        coEvery { repo.createRating(1, any()) } throws RatingException(401, null, null)
        coEvery { repo.isLoggedIn() } returns false

        vm.onRateClicked()
        vm.fillValidForm()
        vm.submit()
        advanceUntilIdle()

        assertEquals("Your session has expired. Sign in again to continue.", vm.state.value.sheet!!.error)
        assertFalse(vm.state.value.isLoggedIn)
    }

    @Test
    fun `comments from hidden reviewers are filtered out and can be shown again`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(
            comments = listOf(publicRating(1, "a"), publicRating(2, "b"), publicRating(3, "a"), publicRating(4, "c")),
            blocked = setOf("a"),
        )
        advanceUntilIdle()

        assertEquals(listOf(2, 4), vm.state.value.visibleComments.map { it.id })
        assertEquals(2, vm.state.value.hiddenComments.size)

        coEvery { repo.blockRatingAuthor(2) } returns "b"
        vm.hideReviewer(2)
        advanceUntilIdle()
        assertEquals(listOf(4), vm.state.value.visibleComments.map { it.id })

        vm.showHiddenReviewers()
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.unblockReviewer("a") }
        coVerify(exactly = 1) { repo.unblockReviewer("b") }
        assertEquals(listOf(1, 2, 3, 4), vm.state.value.visibleComments.map { it.id })
    }

    @Test
    fun `signed out, report and hide route to sign-in without calling the API`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(loggedIn = false, comments = listOf(publicRating(1, "a")))
        advanceUntilIdle()

        vm.startReport(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.signInRequested)
        assertNull(vm.state.value.reportingCommentId)
        vm.consumeSignInRequest()

        vm.hideReviewer(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.signInRequested)
        coVerify(exactly = 0) { repo.blockRatingAuthor(any()) }
    }

    @Test
    fun `reporting a comment sends the chosen reason and thanks the user`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = buildViewModel(comments = listOf(publicRating(1, "a")))
        advanceUntilIdle()

        vm.startReport(1)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.reportingCommentId)
        vm.report(1, "Spam or advertising")
        advanceUntilIdle()

        coVerify { repo.reportRating(1, "Spam or advertising") }
        assertEquals("Thanks. We'll review this comment.", vm.state.value.commentMessages[1])
        assertNull(vm.state.value.reportingCommentId)
    }

    @Test
    fun `show more appends the next page without duplicates`() = runTest(mainDispatcherRule.dispatcher) {
        val first = (1..20).map { publicRating(it, "r$it") }
        val vm = buildViewModel(comments = first)
        coEvery { repo.getStationRatings(1, 1) } returns PublicRatingsResponse(items = first, total = 22)
        coEvery { repo.getStationRatings(1, 2) } returns
            PublicRatingsResponse(items = listOf(publicRating(20, "r20"), publicRating(21, "r21"), publicRating(22, "r22")), total = 22, page = 2)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals((1..22).toList(), vm.state.value.comments.map { it.id })
        assertEquals(2, vm.state.value.page)
    }
}

class RatingsCopyTest {

    @Test
    fun `gap phrases and signed pence read naturally`() {
        assertEquals("3.5p more than listed", gapPhrase(3.5))
        assertEquals("2p less than listed", gapPhrase(-2.0))
        assertEquals("+4p", signedPence(4.0))
        assertEquals("−2.5p", signedPence(-2.5))
        assertEquals("Charged 3.5p more than listed", priceMatchLabel(false, 3.5))
        assertEquals("Price didn't match", priceMatchLabel(false, null))
        assertEquals("Price matched", priceMatchLabel(true, 3.5))
    }

    @Test
    fun `stars round to the nearest whole star`() {
        assertEquals("★★☆☆☆", starString(2.3))
        assertEquals("★★★☆☆", starString(2.5))
        assertEquals("★★★★★", starString(5.0))
    }

    @Test
    fun `dates are UK format in London time`() {
        assertEquals("1 Oct 2026", formatRatingDate("2026-09-30T23:30:00Z"))
    }

    @Test
    fun `saved message depends on where the comment landed`() {
        assertEquals("Thanks — your rating is live.", savedMessage(ownRating(commentStatus = "none")))
        assertEquals(
            "Thanks — your rating counts now. Your comment will appear once it's been checked.",
            savedMessage(ownRating(commentStatus = "held")),
        )
        assertEquals(
            "Your rating counts, but your comment wasn't published: Contains a phone number. You can edit it within 24 hours.",
            savedMessage(ownRating(commentStatus = "rejected").copy(moderationReason = "Contains a phone number")),
        )
    }

    @Test
    fun `own status line covers moderation and cooldown`() {
        val mine = MyRatingResponse(
            rating = ownRating(editableUntil = PAST, commentStatus = "hidden"),
            canRateAt = "2026-10-08T09:00:00Z",
        )
        assertEquals(
            "You rated this station on 1 Oct 2026. Your comment is hidden while it's reviewed. You can rate it again from 8 Oct 2026.",
            ownRatingStatus(mine),
        )
        assertNull(ownRatingStatus(MyRatingResponse()))
    }

    @Test
    fun `an edit window that has closed, or no edits left, is not editable`() {
        assertTrue(isEditable(ownRating()))
        assertFalse(isEditable(ownRating(editableUntil = PAST)))
        assertFalse(isEditable(ownRating(editsRemaining = 0)))
    }
}
