# Android Parity SP0 — Native Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove a NativeAOT `libdni.so` with SQLCipher + llama.cpp statically linked loads on an arm64 Android emulator, the asset-free ABI round-trips over a RegisterNatives JNI shim, and both hostile native libs link + run — via a staged probe ladder (bare → +SQLCipher → +llama), documenting any failure with a shipped fallback.

**Architecture:** The .NET 10 NativeAOT engine cross-compiles to the `linux-bionic-arm64` RID. A thin C JNI shim (`dni_jni`) binds the C ABI (`dni.h`) to a Kotlin `NativeBridge` via `RegisterNatives`. Two path-parameterized gate-probe exports isolate the SQLCipher and llama link questions from the real RAG/SQLite data paths (which need ONNX + Android asset/temp plumbing owned by SP1–SP3). An instrumented `androidTest` walks the three rungs on the emulator.

**Tech Stack:** .NET 10 NativeAOT (`linux-bionic-arm64`), Android NDK r27 (`27.2.12479018`), CMake, JNI (`RegisterNatives`), Kotlin/Compose, `SQLitePCLRaw.bundle_e_sqlcipher`, llama.cpp (tag `b9542`, CPU-only), `androidx.test` instrumented tests.

**Spec:** `docs/superpowers/specs/2026-06-06-android-parity-sp0-native-gate-design.md`

---

## Conventions (read once, apply to every task)

- **Edit on Windows** in the repo root `C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend`. Commit there (authoritative git history). Branch: `feat/android-parity-sp0-native-gate` (already created).
- **Build/run on the Mac mini.** The Windows box has no C/C++ toolchain. Repo on the Mac: `/Users/steve/dotnet-ios-android-poc-native-frontend`. Wrap remote commands in a login shell so `dotnet`/`sdkmanager`/`cmake` are on PATH:
  ```bash
  ssh steve@steve-mac-mini "zsh -lc 'cd /Users/steve/dotnet-ios-android-poc-native-frontend && <cmd>'"
  ```
  Build **as `steve`** (NuGet cache + toolchain perms — see `docs/ios-build-deploy-runbook.md`).
- **Sync Windows → Mac before each remote build** (overlay only changed files; no commit needed for iteration). Run from the Windows repo root:
  ```bash
  tar -cf - <relative paths…> | ssh steve-mac-mini "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
  ```
- **Commits:** Conventional Commits, no AI/Co-Authored-By trailer (workspace rule). Frequent, one per task minimum.
- **Definition of done is "answered, not all-green":** if a rung won't link/run on bionic, capture the exact error in a findings doc and ship the fallback — that is a *passing* SP0 outcome (Task 13 covers this regardless).

---

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `build/build-android-so.sh` | Build llama android libs (S3) + publish `libdni.so` for `linux-bionic-arm64` → `jniLibs/arm64-v8a/` | T1, T11 |
| `native/llama-shim/build-llama.sh` | Add `android-arm64` NDK target; fix default tag to `b9542` | T9 |
| `core/.../NativeBridge.csproj` | `linux-bionic-arm64` hand-link blocks (SQLCipher T7, llama T10) | T7, T10 |
| `core/.../NativeBridge/Ffi/Exports.GateProbe.cs` (new) | `dni_sqlite_probe`, `dni_llama_probe` exports | T6, T10 |
| `core/.../NativeBridge/abi/dni_gate_probe.h` (new) | C declarations for the two probes (keeps `dni.h` frozen) | T6, T10 |
| `android/app/src/main/cpp/jni_bridge.c` | `RegisterNatives` shim, full ABI + probes | T2, T8, T12 |
| `android/app/.../transport/NativeBridge.kt` | Kotlin `external` facade matching the table | T3, T8, T12 |
| `android/app/src/androidTest/.../NativeGateTest.kt` (new) | Instrumented S1→S3 gate assertions | T4, T8, T12 |
| `docs/llama-nativeaot-android-findings.md` (new) | What worked / failed per rung | T13 |
| `.github/workflows/ci-android.yml` | Build llama libs + bionic publish + assembleDebug | T13 |

---

## Task 0: Verify the Mac build host & create the arm64 emulator

**Files:** none (environment). Verification only.

- [ ] **Step 1: Confirm dotnet + Android SDK/NDK present**

Run:
```bash
ssh steve@steve-mac-mini "zsh -lc 'dotnet --version; echo NDK=\$ANDROID_NDK_HOME ANDROID_HOME=\$ANDROID_HOME; ls \$ANDROID_HOME/ndk 2>/dev/null; which cmake adb emulator sdkmanager avdmanager'"
```
Expected: dotnet `10.x`; an `ndk/27.2.12479018` directory; `cmake`, `adb`, `emulator`, `sdkmanager` resolve. If `ANDROID_HOME` is empty it is usually `/Users/steve/Library/Android/sdk`.

- [ ] **Step 2: Install the exact NDK + an arm64 system image if missing**

Run (skip any line already satisfied):
```bash
ssh steve@steve-mac-mini "zsh -lc '
  yes | sdkmanager \"ndk;27.2.12479018\" \"platforms;android-35\" \"build-tools;35.0.0\" \
                  \"system-images;android-35;google_apis;arm64-v8a\" \"emulator\" \"platform-tools\"
'"
```
Expected: `done`. (Accepts licenses via `yes`.)

- [ ] **Step 3: Create + boot a headless arm64 AVD**

Run:
```bash
ssh steve@steve-mac-mini "zsh -lc '
  avdmanager list avd | grep -q dni_arm64 || \
    echo no | avdmanager create avd -n dni_arm64 -k \"system-images;android-35;google_apis;arm64-v8a\" --device pixel_6
  nohup emulator -avd dni_arm64 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect >/tmp/emu.log 2>&1 &
  adb wait-for-device; adb shell getprop sys.boot_completed
'"
```
Expected: eventually prints `1` (boot complete). Leave the emulator running for later tasks (re-boot with the same command if it dies).

- [ ] **Step 4: Record findings** (no commit — environment facts go in the findings doc in Task 13). Note the resolved `ANDROID_HOME`, NDK path, and AVD name for reuse.

---

## Task 1 (S1): Rewrite `build-android-so.sh` to publish a bare bionic `libdni.so`

**Files:**
- Modify: `build/build-android-so.sh` (full rewrite)

- [ ] **Step 1: Replace the stale script** (it references `e_sqlite3`, which the csproj no longer uses, and builds no native deps). Write `build/build-android-so.sh`:

```bash
#!/bin/bash
set -euo pipefail
# build-android-so.sh
# Publishes DotnetNativeInterop.NativeBridge for Android (linux-bionic-arm64) and copies the
# resulting dni.so to the Gradle jniLibs dir as libdni.so.
#
# Staged with the native gate (SP0):
#   - S1 (default): bare publish, no extra native libs.
#   - S3 (WITH_LLAMA=1): build llama android static libs first (see build-llama.sh android-arm64);
#     the .csproj hand-links them + e_sqlcipher for the linux-bionic-arm64 RID.
#
# NativeAOT for Android is experimental in .NET 10 (XA1040); DisableUnsupportedError suppresses it.

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CSPROJ="${PROJECT_DIR}/core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj"
PUBLISH_DIR="${PROJECT_DIR}/build/android-artifacts/linux-bionic-arm64"
JNILIB_DIR="${PROJECT_DIR}/android/app/src/main/jniLibs/arm64-v8a"
WITH_LLAMA="${WITH_LLAMA:-0}"

rm -rf "${PROJECT_DIR}/build/android-artifacts"
mkdir -p "${PUBLISH_DIR}" "${JNILIB_DIR}"

if [ "${WITH_LLAMA}" = "1" ]; then
  echo "[llama] building android-arm64 static libs"
  bash "${PROJECT_DIR}/native/llama-shim/build-llama.sh" android-arm64
fi

echo "[publish] linux-bionic-arm64 (WITH_LLAMA=${WITH_LLAMA})"
dotnet publish "${CSPROJ}" \
  -c Release \
  -r linux-bionic-arm64 \
  -o "${PUBLISH_DIR}" \
  -p:DisableUnsupportedError=true \
  -p:UseAppHost=false

if [ ! -f "${PUBLISH_DIR}/dni.so" ]; then
  echo "ERROR: dni.so not produced in ${PUBLISH_DIR}" >&2
  exit 1
fi

cp "${PUBLISH_DIR}/dni.so" "${JNILIB_DIR}/libdni.so"
echo "OK: ${JNILIB_DIR}/libdni.so ($(du -h "${JNILIB_DIR}/libdni.so" | cut -f1))"
```

- [ ] **Step 2: Sync + run the bare publish on the Mac**

Run from the Windows repo root:
```bash
tar -cf - build/build-android-so.sh | ssh steve-mac-mini "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
ssh steve@steve-mac-mini "zsh -lc 'cd /Users/steve/dotnet-ios-android-poc-native-frontend && bash build/build-android-so.sh'"
```
Expected: `OK: …/jniLibs/arm64-v8a/libdni.so (NN M)`. **This is the S1 make-or-break: does NativeAOT publish for `linux-bionic-arm64` at all?** If it fails, capture the exact error (Task 13) before proceeding.

- [ ] **Step 3: Commit**

```bash
git add build/build-android-so.sh
git commit -m "build(android): rewrite build-android-so.sh for bionic NativeAOT publish (S1)"
```

---

## Task 2 (S1): Rewrite `jni_bridge.c` to RegisterNatives covering the full real ABI

**Files:**
- Modify: `android/app/src/main/cpp/jni_bridge.c` (full rewrite — no probes yet)

- [ ] **Step 1: Write the new shim.** Replace the whole file with:

```c
/*
 * jni_bridge.c — JNI shim (dni_jni) binding the NativeAOT C ABI (dni.h) to
 * io.dotnetnativeinterop.transport.NativeBridge via RegisterNatives.
 *
 * Load order from Kotlin: System.loadLibrary("dni") then System.loadLibrary("dni_jni").
 * RegisterNatives (in JNI_OnLoad) decouples these C functions from the Kotlin package name,
 * so a future reverse-DNS rename cannot break symbol resolution.
 *
 * gRPC is intentionally absent: it is <Compile Remove>'d from the engine, so dni_grpc_* are
 * not exported by libdni.so.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>
#include <pthread.h>

#include "dni.h"

#define TAG "DotnetNativeInteropJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static JavaVM* g_jvm = NULL;
static pthread_key_t  g_tls_key;
static pthread_once_t g_tls_once = PTHREAD_ONCE_INIT;

static void tls_destructor(void* attached) {
    if (attached != NULL && g_jvm != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}
static void create_tls_key(void) { pthread_key_create(&g_tls_key, tls_destructor); }

static JNIEnv* attach_current_thread(void) {
    if (g_jvm == NULL) return NULL;
    pthread_once(&g_tls_once, create_tls_key);
    JNIEnv* env = NULL;
    jint res = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (res == JNI_OK) return env;
    if (res == JNI_EDETACHED) {
        JavaVMAttachArgs args = { .version = JNI_VERSION_1_6, .name = "dotnet-worker", .group = NULL };
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, &args) != JNI_OK) { LOGE("attach failed"); return NULL; }
        pthread_setspecific(g_tls_key, (void*)1);
        return env;
    }
    LOGE("GetEnv unexpected %d", res);
    return NULL;
}

/* ---- streaming callback (Pattern 3, shared by FFI + RAG) ----------------- */
typedef struct { jobject listener_ref; jmethodID on_token; } CallbackState;

static void ffi_token_callback(void* user_data, int32_t index, const char* text, int32_t is_final) {
    CallbackState* st = (CallbackState*)user_data;
    if (st == NULL) return;
    JNIEnv* env = attach_current_thread();
    if (env == NULL) return;
    jstring jtext = (*env)->NewStringUTF(env, text != NULL ? text : "");
    if (jtext == NULL) return;
    (*env)->CallVoidMethod(env, st->listener_ref, st->on_token, (jint)index, jtext, (jboolean)(is_final != 0));
    (*env)->DeleteLocalRef(env, jtext);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if (is_final) { (*env)->DeleteGlobalRef(env, st->listener_ref); free(st); }
}

/* ---- helpers ------------------------------------------------------------ */
/* Copy a heap C string returned by the ABI into a jstring, then release it. */
static jstring take_native_string(JNIEnv* env, const char* s) {
    if (s == NULL) return NULL;
    jstring js = (*env)->NewStringUTF(env, s);
    dni_string_free(s);
    return js;
}

static jlong start_streaming(JNIEnv* env, jstring text, jint max_tokens, jfloat temp,
                             jobject listener, int is_rag) {
    if (text == NULL || listener == NULL) return (jlong)DNI_INVALID_ARGUMENT;
    jclass lc = (*env)->GetObjectClass(env, listener);
    jmethodID on_token = (*env)->GetMethodID(env, lc, "onToken", "(ILjava/lang/String;Z)V");
    (*env)->DeleteLocalRef(env, lc);
    if (on_token == NULL) { LOGE("onToken not found"); return (jlong)DNI_INVALID_ARGUMENT; }
    CallbackState* st = (CallbackState*)malloc(sizeof(CallbackState));
    if (st == NULL) return (jlong)DNI_INTERNAL;
    st->listener_ref = (*env)->NewGlobalRef(env, listener);
    st->on_token = on_token;
    const char* c_text = (*env)->GetStringUTFChars(env, text, NULL);
    if (c_text == NULL) { (*env)->DeleteGlobalRef(env, st->listener_ref); free(st); return (jlong)DNI_INTERNAL; }
    int64_t sid = is_rag
        ? dni_rag_session_start(c_text, (int32_t)max_tokens, (float)temp, ffi_token_callback, st)
        : dni_session_start(c_text, (int32_t)max_tokens, (float)temp, ffi_token_callback, st);
    (*env)->ReleaseStringUTFChars(env, text, c_text);
    if (sid <= 0) { (*env)->DeleteGlobalRef(env, st->listener_ref); free(st); }
    return (jlong)sid;
}

/* ---- native method implementations (receiver is the NativeBridge object) - */
static jint    j_initialize(JNIEnv* e, jobject o) { (void)e; (void)o; return dni_initialize(); }
static void    j_shutdown  (JNIEnv* e, jobject o) { (void)e; (void)o; dni_shutdown(); }

static jlong   j_session_start(JNIEnv* e, jobject o, jstring p, jint mt, jfloat t, jobject l) {
    (void)o; return start_streaming(e, p, mt, t, l, 0); }
static jlong   j_rag_session_start(JNIEnv* e, jobject o, jstring q, jint mt, jfloat t, jobject l) {
    (void)o; return start_streaming(e, q, mt, t, l, 1); }
static jint    j_session_cancel(JNIEnv* e, jobject o, jlong id) { (void)e; (void)o; return dni_session_cancel((int64_t)id); }
static jint    j_session_free  (JNIEnv* e, jobject o, jlong id) { (void)e; (void)o; return dni_session_free((int64_t)id); }

static jint    j_http_start(JNIEnv* e, jobject o) { (void)e; (void)o; return dni_http_start(); }
static jint    j_http_stop (JNIEnv* e, jobject o) { (void)e; (void)o; return dni_http_stop(); }

static jint    j_broker_start(JNIEnv* e, jobject o, jstring path) {
    (void)o;
    if (path == NULL) return DNI_INVALID_ARGUMENT;
    const char* p = (*e)->GetStringUTFChars(e, path, NULL);
    if (p == NULL) return DNI_INTERNAL;
    int32_t r = dni_broker_start(p);
    (*e)->ReleaseStringUTFChars(e, path, p);
    return r;
}
static jint    j_broker_stop(JNIEnv* e, jobject o) { (void)e; (void)o; return dni_broker_stop(); }

static jstring j_features_json (JNIEnv* e, jobject o) { (void)o; return take_native_string(e, dni_features_json()); }
static jstring j_sqlite_features(JNIEnv* e, jobject o) { (void)o; return take_native_string(e, dni_sqlite_features()); }
static jstring j_engine_stats  (JNIEnv* e, jobject o) { (void)o; return take_native_string(e, dni_engine_stats()); }

static jstring j_feature_run(JNIEnv* e, jobject o, jstring id) {
    (void)o; if (id == NULL) return NULL;
    const char* cid = (*e)->GetStringUTFChars(e, id, NULL);
    const char* r = dni_feature_run(cid);
    (*e)->ReleaseStringUTFChars(e, id, cid);
    return take_native_string(e, r);
}
static jstring j_sqlite_run(JNIEnv* e, jobject o, jstring id) {
    (void)o; if (id == NULL) return NULL;
    const char* cid = (*e)->GetStringUTFChars(e, id, NULL);
    const char* r = dni_sqlite_run(cid);
    (*e)->ReleaseStringUTFChars(e, id, cid);
    return take_native_string(e, r);
}
static jstring j_sqlite_rag(JNIEnv* e, jobject o, jstring q) {
    (void)o; if (q == NULL) return NULL;
    const char* cq = (*e)->GetStringUTFChars(e, q, NULL);
    const char* r = dni_sqlite_rag(cq);
    (*e)->ReleaseStringUTFChars(e, q, cq);
    return take_native_string(e, r);
}
static jstring j_search(JNIEnv* e, jobject o, jstring q, jstring c) {
    (void)o; if (q == NULL || c == NULL) return NULL;
    const char* cq = (*e)->GetStringUTFChars(e, q, NULL);
    const char* cc = (*e)->GetStringUTFChars(e, c, NULL);
    const char* r = (cq && cc) ? dni_search(cq, cc) : NULL;
    if (cq) (*e)->ReleaseStringUTFChars(e, q, cq);
    if (cc) (*e)->ReleaseStringUTFChars(e, c, cc);
    return take_native_string(e, r);
}

/* ---- RegisterNatives table --------------------------------------------- */
static const JNINativeMethod kMethods[] = {
    {"nativeInitialize",     "()I",                                                                      (void*)j_initialize},
    {"nativeShutdown",       "()V",                                                                      (void*)j_shutdown},
    {"nativeSessionStart",   "(Ljava/lang/String;IFLio/dotnetnativeinterop/transport/FfiTokenListener;)J", (void*)j_session_start},
    {"nativeRagSessionStart","(Ljava/lang/String;IFLio/dotnetnativeinterop/transport/FfiTokenListener;)J", (void*)j_rag_session_start},
    {"nativeSessionCancel",  "(J)I",                                                                     (void*)j_session_cancel},
    {"nativeSessionFree",    "(J)I",                                                                     (void*)j_session_free},
    {"nativeHttpStart",      "()I",                                                                      (void*)j_http_start},
    {"nativeHttpStop",       "()I",                                                                      (void*)j_http_stop},
    {"nativeBrokerStart",    "(Ljava/lang/String;)I",                                                    (void*)j_broker_start},
    {"nativeBrokerStop",     "()I",                                                                      (void*)j_broker_stop},
    {"nativeFeaturesJson",   "()Ljava/lang/String;",                                                     (void*)j_features_json},
    {"nativeFeatureRun",     "(Ljava/lang/String;)Ljava/lang/String;",                                   (void*)j_feature_run},
    {"nativeSqliteFeatures", "()Ljava/lang/String;",                                                     (void*)j_sqlite_features},
    {"nativeSqliteRun",      "(Ljava/lang/String;)Ljava/lang/String;",                                   (void*)j_sqlite_run},
    {"nativeEngineStats",    "()Ljava/lang/String;",                                                     (void*)j_engine_stats},
    {"nativeSearch",         "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",                 (void*)j_search},
    {"nativeSqliteRag",      "(Ljava/lang/String;)Ljava/lang/String;",                                   (void*)j_sqlite_rag},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, "io/dotnetnativeinterop/transport/NativeBridge");
    if (clazz == NULL) { LOGE("NativeBridge class not found"); return JNI_ERR; }
    if ((*env)->RegisterNatives(env, clazz, kMethods, (jint)(sizeof(kMethods)/sizeof(kMethods[0]))) != JNI_OK) {
        LOGE("RegisterNatives failed"); return JNI_ERR;
    }
    (*env)->DeleteLocalRef(env, clazz);
    LOGD("dni_jni loaded; %d natives registered", (int)(sizeof(kMethods)/sizeof(kMethods[0])));
    return JNI_VERSION_1_6;
}
```

- [ ] **Step 2: Commit** (compiles in Task 4 via Gradle's NDK build; no standalone compile here)

```bash
git add android/app/src/main/cpp/jni_bridge.c
git commit -m "feat(android): rewrite JNI shim to RegisterNatives covering full ABI (S1)"
```

---

## Task 3 (S1): Update `NativeBridge.kt` to the full ABI facade

**Files:**
- Modify: `android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt` (full rewrite)

- [ ] **Step 1: Replace the file** (drop the dead gRPC declarations; add the new functions). Method names + signatures MUST match `kMethods` in Task 2 exactly:

```kotlin
package io.dotnetnativeinterop.transport

/**
 * Kotlin facade for the JNI shim (dni_jni). Native methods are bound via RegisterNatives in the
 * shim's JNI_OnLoad, so these names are decoupled from the package name.
 *
 * Load order: System.loadLibrary("dni") then System.loadLibrary("dni_jni").
 */
public object NativeBridge {
    // Lifecycle
    public external fun nativeInitialize(): Int
    public external fun nativeShutdown()

    // Pattern 3 — FFI streaming (RAG shares cancel/free + the FfiTokenListener callback)
    public external fun nativeSessionStart(prompt: String, maxTokens: Int, temperature: Float, listener: FfiTokenListener): Long
    public external fun nativeRagSessionStart(query: String, maxTokens: Int, temperature: Float, listener: FfiTokenListener): Long
    public external fun nativeSessionCancel(sessionId: Long): Int
    public external fun nativeSessionFree(sessionId: Long): Int

    // Pattern 1 — HTTP loopback
    public external fun nativeHttpStart(): Int
    public external fun nativeHttpStop(): Int

    // Pattern 4 — SQLite WAL broker
    public external fun nativeBrokerStart(dbPath: String): Int
    public external fun nativeBrokerStop(): Int

    // Structured feature catalog (string-returning; null on failure)
    public external fun nativeFeaturesJson(): String?
    public external fun nativeFeatureRun(id: String): String?
    public external fun nativeSqliteFeatures(): String?
    public external fun nativeSqliteRun(id: String): String?

    // Introspection + onboard AI
    public external fun nativeEngineStats(): String?
    public external fun nativeSearch(query: String, corpus: String): String?
    public external fun nativeSqliteRag(query: String): String?
}

/**
 * Per-token callback (Pattern 3). onToken fires on a .NET background thread already attached to the
 * JVM by the shim; implementations MUST NOT block — enqueue and return. Reused for RAG streaming.
 */
public interface FfiTokenListener {
    public fun onToken(index: Int, text: String, isFinal: Boolean)
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt
git commit -m "feat(android): NativeBridge facade for full ABI; drop dead gRPC externs (S1)"
```

---

## Task 4 (S1): Instrumented gate test — prove the bare `.so` round-trips on the emulator

**Files:**
- Create: `android/app/src/androidTest/kotlin/io/dotnetnativeinterop/NativeGateTest.kt`

- [ ] **Step 1: Write the S1 test.** (S2/S3 tests are appended in later tasks.)

```kotlin
package io.dotnetnativeinterop

import io.dotnetnativeinterop.transport.NativeBridge
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * SP0 native gate. Walks the staged probe ladder on a real arm64 emulator.
 * Emits PASS: lines to logcat (tag NativeGate) mirroring the iOS gate signal.
 */
public class NativeGateTest {

    public companion object {
        private const val TAG = "NativeGate"

        @JvmStatic
        @BeforeClass
        public fun loadLibraries() {
            System.loadLibrary("dni")      // NativeAOT lib first — all C symbols resident
            System.loadLibrary("dni_jni")  // shim registers natives in JNI_OnLoad
        }

        private fun pass(msg: String) = android.util.Log.i(TAG, "PASS: $msg")
    }

    @Test
    public fun s1_bareAbiRoundTrips() {
        assertEquals("dni_initialize", 0, NativeBridge.nativeInitialize())
        pass("dni_initialize == 0")

        val json = NativeBridge.nativeFeaturesJson()
        assertTrue("features json non-empty", !json.isNullOrBlank())
        val arr = JSONArray(json)
        assertTrue("features catalog non-empty", arr.length() > 0)
        pass("dni_features_json -> ${arr.length()} features")

        val firstId = arr.getJSONObject(0).getString("id")
        val run = NativeBridge.nativeFeatureRun(firstId)
        assertTrue("feature_run non-empty", !run.isNullOrBlank())
        assertTrue("feature_run ok==true", org.json.JSONObject(run).getBoolean("ok"))
        pass("dni_feature_run($firstId).ok == true")
    }
}
```

- [ ] **Step 2: Build the bare `.so`, then build+install+run the test on the emulator**

Run from the Windows repo root:
```bash
tar -cf - android/app/src core/DotnetNativeInterop.NativeBridge/abi | ssh steve-mac-mini "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
ssh steve@steve-mac-mini "zsh -lc '
  cd /Users/steve/dotnet-ios-android-poc-native-frontend &&
  bash build/build-android-so.sh &&
  adb wait-for-device &&
  cd android && ./gradlew :app:connectedDebugAndroidTest --tests io.dotnetnativeinterop.NativeGateTest
'"
```
Expected: `BUILD SUCCESSFUL`; `s1_bareAbiRoundTrips` passes. Confirm the gate signal:
```bash
ssh steve@steve-mac-mini "zsh -lc 'adb logcat -d -s NativeGate'"
```
Expected: three `PASS:` lines. **If `connectedDebugAndroidTest` fails at native build, the `RegisterNatives` table or a JNI signature is wrong — fix before moving on.**

- [ ] **Step 3: Commit**

```bash
git add android/app/src/androidTest/kotlin/io/dotnetnativeinterop/NativeGateTest.kt
git commit -m "test(android): instrumented S1 gate — bare ABI round-trip on emulator"
```

---

## Task 5 (S2): Resolve the bionic `e_sqlcipher` static lib path

**Files:** none yet (investigation feeding Task 7). Verification only.

- [ ] **Step 1: Check whether the SQLCipher bundle ships a bionic native**

Run:
```bash
ssh steve@steve-mac-mini "zsh -lc '
  find ~/.nuget/packages/sqlitepclraw.lib.e_sqlcipher* -path \"*linux-bionic*\" 2>/dev/null;
  find ~/.nuget/packages/sqlitepclraw.lib.e_sqlcipher* -name \"*.a\" 2>/dev/null | head
'"
```
Expected, one of:
- **(A)** a `runtimes/linux-bionic-arm64/native/*.a` (or `.so`) exists → use it directly in Task 7.
- **(B)** nothing for bionic → SQLCipher must be built for the Android NDK from source (fallback in Task 7 Step 2).

- [ ] **Step 2: Record which branch (A/B) applies.** No commit (the choice is encoded in Task 7).

---

## Task 6 (S2): Add the `dni_sqlite_probe` export + its header

**Files:**
- Create: `core/DotnetNativeInterop.NativeBridge/abi/dni_gate_probe.h`
- Create: `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.GateProbe.cs`

- [ ] **Step 1: Write the probe header** (`abi/dni_gate_probe.h`):

```c
/* dni_gate_probe.h — SP0 Android native-link gate probes (NOT part of the frozen dni.h ABI).
 * Path-parameterized so they avoid the APK-asset / unwritable-/tmp issues the real data paths
 * have on Android. Remove/replace once SP1/SP2 wire the real paths. Returns are heap UTF-8
 * strings freed with dni_string_free; NULL on failure. */
#ifndef DNI_GATE_PROBE_H
#define DNI_GATE_PROBE_H
#ifdef __cplusplus
extern "C" {
#endif
const char* dni_sqlite_probe(const char* db_path);                 /* "ok:<rows>" or NULL */
const char* dni_llama_probe(const char* gguf_path, const char* prompt); /* generated text or NULL */
#ifdef __cplusplus
}
#endif
#endif /* DNI_GATE_PROBE_H */
```

- [ ] **Step 2: Write the SQLCipher probe** (`Ffi/Exports.GateProbe.cs`) — the llama probe is appended in Task 10:

```csharp
using System.Runtime.InteropServices;
using Microsoft.Data.Sqlite;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// SP0 Android native-link gate probes. Path-parameterized to sidestep the APK-asset and
/// unwritable-/tmp issues the real exports have on Android. Returns plain strings (reflection-free
/// under NativeAOT). NOT part of the frozen dni.h contract — see abi/dni_gate_probe.h.
/// </summary>
internal static class GateProbe
{
    /// <summary>Opens an encrypted e_sqlcipher db at <paramref name="dbPath"/>, round-trips a row.
    /// Returns "ok:&lt;rows&gt;" or 0 (NULL) on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_sqlite_probe")]
    public static unsafe nint SqliteProbe(byte* dbPath)
    {
        try
        {
            var path = NativeText.Read((nint)dbPath);
            SQLitePCL.Batteries_V2.Init();
            var cs = new SqliteConnectionStringBuilder
            {
                DataSource = path,
                Mode = SqliteOpenMode.ReadWriteCreate,
                Password = "dni-gate-key",   // => PRAGMA key (SQLCipher encrypts at rest)
                Pooling = false,
            }.ToString();

            using var conn = new SqliteConnection(cs);
            conn.Open();
            Exec(conn, "CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY, v TEXT);");
            Exec(conn, "INSERT INTO probe (v) VALUES ('ok');");
            long rows;
            using (var cmd = conn.CreateCommand())
            {
                cmd.CommandText = "SELECT COUNT(*) FROM probe;";
                rows = (long)cmd.ExecuteScalar()!;
            }
            return NativeText.Allocate($"ok:{rows}");
        }
        catch (Exception)
        {
            return 0;
        }
    }

    private static void Exec(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }
}
```

- [ ] **Step 3: Host sanity-check the probe logic** (win-x64 auto-resolves `e_sqlcipher`, so the C# is verified independent of bionic). On the Windows box:

```bash
dotnet build core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj -c Release
```
Expected: build succeeds (the new file compiles). The export's runtime behavior is proven on-device in Task 8.

- [ ] **Step 4: Commit**

```bash
git add core/DotnetNativeInterop.NativeBridge/abi/dni_gate_probe.h core/DotnetNativeInterop.NativeBridge/Ffi/Exports.GateProbe.cs
git commit -m "feat(engine): add dni_sqlite_probe gate export + dni_gate_probe.h (S2)"
```

---

## Task 7 (S2): Hand-link `e_sqlcipher` into the bionic NativeAOT image

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj`

- [ ] **Step 1: Add the bionic SQLCipher restore + link.** Insert after the iOS `e_sqlcipher` ItemGroups (around line 42). **Branch A** (bundle ships a bionic native — from Task 5):

```xml
  <!-- SQLCipher for Android (linux-bionic-arm64): the bundle's auto-link targets only fire for
       -android TFMs, not NativeAOT RIDs, so hand-link exactly like the iOS blocks above. -->
  <ItemGroup Condition="'$(RuntimeIdentifier)' == 'linux-bionic-arm64'">
    <PackageReference Include="SQLitePCLRaw.lib.e_sqlcipher.android" Version="2.1.11"
                      GeneratePathProperty="true" ExcludeAssets="all" PrivateAssets="all" />
  </ItemGroup>
  <ItemGroup Condition="'$(RuntimeIdentifier)' == 'linux-bionic-arm64'">
    <DirectPInvoke Include="e_sqlcipher" />
    <NativeLibrary Include="$(PkgSQLitePCLRaw_lib_e_sqlcipher_android)/runtimes/linux-bionic-arm64/native/libe_sqlcipher.a" />
  </ItemGroup>
```

> **Branch B** (no bionic static lib in the bundle): instead build SQLCipher with the NDK and point `<NativeLibrary>` at the built `libe_sqlcipher.a`. Document the exact source/tag in Task 13. Do not block the gate: if SQLCipher cannot be produced for bionic, mark S2 a documented failure (Task 13) and skip to S3 with the SQLite transport noted unsupported on Android.

- [ ] **Step 2: Rebuild `libdni.so` with SQLCipher; confirm it still publishes**

Run from the Windows repo root:
```bash
tar -cf - core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj | ssh steve-mac-mini "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
ssh steve@steve-mac-mini "zsh -lc 'cd /Users/steve/dotnet-ios-android-poc-native-frontend && bash build/build-android-so.sh'"
```
Expected: `OK: …/libdni.so`. **This answers the S2 unknown: does `e_sqlcipher` static-link under NativeAOT-bionic?** Capture any linker error for Task 13.

- [ ] **Step 3: Commit**

```bash
git add core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj
git commit -m "build(android): hand-link e_sqlcipher into the bionic NativeAOT image (S2)"
```

---

## Task 8 (S2): Bind + assert the SQLCipher probe on the emulator

**Files:**
- Modify: `android/app/src/main/cpp/jni_bridge.c` (add probe include, impl, table row)
- Modify: `android/app/.../transport/NativeBridge.kt` (add `nativeSqliteProbe`)
- Modify: `android/app/src/androidTest/.../NativeGateTest.kt` (add S2 test)

- [ ] **Step 1: jni_bridge.c — include the probe header** (after `#include "dni.h"`):

```c
#include "dni_gate_probe.h"
```

- [ ] **Step 2: jni_bridge.c — add the impl** (after `j_search`):

```c
static jstring j_sqlite_probe(JNIEnv* e, jobject o, jstring path) {
    (void)o; if (path == NULL) return NULL;
    const char* p = (*e)->GetStringUTFChars(e, path, NULL);
    const char* r = p ? dni_sqlite_probe(p) : NULL;
    if (p) (*e)->ReleaseStringUTFChars(e, path, p);
    return take_native_string(e, r);
}
```

- [ ] **Step 3: jni_bridge.c — add the table row** (inside `kMethods[]`, after `nativeSqliteRag`):

```c
    {"nativeSqliteProbe",    "(Ljava/lang/String;)Ljava/lang/String;",                                  (void*)j_sqlite_probe},
```

- [ ] **Step 4: NativeBridge.kt — add the external** (after `nativeSqliteRag`):

```kotlin
    // SP0 gate probe (path-parameterized; see dni_gate_probe.h)
    public external fun nativeSqliteProbe(dbPath: String): String?
```

- [ ] **Step 5: NativeGateTest.kt — add the S2 test method**:

```kotlin
    @Test
    public fun s2_sqlcipherLinksAndEncrypts() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dbPath = java.io.File(ctx.filesDir, "gate.db").absolutePath
        java.io.File(dbPath).delete()
        val result = NativeBridge.nativeSqliteProbe(dbPath)
        assertTrue("sqlite probe non-null", result != null)
        assertTrue("sqlite probe ok:N (got $result)", result!!.startsWith("ok:"))
        pass("dni_sqlite_probe -> $result")
    }
```

- [ ] **Step 6: Sync, rebuild `.so`, run S1+S2 on the emulator**

```bash
tar -cf - android/app/src core/DotnetNativeInterop.NativeBridge | ssh steve-mac-mini "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
ssh steve@steve-mac-mini "zsh -lc '
  cd /Users/steve/dotnet-ios-android-poc-native-frontend &&
  bash build/build-android-so.sh &&
  adb wait-for-device &&
  cd android && ./gradlew :app:connectedDebugAndroidTest --tests io.dotnetnativeinterop.NativeGateTest
'"
```
Expected: both tests pass; `adb logcat -d -s NativeGate` shows `PASS: dni_sqlite_probe -> ok:1`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/cpp/jni_bridge.c android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt android/app/src/androidTest/kotlin/io/dotnetnativeinterop/NativeGateTest.kt
git commit -m "test(android): bind + assert dni_sqlite_probe on emulator (S2 green)"
```

---

## Task 9 (S3): Add an `android-arm64` target to `build-llama.sh`

**Files:**
- Modify: `native/llama-shim/build-llama.sh`

- [ ] **Step 1: Fix the default tag + add the NDK case.** Change the pinned tag to the proven one and add `android-arm64`:

Change line 6 from `LLAMA_TAG="${LLAMA_TAG:-b4585}"` to:
```bash
LLAMA_TAG="${LLAMA_TAG:-b9542}"   # proven working tag (matches the iOS llama gate findings)
```
Add a new `case` arm (after the `iossimulator-arm64)` block, before `host)`):
```bash
  android-arm64)
    : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK 27 dir}"
    CMAKE_ARGS+=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
                 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
                 -DGGML_NATIVE=OFF) ;;
```

> Note: `GGML_NATIVE=OFF` stops ggml probing the *host* CPU for the cross target. CPU-only flags (`GGML_METAL/BLAS/ACCELERATE=OFF`) are already in the shared `CMAKE_ARGS`.

- [ ] **Step 2: Build the android static libs on the Mac**

```bash
tar -cf - native/llama-shim/build-llama.sh | ssh steve-mac-mini "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
ssh steve@steve-mac-mini "zsh -lc '
  cd /Users/steve/dotnet-ios-android-poc-native-frontend &&
  export ANDROID_NDK_HOME=\"\$ANDROID_HOME/ndk/27.2.12479018\" &&
  bash native/llama-shim/build-llama.sh android-arm64
'"
```
Expected: `Built static libs in …/build/android-arm64/lib` listing `libdni_llama.a libllama.a libggml.a libggml-cpu.a libggml-base.a`. **Make-or-break S3 part 1: does llama.cpp cross-compile for android-arm64 via the NDK?**

- [ ] **Step 3: Commit**

```bash
git add native/llama-shim/build-llama.sh
git commit -m "build(llama): add android-arm64 NDK target; pin default tag b9542 (S3)"
```

---

## Task 10 (S3): Add the `dni_llama_probe` export + hand-link llama into the bionic image

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.GateProbe.cs` (append llama probe)
- Modify: `core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj` (bionic llama link)

- [ ] **Step 1: Append the llama probe** to `Exports.GateProbe.cs` (add `using` + method inside `GateProbe`):

Add at the top with the other usings:
```csharp
using System.Text;
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.Engine.Llama;
```
Add inside the `GateProbe` class:
```csharp
    /// <summary>Loads <paramref name="ggufPath"/> via llama.cpp, decodes a few tokens of
    /// <paramref name="prompt"/>, returns the generated text or 0 (NULL) on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_llama_probe")]
    public static unsafe nint LlamaProbe(byte* ggufPath, byte* prompt)
    {
        try
        {
            var path = NativeText.Read((nint)ggufPath);
            var p = NativeText.Read((nint)prompt);
            using var model = new LlamaLanguageModel(path);
            var sb = new StringBuilder();
            var req = new InferenceRequest(p, 16, 0.7f);
            // UnmanagedCallersOnly must be sync; block on the async stream.
            Task.Run(async () =>
            {
                await foreach (var piece in model.GenerateAsync(req).ConfigureAwait(false))
                {
                    sb.Append(piece);
                }
            }).GetAwaiter().GetResult();
            return NativeText.Allocate(sb.ToString());
        }
        catch (Exception)
        {
            return 0;
        }
    }
```

- [ ] **Step 2: Add the bionic llama hand-link to the csproj.** After the iOS llama ItemGroups (around line 94), add:

```xml
  <!-- llama.cpp for Android (linux-bionic-arm64): static libs built by build-llama.sh android-arm64,
       hand-linked exactly like the iOS blocks. llama.cpp is C++ — link the NDK libc++. -->
  <ItemGroup Condition="'$(RuntimeIdentifier)' == 'linux-bionic-arm64'">
    <DirectPInvoke Include="dni_llama" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/android-arm64/lib/libdni_llama.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/android-arm64/lib/libllama.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/android-arm64/lib/libggml.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/android-arm64/lib/libggml-cpu.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/android-arm64/lib/libggml-base.a" />
    <LinkerArg Include="-lc++" />
    <LinkerArg Include="-lc++abi" />
  </ItemGroup>
```

> If the bionic linker can't find `c++`/`c++abi`, the NDK static libc++ may need an explicit path (`-L$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android`). Record the working incantation in Task 13.

- [ ] **Step 3: Build the full-stack `.so` (llama + SQLCipher)**

```bash
tar -cf - core/DotnetNativeInterop.NativeBridge | ssh steve-mac-mini "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
ssh steve@steve-mac-mini "zsh -lc '
  cd /Users/steve/dotnet-ios-android-poc-native-frontend &&
  export ANDROID_NDK_HOME=\"\$ANDROID_HOME/ndk/27.2.12479018\" &&
  WITH_LLAMA=1 bash build/build-android-so.sh
'"
```
Expected: `OK: …/libdni.so` (notably larger — llama+ggml linked). **Make-or-break S3 part 2: does NativeAOT-bionic link static C++ (llama+ggml)?** Capture any linker error for Task 13.

- [ ] **Step 4: Commit**

```bash
git add core/DotnetNativeInterop.NativeBridge/Ffi/Exports.GateProbe.cs core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj
git commit -m "build(android): dni_llama_probe + hand-link llama.cpp into the bionic image (S3)"
```

---

## Task 11 (S3): Bind + assert the llama probe on the emulator

**Files:**
- Modify: `android/app/src/main/cpp/jni_bridge.c` (llama probe impl + table row)
- Modify: `android/app/.../transport/NativeBridge.kt` (`nativeLlamaProbe`)
- Modify: `android/app/src/androidTest/.../NativeGateTest.kt` (S3 test)

- [ ] **Step 1: jni_bridge.c — add the impl** (after `j_sqlite_probe`):

```c
static jstring j_llama_probe(JNIEnv* e, jobject o, jstring gguf, jstring prompt) {
    (void)o; if (gguf == NULL || prompt == NULL) return NULL;
    const char* g = (*e)->GetStringUTFChars(e, gguf, NULL);
    const char* p = (*e)->GetStringUTFChars(e, prompt, NULL);
    const char* r = (g && p) ? dni_llama_probe(g, p) : NULL;
    if (g) (*e)->ReleaseStringUTFChars(e, gguf, g);
    if (p) (*e)->ReleaseStringUTFChars(e, prompt, p);
    return take_native_string(e, r);
}
```

- [ ] **Step 2: jni_bridge.c — add the table row** (after `nativeSqliteProbe`):

```c
    {"nativeLlamaProbe",     "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",                (void*)j_llama_probe},
```

- [ ] **Step 3: NativeBridge.kt — add the external** (after `nativeSqliteProbe`):

```kotlin
    public external fun nativeLlamaProbe(ggufPath: String, prompt: String): String?
```

- [ ] **Step 4: NativeGateTest.kt — add the S3 test** (reads the GGUF pushed to `/data/local/tmp`):

```kotlin
    @Test
    public fun s3_llamaLinksAndDecodes() {
        val gguf = "/data/local/tmp/gate.gguf"
        assertTrue("push the probe GGUF first: adb push <model> $gguf", java.io.File(gguf).exists())
        val text = NativeBridge.nativeLlamaProbe(gguf, "Hello")
        assertTrue("llama probe non-null", text != null)
        assertTrue("llama probe produced text", !text.isNullOrBlank())
        pass("dni_llama_probe -> \"${text!!.take(40).replace("\n", " ")}…\"")
    }
```

- [ ] **Step 5: Fetch a small probe GGUF + push it to the emulator** (Qwen2.5-0.5B-Instruct Q4, ~300 MiB — the iOS host-probe model):

```bash
ssh steve@steve-mac-mini "zsh -lc '
  cd /tmp &&
  [ -f gate.gguf ] || curl -L -o gate.gguf \
    https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf &&
  adb wait-for-device && adb push gate.gguf /data/local/tmp/gate.gguf && adb shell ls -l /data/local/tmp/gate.gguf
'"
```
Expected: the file lists on the device. (If the URL 404s, any small Q4 GGUF works — record the one used in Task 13.)

- [ ] **Step 6: Rebuild full-stack `.so`, run S1+S2+S3 on the emulator**

```bash
tar -cf - android/app/src | ssh steve-mac-mini "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
ssh steve@steve-mac-mini "zsh -lc '
  cd /Users/steve/dotnet-ios-android-poc-native-frontend &&
  export ANDROID_NDK_HOME=\"\$ANDROID_HOME/ndk/27.2.12479018\" &&
  WITH_LLAMA=1 bash build/build-android-so.sh &&
  adb wait-for-device &&
  cd android && ./gradlew :app:connectedDebugAndroidTest --tests io.dotnetnativeinterop.NativeGateTest
'"
ssh steve@steve-mac-mini "zsh -lc 'adb logcat -d -s NativeGate'"
```
Expected: all three tests pass; logcat shows the five `PASS:` lines. **If llama loads but the JNI process crashes (SIGABRT/SIGSEGV) on decode, that is the gate's real answer — document it (Task 13) and ship the managed `ExtractiveLanguageModel` fallback.**

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/cpp/jni_bridge.c android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt android/app/src/androidTest/kotlin/io/dotnetnativeinterop/NativeGateTest.kt
git commit -m "test(android): bind + assert dni_llama_probe on emulator (S3 green)"
```

---

## Task 12: Document the gate, update CI, update README

**Files:**
- Create: `docs/llama-nativeaot-android-findings.md`
- Modify: `.github/workflows/ci-android.yml`
- Modify: `README.md` (Status section)

- [ ] **Step 1: Write the findings doc** — what worked / what failed, per rung, with exact errors and the resolved toolchain facts (NDK path, `ANDROID_NDK_HOME`, SQLCipher branch A/B from Task 5, libc++ incantation from Task 10, probe model URL). Mirror the structure of `docs/llama-nativeaot-ios-findings.md`. Include the `PASS:` logcat lines as the gate signal. If any rung failed, state the fallback shipped.

- [ ] **Step 2: Update `ci-android.yml`** to build the real path. Minimum viable (no emulator in CI): install NDK 27, `WITH_LLAMA=1 bash build/build-android-so.sh`, then `./gradlew :app:assembleDebug`. Add a comment that the instrumented gate test runs on the Mac mini emulator (emulator-in-CI is a stretch). Verify the workflow file parses:

```bash
ssh steve@steve-mac-mini "zsh -lc 'cd /Users/steve/dotnet-ios-android-poc-native-frontend && python3 -c \"import yaml,sys; yaml.safe_load(open(\\\".github/workflows/ci-android.yml\\\"))\" && echo YAML-OK'"
```
Expected: `YAML-OK`.

- [ ] **Step 3: Update the README Status** — change the Android line from "open follow-on" to the gate outcome (SP0 native gate passed/answered on emulator; SP1–SP5 are the remaining parity cycles). Keep it honest about what's proven (linkage + probes) vs deferred (full RAG/EVS/UI).

- [ ] **Step 4: Commit**

```bash
git add docs/llama-nativeaot-android-findings.md .github/workflows/ci-android.yml README.md
git commit -m "docs(android): SP0 native-gate findings; CI bionic build; README status"
```

---

## Task 13: Open the PR

- [ ] **Step 1: Push the branch + open a PR** (workspace rule — merge via squash PR, never push to `main`):

```bash
git push -u origin feat/android-parity-sp0-native-gate
gh pr create --title "Android parity SP0: native gate (bionic libdni.so + JNI + probes)" --body "$(cat <<'EOF'
## Summary
- SP0 of Android 1:1 parity: prove NativeAOT libdni.so links + runs on android-arm64.
- Staged probe ladder: bare bionic AOT -> +SQLCipher -> +llama.cpp, each asserted on an arm64 emulator.
- JNI shim rewritten to RegisterNatives covering the full ABI; two path-parameterized gate probes
  (dni_sqlite_probe, dni_llama_probe) isolate the native-link question from RAG/SQLite data paths
  (which need ONNX + Android asset/temp plumbing — SP1/SP2/SP3).

## Outcome
- See docs/llama-nativeaot-android-findings.md for what linked/ran vs what was documented + fell back.

## Test plan
- [ ] ./gradlew :app:connectedDebugAndroidTest --tests io.dotnetnativeinterop.NativeGateTest (5 PASS lines)
- [ ] WITH_LLAMA=1 build/build-android-so.sh produces libdni.so
- [ ] CI ci-android: bionic publish + assembleDebug green
EOF
)"
```
Expected: PR URL. Report it.

---

## Self-Review (completed during authoring)

- **Spec coverage:** SP0 DoD → Tasks 4/8/11 (S1/S2/S3 assertions). Staged ladder → Tasks 1–11. JNI RegisterNatives → Task 2. Probes → Tasks 6/10. csproj bionic links → Tasks 7/10. build-llama android target → Task 9. build-android-so rewrite → Tasks 1/9-step? (rewritten T1, llama wired via `WITH_LLAMA` T1+T9). Proof harness → Tasks 4/8/11. Failure policy → called out in Tasks 1/7/10/11 + documented in Task 12. CI → Task 12. Out-of-scope (GrpcUdsClient removal, ONNX, UI) → untouched. ✅
- **Placeholder scan:** no TBD/TODO; every code step shows full code; commands have expected output. Branch B (SQLCipher-from-source) is a documented contingency, not a placeholder — the gate proceeds either way. ✅
- **Type consistency:** `kMethods` signatures ↔ `NativeBridge.kt` externals ↔ JNI impls cross-checked (names `native*`, JNI descriptors, arg order). `LlamaLanguageModel(path)` + `GenerateAsync(InferenceRequest, ct)` match the engine. `FfiTokenListener.onToken(ILjava/lang/String;Z)V` matches existing code. ✅
- **Probe correctness:** returns plain strings via `NativeText.Allocate` (reflection-free); freed by `dni_string_free` in `take_native_string`. ✅
