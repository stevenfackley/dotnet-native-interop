package io.dotnetnativeinterop.log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins LogViewModel's drain / severity-filter / overflow-disclosure behavior with an INJECTED fake drain
 * source (no native lib) — parity with iOS LogModelsTests' view-model coverage, and the reason
 * LogViewModel now takes a [LogDrainSource]. Dispatchers.Unconfined + setMain make the drain's IO hop and
 * the viewModelScope launch run synchronously in the test call frame (same shape as AgentViewModelTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class LogViewModelTest {
    @Before public fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After public fun tearDown() { Dispatchers.resetMain() }

    private fun vm(json: String?): LogViewModel = LogViewModel(LogDrainSource { json }, Dispatchers.Unconfined)

    private val threeRecords = """
        {"nowUs":5.0,"dropped":1,"capacity":256,"records":[
          {"level":"Information","category":"c","message":"i","timestampUs":1},
          {"level":"Warning","category":"c","message":"w","timestampUs":2},
          {"level":"Error","category":"c","message":"e","timestampUs":3,"exception":"X: boom"}]}
    """.trimIndent()

    @Test public fun drainPopulatesRecordsAndDisclosesOverflow() {
        val vm = vm(threeRecords)
        vm.drain()
        val s = vm.state.value
        assertNull(s.error)
        assertEquals(3, s.records.size)
        assertEquals(3, s.lastDrainCount)
        assertEquals(1L, s.droppedTotal) // ring overflow disclosed + accumulated, never silent
        assertEquals(256, s.capacity)
        assertEquals(3, s.visibleRecords.size) // default filter (all) shows everything
        assertEquals("X: boom", s.records[2].exception)
    }

    @Test public fun severityFilterHidesLowerLevels() {
        val vm = vm(threeRecords)
        vm.drain()

        vm.selectFilter(LogFilter.ErrorsOnly)
        assertEquals(listOf("Error"), vm.state.value.visibleRecords.map { it.level })
        assertEquals(2, vm.state.value.hiddenByFilter)

        vm.selectFilter(LogFilter.WarnPlus)
        assertEquals(listOf("Warning", "Error"), vm.state.value.visibleRecords.map { it.level })
    }

    @Test public fun droppedAccumulatesAcrossDrains() {
        val vm = vm(threeRecords) // each drain reports dropped=1
        vm.drain()
        vm.drain()
        assertEquals("droppedTotal is cumulative across drains", 2L, vm.state.value.droppedTotal)
    }

    @Test public fun nullDrainSurfacesErrorNeverCrashes() {
        val vm = vm(null)
        vm.drain()
        assertNotNull("a NULL drain is surfaced honestly, not swallowed", vm.state.value.error)
        assertTrue(vm.state.value.records.isEmpty())
    }

    @Test public fun malformedJsonSurfacesError() {
        val vm = vm("{ this is not valid json")
        vm.drain()
        assertNotNull("an undecodable drain payload surfaces an error, never crashes", vm.state.value.error)
    }
}
