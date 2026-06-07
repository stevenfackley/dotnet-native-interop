package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult

/** Transport-agnostic catalog access (mirrors iOS FeatureService). Distinct from the streaming
 *  InferenceClient in transport/. Implementations are suspend + main-safe (do IO off the main thread). */
public interface FeatureCatalogService {
    public suspend fun descriptors(): List<FeatureDescriptor>
    public suspend fun run(id: String): FeatureResult
}
