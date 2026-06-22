# FFI Boundary — Android Screen + JNI (Plan C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add the Android **Boundary** screen (Jetpack Compose) + the JNI bindings for the three Plan A exports, mirroring the iOS Boundary screen, so Android demonstrates the FFI method to the same depth (with the JNI shim lane *visible* — the extra hop iOS doesn't have).

**Architecture:** New `io.dotnetnativeinterop.boundary` package: `@Serializable` models → `BoundaryService`/`FfiBoundaryService` (calls 3 new `NativeBridge` external funs; streaming via a `Channel<BoundaryStreamToken>` `Flow` like `FfiClient`) → `BoundaryViewModel` (StateFlow) → `SwimlaneCanvas` + inspector composables + `BoundaryScreen`, wired into `AppShell`. JNI: 3 new wrappers + an extended trace callback in `jni_bridge.c`, registered in the `RegisterNatives` table. Model-decode + VM logic are JVM unit tests (`src/test`, no device); JNI + UI verify on the emulator.

**Tech Stack:** Kotlin / Jetpack Compose / Material3, JNI (C), the prebuilt NativeAOT `libdni.so`, `kotlinx.serialization`, the "Precision Instrument" Compose theme.

**Spec:** `docs/superpowers/specs/2026-06-21-ffi-boundary-showcase-design.md`. **Depends on Plan A** (the `dni_ffi_*` exports + `dni_trace_cb`, already implemented). Task 10 rebuilds `libdni.so` so the JNI shim links the new symbols.

**Branch:** continue on `feat/ffi-boundary-showcase`.

**Exact C ABI being bound (from `abi/dni.h`, authoritative — the grounding agent's draft got these wrong, do NOT trust a paraphrase):**
```c
const char* dni_ffi_echo(const char* utf8, int32_t len);   /* len is REQUIRED */
const char* dni_ffi_throw(void);
typedef void (*dni_trace_cb)(void* user_data, int32_t index, const char* text,
                             int32_t is_final, int64_t managed_thread_id, int64_t elapsed_us); /* is_final BEFORE the two int64s */
int64_t dni_ffi_stream_start(const char* prompt, int32_t max_tokens, dni_trace_cb cb, void* user_data); /* max_tokens is REQUIRED */
```

---

## File Structure

| File | Create/Modify | Responsibility |
|------|---------------|----------------|
| `…/model/Models.kt` | Modify | Add `BoundaryEcho`, `BoundaryThrow` (`@Serializable`, mirror the C# JSON) + `BoundaryStreamToken`, `PhaseTiming`, `OwnershipEntry`, `BoundaryPreset`/`BoundaryPhase`/`BoundaryInspector`. |
| `…/transport/NativeBridge.kt` | Modify | Add `nativeFfiEcho`/`nativeFfiThrow`/`nativeFfiStreamStart` + `FfiTraceListener`. |
| `…/cpp/jni_bridge.c` | Modify | Add `j_ffi_echo`/`j_ffi_throw`/`j_ffi_stream_start` + `TraceCallbackState`/`ffi_trace_callback`; register in `kMethods`. |
| `…/boundary/BoundaryService.kt` | Create | `BoundaryService` + `FfiBoundaryService` (echo/throw/stream) + `FakeBoundaryService`. |
| `…/boundary/BoundaryViewModel.kt` | Create | StateFlow UI state + presets/run/auto-step. |
| `…/boundary/SwimlaneCanvas.kt` | Create | The hero swimlane (JNI lane **visible**) + thread-hop. |
| `…/boundary/BoundaryInspectors.kt` | Create | The 6 segment composables. |
| `…/boundary/BoundaryScreen.kt` | Create | The A1 screen. |
| `…/ui/AppShell.kt` | Modify | Add `Boundary` as the first tab + the `when` handler. |
| `…/src/test/.../BoundaryModelsTest.kt` | Create | JVM decode + VM-logic tests (no device). |

(`…` = `android/app/src/main/kotlin/io/dotnetnativeinterop`.)

---

## Task 1: Models + JVM decode test

**Files:**
- Modify: `android/app/src/main/kotlin/io/dotnetnativeinterop/model/Models.kt`
- Test: `android/app/src/test/kotlin/io/dotnetnativeinterop/BoundaryModelsTest.kt`

- [ ] **Step 1: Write the failing test** (JVM unit test — runs without a device)

Create `android/app/src/test/kotlin/io/dotnetnativeinterop/BoundaryModelsTest.kt`:
```kotlin
package io.dotnetnativeinterop

import io.dotnetnativeinterop.model.BoundaryEcho
import io.dotnetnativeinterop.model.BoundaryThrow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class BoundaryModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    public fun echoDecodesCamelCase() {
        val s = """{"bytesHex":"48656C6C6F","len":5,"decoded":"Hello","managedThreadId":2,"executeUs":3.4,"ptrIn":"0x16d"}"""
        val e = json.decodeFromString<BoundaryEcho>(s)
        assertEquals("Hello", e.decoded)
        assertEquals(5, e.len)
        assertEquals(2L, e.managedThreadId)
        assertEquals("48656C6C6F", e.bytesHex)
    }

    @Test
    public fun throwDecodes() {
        val s = """{"caught":true,"type":"System.InvalidOperationException","message":"x","status":-5}"""
        val t = json.decodeFromString<BoundaryThrow>(s)
        assertTrue(t.caught)
        assertEquals(-5, t.status)
        assertTrue(t.type.contains("InvalidOperationException"))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.dotnetnativeinterop.BoundaryModelsTest"`
Expected: FAIL — unresolved reference `BoundaryEcho`/`BoundaryThrow`.

- [ ] **Step 3: Add the models**

Append to `android/app/src/main/kotlin/io/dotnetnativeinterop/model/Models.kt`:
```kotlin
/** dni_ffi_echo result — native-measured; camelCase mirrors the C# record + iOS BoundaryEcho. */
@Serializable
public data class BoundaryEcho(
    val bytesHex: String,
    val len: Int,
    val decoded: String,
    val managedThreadId: Long,
    val executeUs: Double,
    val ptrIn: String,
)

/** dni_ffi_throw result — a managed exception contained at the boundary. */
@Serializable
public data class BoundaryThrow(
    val caught: Boolean,
    val type: String,
    val message: String,
    val status: Int,
)

/** One token from dni_ffi_stream_start's extended callback (NOT JSON — comes via FfiTraceListener). */
public data class BoundaryStreamToken(
    val index: Int,
    val text: String,
    val isFinal: Boolean,
    val managedThreadId: Long,
    val elapsedUs: Long,
)

/** Per-phase µs split. marshal/cross/free are frontend-measured; execute is native (executeUs). */
public data class PhaseTiming(
    val marshalUs: Double = 0.0,
    val crossUs: Double = 0.0,
    val executeUs: Double = 0.0,
    val callbackUs: Double = 0.0,
    val freeUs: Double = 0.0,
) {
    val totalUs: Double get() = marshalUs + crossUs + executeUs + callbackUs + freeUs
}

public data class OwnershipEntry(
    val buffer: String,
    val allocatedBy: String,
    val freedBy: String,
    val bytes: Int,
    val freed: Boolean,
)

public enum class BoundaryPreset(public val title: String) {
    Echo("echo"), Feature("feature"), Pixels("pixels"), Stream("stream"), Exception("throw"),
}

public enum class BoundaryPhase { Marshal, Cross, Execute, Callback, Free }

public enum class BoundaryInspector(public val label: String) {
    Bytes("bytes"), Timing("µs"), Memory("memory"), Threads("threads"), Abi("ABI"), Error("err"),
}
```
(`Models.kt` already has `import kotlinx.serialization.Serializable`.)

- [ ] **Step 4: Run it — expect PASS**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.dotnetnativeinterop.BoundaryModelsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add android/app/src/main/kotlin/io/dotnetnativeinterop/model/Models.kt android/app/src/test/kotlin/io/dotnetnativeinterop/BoundaryModelsTest.kt
git commit -m "feat(ffi-boundary): android Boundary models + JVM decode test"
```

---

## Task 2: NativeBridge external funs + FfiTraceListener

**Files:**
- Modify: `android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt`

- [ ] **Step 1: Add the three external funs + the listener**

In `NativeBridge.kt`, add inside the `object NativeBridge` (next to the other `external fun`s):
```kotlin
    // Pattern 3 — Boundary instrumentation (additive). Mirrors dni_ffi_echo / dni_ffi_throw /
    // dni_ffi_stream_start. nativeFfiEcho passes the byte length internally (the JNI shim computes it).
    public external fun nativeFfiEcho(text: String): String?
    public external fun nativeFfiThrow(): String?
    public external fun nativeFfiStreamStart(prompt: String, maxTokens: Int, listener: FfiTraceListener): Long
```
And add this interface alongside `FfiTokenListener` (top-level in the file):
```kotlin
/**
 * Extended per-token callback for Boundary tracing: adds managedThreadId + elapsedUs to the base
 * token callback. Fires on a .NET background thread already attached to the JVM by the shim; MUST NOT
 * block — enqueue and return.
 */
public interface FfiTraceListener {
    public fun onTrace(index: Int, text: String, managedThreadId: Long, elapsedUs: Long, isFinal: Boolean)
}
```

- [ ] **Step 2: Compile-check**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: PASS (the `external fun`s are unresolved at runtime until Task 3 registers them, but Kotlin compiles).

- [ ] **Step 3: Commit**

```powershell
git add android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt
git commit -m "feat(ffi-boundary): android NativeBridge funs + FfiTraceListener"
```

---

## Task 3: JNI shim — 3 wrappers + extended trace callback

**Files:**
- Modify: `android/app/src/main/cpp/jni_bridge.c`

- [ ] **Step 1: Add the wrappers + the trace callback**

In `jni_bridge.c`, add these **above** the `kMethods` table (reuse the existing `attach_current_thread`, `take_native_string`, and the `DNI_*` constants from `dni.h`; ensure `#include <string.h>` is present for `strlen`):
```c
/* ---- Boundary: echo / throw (sync, string in/out) ----------------------- */
static jstring j_ffi_echo(JNIEnv* e, jobject o, jstring text) {
    (void)o; if (text == NULL) return NULL;
    const char* ctext = (*e)->GetStringUTFChars(e, text, NULL);
    if (ctext == NULL) return NULL;
    const char* r = dni_ffi_echo(ctext, (int32_t)strlen(ctext)); /* len is REQUIRED by the ABI */
    (*e)->ReleaseStringUTFChars(e, text, ctext);
    return take_native_string(e, r);
}

static jstring j_ffi_throw(JNIEnv* e, jobject o) {
    (void)e; (void)o;
    return take_native_string(e, dni_ffi_throw());
}

/* ---- Boundary: streaming with the extended trace callback ---------------- */
typedef struct { jobject listener_ref; jmethodID on_trace; } TraceCallbackState;

/* Param order MUST match dni_trace_cb: (ud, index, text, is_final, managed_thread_id, elapsed_us). */
static void ffi_trace_callback(void* user_data, int32_t index, const char* text,
                               int32_t is_final, int64_t managed_thread_id, int64_t elapsed_us) {
    TraceCallbackState* st = (TraceCallbackState*)user_data;
    if (st == NULL) return;
    JNIEnv* env = attach_current_thread();
    if (env == NULL) return;
    jstring jtext = (*env)->NewStringUTF(env, text != NULL ? text : "");
    if (jtext == NULL) return;
    /* Kotlin onTrace(index, text, managedThreadId, elapsedUs, isFinal) — reorder to that signature. */
    (*env)->CallVoidMethod(env, st->listener_ref, st->on_trace,
        (jint)index, jtext, (jlong)managed_thread_id, (jlong)elapsed_us, (jboolean)(is_final != 0));
    (*env)->DeleteLocalRef(env, jtext);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if (is_final) { (*env)->DeleteGlobalRef(env, st->listener_ref); free(st); }
}

static jlong j_ffi_stream_start(JNIEnv* e, jobject o, jstring prompt, jint max_tokens, jobject listener) {
    (void)o;
    if (prompt == NULL || listener == NULL) return (jlong)DNI_INVALID_ARGUMENT;
    jclass lc = (*e)->GetObjectClass(e, listener);
    jmethodID on_trace = (*e)->GetMethodID(e, lc, "onTrace", "(ILjava/lang/String;JJZ)V");
    (*e)->DeleteLocalRef(e, lc);
    if (on_trace == NULL) { LOGE("onTrace not found"); return (jlong)DNI_INVALID_ARGUMENT; }
    TraceCallbackState* st = (TraceCallbackState*)malloc(sizeof(TraceCallbackState));
    if (st == NULL) return (jlong)DNI_INTERNAL;
    st->listener_ref = (*e)->NewGlobalRef(e, listener);
    st->on_trace = on_trace;
    const char* c_prompt = (*e)->GetStringUTFChars(e, prompt, NULL);
    if (c_prompt == NULL) { (*e)->DeleteGlobalRef(e, st->listener_ref); free(st); return (jlong)DNI_INTERNAL; }
    int64_t sid = dni_ffi_stream_start(c_prompt, (int32_t)max_tokens, ffi_trace_callback, st);
    (*e)->ReleaseStringUTFChars(e, prompt, c_prompt);
    if (sid <= 0) { (*e)->DeleteGlobalRef(e, st->listener_ref); free(st); }
    return (jlong)sid;
}
```

- [ ] **Step 2: Register them in `kMethods`**

Add these three rows to the `static const JNINativeMethod kMethods[]` table:
```c
    {"nativeFfiEcho",        "(Ljava/lang/String;)Ljava/lang/String;",                                    (void*)j_ffi_echo},
    {"nativeFfiThrow",       "()Ljava/lang/String;",                                                      (void*)j_ffi_throw},
    {"nativeFfiStreamStart", "(Ljava/lang/String;ILio/dotnetnativeinterop/transport/FfiTraceListener;)J", (void*)j_ffi_stream_start},
```

- [ ] **Step 3: Verify the JNI descriptors match**

By inspection: `j_ffi_stream_start` arg `jint max_tokens` ↔ the `I` in `(Ljava/lang/String;I…)J`; the trace `GetMethodID` descriptor `(ILjava/lang/String;JJZ)V` ↔ Kotlin `onTrace(Int, String, Long, Long, Boolean)`; `ffi_trace_callback`'s C params are in `dni_trace_cb` order (`is_final` before the two `int64`s). Compilation happens in Task 10's Gradle build (CMake recompiles `jni_bridge.c` against the rebuilt `libdni.so`).

- [ ] **Step 4: Commit**

```powershell
git add android/app/src/main/cpp/jni_bridge.c
git commit -m "feat(ffi-boundary): android JNI wrappers + extended trace callback"
```

---

## Task 4: BoundaryService (echo/throw/stream) + fake

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryService.kt`

- [ ] **Step 1: Write the service**

Create `android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryService.kt`:
```kotlin
package io.dotnetnativeinterop.boundary

import io.dotnetnativeinterop.model.BoundaryEcho
import io.dotnetnativeinterop.model.BoundaryStreamToken
import io.dotnetnativeinterop.model.BoundaryThrow
import io.dotnetnativeinterop.model.OwnershipEntry
import io.dotnetnativeinterop.model.PhaseTiming
import io.dotnetnativeinterop.transport.FfiTraceListener
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal val boundaryJson: Json = Json { ignoreUnknownKeys = true }

/** Result of one echo trace: native echo + frontend timing + thread/leak context. */
public data class BoundaryEchoTrace(
    val echo: BoundaryEcho,
    val timing: PhaseTiming,
    val callerThreadId: Long,
)

public interface BoundaryService {
    public suspend fun echo(input: String): BoundaryEchoTrace
    public suspend fun throwDemo(): BoundaryThrow
    public fun stream(prompt: String, maxTokens: Int): Flow<BoundaryStreamToken>
}

/** BoundaryService over the in-process C ABI (Pattern 3 — boundary instrumentation). */
public class FfiBoundaryService : BoundaryService {

    override suspend fun echo(input: String): BoundaryEchoTrace = withContext(Dispatchers.IO) {
        val callerTid = Thread.currentThread().id
        val cStart = System.nanoTime()
        val raw = NativeBridge.nativeFfiEcho(input) ?: error("nativeFfiEcho returned null")
        val callWallUs = (System.nanoTime() - cStart) / 1000.0
        val echo = boundaryJson.decodeFromString<BoundaryEcho>(raw)
        // marshal/free happen inside the JNI shim (not separately observable from Kotlin) — fold into cross;
        // executeUs is native; cross = round-trip - native execute. (Honest split is labelled in the UI.)
        val timing = PhaseTiming(
            crossUs = (callWallUs - echo.executeUs).coerceAtLeast(0.0),
            executeUs = echo.executeUs,
        )
        BoundaryEchoTrace(echo, timing, callerTid)
    }

    override suspend fun throwDemo(): BoundaryThrow = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeFfiThrow() ?: error("nativeFfiThrow returned null")
        boundaryJson.decodeFromString(raw)
    }

    override fun stream(prompt: String, maxTokens: Int): Flow<BoundaryStreamToken> = flow {
        val channel = Channel<BoundaryStreamToken>(capacity = 64)
        val listener = object : FfiTraceListener {
            override fun onTrace(index: Int, text: String, managedThreadId: Long, elapsedUs: Long, isFinal: Boolean) {
                channel.trySend(BoundaryStreamToken(index, text, isFinal, managedThreadId, elapsedUs))
                if (isFinal) channel.close()
            }
        }
        val sessionId = withContext(Dispatchers.Default) {
            NativeBridge.nativeFfiStreamStart(prompt, maxTokens, listener)
        }
        if (sessionId <= 0) throw IllegalStateException("nativeFfiStreamStart failed: status $sessionId")
        try {
            for (token in channel) emit(token)
        } finally {
            // Stop via the shared lifecycle exports (no-ops if the stream already finished).
            withContext(Dispatchers.Default) {
                NativeBridge.nativeSessionCancel(sessionId)
                NativeBridge.nativeSessionFree(sessionId)
            }
        }
    }.flowOn(Dispatchers.Default)
}

/** Ownership ledger row builder (shared by the VM). On Android the JNI shim frees the result string
 *  eagerly (take_native_string -> dni_string_free), so the "leak" is illustrative, not a real leak. */
public fun echoLedger(inputBytes: Int, resultBytes: Int): List<OwnershipEntry> = listOf(
    OwnershipEntry("input utf8", "host", "JNI (ReleaseStringUTFChars)", inputBytes, freed = true),
    OwnershipEntry("result json", ".NET", "JNI (dni_string_free)", resultBytes, freed = true),
)

/** Deterministic fake for unit tests + previews. */
public class FakeBoundaryService : BoundaryService {
    override suspend fun echo(input: String): BoundaryEchoTrace {
        val hex = input.encodeToByteArray().joinToString("") { "%02X".format(it) }
        return BoundaryEchoTrace(
            BoundaryEcho(hex, input.encodeToByteArray().size, input, 7L, 4.2, "0x1000"),
            PhaseTiming(crossUs = 2.0, executeUs = 4.2), 1L,
        )
    }
    override suspend fun throwDemo(): BoundaryThrow =
        BoundaryThrow(true, "System.InvalidOperationException", "Boundary demo: contained.", -5)
    override fun stream(prompt: String, maxTokens: Int): Flow<BoundaryStreamToken> = flow {
        repeat(3) { emit(BoundaryStreamToken(it, "tok$it ", false, 9L, it * 800L)) }
        emit(BoundaryStreamToken(3, "", true, 9L, 2400L))
    }
}
```

- [ ] **Step 2: Compile-check + commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin` → PASS.
```powershell
git add android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryService.kt
git commit -m "feat(ffi-boundary): android BoundaryService (echo/throw/stream) + fake"
```

---

## Task 5: BoundaryViewModel + JVM logic test

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryViewModel.kt`
- Test: append to `android/app/src/test/kotlin/io/dotnetnativeinterop/BoundaryModelsTest.kt` (or a new `BoundaryViewModelTest.kt`)

- [ ] **Step 1: Write the failing VM test**

Create `android/app/src/test/kotlin/io/dotnetnativeinterop/BoundaryViewModelTest.kt`:
```kotlin
package io.dotnetnativeinterop

import io.dotnetnativeinterop.boundary.BoundaryViewModel
import io.dotnetnativeinterop.boundary.FakeBoundaryService
import io.dotnetnativeinterop.model.BoundaryPreset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class BoundaryViewModelTest {
    @Test
    public fun echoPopulatesEchoAndTiming(): Unit = runTest {
        val vm = BoundaryViewModel(FakeBoundaryService())
        vm.selectPreset(BoundaryPreset.Echo)
        vm.setInput("Hello")
        vm.run()
        assertEquals("Hello", vm.state.value.echo?.decoded)
        assertTrue(vm.state.value.timing.totalUs > 0.0)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    public fun throwIsContained(): Unit = runTest {
        val vm = BoundaryViewModel(FakeBoundaryService())
        vm.selectPreset(BoundaryPreset.Exception)
        vm.run()
        assertEquals(true, vm.state.value.thrown?.caught)
        assertEquals(-5, vm.state.value.thrown?.status)
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`BoundaryViewModel` unresolved).

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.dotnetnativeinterop.BoundaryViewModelTest"` → FAIL.

- [ ] **Step 3: Write the view model**

Create `android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryViewModel.kt`:
```kotlin
package io.dotnetnativeinterop.boundary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.BoundaryEcho
import io.dotnetnativeinterop.model.BoundaryInspector
import io.dotnetnativeinterop.model.BoundaryPhase
import io.dotnetnativeinterop.model.BoundaryPreset
import io.dotnetnativeinterop.model.BoundaryStreamToken
import io.dotnetnativeinterop.model.BoundaryThrow
import io.dotnetnativeinterop.model.OwnershipEntry
import io.dotnetnativeinterop.model.PhaseTiming
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class BoundaryUiState(
    val preset: BoundaryPreset = BoundaryPreset.Echo,
    val inspector: BoundaryInspector = BoundaryInspector.Timing,
    val input: String = "Hello",
    val running: Boolean = false,
    val activePhase: BoundaryPhase? = null,
    val timing: PhaseTiming = PhaseTiming(),
    val echo: BoundaryEcho? = null,
    val thrown: BoundaryThrow? = null,
    val streamTokens: List<BoundaryStreamToken> = emptyList(),
    val ledger: List<OwnershipEntry> = emptyList(),
    val callerThreadId: Long = 0,
    val error: String? = null,
) {
    val callbackThreadId: Long? get() = streamTokens.lastOrNull()?.managedThreadId ?: echo?.managedThreadId
}

public class BoundaryViewModel(private val service: BoundaryService = FfiBoundaryService()) : ViewModel() {
    private val _state = MutableStateFlow(BoundaryUiState())
    public val state: StateFlow<BoundaryUiState> = _state.asStateFlow()
    private var streamJob: Job? = null

    public fun selectPreset(p: BoundaryPreset) { _state.update { it.copy(preset = p) } }
    public fun selectInspector(i: BoundaryInspector) { _state.update { it.copy(inspector = i) } }
    public fun setInput(s: String) { _state.update { it.copy(input = s) } }

    public fun run() {
        when (_state.value.preset) {
            BoundaryPreset.Echo, BoundaryPreset.Feature, BoundaryPreset.Pixels -> runEcho(_state.value.preset == BoundaryPreset.Pixels)
            BoundaryPreset.Exception -> runThrow()
            BoundaryPreset.Stream -> runStream()
        }
    }

    private fun runEcho(large: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            val payload = if (large) "▇".repeat(4096) else _state.value.input
            runCatching { service.echo(payload) }
                .onSuccess { t ->
                    _state.update {
                        it.copy(running = false, echo = t.echo, timing = t.timing, callerThreadId = t.callerThreadId,
                            ledger = echoLedger(it.input.encodeToByteArray().size, t.echo.len))
                    }
                }
                .onFailure { e -> _state.update { it.copy(running = false, error = "Echo over FFI failed: ${e.message}") } }
        }
    }

    private fun runThrow() {
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            runCatching { service.throwDemo() }
                .onSuccess { t -> _state.update { it.copy(running = false, thrown = t, inspector = BoundaryInspector.Error) } }
                .onFailure { e -> _state.update { it.copy(running = false, error = "Throw demo failed: ${e.message}") } }
        }
    }

    private fun runStream() {
        streamJob?.cancel()
        _state.update { it.copy(running = true, streamTokens = emptyList(), inspector = BoundaryInspector.Threads, error = null) }
        streamJob = viewModelScope.launch {
            runCatching {
                service.stream(_state.value.input.ifBlank { "stream demo" }, maxTokens = 24).collect { tok ->
                    if (tok.text.isNotEmpty()) _state.update { it.copy(streamTokens = it.streamTokens + tok) }
                }
            }.onFailure { e -> _state.update { it.copy(error = "Stream over FFI failed: ${e.message}") } }
            _state.update { it.copy(running = false) }
        }
    }

    public fun stop() { streamJob?.cancel(); _state.update { it.copy(running = false) } }

    /** Walk the phases on a cadence, auto-selecting each phase's inspector segment. */
    public fun autoStep() {
        viewModelScope.launch {
            for (phase in BoundaryPhase.values()) {
                _state.update { it.copy(activePhase = phase, inspector = segmentFor(phase)) }
                delay(600)
            }
            _state.update { it.copy(activePhase = null) }
        }
    }

    private fun segmentFor(p: BoundaryPhase): BoundaryInspector = when (p) {
        BoundaryPhase.Marshal -> BoundaryInspector.Bytes
        BoundaryPhase.Cross -> BoundaryInspector.Abi
        BoundaryPhase.Execute -> BoundaryInspector.Timing
        BoundaryPhase.Callback -> BoundaryInspector.Threads
        BoundaryPhase.Free -> BoundaryInspector.Memory
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.dotnetnativeinterop.BoundaryViewModelTest"` → PASS.
(If `runTest` is unresolved, ensure `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")` is in `app/build.gradle.kts` — the project already uses coroutines; add the test artifact if missing.)

- [ ] **Step 5: Commit**

```powershell
git add android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryViewModel.kt android/app/src/test/kotlin/io/dotnetnativeinterop/BoundaryViewModelTest.kt
git commit -m "feat(ffi-boundary): android BoundaryViewModel + JVM logic test"
```

---

## Task 6: SwimlaneCanvas (hero, JNI lane visible)

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/SwimlaneCanvas.kt`

- [ ] **Step 1: Write the swimlane** (Android shows the **JNI shim** lane — the extra hop iOS lacks)
```kotlin
package io.dotnetnativeinterop.boundary

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.dotnetnativeinterop.model.BoundaryPhase
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.PanelLabelText // see note below

private enum class Lane(val display: String) {
    Ui("UI thread"), Binding("binding"), Jni("JNI shim"), CAbi("C ABI"), Net(".NET AOT"), Worker("worker")
}

private fun hotLane(phase: BoundaryPhase?): Set<Lane> = when (phase) {
    BoundaryPhase.Marshal -> setOf(Lane.Binding, Lane.Jni)
    BoundaryPhase.Cross -> setOf(Lane.CAbi)
    BoundaryPhase.Execute -> setOf(Lane.Net)
    BoundaryPhase.Callback -> setOf(Lane.Worker, Lane.Jni, Lane.Ui) // worker -> JNI -> UI hop
    BoundaryPhase.Free -> setOf(Lane.Ui)
    null -> emptySet()
}

@Composable
internal fun SwimlaneCanvas(activePhase: BoundaryPhase?, modifier: Modifier = Modifier) {
    val hot = hotLane(activePhase)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Lane.values().forEach { lane ->
            val isHot = lane in hot
            val border by animateColorAsState(if (isHot) Instrument.accent else Instrument.hairline, label = "lane")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PanelLabelText(
                    text = lane.display,
                    color = if (isHot) Instrument.accent else Instrument.textTertiary,
                    modifier = Modifier.width(72.dp), textAlign = TextAlign.End,
                )
                Box(
                    Modifier.weight(1f).height(16.dp)
                        .background(Instrument.bg2, RoundedCornerShape(3.dp))
                        .border(if (isHot) 1.5.dp else 1.dp, border, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}
```
**Note:** if `InstrumentComponents.kt` has no `PanelLabelText`, replace it with a `Text(lane.display, style = MaterialTheme.typography.labelSmall, color = …)` using the monospaced label style already defined in `Theme.kt`. Keep the visual identical to iOS `SwimlaneView` but with the extra `Jni` lane.

- [ ] **Step 2: Compile-check + commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin` → PASS.
```powershell
git add android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/SwimlaneCanvas.kt
git commit -m "feat(ffi-boundary): android swimlane (JNI lane visible) + thread-hop"
```

---

## Task 7: Inspector composables

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryInspectors.kt`

- [ ] **Step 1: Write the six segment panels** — mirror the iOS `BoundaryInspectorPanels.swift` content (bytes/µs/memory/threads/ABI/err), built from the Android `InstrumentCard`, `PanelHeader`, `StatCell` composables (signatures from grounding §6). One `@Composable BoundaryInspectorPanel(state: BoundaryUiState, onToggleLeak: …)` that `when (state.inspector)`-switches to:
  - **Bytes**: `state.echo?.bytesHex` chunked into pairs (cyan, monospace) + `StatCell`s for decoded/len/ptrIn.
  - **Timing**: `StatCell`s marshal/cross/execute·native(ok-green)/callback/free/total(accent) + the caption "marshal/cross/free are frontend-measured; execute is native".
  - **Memory**: `state.ledger` rows (buffer · bytes · freed/leaked) + the note that on Android the JNI shim frees eagerly (`take_native_string` → `dni_string_free`), so ownership is shown, not leaked.
  - **Threads**: `StatCell` caller `#${state.callerThreadId}` vs managed `#${state.callbackThreadId}` (warn), the `⚠ callback fires on a .NET worker thread → AttachCurrentThread → main Handler` note, and the token count/last-elapsedUs when streaming.
  - **Abi**: the C ⇄ Kotlin/JNI mapping rows for the active preset (`dni_ffi_echo(const char*, int32_t)` ↔ `external fun nativeFfiEcho(String)`; `dni_trace_cb(... is_final, int64, int64)` ↔ `FfiTraceListener.onTrace`), with the version note (`RegisterNatives`, `[UnmanagedCallersOnly]` .NET 5+ / C# 9+).
  - **Error**: `state.thrown` — "contained at the boundary — no crash", type/status/message.

Write each as a small private `@Composable` exactly paralleling the iOS panels (same labels/copy) so the two platforms read identically.

- [ ] **Step 2: Compile-check + commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin` → PASS.
```powershell
git add android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryInspectors.kt
git commit -m "feat(ffi-boundary): android inspector segment composables"
```

---

## Task 8: BoundaryScreen (A1)

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryScreen.kt`

- [ ] **Step 1: Write the screen** — `@Composable BoundaryScreen(vm: BoundaryViewModel, …)` collecting `vm.state`, laid out top→bottom exactly like iOS `BoundaryView`:
  - `PanelHeader("Boundary · trace one FFI call")`
  - preset row: `BoundaryPreset.values()` as selectable chips (accent when selected) → `vm.selectPreset`
  - `InstrumentCard { PanelHeader("lifecycle — swimlane"); SwimlaneCanvas(state.activePhase); Text("⚠ callback fires off the UI thread → AttachCurrentThread → main", warn) }`
  - controls Row: `Button("Run"/"Run stream")` → `vm.run()` (disabled while `state.running`); `OutlinedButton("Auto-step")` → `vm.autoStep()`; a Stop button when streaming.
  - `if (state.error != null) ErrorBanner(state.error) { vm.run() }`
  - segmented inspector (a Row of `BoundaryInspector.values()` chips) → `vm.selectInspector`, then `BoundaryInspectorPanel(state, onToggleLeak = …)`.

Use the Compose Material3 `SegmentedButton`/`FilterChip` already used elsewhere (grounding shows `TransportPicker` uses segmented buttons) for the preset + inspector rows.

- [ ] **Step 2: Compile-check + commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin` → PASS.
```powershell
git add android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/BoundaryScreen.kt
git commit -m "feat(ffi-boundary): android BoundaryScreen (A1)"
```

---

## Task 9: Register the Boundary tab (first)

**Files:**
- Modify: `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt`

- [ ] **Step 1: Add the enum entry** — make `Boundary` the FIRST entry of the `Tab` enum (before `Dashboard`):
```kotlin
    Boundary("Boundary", Icons.Outlined.SwapHoriz),
```
(`Icons.Outlined.SwapHoriz` mirrors the iOS `arrow.left.arrow.right`; add the import.)

- [ ] **Step 2: Add the `when` handler** — in the destination `when (tab)` block:
```kotlin
            Tab.Boundary -> {
                val vm: io.dotnetnativeinterop.boundary.BoundaryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                io.dotnetnativeinterop.boundary.BoundaryScreen(vm)
            }
```

- [ ] **Step 3: Build + commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin` → PASS.
```powershell
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt
git commit -m "feat(ffi-boundary): register Boundary as the first Android tab"
```

---

## Task 10: Rebuild libdni.so + emulator verification

**Files:** none (build + verification)

- [ ] **Step 1: Rebuild `libdni.so` with the new exports** (Plan A changed the ABI). On the Mac (or the Android build host), per `docs/...` + grounding §8:
```bash
ssh steve-mac-mini "zsh -lc 'cd /Users/steve/dni-build && bash build/build-android-so.sh'"
```
Expected: `app/src/main/jniLibs/arm64-v8a/libdni.so` rebuilt (contains `dni_ffi_echo`/`dni_ffi_throw`/`dni_ffi_stream_start`). For the **x86_64 emulator**, also build that RID (`-r linux-bionic-x64`) into `jniLibs/x86_64/` and ensure `abiFilters` includes `x86_64`.

- [ ] **Step 2: JVM unit tests (no device)**

Run: `cd android && ./gradlew :app:testDebugUnitTest` → `BoundaryModelsTest` + `BoundaryViewModelTest` green.

- [ ] **Step 3: Assemble + install + instrumented walk on the emulator** (`Tablet_API35` AVD per the Android-emulator note)

Run: `cd android && ./gradlew :app:assembleDebug` (recompiles `jni_bridge.c` via CMake against the rebuilt `libdni.so`), then install on the running AVD and launch. Confirm: Boundary is the first tab; `echo` fills bytes/µs; `stream` shows the worker→JNI→UI thread-hop with real `managedThreadId`; `throw` shows the contained exception (no crash); Auto-step walks phases. Screenshot the Boundary screen (visual-parity rule).

- [ ] **Step 4: Done** — ABI consumed on both platforms; Boundary parity achieved.

---

## Self-Review

**Spec coverage:** swimlane hero w/ thread-hop + **visible JNI lane** (Task 6) ✓; byte/µs/memory/threads/ABI/err inspectors (Task 7) ✓; 5 presets + run/auto-step (Tasks 5, 8) ✓; A1 layout, Boundary first tab (Tasks 8, 9) ✓; the 3 exports bound correctly (Tasks 2, 3) ✓.

**Correctness vs the real ABI (the grounding agent's draft was wrong here — fixed):** `dni_ffi_echo` passes `len` (`strlen`); `dni_ffi_stream_start` passes `max_tokens`; `ffi_trace_callback`'s C params are in `dni_trace_cb` order (`is_final` *before* the two `int64`s); the `onTrace` JNI descriptor is `(ILjava/lang/String;JJZ)V` matching `onTrace(Int, String, Long, Long, Boolean)`. Cross-checked against `abi/dni.h` + the C# `InvokeTraced`.

**Type consistency:** `BoundaryService` signatures identical across interface / `FfiBoundaryService` / `FakeBoundaryService` / VM; `BoundaryUiState` fields match the composables; `BoundaryStreamToken` field order matches the listener call.

**Platform-honest difference:** Android shows the **JNI shim** lane (real extra hop) and the leak demo is illustrative (the JNI frees the result string eagerly via `take_native_string`) — both noted in-UI, not hidden.

**Placeholder note:** Tasks 7 & 8 describe the composables structurally (labels/copy mirror the committed iOS panels verbatim) rather than re-listing identical Compose for each of 6 panels; the iOS `BoundaryInspectorPanels.swift` / `BoundaryView.swift` are the exact reference. All load-bearing native/JNI/binding/service/VM code is given in full.
