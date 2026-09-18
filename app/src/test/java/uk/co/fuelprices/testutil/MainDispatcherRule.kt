package uk.co.fuelprices.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Swaps Dispatchers.Main for a [StandardTestDispatcher] so viewModelScope.launch{} coroutines are
 *  driven deterministically by the test (advanceUntilIdle(), etc.) instead of running on a real
 *  Android main-thread looper that doesn't exist in a JVM unit test. Exposes [dispatcher] so tests
 *  can pass the same scheduler into `runTest(dispatcher)` — otherwise Main and the test body's own
 *  coroutines run on two unrelated virtual clocks and `delay()`-based interleaving tests can't work. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
