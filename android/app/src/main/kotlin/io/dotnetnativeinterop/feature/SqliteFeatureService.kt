package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Catalog over the encrypted SQLCipher store (dni_sqlite_features / dni_sqlite_run). */
public class SqliteFeatureService : FeatureCatalogService {
    override suspend fun descriptors(): List<FeatureDescriptor> = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeSqliteFeatures()
            ?: error("nativeSqliteFeatures returned null")
        catalogJson.decodeFromString(raw)
    }

    override suspend fun run(id: String): FeatureResult = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeSqliteRun(id)
            ?: error("nativeSqliteRun($id) returned null")
        catalogJson.decodeFromString(raw)
    }
}
