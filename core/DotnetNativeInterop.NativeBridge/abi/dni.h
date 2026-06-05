/*
 * DotnetNativeInterop — C ABI (FROZEN)
 *
 * The stable surface exported by the NativeAOT shared library `dni`
 * (dni.dylib / libdni.so). Mirrors the [UnmanagedCallersOnly]
 * exports in DotnetNativeInterop.NativeBridge. Swift imports this header directly; the
 * Android JNI shim (jni_bridge.c) includes it.
 */
#ifndef DNI_H
#define DNI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Status codes — mirror DotnetNativeInterop.NativeBridge.NativeStatus. */
#define DNI_OK                 0
#define DNI_NOT_INITIALIZED   -1
#define DNI_INVALID_ARGUMENT  -2
#define DNI_UNKNOWN_SESSION   -3
#define DNI_ALREADY_RUNNING   -4
#define DNI_INTERNAL          -5

/*
 * Per-token callback (Pattern 3). Invoked on a .NET background thread — the host
 * is responsible for hopping to its UI thread. `text` is UTF-8 and valid ONLY for
 * the duration of the call; copy it. `is_final` is 1 on the terminal marker.
 */
typedef void (*dni_token_cb)(void* user_data,
                                     int32_t index,
                                     const char* text,
                                     int32_t is_final);

/* ---- Lifecycle ---------------------------------------------------------- */
int32_t dni_initialize(void);
void    dni_shutdown(void);

/* ---- Pattern 3: FFI ----------------------------------------------------- */
/* Returns a session id (> 0) or a negative status code. */
int64_t dni_session_start(const char* prompt,
                                  int32_t max_tokens,
                                  float temperature,
                                  dni_token_cb callback,
                                  void* user_data);
int32_t dni_session_cancel(int64_t session_id);
int32_t dni_session_free(int64_t session_id);

/* ---- Pattern 3: structured feature catalog (FFI) ----------------------- */
/* Return heap UTF-8 JSON (or NULL on failure); copy the text, then release it
 * with dni_string_free. */
const char* dni_features_json(void);          /* [{id,title,version,code,expected}] */
const char* dni_feature_run(const char* id);  /* {id,result,elapsedMs,ok}           */
void        dni_string_free(const char* s);

/* ---- Pattern 1: HTTP loopback ------------------------------------------- */
/* start() returns the bound 127.0.0.1 port (> 0) or a negative status code. */
int32_t dni_http_start(void);
int32_t dni_http_stop(void);

/* ---- Pattern 2: gRPC over UDS — EXCLUDED FROM BUILD --------------------- */
/* gRPC has no NativeAOT mobile runtime pack, so these are NOT exported by the
 * shipped library. Declarations kept (commented) for reference — do not link.
 *   int32_t dni_grpc_start(const char* socket_path);
 *   int32_t dni_grpc_stop(void);
 */

/* ---- Pattern 4: SQLite WAL broker --------------------------------------- */
int32_t dni_broker_start(const char* db_path);
int32_t dni_broker_stop(void);

/* ---- Pattern 4: structured feature catalog via SQLCipher ---------------- */
/* The library round-trips the catalog/results through an on-disk, key-encrypted
 * (PRAGMA key) SQLCipher database and returns JSON. The native side owns the key;
 * copy the result then release it with dni_string_free. */
const char* dni_sqlite_features(void);          /* [{id,title,version,code,expected}] */
const char* dni_sqlite_run(const char* id);     /* {id,result,elapsedMs,ok}           */

/* HTTP feature routes (served by dni_http_start's loopback server):
 *   GET /features          -> [{id,title,version,code,expected}]
 *   GET /feature/run/{id}  -> {id,result,elapsedMs,ok}                       */

/* ---- Engine introspection ----------------------------------------------- */
/* Returns heap UTF-8 JSON of live runtime stats
 * {gcGen0,gcGen1,gcGen2,heapBytes,committedBytes,allocatedBytes,gcPauseMs,
 *  threadCount,processorCount,uptimeMs}; copy then release with dni_string_free. */
const char* dni_engine_stats(void);

#ifdef __cplusplus
}
#endif

#endif /* DNI_H */
