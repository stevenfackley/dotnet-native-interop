package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the pingSeries failure policy (skip + disclose) shared with the iOS LatencyViewModel. */
public class LatencyViewModelTest {

    private fun vmWith(behavior: (Int) -> FeatureResult): LatencyViewModel {
        var n = 0
        val svc = object : FeatureCatalogService {
            override suspend fun descriptors(): List<FeatureDescriptor> = emptyList()
            override suspend fun run(id: String): FeatureResult = behavior(n++)
        }
        return LatencyViewModel(serviceFor = { _ -> svc })
    }

    private fun ok(): FeatureResult = FeatureResult("ping", "x", 0.1, true)

    @Test
    public fun allSuccessProducesEverySample(): Unit = runTest {
        val series = vmWith { ok() }.pingSeries(10, TransportKind.Ffi)
        assertEquals(10, series.samples.size)
        assertEquals(0, series.failures)
    }

    @Test
    public fun allFailEarlyStopsButDisclosesFullCount(): Unit = runTest {
        val series = vmWith { throw RuntimeException("dead transport") }.pingSeries(50, TransportKind.Ffi)
        assertEquals(0, series.samples.size)
        // Bails after 5 opening failures, but reports the full intended count as failed.
        assertEquals(50, series.failures)
    }

    @Test
    public fun failuresAfterFirstSampleAreTalliedNotEarlyStopped(): Unit = runTest {
        // First 4 succeed, the rest throw — once a sample exists, no early stop; every failure counts.
        val series = vmWith { i -> if (i < 4) ok() else throw RuntimeException("flaky") }
            .pingSeries(10, TransportKind.Ffi)
        assertEquals(4, series.samples.size)
        assertEquals(6, series.failures)
    }
}
