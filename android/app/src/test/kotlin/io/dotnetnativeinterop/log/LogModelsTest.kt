package io.dotnetnativeinterop.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

/**
 * Pins the client half of the `dni_log_drain` contract (abi/dni.h): the camelCase payload decodes, a
 * record with no exception/requestId leaves them null, an exception-carrying record surfaces the detail,
 * unknown keys are tolerated (the engine may evolve the payload), and the severity rank/filter behave.
 */
public class LogModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    public fun decodesADrainWithRecords() {
        val raw = """
            {"nowUs":29846.8,"dropped":2,"capacity":256,"records":[
              {"level":"Information","category":"Dni.Engine","message":"Engine initialized","timestampUs":100.0},
              {"level":"Warning","category":"Dni.Engine","message":"Session 42 token drain ended abnormally",
               "timestampUs":200.5,"exception":"OperationCanceledException: cancelled"}]}
        """.trimIndent()

        val drain = json.decodeFromString<LogDrain>(raw)
        assertEquals(29846.8, drain.nowUs, 0.0)
        assertEquals(2, drain.dropped)
        assertEquals(256, drain.capacity)
        assertEquals(2, drain.records.size)

        val info = drain.records[0]
        assertEquals("Information", info.level)
        assertEquals("Dni.Engine", info.category)
        assertEquals("Engine initialized", info.message)
        assertNull("a record with no exception leaves it null", info.exception)
        assertNull("a record with no requestId leaves it null", info.requestId)

        val warn = drain.records[1]
        assertEquals("Warning", warn.level)
        assertEquals("OperationCanceledException: cancelled", warn.exception)
    }

    @Test
    public fun toleratesUnknownKeysAndMissingFields() {
        // A future engine adds a key; a missing "records" degrades to empty, not a decode failure.
        val drain = json.decodeFromString<LogDrain>("""{"nowUs":1.0,"dropped":0,"capacity":256,"newKey":true}""")
        assertTrue(drain.records.isEmpty())
        assertEquals(256, drain.capacity)
    }

    @Test
    public fun severityRankOrdersLevels() {
        assertTrue(levelRank("Critical") > levelRank("Error"))
        assertTrue(levelRank("Error") > levelRank("Warning"))
        assertTrue(levelRank("Warning") > levelRank("Information"))
        assertEquals("an unknown level ranks at the bottom (never hidden by a severity filter)", 0, levelRank("Verbose"))
    }

    @Test
    public fun filterMinRanksAreOrdered() {
        assertEquals(0, LogFilter.All.minRank)
        assertEquals(1, LogFilter.WarnPlus.minRank)
        assertEquals(2, LogFilter.ErrorsOnly.minRank)
        // The UI keeps a record iff levelRank(level) >= filter.minRank — so "errors" hides Warning/Info.
        assertTrue(levelRank("Warning") < LogFilter.ErrorsOnly.minRank)
        assertTrue(levelRank("Error") >= LogFilter.ErrorsOnly.minRank)
    }
}
