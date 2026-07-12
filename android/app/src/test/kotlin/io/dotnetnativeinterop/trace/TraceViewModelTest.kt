package io.dotnetnativeinterop.trace

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
 * Pins TraceViewModel's drain / request-filter / startUs-ordering / overflow-disclosure behavior with an
 * INJECTED fake drain source (no native lib) — parity with LogViewModelTest and the reason TraceViewModel now
 * takes a [TraceDrainSource]. Dispatchers.Unconfined + setMain make the drain's IO hop and the
 * viewModelScope launch run synchronously in the test call frame (same shape as LogViewModelTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class TraceViewModelTest {
    @Before public fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After public fun tearDown() { Dispatchers.resetMain() }

    private fun vm(json: String?): TraceViewModel = TraceViewModel(TraceDrainSource { json }, Dispatchers.Unconfined)

    // Two request ids; span startUs are deliberately OUT of order (r1's 30 before r1's 10) so the
    // visibleSpans sort-by-startUs is actually exercised, not accidentally satisfied by input order.
    private val twoRequests = """
        {"nowUs":100.0,"dropped":2,"capacity":512,"spans":[
          {"name":"c","startUs":30.0,"durUs":1.0,"requestId":"r1"},
          {"name":"a","startUs":10.0,"durUs":1.0,"requestId":"r1"},
          {"name":"b","startUs":20.0,"durUs":1.0,"requestId":"r2"}]}
    """.trimIndent()

    @Test public fun drainPopulatesSpansAndDisclosesOverflow() {
        val vm = vm(twoRequests)
        vm.drain()
        val s = vm.state.value
        assertNull(s.error)
        assertEquals(3, s.spans.size)
        assertEquals(3, s.lastDrainCount)
        assertEquals(2L, s.droppedTotal) // ring overflow disclosed + accumulated, never silent
        assertEquals(512, s.capacity)
        assertEquals(100.0, s.nowUs, 0.0)
        assertEquals(listOf("r1", "r2"), s.requestIds) // distinct, first-seen order
    }

    @Test public fun visibleSpansSortByStartUsRegardlessOfDrainOrder() {
        val vm = vm(twoRequests)
        vm.drain()
        // No selection: all spans, sorted by startUs (10, 20, 30) even though drained 30, 10, 20.
        assertEquals(listOf("a", "b", "c"), vm.state.value.visibleSpans.map { it.name })
    }

    @Test public fun selectRequestFiltersToThatRequestOnly() {
        val vm = vm(twoRequests)
        vm.drain()

        vm.selectRequest("r1")
        assertEquals(listOf("a", "c"), vm.state.value.visibleSpans.map { it.name }) // r1's two, startUs-sorted
        assertEquals("r1", vm.state.value.selectedRequestId)

        vm.selectRequest(null) // clearing the picker shows everything again
        assertEquals(3, vm.state.value.visibleSpans.size)
    }

    @Test public fun freshDrainResetsStaleSelection() {
        val vm = vm(twoRequests)
        vm.drain()
        vm.selectRequest("r2")
        assertEquals(1, vm.state.value.visibleSpans.size)

        vm.drain() // a new drain must clear the old selection, else the new spans would be hidden by a stale id
        assertNull(vm.state.value.selectedRequestId)
        assertEquals(3, vm.state.value.visibleSpans.size)
    }

    @Test public fun droppedAccumulatesAcrossDrains() {
        val vm = vm(twoRequests) // each drain reports dropped=2
        vm.drain()
        vm.drain()
        assertEquals("droppedTotal is cumulative across drains", 4L, vm.state.value.droppedTotal)
    }

    @Test public fun nullDrainSurfacesErrorNeverCrashes() {
        val vm = vm(null)
        vm.drain()
        assertNotNull("a NULL drain is surfaced honestly, not swallowed", vm.state.value.error)
        assertTrue(vm.state.value.spans.isEmpty())
    }

    @Test public fun malformedJsonSurfacesError() {
        val vm = vm("{ this is not valid json")
        vm.drain()
        assertNotNull("an undecodable drain payload surfaces an error, never crashes", vm.state.value.error)
    }
}
