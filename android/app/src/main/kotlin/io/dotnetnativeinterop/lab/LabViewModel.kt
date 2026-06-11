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

    /** Most recent render failure, with command + transport context. Cleared on the next success. */
    private val _lastError = MutableStateFlow<String?>(null)
    public val lastError: StateFlow<String?> = _lastError.asStateFlow()

    public fun setTransport(t: TransportKind) { _transport.value = t }

    /** Runs one command over the currently selected transport; null on error. The failure is never
     *  silent: [lastError] carries the context for the Lab screens to display. The message strips
     *  per-frame parameters (after '~') so retry ticks don't republish a new string every frame. */
    public suspend fun render(command: String): FeatureResult? =
        runCatching { serviceFor(_transport.value).run(command) }
            .onSuccess { _lastError.value = null }
            .onFailure { e ->
                val name = command.substringBefore('~')
                _lastError.value =
                    "‘$name’ over ${_transport.value.displayName} failed: ${e.message ?: e.javaClass.simpleName}"
            }
            .getOrNull()
}
