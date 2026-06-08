package io.dotnetnativeinterop.lab

import io.dotnetnativeinterop.feature.FeatureCatalogService
import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

public class LabViewModelTest {

    private class FakeService(private val tag: String) : FeatureCatalogService {
        override suspend fun descriptors(): List<FeatureDescriptor> = emptyList()
        override suspend fun run(id: String): FeatureResult = FeatureResult(id, "$tag:$id", 1.0, true)
    }

    @Test
    public fun renderUsesSelectedTransport(): Unit = runBlocking {
        val vm = LabViewModel(serviceFor = { t -> FakeService(t.name) })
        assertEquals("Ffi:cmd", vm.render("cmd")?.result)
        vm.setTransport(TransportKind.Sqlite)
        assertEquals(TransportKind.Sqlite, vm.transport.value)
        assertEquals("Sqlite:cmd", vm.render("cmd")?.result)
    }

    @Test
    public fun renderReturnsNullOnError(): Unit = runBlocking {
        val vm = LabViewModel(serviceFor = {
            object : FeatureCatalogService {
                override suspend fun descriptors() = emptyList<FeatureDescriptor>()
                override suspend fun run(id: String): FeatureResult = error("boom")
            }
        })
        assertEquals(null, vm.render("cmd"))
    }
}
