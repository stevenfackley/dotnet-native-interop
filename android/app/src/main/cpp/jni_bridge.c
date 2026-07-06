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
#include <string.h>
#include <android/log.h>
#include <pthread.h>

#include "dni.h"
#include "dni_gate_probe.h"   /* SP0 gate-only probes (dni_sqlite_probe); not part of the app ABI */

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

/* ---- Wave B: framed-protobuf transport + trace drain -------------------- */
static jint    j_pb_start(JNIEnv* e, jobject o, jint flags) { (void)e; (void)o; return dni_pb_start((int32_t)flags); }
static void    j_pb_stop (JNIEnv* e, jobject o) { (void)e; (void)o; dni_pb_stop(); }
static jstring j_trace_drain(JNIEnv* e, jobject o) { (void)o; return take_native_string(e, dni_trace_drain()); }

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

/* AI: point the engine at the on-device assets dir + enable NNAPI (0 ok, -1 bad path). */
static jint j_set_assets_dir(JNIEnv* e, jobject o, jstring path) {
    (void)o; if (path == NULL) return -1;
    const char* p = (*e)->GetStringUTFChars(e, path, NULL);
    int32_t r = p ? dni_set_assets_dir(p) : -1;
    if (p) (*e)->ReleaseStringUTFChars(e, path, p);
    return r;
}

/* SP0 gate probe (S2): SQLCipher at a caller-supplied (writable) path. */
static jstring j_sqlite_probe(JNIEnv* e, jobject o, jstring path) {
    (void)o; if (path == NULL) return NULL;
    const char* p = (*e)->GetStringUTFChars(e, path, NULL);
    const char* r = p ? dni_sqlite_probe(p) : NULL;
    if (p) (*e)->ReleaseStringUTFChars(e, path, p);
    return take_native_string(e, r);
}

/* SP0 gate probe (S3): load a GGUF model at `path`, generate a few tokens, free it. */
static jstring j_llama_probe(JNIEnv* e, jobject o, jstring path) {
    (void)o; if (path == NULL) return NULL;
    const char* p = (*e)->GetStringUTFChars(e, path, NULL);
    const char* r = p ? dni_llama_probe(p) : NULL;
    if (p) (*e)->ReleaseStringUTFChars(e, path, p);
    return take_native_string(e, r);
}

/* ---- Foreman agent (additive) -------------------------------------------- */
/* dni_agent_session_start has a narrower signature than dni_session_start/dni_rag_session_start (no
 * max_tokens/temperature — the turn loop decides its own length), so it gets its own start function
 * rather than reusing start_streaming(); it shares ffi_token_callback/CallbackState (same dni_token_cb
 * ABI) and the SAME cancel/free lifecycle (nativeSessionCancel/nativeSessionFree — no new exports). */
static jlong j_agent_session_start(JNIEnv* e, jobject o, jstring query, jobject listener) {
    (void)o;
    if (query == NULL || listener == NULL) return (jlong)DNI_INVALID_ARGUMENT;
    jclass lc = (*e)->GetObjectClass(e, listener);
    jmethodID on_token = (*e)->GetMethodID(e, lc, "onToken", "(ILjava/lang/String;Z)V");
    (*e)->DeleteLocalRef(e, lc);
    if (on_token == NULL) { LOGE("onToken not found"); return (jlong)DNI_INVALID_ARGUMENT; }
    CallbackState* st = (CallbackState*)malloc(sizeof(CallbackState));
    if (st == NULL) return (jlong)DNI_INTERNAL;
    st->listener_ref = (*e)->NewGlobalRef(e, listener);
    st->on_token = on_token;
    const char* c_query = (*e)->GetStringUTFChars(e, query, NULL);
    if (c_query == NULL) { (*e)->DeleteGlobalRef(e, st->listener_ref); free(st); return (jlong)DNI_INTERNAL; }
    int64_t sid = dni_agent_session_start(c_query, ffi_token_callback, st);
    (*e)->ReleaseStringUTFChars(e, query, c_query);
    if (sid <= 0) { (*e)->DeleteGlobalRef(e, st->listener_ref); free(st); }
    return (jlong)sid;
}

/* ---- Boundary instrumentation: echo / throw (sync) + traced streaming ---- */
static jstring j_ffi_echo(JNIEnv* e, jobject o, jstring text) {
    (void)o; if (text == NULL) return NULL;
    const char* ctext = (*e)->GetStringUTFChars(e, text, NULL);
    if (ctext == NULL) return NULL;
    const char* r = dni_ffi_echo(ctext, (int32_t)strlen(ctext)); /* len is REQUIRED by the ABI */
    (*e)->ReleaseStringUTFChars(e, text, ctext);
    return take_native_string(e, r);
}

static jstring j_ffi_throw(JNIEnv* e, jobject o) {
    (void)o;
    return take_native_string(e, dni_ffi_throw());
}

/* Param order MUST match dni_trace_cb: (ud, index, text, is_final, managed_thread_id, elapsed_us). */
typedef struct { jobject listener_ref; jmethodID on_trace; } TraceCallbackState;

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

/* ---- RegisterNatives table --------------------------------------------- */
static const JNINativeMethod kMethods[] = {
    {"nativeInitialize",     "()I",                                                                      (void*)j_initialize},
    {"nativeShutdown",       "()V",                                                                      (void*)j_shutdown},
    {"nativeSessionStart",   "(Ljava/lang/String;IFLio/dotnetnativeinterop/transport/FfiTokenListener;)J", (void*)j_session_start},
    {"nativeRagSessionStart","(Ljava/lang/String;IFLio/dotnetnativeinterop/transport/FfiTokenListener;)J", (void*)j_rag_session_start},
    {"nativeAgentSessionStart","(Ljava/lang/String;Lio/dotnetnativeinterop/transport/FfiTokenListener;)J",  (void*)j_agent_session_start},
    {"nativeSessionCancel",  "(J)I",                                                                     (void*)j_session_cancel},
    {"nativeSessionFree",    "(J)I",                                                                     (void*)j_session_free},
    {"nativeHttpStart",      "()I",                                                                      (void*)j_http_start},
    {"nativeHttpStop",       "()I",                                                                      (void*)j_http_stop},
    {"nativePbStart",        "(I)I",                                                                     (void*)j_pb_start},
    {"nativePbStop",         "()V",                                                                      (void*)j_pb_stop},
    {"nativeTraceDrain",     "()Ljava/lang/String;",                                                     (void*)j_trace_drain},
    {"nativeBrokerStart",    "(Ljava/lang/String;)I",                                                    (void*)j_broker_start},
    {"nativeBrokerStop",     "()I",                                                                      (void*)j_broker_stop},
    {"nativeFeaturesJson",   "()Ljava/lang/String;",                                                     (void*)j_features_json},
    {"nativeFeatureRun",     "(Ljava/lang/String;)Ljava/lang/String;",                                   (void*)j_feature_run},
    {"nativeSqliteFeatures", "()Ljava/lang/String;",                                                     (void*)j_sqlite_features},
    {"nativeSqliteRun",      "(Ljava/lang/String;)Ljava/lang/String;",                                   (void*)j_sqlite_run},
    {"nativeEngineStats",    "()Ljava/lang/String;",                                                     (void*)j_engine_stats},
    {"nativeSearch",         "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",                 (void*)j_search},
    {"nativeSetAssetsDir",   "(Ljava/lang/String;)I",                                                    (void*)j_set_assets_dir},
    {"nativeSqliteRag",      "(Ljava/lang/String;)Ljava/lang/String;",                                   (void*)j_sqlite_rag},
    {"nativeSqliteProbe",    "(Ljava/lang/String;)Ljava/lang/String;",                                   (void*)j_sqlite_probe},
    {"nativeLlamaProbe",     "(Ljava/lang/String;)Ljava/lang/String;",                                   (void*)j_llama_probe},
    {"nativeFfiEcho",        "(Ljava/lang/String;)Ljava/lang/String;",                                    (void*)j_ffi_echo},
    {"nativeFfiThrow",       "()Ljava/lang/String;",                                                      (void*)j_ffi_throw},
    {"nativeFfiStreamStart", "(Ljava/lang/String;ILio/dotnetnativeinterop/transport/FfiTraceListener;)J", (void*)j_ffi_stream_start},
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
