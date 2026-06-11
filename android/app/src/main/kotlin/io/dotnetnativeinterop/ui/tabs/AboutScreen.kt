package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.PatternInfo
import io.dotnetnativeinterop.ui.PatternsJson
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
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
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
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
            color = Instrument.textSecondary,
        )

        if (patterns.isNotEmpty()) {
            InstrumentCard {
                PanelHeader("Transports")
                patterns.forEach { pattern ->
                    PatternCard(pattern = pattern)
                }
            }
        }
    }
}

private fun transportColorForString(transport: String): Color = when {
    transport.contains("ffi", ignoreCase = true) -> Instrument.transportFfi
    transport.contains("http", ignoreCase = true) -> Instrument.transportHttp
    transport.contains("sqlite", ignoreCase = true) || transport.contains("sql", ignoreCase = true) -> Instrument.transportSqlite
    else -> Instrument.textSecondary
}

@Composable
private fun PatternCard(pattern: PatternInfo) {
    InstrumentCard {
        PanelHeader(pattern.name)
        Text(
            text = pattern.transport,
            style = MaterialTheme.typography.labelSmall,
            color = transportColorForString(pattern.transport),
        )
        Text(
            text = pattern.summary,
            style = MaterialTheme.typography.bodySmall,
            color = Instrument.textSecondary,
        )
        if (pattern.limitations.isNotEmpty()) {
            HorizontalDivider(color = Instrument.hairline)
            Text(
                text = "Limitations",
                style = MaterialTheme.typography.labelSmall,
                color = Instrument.textTertiary,
            )
            pattern.limitations.forEach { lim ->
                Text(
                    text = "• $lim",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.textSecondary,
                )
            }
        }
    }
}
