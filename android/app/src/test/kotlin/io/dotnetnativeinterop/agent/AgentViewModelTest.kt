package io.dotnetnativeinterop.agent

import io.dotnetnativeinterop.trace.TraceDrain
import io.dotnetnativeinterop.trace.TraceSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins AgentViewModel's honesty contract: a turn's [TurnOutcome] must always match the wire's stopReason
 * (Answered/StepCapReached/Error never conflated), the backend badge must be whatever the wire says (never
 * hardcoded), and a transport-level failure with no status fragment at all must still land as Error.
 */
public class AgentViewModelTest {
    @Before public fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After public fun tearDown() { Dispatchers.resetMain() }

    private fun scriptedService(fragments: List<AgentFragment>): AgentService = object : AgentService {
        override fun run(query: String): Flow<AgentFragment> = flow { fragments.forEach { emit(it) } }
    }

    private fun failingService(message: String): AgentService = object : AgentService {
        override fun run(query: String): Flow<AgentFragment> = flow {
            throw IllegalStateException(message)
        }
    }

    private val noSpansReader = AgentTraceReader { null }

    // Dispatchers.Unconfined (not the real Dispatchers.IO default) so the trace-drain hop inside
    // applyStatus runs synchronously in this test's call frame instead of a real background thread.
    private fun vmUnder(service: AgentService, traceReader: AgentTraceReader = noSpansReader): AgentViewModel =
        AgentViewModel(service, traceReader, Dispatchers.Unconfined)

    @Test
    public fun answeredTurnAccumulatesTextAndRecordsTheBackendFromTheWire(): Unit = runTest {
        val vm = vmUnder(
            scriptedService(
                listOf(
                    AgentFragment.Answer("check the "),
                    AgentFragment.Answer("condenser fan."),
                    AgentFragment.Status(AgentTurnStatus(AgentStopReason.Answered, 1, "scripted routing — no on-device LLM present")),
                ),
            ),
        )
        vm.setQuery("compressor overheating")
        vm.ask()

        val turn = vm.state.value.turns.single()
        assertEquals("check the condenser fan.", turn.answer)
        assertEquals(TurnOutcome.Answered, turn.outcome)
        assertEquals(1, turn.toolSteps)
        assertEquals("scripted routing — no on-device LLM present", turn.backend)
        assertTrue(!vm.state.value.running)
        assertNull(vm.state.value.error)
    }

    @Test
    public fun stepCapReachedIsNeverPresentedAsAnsweredEvenWithAnswerText(): Unit = runTest {
        val vm = vmUnder(
            scriptedService(
                listOf(
                    AgentFragment.Answer("capped answer"),
                    AgentFragment.Status(AgentTurnStatus(AgentStopReason.StepCapReached, 5, "scripted (harness)")),
                ),
            ),
        )
        vm.setQuery("loop forever")
        vm.ask()

        val turn = vm.state.value.turns.single()
        assertEquals(TurnOutcome.StepCapReached, turn.outcome)
        assertTrue(turn.outcome != TurnOutcome.Answered)
        assertEquals(5, turn.toolSteps)
    }

    @Test
    public fun errorStatusFragmentIsRecordedAsError(): Unit = runTest {
        val vm = vmUnder(
            scriptedService(
                listOf(
                    AgentFragment.Answer("(agent error: InvalidOperationException)"),
                    AgentFragment.Status(AgentTurnStatus(AgentStopReason.Error, 0, "on-device LLM (grammar-constrained)")),
                ),
            ),
        )
        vm.setQuery("q")
        vm.ask()

        val turn = vm.state.value.turns.single()
        assertEquals(TurnOutcome.Error, turn.outcome)
        assertTrue(turn.answer.contains("agent error"))
    }

    @Test
    public fun transportFailureWithNoStatusFragmentIsStillHonestlyMarkedError(): Unit = runTest {
        val vm = vmUnder(failingService("nativeAgentSessionStart failed: status -2"))
        vm.setQuery("q")
        vm.ask()

        val turn = vm.state.value.turns.single()
        assertEquals(TurnOutcome.Error, turn.outcome)
        assertTrue(!vm.state.value.running)
        assertTrue(vm.state.value.error?.contains("Foreman turn failed") == true)
    }

    @Test
    public fun turnEndingWithNoStatusFragmentIsMarkedErrorNotLeftStreaming(): Unit = runTest {
        // Stream completes normally but no terminal status fragment ever arrives (the stuck-spinner root):
        // the turn must land as Error, never stay Streaming forever.
        val vm = vmUnder(scriptedService(listOf(AgentFragment.Answer("partial answer, then the stream just ended"))))
        vm.setQuery("q")
        vm.ask()

        val turn = vm.state.value.turns.single()
        assertEquals(TurnOutcome.Error, turn.outcome)
        assertTrue(turn.outcome != TurnOutcome.Streaming)
        assertTrue(!vm.state.value.running)
    }

    @Test
    public fun toolStripIsPopulatedFromTheTraceDrainAfterStatusArrives(): Unit = runTest {
        val drain = TraceDrain(
            nowUs = 10.0,
            dropped = 0,
            capacity = 512,
            spans = listOf(
                TraceSpan("agent.turn", 0.0, 20.0, null, null),
                TraceSpan("agent.tool.search_manuals", 1.0, 8.0, null, null),
            ),
        )
        val vm = vmUnder(
            scriptedService(
                listOf(
                    AgentFragment.Answer("grounded answer"),
                    AgentFragment.Status(AgentTurnStatus(AgentStopReason.Answered, 1, "scripted routing — no on-device LLM present")),
                ),
            ),
            traceReader = AgentTraceReader { drain },
        )
        vm.setQuery("q")
        vm.ask()

        val turn = vm.state.value.turns.single()
        assertEquals(listOf("agent.turn", "agent.tool.search_manuals"), turn.toolSpans.map { it.name })
    }

    @Test
    public fun queryIsClearedAfterAskAndEmptyQueryIsIgnored(): Unit = runTest {
        val vm = vmUnder(
            scriptedService(
                listOf(AgentFragment.Status(AgentTurnStatus(AgentStopReason.Answered, 0, "x"))),
            ),
        )
        vm.setQuery("   ")
        vm.ask()
        assertTrue(vm.state.value.turns.isEmpty()) // blank query never starts a turn

        vm.setQuery("real question")
        vm.ask()
        assertEquals("", vm.state.value.query)
        assertEquals(1, vm.state.value.turns.size)
    }
}
