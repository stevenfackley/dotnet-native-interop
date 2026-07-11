package io.dotnetnativeinterop.agent

import io.dotnetnativeinterop.trace.TraceDrain
import io.dotnetnativeinterop.trace.TraceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the client-side half of dni_agent_session_start's completion contract (abi/dni.h): detection is
 * on the leading 0x01 control byte, NEVER on the readable "dni.agent.status" tag text, and the three
 * ForemanStopReason values must never be confused with one another.
 */
public class AgentModelsTest {

    // Built from the SAME CONTROL_BYTE/STATUS_TAG production constants (not a hand-rolled duplicate) so
    // this test can never drift from what parseAgentFragment actually detects.
    private fun statusFragment(stopReason: String, toolSteps: Int, backend: String): String =
        "$CONTROL_BYTE$STATUS_TAG{\"stopReason\":\"$stopReason\",\"toolSteps\":$toolSteps,\"backend\":\"$backend\"}"

    @Test
    public fun plainTextFragmentIsAnswer() {
        val fragment = parseAgentFragment("the compressor is overheating; check the condenser fan.")
        assertTrue(fragment is AgentFragment.Answer)
        assertEquals("the compressor is overheating; check the condenser fan.", (fragment as AgentFragment.Answer).text)
    }

    @Test
    public fun fragmentContainingTheReadableTagButNotTheControlByteIsStillAnswer() {
        // An on-device LLM could legitimately stream this literal text while answering a question ABOUT
        // the marker — dni.h is explicit that the tag text alone must never be treated as the signal.
        val fragment = parseAgentFragment("the status marker is called dni.agent.status internally")
        assertTrue(fragment is AgentFragment.Answer)
    }

    @Test
    public fun controlByteFragmentParsesAnsweredStatus() {
        val fragment = parseAgentFragment(statusFragment("Answered", 1, "scripted routing — no on-device LLM present"))
        assertTrue(fragment is AgentFragment.Status)
        val status = (fragment as AgentFragment.Status).status
        assertEquals(AgentStopReason.Answered, status.stopReason)
        assertEquals(1, status.toolSteps)
        assertEquals("scripted routing — no on-device LLM present", status.backend)
    }

    @Test
    public fun doubledControlBytePrefixStillParses() {
        // Ground-truth regression: the LIVE engine writes the marker with a REPEATED 0x01 prefix
        // (0x01 0x01 dni.agent.status…), captured from a real iOS turn on the simulator. A fixed-length
        // skip lands a byte short and yields invalid JSON; the tag-anchored parse must still decode it.
        val doubled = "$CONTROL_BYTE$CONTROL_BYTE$STATUS_TAG" +
            "{\"stopReason\":\"Answered\",\"toolSteps\":0,\"backend\":\"scripted routing — no on-device LLM present\"}"
        val fragment = parseAgentFragment(doubled)
        assertTrue(fragment is AgentFragment.Status)
        val status = (fragment as AgentFragment.Status).status
        assertEquals(AgentStopReason.Answered, status.stopReason)
        assertEquals(0, status.toolSteps)
        assertEquals("scripted routing — no on-device LLM present", status.backend)
    }

    @Test(expected = IllegalArgumentException::class)
    public fun controlByteWithoutTagThrows() {
        // A leading 0x01 with no readable tag is a malformed marker → an honest throw (the service maps it
        // to an Error status), never a mis-parse.
        parseAgentFragment("$CONTROL_BYTE{\"stopReason\":\"Answered\"}")
    }

    @Test
    public fun controlByteFragmentParsesStepCapReachedStatus() {
        val fragment = parseAgentFragment(statusFragment("StepCapReached", 5, "scripted (harness)"))
        val status = (fragment as AgentFragment.Status).status
        assertEquals(AgentStopReason.StepCapReached, status.stopReason)
        assertEquals(5, status.toolSteps)
    }

    @Test
    public fun controlByteFragmentParsesErrorStatus() {
        val fragment = parseAgentFragment(statusFragment("Error", 0, "on-device LLM (Llama-3.2-1B, grammar-constrained)"))
        val status = (fragment as AgentFragment.Status).status
        assertEquals(AgentStopReason.Error, status.stopReason)
        assertEquals("on-device LLM (Llama-3.2-1B, grammar-constrained)", status.backend)
    }

    @Test
    public fun malformedStatusFragmentThrows() {
        // A 0x01-prefixed fragment with a garbage JSON payload throws — this is the exact failure the
        // FfiAgentService.onToken guard contains (a throw there would be swallowed by the JNI shim and
        // leave the turn stuck at Streaming). Pinning the throw documents why that guard exists.
        var threw = false
        try {
            parseAgentFragment("$CONTROL_BYTE$STATUS_TAG{not valid json")
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    public fun theThreeStopReasonsAreMutuallyDistinguishable() {
        val answered = (parseAgentFragment(statusFragment("Answered", 1, "x")) as AgentFragment.Status).status.stopReason
        val capped = (parseAgentFragment(statusFragment("StepCapReached", 5, "x")) as AgentFragment.Status).status.stopReason
        val errored = (parseAgentFragment(statusFragment("Error", 0, "x")) as AgentFragment.Status).status.stopReason
        assertTrue(answered != capped && capped != errored && answered != errored)
    }

    @Test
    public fun turnSpansFromFiltersToTheLastExpectedToolSpansPlusTheTurnSpan() {
        val drain = TraceDrain(
            nowUs = 100.0,
            dropped = 0,
            capacity = 512,
            spans = listOf(
                TraceSpan("rag.retrieve", 0.0, 5.0, null, null), // unrelated span from a prior screen
                TraceSpan("agent.turn", 1.0, 40.0, null, null),
                TraceSpan("agent.tool.search_manuals", 2.0, 10.0, null, null),
                TraceSpan("agent.tool.engine_stats", 20.0, 3.0, null, null),
            ),
        )
        val spans = turnSpansFrom(drain, expectedToolSteps = 2)
        assertEquals(3, spans.size) // agent.turn + the 2 tool spans, not the unrelated rag. span
        assertEquals(listOf("agent.turn", "agent.tool.search_manuals", "agent.tool.engine_stats"), spans.map { it.name })
    }

    @Test
    public fun turnSpansFromWithZeroToolStepsReturnsOnlyTheTurnSpan() {
        val drain = TraceDrain(0.0, 0, 512, listOf(TraceSpan("agent.turn", 0.0, 10.0, null, null)))
        val spans = turnSpansFrom(drain, expectedToolSteps = 0)
        assertEquals(listOf("agent.turn"), spans.map { it.name })
    }

    @Test
    public fun turnSpansFromNullDrainReturnsEmpty() {
        assertEquals(emptyList<TraceSpan>(), turnSpansFrom(null, expectedToolSteps = 3))
    }

    @Test
    public fun turnSpansFromPreservesToolArgsAndToolResultTags() {
        // Proves the tag-carrying fields survive turnSpansFrom's filter/sort — the strip reads them off
        // the SAME spans this function slices, not a separately-fetched payload.
        val drain = TraceDrain(
            nowUs = 100.0,
            dropped = 0,
            capacity = 512,
            spans = listOf(
                TraceSpan("agent.turn", 1.0, 40.0, null, null),
                TraceSpan(
                    "agent.tool.search_manuals", 2.0, 10.0, null, null,
                    toolArgs = "{\"query\":\"E3\"}", toolResult = "{\"snippets\":[\"a\",\"b\",\"c\"]}",
                ),
            ),
        )
        val spans = turnSpansFrom(drain, expectedToolSteps = 1)
        val toolSpan = spans.first { it.name == "agent.tool.search_manuals" }
        assertEquals("{\"query\":\"E3\"}", toolSpan.toolArgs)
        assertEquals("{\"snippets\":[\"a\",\"b\",\"c\"]}", toolSpan.toolResult)
    }

    @Test
    public fun isToolCallTrueForAgentToolSpansFalseForAgentTurn() {
        assertTrue(TraceSpan("agent.tool.engine_stats", 0.0, 1.0, null, null).isToolCall())
        assertTrue(!TraceSpan("agent.turn", 0.0, 1.0, null, null).isToolCall())
        assertTrue(!TraceSpan("rag.retrieve", 0.0, 1.0, null, null).isToolCall())
    }

    @Test
    public fun formatToolCallRendersRealArgsAndResult() {
        val span = TraceSpan(
            "agent.tool.run_feature", 0.0, 1.0, null, null,
            toolArgs = "{\"id\":\"ping\"}", toolResult = "{\"ok\":true,\"result\":\"pong\"}",
        )
        assertEquals(
            "run_feature({\"id\":\"ping\"}) -> {\"ok\":true,\"result\":\"pong\"}",
            formatToolCall(span),
        )
    }

    @Test
    public fun formatToolCallShowsTheTruncationMarkerWhenTheEngineTruncated() {
        // The engine (ForemanAgent.Bound) appends this exact suffix — the client just renders it, never
        // hides that a result was clamped.
        val span = TraceSpan(
            "agent.tool.search_manuals", 0.0, 1.0, null, null,
            toolArgs = "{\"query\":\"E3\"}", toolResult = "z".repeat(512) + "…(truncated)",
        )
        assertTrue(formatToolCall(span).contains("…(truncated)"))
    }

    @Test
    public fun formatToolCallFallsBackHonestlyWhenTagsAreAbsent() {
        // A span from an engine build that predates this feature carries null tags — the strip must
        // never render a blank/misleading call, and must never throw.
        val span = TraceSpan("agent.tool.engine_stats", 0.0, 1.0, null, null)
        val formatted = formatToolCall(span)
        assertTrue(formatted.contains("not captured"))
        assertTrue(formatted.startsWith("engine_stats("))
    }
}
