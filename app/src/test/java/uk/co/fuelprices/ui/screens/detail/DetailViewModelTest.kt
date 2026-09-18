package uk.co.fuelprices.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uk.co.fuelprices.data.api.AveragesResponse
import uk.co.fuelprices.data.api.FavouriteDto
import uk.co.fuelprices.data.api.PriceHistoryResponse
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferences
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.testutil.MainDispatcherRule
import uk.co.fuelprices.testutil.testPriceDto
import uk.co.fuelprices.testutil.testStationDto
import uk.co.fuelprices.util.AppAnalytics
import uk.co.fuelprices.util.LocationHelper

/** Regression tests for the pendingFavouriteToggle re-entrancy guard (fixed in commit 90013f8) —
 *  see NearbyViewModelTest for the equivalent guard on that screen. Two distinct scenarios: the
 *  first test only proves the synchronous boolean check rejects a same-tick second call (no
 *  coroutine has run yet at that point); the second is the one that actually exercises a toggle
 *  genuinely suspended mid-flight racing a concurrent state change. */
class DetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: FuelRepository
    private lateinit var locationHelper: LocationHelper
    private lateinit var preferencesStore: UserPreferencesStore
    private lateinit var analytics: AppAnalytics

    private fun buildViewModel(): DetailViewModel {
        repo = mockk(relaxed = true)
        locationHelper = mockk(relaxed = true)
        preferencesStore = mockk(relaxed = true)
        analytics = mockk(relaxed = true)

        coEvery { repo.getStation(1) } returns testStationDto(
            id = 1,
            name = "Station",
            prices = listOf(testPriceDto("E10", 140.0), testPriceDto("E5", 150.0)),
        )
        coEvery { repo.getPriceHistory(1, "E10") } returns PriceHistoryResponse(1, "Station", "E10", emptyList())
        coEvery { repo.getPriceHistory(1, "E5") } returns PriceHistoryResponse(1, "Station", "E5", emptyList())
        coEvery { repo.getFavourites() } returns emptyList()
        coEvery { repo.getNationalAverages() } returns AveragesResponse(emptyList(), "", "")
        coEvery { preferencesStore.get() } returns UserPreferences(fuelType = "E10")

        val savedState = SavedStateHandle(mapOf("stationId" to 1))
        return DetailViewModel(savedState, repo, locationHelper, preferencesStore, analytics)
    }

    @Test
    fun `rapid double toggleFavourite is rejected synchronously by the pending guard, resulting in exactly one repository call`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        coEvery { repo.addFavourite(any(), any()) } returns FavouriteDto(99, 1, "E10", true)

        viewModel.toggleFavourite()
        viewModel.toggleFavourite()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.addFavourite(any(), any()) }
        assertEquals(true, viewModel.state.value.isFavourite)
        assertEquals(99, viewModel.state.value.favouriteId)
    }

    @Test
    fun `a concurrent setFuelType while toggleFavourite is in flight is not clobbered`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        coEvery { repo.addFavourite(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(1_000)
            FavouriteDto(99, 1, "E10", true)
        }

        // Let init{}'s load() finish so selectedFuelType starts out as "E10".
        advanceUntilIdle()
        assertEquals("E10", viewModel.state.value.selectedFuelType)

        viewModel.toggleFavourite()
        testScheduler.runCurrent() // starts the toggle coroutine up to the delay(), no further.

        viewModel.setFuelType("E5")
        advanceUntilIdle() // resolves the delay and both coroutines' remaining work.

        val finalState = viewModel.state.value
        assertEquals("the concurrent fuel-type change must survive", "E5", finalState.selectedFuelType)
        assertTrue("the in-flight favourite toggle must still land", finalState.isFavourite)
        assertEquals(99, finalState.favouriteId)
    }
}
