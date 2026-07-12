package io.dotnetnativeinterop.trust

import io.dotnetnativeinterop.feature.FeatureCatalogService
import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins TrustViewModel's posture decode and — the reason the PQ actuator is now injected — the honesty
 * invariant in setPqEnabled: the switch must NEVER read ON while the negotiation failed and the posture is
 * still plaintext. Uses fake catalogs + a fake pqToggle (no native pb server). Dispatchers.Unconfined +
 * setMain make init's refresh() and each viewModelScope.launch run synchronously in the test call frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class TrustViewModelTest {
    @Before public fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After public fun tearDown() { Dispatchers.resetMain() }

    /** A catalog whose run() returns fixed JSON, or throws when [fail] is set (to drive the failure paths). */
    private class FakeCatalog(private val json: String = "{}", private val fail: Boolean = false) : FeatureCatalogService {
        override suspend fun descriptors(): List<FeatureDescriptor> = emptyList()
        override suspend fun run(id: String): FeatureResult {
            if (fail) throw IllegalStateException("boom($id)")
            return FeatureResult(id = id, result = json, elapsedMs = 0.0, ok = true)
        }
    }

    // A binary transport reporting plaintext (encrypted=false) and no live PQ channel — the honest default.
    private val plaintextPosture = """
        {"transports":[
          {"transport":"ffi","inProcess":true,"encrypted":false,"wire":"in-process","detail":"C ABI"},
          {"transport":"binary","inProcess":false,"encrypted":false,"wire":"loopback","detail":"framed protobuf"}],
         "binaryPqChannel":null}
    """.trimIndent()

    @Test public fun initRefreshDecodesPosture() {
        val vm = TrustViewModel(FakeCatalog(plaintextPosture), FakeCatalog(plaintextPosture)) { /* no-op toggle */ }
        val s = vm.state.value
        assertNull(s.error)
        assertFalse(s.loading)
        assertNotNull(s.report)
        assertEquals(listOf("ffi", "binary"), s.report!!.transports.map { it.transport })
        assertNull("plaintext posture carries no live PQ channel", s.report!!.binaryPqChannel)
    }

    @Test public fun refreshSurfacesCatalogFailure() {
        val vm = TrustViewModel(FakeCatalog(fail = true), FakeCatalog()) { }
        val s = vm.state.value
        assertNotNull("a failed trust~posture is surfaced, not swallowed", s.error)
        assertNull(s.report)
        assertFalse(s.loading)
    }

    @Test public fun setPqEnabledSuccessInvokesActuatorAndHoldsToggle() {
        val toggleCalls = mutableListOf<Boolean>()
        val vm = TrustViewModel(FakeCatalog(plaintextPosture), FakeCatalog(plaintextPosture)) { toggleCalls.add(it) }

        vm.setPqEnabled(true)

        assertEquals("the PQ actuator is driven with the requested state", listOf(true), toggleCalls)
        val s = vm.state.value
        assertTrue("a successful negotiation holds the switch ON", s.pqRequested)
        assertFalse(s.negotiating)
        assertNull(s.error)
    }

    @Test public fun setPqEnabledRevertsToggleWhenNegotiationFails() {
        // catalog (refresh) succeeds so we isolate the failure to the PQ handshake ping on the binary catalog.
        val vm = TrustViewModel(FakeCatalog(plaintextPosture), FakeCatalog(fail = true)) { /* actuator ok */ }

        vm.setPqEnabled(true)

        val s = vm.state.value
        assertFalse("the switch must NOT read ON when the handshake failed (posture is still plaintext)", s.pqRequested)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("PQ negotiation failed"))
        assertFalse(s.negotiating)
    }
}
