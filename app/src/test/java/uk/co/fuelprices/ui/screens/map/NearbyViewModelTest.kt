package uk.co.fuelprices.ui.screens.map

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uk.co.fuelprices.data.api.FavouriteDto
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.api.StationListResponse
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferences
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.testutil.MainDispatcherRule
import uk.co.fuelprices.util.AppAnalytics
import uk.co.fuelprices.util.LocationHelper

/** Regression tests for the pendingFavouriteToggles re-entrancy guard — see DetailViewModelTest
 *  for the single-station equivalent on that screen. */
class NearbyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: FuelRepository
    private lateinit var locationHelper: LocationHelper
    private lateinit var preferencesStore: UserPreferencesStore
    private lateinit var analytics: AppAnalytics

    private fun buildViewModel(): NearbyViewModel {
        repo = mockk(relaxed = true)
        locationHelper = mockk(relaxed = true)
        preferencesStore = mockk(relaxed = true)
        analytics = mockk(relaxed = true)

        every { repo.apiFailureCount } returns MutableStateFlow(0)
        every { locationHelper.hasPermission() } returns true
        // MockK's relaxed auto-answering can't synthesize a working SharedFlow/Flow instance for
        // these (it throws KotlinNothingValueException on first collection) — stub real, inert
        // ones instead: permissionGranted never emits (matches "permission already granted, no
        // further change"), locationUpdates() completes immediately (matches "no GPS fixes").
        every { locationHelper.permissionGranted } returns MutableSharedFlow()
        every { locationHelper.locationUpdates() } returns emptyFlow()
        coEvery { preferencesStore.get() } returns UserPreferences(fuelType = "E10")
        coEvery { repo.getNearbyStations(any(), any(), any(), any()) } returns StationListResponse(0, emptyList())
        coEvery { repo.isLoggedIn() } returns true
        coEvery { repo.getFavourites() } returns emptyList()

        return NearbyViewModel(repo, locationHelper, preferencesStore, analytics)
    }

    private fun station(id: Int) = StationDto(
        id = id,
        govId = "gov-$id",
        name = "Station $id",
        latitude = 51.5,
        longitude = -0.1,
    )

    @Test
    fun `rapid double toggleFavourite on the same station results in exactly one repository call`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val station = station(7)
        coEvery { repo.addFavourite(any(), any()) } returns FavouriteDto(77, 7, "E10", true)

        viewModel.toggleFavourite(station)
        viewModel.toggleFavourite(station)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.addFavourite(any(), any()) }
        assertEquals(77, viewModel.state.value.favouriteStationIds?.get(7))
    }

    @Test
    fun `an in-flight toggle and a concurrent refreshFavourites both land without clobbering each other`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val station = station(7)
        coEvery { repo.getFavourites() } returns listOf(FavouriteDto(50, 5, "E10", true))
        coEvery { repo.addFavourite(any(), any()) } coAnswers {
            delay(1_000)
            FavouriteDto(77, 7, "E10", true)
        }

        viewModel.toggleFavourite(station)
        testScheduler.runCurrent() // runs the toggle up to addFavourite's delay(), no further.

        viewModel.refreshFavourites()
        testScheduler.runCurrent() // refreshFavourites has no delay, so it completes fully here —
        // while the toggle above is still suspended mid-flight.

        advanceUntilIdle() // resolves the toggle's delay and its remaining write.

        assertEquals(
            "both the refresh's and the in-flight toggle's results must be present",
            mapOf(5 to 50, 7 to 77),
            viewModel.state.value.favouriteStationIds,
        )
    }
}
