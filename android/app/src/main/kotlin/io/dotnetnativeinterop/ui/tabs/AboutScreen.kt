package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.dotnetnativeinterop.ui.PatternInfo
import io.dotnetnativeinterop.ui.PatternsJson
import kotlinx.serialization.json.Json

private val patternsJson = Json { ignoreUnknownKeys = true }

@Composable
internal fun AboutScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val patterns = remember {
        runCatching {
            val raw = context.assets.open("patterns.json").bufferedReader().readText()
            patternsJson.decodeFromString(PatternsJson.serializer(), raw).patterns
        }.getOrElse { emptyList() }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "About this demo",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = "One .NET 10 NativeAOT engine, embedded in this app and reached three ways — " +
                "in-process FFI, loopback HTTP, and an encrypted SQLCipher store. " +
                "The same C#/.NET payload runs natively on-device; the transports differ only " +
                "in how bytes cross the managed↔native boundary.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (patterns.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Transports",
                style = MaterialTheme.typography.titleMedium,
            )
            patterns.forEach { pattern ->
                PatternCard(pattern = pattern)
            }
        }
    }
}

@Composable
private fun PatternCard(pattern: PatternInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = pattern.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = pattern.transport,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = pattern.summary,
                style = MaterialTheme.typography.bodySmall,
            )
            if (pattern.limitations.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    text = "Limitations",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pattern.limitations.forEach { lim ->
                    Text(
                        text = "• $lim",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
