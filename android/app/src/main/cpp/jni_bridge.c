/*
 * jni_bridge.c — JNI shim between Kotlin and the NativeAOT C ABI.
 *
 * Load order required from Kotlin:
 *   System.loadLibrary("ondevicellm")       // NativeAOT .so — must be first
 *   System.loadLibrary("ondevicellm_jni")   // this shim
 *
 * Threading contract (FFI callback):
 *   The NativeAOT runtime invokes ondevicellm_token_cb on a .NET background thread.
 *   That thread is NOT a JVM thread; we must AttachCurrentThread once per worker
 *   thread before calling back into Java/Kotlin.  We use a pthread_once-per-thread
 *   TLS destructor to DetachCurrentThread when the thread exits, preventing leaks.
 *
 *   Flow:
 *     1. JNI_OnLoad caches the JavaVM*.
 *     2. Kotlin calls nativeSessionStart(prompt, maxTokens, temp, listener).
 *        listener is a global ref to a FfiTokenListener Kotlin object.
 *     3. We store (global JavaVM*, global listener ref, methodID) in a heap-
 *        allocated CallbackState passed as user_data.
 *     4. C callback fires on .NET thread → AttachCurrentThread (idempotent if
 *        already attached via TLS flag) → CallVoidMethod → check for Exception.
 *     5. When is_final==1, DeleteGlobalRef(listener) and free(state).
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <pthread.h>

#include "ondevicellm.h"

#define TAG "OnDeviceLlmJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

/* --------------------------------------------------------------------------
 * Cached JVM — set in JNI_OnLoad, valid for the process lifetime.
 * -------------------------------------------------------------------------- */
static JavaVM* g_jvm = NULL;

/* --------------------------------------------------------------------------
 * Per-thread attach tracking via TLS destructor.
 * -------------------------------------------------------------------------- */
static pthread_key_t  g_tls_key;
static pthread_once_t g_tls_once = PTHREAD_ONCE_INIT;

static void tls_destructor(void* attached) {
    if (attached != NULL && g_jvm != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
        LOGD("DetachCurrentThread on worker thread exit");
    }
}

static void create_tls_key(void) {
    pthread_key_create(&g_tls_key, tls_destructor);
}

/*
 * attach_current_thread — attaches the calling thread to the JVM if needed.
 * Returns a valid JNIEnv* or NULL on failure.
 * The TLS destructor ensures DetachCurrentThread is called when the .NET
 * background thread is eventually destroyed.
 */
static JNIEnv* attach_current_thread(void) {
    if (g_jvm == NULL) return NULL;

    pthread_once(&g_tls_once, create_tls_key);

    JNIEnv* env = NULL;
    jint res = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);

    if (res == JNI_OK) {
        /* Already attached (e.g. this is the main thread or a re-entrant call). */
        return env;
    }

    if (res == JNI_EDETACHED) {
        JavaVMAttachArgs args = {
            .version = JNI_VERSION_1_6,
            .name    = "dotnet-worker",
            .group   = NULL,
        };
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, &args) != JNI_OK) {
            LOGE("AttachCurrentThread failed");
            return NULL;
        }
        /* Mark TLS so the destructor will Detach when this thread exits. */
        pthread_setspecific(g_tls_key, (void*)1);
        LOGD("AttachCurrentThread succeeded for .NET worker");
        return env;
    }

    LOGE("GetEnv returned unexpected code %d", res);
    return NULL;
}

/* --------------------------------------------------------------------------
 * Per-session callback state (heap-allocated, owned by the callback).
 * -------------------------------------------------------------------------- */
typedef struct {
    jobject  listener_ref;  /* global ref to FfiTokenListener Kotlin object */
    jmethodID on_token;     /* void onToken(int index, String text, boolean isFinal) */
} CallbackState;

/* --------------------------------------------------------------------------
 * The C callback invoked by .NET per token.
 * -------------------------------------------------------------------------- */
static void ffi_token_callback(void* user_data,
                                int32_t index,
                                const char* text,
                                int32_t is_final) {
    CallbackState* state = (CallbackState*)user_data;
    if (state == NULL) return;

    JNIEnv* env = attach_current_thread();
    if (env == NULL) {
        LOGE("ffi_token_callback: could not obtain JNIEnv");
        return;
    }

    /* Copy text to a Java String immediately — the C string is only valid now. */
    jstring jtext = (*env)->NewStringUTF(env, text != NULL ? text : "");
    if (jtext == NULL) {
        LOGE("ffi_token_callback: NewStringUTF OOM");
        return;
    }

    (*env)->CallVoidMethod(env,
                           state->listener_ref,
                           state->on_token,
                           (jint)index,
                           jtext,
                           (jboolean)(is_final != 0));

    (*env)->DeleteLocalRef(env, jtext);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        LOGE("ffi_token_callback: Kotlin onToken threw an exception");
    }

    /* When the stream is done, release the global ref and free state. */
    if (is_final) {
        (*env)->DeleteGlobalRef(env, state->listener_ref);
        free(state);
    }
}

/* ==========================================================================
 * JNI_OnLoad — called when System.loadLibrary("ondevicellm_jni") executes.
 * ========================================================================== */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    LOGD("JNI_OnLoad — ondevicellm_jni loaded, JavaVM cached");
    return JNI_VERSION_1_6;
}

/* ==========================================================================
 * Lifecycle
 * ========================================================================== */

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeInitialize(
        JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return ondevicellm_initialize();
}

JNIEXPORT void JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeShutdown(
        JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    ondevicellm_shutdown();
}

/* ==========================================================================
 * Pattern 3 — FFI
 *
 * Kotlin signature:
 *   external fun nativeSessionStart(
 *       prompt: String, maxTokens: Int, temperature: Float,
 *       listener: FfiTokenListener
 *   ): Long
 * ========================================================================== */

JNIEXPORT jlong JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeSessionStart(
        JNIEnv* env, jclass clazz,
        jstring prompt, jint max_tokens, jfloat temperature,
        jobject listener) {
    (void)clazz;

    if (prompt == NULL || listener == NULL) {
        return (jlong)ONDEVICELLM_INVALID_ARGUMENT;
    }

    /* Cache the listener method ID (class invariant — same for every call). */
    jclass listener_class = (*env)->GetObjectClass(env, listener);
    jmethodID on_token = (*env)->GetMethodID(
        env, listener_class, "onToken", "(ILjava/lang/String;Z)V");
    (*env)->DeleteLocalRef(env, listener_class);
    if (on_token == NULL) {
        LOGE("nativeSessionStart: onToken method not found");
        return (jlong)ONDEVICELLM_INVALID_ARGUMENT;
    }

    CallbackState* state = (CallbackState*)malloc(sizeof(CallbackState));
    if (state == NULL) {
        LOGE("nativeSessionStart: malloc failed");
        return (jlong)ONDEVICELLM_INTERNAL;
    }
    state->listener_ref = (*env)->NewGlobalRef(env, listener);
    state->on_token     = on_token;

    const char* c_prompt = (*env)->GetStringUTFChars(env, prompt, NULL);
    if (c_prompt == NULL) {
        (*env)->DeleteGlobalRef(env, state->listener_ref);
        free(state);
        return (jlong)ONDEVICELLM_INTERNAL;
    }

    int64_t session_id = ondevicellm_session_start(
        c_prompt,
        (int32_t)max_tokens,
        (float)temperature,
        ffi_token_callback,
        state);

    (*env)->ReleaseStringUTFChars(env, prompt, c_prompt);

    if (session_id <= 0) {
        /* Start failed — release resources immediately. */
        (*env)->DeleteGlobalRef(env, state->listener_ref);
        free(state);
    }

    return (jlong)session_id;
}

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeSessionCancel(
        JNIEnv* env, jclass clazz, jlong session_id) {
    (void)env; (void)clazz;
    return ondevicellm_session_cancel((int64_t)session_id);
}

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeSessionFree(
        JNIEnv* env, jclass clazz, jlong session_id) {
    (void)env; (void)clazz;
    return ondevicellm_session_free((int64_t)session_id);
}

/* ==========================================================================
 * Pattern 1 — HTTP loopback
 * ========================================================================== */

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeHttpStart(
        JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return ondevicellm_http_start();
}

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeHttpStop(
        JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return ondevicellm_http_stop();
}

/* ==========================================================================
 * Pattern 2 — gRPC over UDS
 * ========================================================================== */

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeGrpcStart(
        JNIEnv* env, jclass clazz, jstring socket_path) {
    (void)clazz;
    if (socket_path == NULL) return ONDEVICELLM_INVALID_ARGUMENT;
    const char* path = (*env)->GetStringUTFChars(env, socket_path, NULL);
    if (path == NULL) return ONDEVICELLM_INTERNAL;
    int32_t result = ondevicellm_grpc_start(path);
    (*env)->ReleaseStringUTFChars(env, socket_path, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeGrpcStop(
        JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return ondevicellm_grpc_stop();
}

/* ==========================================================================
 * Pattern 4 — SQLite WAL broker
 * ========================================================================== */

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeBrokerStart(
        JNIEnv* env, jclass clazz, jstring db_path) {
    (void)clazz;
    if (db_path == NULL) return ONDEVICELLM_INVALID_ARGUMENT;
    const char* path = (*env)->GetStringUTFChars(env, db_path, NULL);
    if (path == NULL) return ONDEVICELLM_INTERNAL;
    int32_t result = ondevicellm_broker_start(path);
    (*env)->ReleaseStringUTFChars(env, db_path, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_ondevicellm_transport_NativeBridge_nativeBrokerStop(
        JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return ondevicellm_broker_stop();
}
