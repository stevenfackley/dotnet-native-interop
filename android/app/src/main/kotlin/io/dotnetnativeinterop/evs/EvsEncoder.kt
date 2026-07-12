package io.dotnetnativeinterop.evs

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import kotlin.math.sqrt

/** all-MiniLM sentence encoder via onnxruntime-android (app-layer; CPU EP). Mean-pool + L2-normalize. */
public class EvsEncoder(modelPath: String) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    // createSession READS the options but does not take ownership — SessionOptions owns a native handle
    // (AutoCloseable), so an inline `SessionOptions()` would leak it. Close it once the session is built.
    private val session: OrtSession = OrtSession.SessionOptions().use { opts ->
        env.createSession(modelPath, opts)
    }
    private val outputName: String = session.outputNames.first()

    public fun embed(ids: LongArray, mask: LongArray): FloatArray {
        val shape = longArrayOf(1L, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskT ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape).use { typeT ->
                    val inputs = mapOf(
                        "input_ids" to idsT,
                        "attention_mask" to maskT,
                        "token_type_ids" to typeT,
                    )
                    session.run(inputs).use { results ->
                        @Suppress("UNCHECKED_CAST")
                        // results.get(name) returns Optional<OnnxValue>; .get() unwraps it; .value is Object
                        val hidden = (results.get(outputName).get().value as Array<Array<FloatArray>>)[0] // [len][384]
                        val dim = hidden[0].size
                        val pooled = FloatArray(dim)
                        var count = 0
                        for (t in ids.indices) {
                            if (mask[t] == 0L) continue
                            count++
                            for (d in 0 until dim) pooled[d] += hidden[t][d]
                        }
                        if (count > 0) for (d in 0 until dim) pooled[d] /= count
                        var norm = 0f
                        for (v in pooled) norm += v * v
                        norm = sqrt(norm)
                        if (norm > 0f) for (d in 0 until dim) pooled[d] /= norm
                        return pooled
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }
}
