package io.dotnetnativeinterop.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.trace.SpanWaterfall
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader

/**
 * Foreman: the on-device tool-calling agent, made visible. Chat-style turns, a per-turn tool-call strip
 * that expands into the Analysis · Trace waterfall (reused, not duplicated), and an honest backend badge
 * read off the wire every turn — never a hardcoded assumption. Empty/loading/error/step-cap states per
 * the repo's no-silent-blank rule.
 */
@Composable
internal fun ForemanScreen(vm: AgentViewModel = viewModel(), modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        PanelHeader("Foreman · on-device tool-calling agent")

        if (s.turns.isEmpty()) {
            Text(
                "Ask Foreman a maintenance question. It can search the manuals, run an engine feature, or " +
                    "check live engine stats — every tool call is a real native operation you can inspect below.",
                color = Instrument.textSecondary,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(s.turns.size) { idx ->
                ForemanTurnCard(s.turns[idx], modifier = Modifier.fillMaxWidth())
            }
        }

        s.error?.let { ErrorBanner(it) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                label = { Text("Ask Foreman…") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !s.running,
            )
            if (s.running) {
                Button(onClick = { vm.cancel() }) { Text("Cancel") }
            } else {
                Button(onClick = { vm.ask() }) { Text("Ask") }
            }
        }
    }
}

@Composable
private fun ForemanTurnCard(turn: ForemanTurn, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    InstrumentCard(modifier = modifier) {
        PanelHeader("you asked")
        Text(turn.query, color = Instrument.textPrimary)

        PanelHeader("foreman")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                turn.answer.ifEmpty { if (turn.outcome == TurnOutcome.Streaming) "…" else "(no answer text)" },
                color = Instrument.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (turn.outcome == TurnOutcome.Streaming) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
        }

        TurnOutcomeBanner(turn.outcome)

        turn.backend?.let { badge ->
            Text(
                "backend: $badge",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Instrument.textTertiary,
            )
        }

        if (turn.outcome != TurnOutcome.Streaming) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${turn.toolSteps} tool call(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Instrument.textSecondary,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (turn.toolSpans.isNotEmpty()) {
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide trace" else "Show trace")
                    }
                }
            }
            if (expanded && turn.toolSpans.isNotEmpty()) {
                SpanWaterfall(turn.toolSpans)
                turn.toolSpans.filter { it.isToolCall() }.forEach { span ->
                    Text(
                        formatToolCall(span),
                        color = Instrument.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Tool args/result are bounded (args ≤ 256 chars, result ≤ 512 chars) and truncated " +
                        "with \"…(truncated)\" past that — the same dni_trace_drain ring, now carrying " +
                        "dni.agent.tool_args / dni.agent.tool_result span tags. A failed/unknown tool " +
                        "call still shows its real error here, never a blank result.",
                    color = Instrument.textTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TurnOutcomeBanner(outcome: TurnOutcome) {
    when (outcome) {
        TurnOutcome.Streaming -> Unit // no banner while streaming — the spinner above already says so
        TurnOutcome.Answered -> Text(
            "Answered",
            style = MaterialTheme.typography.labelSmall,
            color = Instrument.ok,
        )
        TurnOutcome.StepCapReached -> Text(
            "Stopped: step cap reached before a definitive answer. Shown honestly, not as a clean answer.",
            style = MaterialTheme.typography.labelSmall,
            color = Instrument.warn,
        )
        TurnOutcome.Error -> Text(
            "Turn errored — the text above is whatever the agent managed to say before failing.",
            style = MaterialTheme.typography.labelSmall,
            color = Instrument.fail,
        )
    }
}
