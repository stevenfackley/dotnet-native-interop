package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal val catalogJson: Json = Json { ignoreUnknownKeys = true }

/** Catalog over the in-process C ABI (dni_features_json / dni_feature_run). */
public class FfiFeatureService : FeatureCatalogService {
    override suspend fun descriptors(): List<FeatureDescriptor> = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeFeaturesJson()
            ?: error("nativeFeaturesJson returned null")
        catalogJson.decodeFromString(raw)
    }

    override suspend fun run(id: String): FeatureResult = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeFeatureRun(id)
            ?: error("nativeFeatureRun($id) returned null")
        catalogJson.decodeFromString(raw)
    }
}
