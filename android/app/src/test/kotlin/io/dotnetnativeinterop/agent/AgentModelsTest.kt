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
}
