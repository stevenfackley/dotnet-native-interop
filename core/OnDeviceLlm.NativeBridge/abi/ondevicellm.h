/*
 * OnDeviceLlm — C ABI (FROZEN)
 *
 * The stable surface exported by the NativeAOT shared library `ondevicellm`
 * (ondevicellm.dylib / libondevicellm.so). Mirrors the [UnmanagedCallersOnly]
 * exports in OnDeviceLlm.NativeBridge. Swift imports this header directly; the
 * Android JNI shim (jni_bridge.c) includes it.
 */
#ifndef ONDEVICELLM_H
#define ONDEVICELLM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Status codes — mirror OnDeviceLlm.NativeBridge.NativeStatus. */
#define ONDEVICELLM_OK                 0
#define ONDEVICELLM_NOT_INITIALIZED   -1
#define ONDEVICELLM_INVALID_ARGUMENT  -2
#define ONDEVICELLM_UNKNOWN_SESSION   -3
#define ONDEVICELLM_ALREADY_RUNNING   -4
#define ONDEVICELLM_INTERNAL          -5

/*
 * Per-token callback (Pattern 3). Invoked on a .NET background thread — the host
 * is responsible for hopping to its UI thread. `text` is UTF-8 and valid ONLY for
 * the duration of the call; copy it. `is_final` is 1 on the terminal marker.
 */
typedef void (*ondevicellm_token_cb)(void* user_data,
                                     int32_t index,
                                     const char* text,
                                     int32_t is_final);

/* ---- Lifecycle ---------------------------------------------------------- */
int32_t ondevicellm_initialize(void);
void    ondevicellm_shutdown(void);

/* ---- Pattern 3: FFI ----------------------------------------------------- */
/* Returns a session id (> 0) or a negative status code. */
int64_t ondevicellm_session_start(const char* prompt,
                                  int32_t max_tokens,
                                  float temperature,
                                  ondevicellm_token_cb callback,
                                  void* user_data);
int32_t ondevicellm_session_cancel(int64_t session_id);
int32_t ondevicellm_session_free(int64_t session_id);

/* ---- Pattern 3: structured feature catalog (FFI) ----------------------- */
/* Return heap UTF-8 JSON (or NULL on failure); copy the text, then release it
 * with ondevicellm_string_free. */
const char* ondevicellm_features_json(void);          /* [{id,title,version,code,expected}] */
const char* ondevicellm_feature_run(const char* id);  /* {id,result,elapsedMs,ok}           */
void        ondevicellm_string_free(const char* s);

/* ---- Pattern 1: HTTP loopback ------------------------------------------- */
/* start() returns the bound 127.0.0.1 port (> 0) or a negative status code. */
int32_t ondevicellm_http_start(void);
int32_t ondevicellm_http_stop(void);

/* ---- Pattern 2: gRPC over UDS — EXCLUDED FROM BUILD --------------------- */
/* gRPC has no NativeAOT mobile runtime pack, so these are NOT exported by the
 * shipped library. Declarations kept (commented) for reference — do not link.
 *   int32_t ondevicellm_grpc_start(const char* socket_path);
 *   int32_t ondevicellm_grpc_stop(void);
 */

/* ---- Pattern 4: SQLite WAL broker --------------------------------------- */
int32_t ondevicellm_broker_start(const char* db_path);
int32_t ondevicellm_broker_stop(void);

/* ---- Pattern 4: structured feature catalog via SQLCipher ---------------- */
/* The library round-trips the catalog/results through an on-disk, key-encrypted
 * (PRAGMA key) SQLCipher database and returns JSON. The native side owns the key;
 * copy the result then release it with ondevicellm_string_free. */
const char* ondevicellm_sqlite_features(void);          /* [{id,title,version,code,expected}] */
const char* ondevicellm_sqlite_run(const char* id);     /* {id,result,elapsedMs,ok}           */

/* HTTP feature routes (served by ondevicellm_http_start's loopback server):
 *   GET /features          -> [{id,title,version,code,expected}]
 *   GET /feature/run/{id}  -> {id,result,elapsedMs,ok}                       */

#ifdef __cplusplus
}
#endif

#endif /* ONDEVICELLM_H */
