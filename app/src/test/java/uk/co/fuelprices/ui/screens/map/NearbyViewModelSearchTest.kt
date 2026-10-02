package uk.co.fuelprices.ui.screens.map

import android.location.Location
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.co.fuelprices.data.api.StationListResponse
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferences
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.AppAnalytics
import uk.co.fuelprices.util.DefaultLocation
import uk.co.fuelprices.util.LocationHelper

/**
 * Guards the one rule that can't be enforced in the data layer: search must send the user's
 * *actual* position or nothing at all.
 *
 * [NearbyUiState.userLat]/[NearbyUiState.userLng] are not a reliable answer to "where is the
 * user" — `loadNearby()` fills them with [DefaultLocation] (a hardcoded Oxford point) so the map
 * has somewhere to point when location is unavailable. Passing those straight through would send
 * Oxford as a real fix for every user who denied the location permission, quietly ranking a
 * Glasgow user's search results by distance from Oxford and printing a bogus mileage under each
 * row. [NearbyUiState.hasGpsFix] is what distinguishes the two, and these tests are what stop it
 * being "simplified" away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyViewModelSearchTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repo: FuelRepository
    private lateinit var locationHelper: LocationHelper
    private lateinit var preferencesStore: UserPreferencesStore
    private lateinit var analytics: AppAnalytics

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        repo = mockk(relaxed = true)
        locationHelper = mockk(relaxed = true)
        preferencesStore = mockk(relaxed = true)
        analytics = mockk(relaxed = true)

        every { repo.apiFailureCount } returns MutableStateFlow(0)
        coEvery { repo.getNearbyStations(any(), any(), any(), any()) } returns emptyResponse()
        coEvery { repo.searchStations(any(), any(), any()) } returns emptyResponse()
        coEvery { preferencesStore.get() } returns UserPreferences()
        // Never emits — the ViewModel's init collects this indefinitely.
        every { locationHelper.permissionGranted } returns MutableSharedFlow()
        every { locationHelper.locationUpdates() } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sends no coordinates when location is unavailable, despite the Oxford map fallback`() =
        runTest(dispatcher) {
            every { locationHelper.hasPermission() } returns false
            coEvery { locationHelper.getCurrentLocation() } returns null

            val vm = newViewModel()
            advanceUntilIdle()

            // The map has been anchored to the fallback — this is the exact state that used to
            // leak Oxford into the search request.
            assertEquals(DefaultLocation.LAT, vm.state.value.userLat)
            assertEquals(DefaultLocation.LNG, vm.state.value.userLng)
            assertFalse("a fallback centre is not a GPS fix", vm.state.value.hasGpsFix)

            vm.setSearchQuery("tesco")
            advanceUntilIdle()

            coVerify { repo.searchStations("tesco", null, null) }
        }

    @Test
    fun `sends the real fix when there is one`() = runTest(dispatcher) {
        every { locationHelper.hasPermission() } returns true
        coEvery { locationHelper.getCurrentLocation() } returns location(55.8642, -4.2518)

        val vm = newViewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.hasGpsFix)

        vm.setSearchQuery("tesco")
        advanceUntilIdle()

        coVerify { repo.searchStations("tesco", 55.8642, -4.2518) }
    }

    /**
     * The invariant that ties the two above together: `hasGpsFix == true` must mean
     * `userLat`/`userLng` currently hold a *real* position.
     *
     * `hasGpsFix` is sticky (a momentary GPS failure shouldn't unlearn a position we had), so the
     * coordinates have to be sticky with it. They weren't: `loadNearby()` overwrote them with the
     * Oxford fallback on every run while leaving the flag true. `getCurrentLocation()` returning
     * null after a good fix is routine — indoors, on an emulator, after a Play Services hiccup —
     * so a pull-to-refresh or a radius change was enough to silently re-rank a Glasgow user's next
     * search around Oxford and label every row ~300 mi, with no error and a plausible-looking
     * result.
     */
    @Test
    fun `keeps a known fix rather than letting a later failed read replace it with the fallback`() =
        runTest(dispatcher) {
            every { locationHelper.hasPermission() } returns true
            coEvery { locationHelper.getCurrentLocation() } returns location(55.8642, -4.2518)

            val vm = newViewModel()
            advanceUntilIdle()
            assertTrue(vm.state.value.hasGpsFix)

            // GPS goes dark, then anything that reloads — refresh, radius change, clearing the
            // search box back under two characters.
            coEvery { locationHelper.getCurrentLocation() } returns null
            vm.refresh()
            advanceUntilIdle()

            assertTrue("a failed re-read must not unlearn the fix", vm.state.value.hasGpsFix)
            assertEquals(
                "the flag still says 'real fix', so the value must still be the real fix",
                55.8642,
                vm.state.value.userLat,
            )
            assertEquals(-4.2518, vm.state.value.userLng)

            vm.setSearchQuery("tesco")
            advanceUntilIdle()

            coVerify { repo.searchStations("tesco", 55.8642, -4.2518) }
        }

    /** The 2-character minimum and the 400ms debounce are shared with the web and iOS clients, so
     *  they're pinned here rather than left to survive by convention. */
    @Test
    fun `does not search below the two character minimum`() = runTest(dispatcher) {
        every { locationHelper.hasPermission() } returns false
        coEvery { locationHelper.getCurrentLocation() } returns null

        val vm = newViewModel()
        advanceUntilIdle()

        vm.setSearchQuery("t")
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.searchStations(any(), any(), any()) }
    }

    @Test
    fun `debounces so only the settled query is searched`() = runTest(dispatcher) {
        every { locationHelper.hasPermission() } returns false
        coEvery { locationHelper.getCurrentLocation() } returns null

        val vm = newViewModel()
        advanceUntilIdle()

        vm.setSearchQuery("te")
        vm.setSearchQuery("tes")
        vm.setSearchQuery("tesco")
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.searchStations(any(), any(), any()) }
        coVerify { repo.searchStations("tesco", null, null) }
    }

    // ── Helpers ──────────────────────────────────────────

    private fun newViewModel() =
        NearbyViewModel(repo, locationHelper, preferencesStore, analytics)

    private fun emptyResponse() = StationListResponse(count = 0, stations = emptyList())

    private fun location(lat: Double, lng: Double): Location = mockk {
        every { latitude } returns lat
        every { longitude } returns lng
    }
}
