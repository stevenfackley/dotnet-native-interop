package io.dotnetnativeinterop

import io.dotnetnativeinterop.boundary.BoundaryViewModel
import io.dotnetnativeinterop.boundary.FakeBoundaryService
import io.dotnetnativeinterop.model.BoundaryPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** viewModelScope uses Dispatchers.Main, absent in a plain JVM test — install an eager test dispatcher. */
public class BoundaryViewModelTest {
    @Before public fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After public fun tearDown() { Dispatchers.resetMain() }

    @Test
    public fun echoPopulatesEchoAndTiming(): Unit = runTest {
        val vm = BoundaryViewModel(FakeBoundaryService())
        vm.selectPreset(BoundaryPreset.Echo)
        vm.setInput("Hello")
        vm.run()
        assertEquals("Hello", vm.state.value.echo?.decoded)
        assertTrue(vm.state.value.timing.totalUs > 0.0)
        assertNull(vm.state.value.error)
    }

    @Test
    public fun throwIsContained(): Unit = runTest {
        val vm = BoundaryViewModel(FakeBoundaryService())
        vm.selectPreset(BoundaryPreset.Exception)
        vm.run()
        assertEquals(true, vm.state.value.thrown?.caught)
        assertEquals(-5, vm.state.value.thrown?.status)
    }
}
