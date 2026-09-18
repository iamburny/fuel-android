package uk.co.fuelprices.ui.screens.favourites

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import uk.co.fuelprices.data.api.FavouriteDto
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.testutil.MainDispatcherRule
import uk.co.fuelprices.util.AppAnalytics
import uk.co.fuelprices.util.LocationHelper

/** Regression tests for the pendingNotifyToggleIds re-entrancy guard — mirrors
 *  NearbyViewModelTest/DetailViewModelTest's guard tests for the same pattern applied per-id. */
class FavouritesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: FuelRepository
    private lateinit var locationHelper: LocationHelper
    private lateinit var analytics: AppAnalytics

    private val fav1 = FavouriteDto(id = 1, stationId = 10, fuelType = "E10", notifyOnDrop = true)
    private val fav2 = FavouriteDto(id = 2, stationId = 20, fuelType = "E10", notifyOnDrop = true)

    private fun buildViewModel(): FavouritesViewModel {
        repo = mockk(relaxed = true)
        locationHelper = mockk(relaxed = true)
        analytics = mockk(relaxed = true)

        coEvery { repo.isLoggedIn() } returns true
        coEvery { repo.getFavourites() } returns listOf(fav1, fav2)
        coEvery { repo.getAlerts() } returns emptyList()

        return FavouritesViewModel(repo, locationHelper, analytics)
    }

    @Test
    fun `rapid double toggleNotify on the same favourite results in exactly one repository call`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        viewModel.load()
        advanceUntilIdle()
        coEvery { repo.updateFavourite(1, false) } returns fav1.copy(notifyOnDrop = false)

        viewModel.toggleNotify(fav1)
        viewModel.toggleNotify(fav1)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.updateFavourite(1, false) }
        assertFalse(viewModel.state.value.favourites.first { it.id == 1 }.notifyOnDrop)
    }

    @Test
    fun `an in-flight toggleNotify on one favourite does not block a concurrent toggleNotify on another`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        viewModel.load()
        advanceUntilIdle()
        coEvery { repo.updateFavourite(1, false) } coAnswers {
            delay(1_000)
            fav1.copy(notifyOnDrop = false)
        }
        coEvery { repo.updateFavourite(2, false) } returns fav2.copy(notifyOnDrop = false)

        viewModel.toggleNotify(fav1)
        testScheduler.runCurrent() // runs fav1's toggle up to its delay(), no further.

        viewModel.toggleNotify(fav2) // a different id — must proceed despite fav1 still pending.
        testScheduler.runCurrent()

        advanceUntilIdle() // resolves fav1's delay and its remaining write.

        coVerify(exactly = 1) { repo.updateFavourite(1, false) }
        coVerify(exactly = 1) { repo.updateFavourite(2, false) }
        assertFalse(viewModel.state.value.favourites.first { it.id == 1 }.notifyOnDrop)
        assertFalse(viewModel.state.value.favourites.first { it.id == 2 }.notifyOnDrop)
    }
}
