package io.dotnetnativeinterop.lab

import androidx.lifecycle.ViewModel
import io.dotnetnativeinterop.feature.FeatureCatalogService
import io.dotnetnativeinterop.feature.defaultServiceFor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Backs the Lab tab: one shared selected transport, and a single render() that runs a parametric
 *  ShowcaseCommand id over the existing FeatureCatalogService.run path. */
public class LabViewModel(
    private val serviceFor: (TransportKind) -> FeatureCatalogService = ::defaultServiceFor,
) : ViewModel() {

    private val _transport = MutableStateFlow(TransportKind.Ffi)
    public val transport: StateFlow<TransportKind> = _transport.asStateFlow()

    public fun setTransport(t: TransportKind) { _transport.value = t }

    /** Runs one command over the currently selected transport; null on error (surfaced as a fallback). */
    public suspend fun render(command: String): FeatureResult? =
        runCatching { serviceFor(_transport.value).run(command) }.getOrNull()
}
