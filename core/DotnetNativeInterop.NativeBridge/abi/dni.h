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

/* ---- Onboard AI: semantic search --------------------------------------- */
/* Ranks `corpus` ("features" | "facts") by cosine similarity to free-text `query`;
 * returns heap UTF-8 JSON [{text,score}] (top-K). Copy then release with dni_string_free. */
const char* dni_search(const char* query, const char* corpus);

/* Point the engine at an on-device assets dir (model.onnx/vocab.txt/corpus.txt/manuals) and enable the
 * Android NNAPI execution provider. Call once before the first dni_search/RAG. 0 = ok, -1 = bad path. */
int32_t dni_set_assets_dir(const char* path);

/* ---- Ask the Manuals: on-device RAG ------------------------------------ */
/* Grounded generation over the bundled manuals corpus (retrieve top-K → answer).
 * FFI (streaming): starts a session that streams grounded-answer fragments via dni_token_cb;
 *   cancel/free with dni_session_cancel / dni_session_free (shared with dni_session_start). */
int64_t dni_rag_session_start(const char* query,
                                      int32_t max_tokens,
                                      float temperature,
                                      dni_token_cb callback,
                                      void* user_data);

/* SQLCipher (round-trip, non-streaming): returns heap UTF-8 JSON {"answer":"…"} (or NULL on
 * failure); copy the text, then release it with dni_string_free. */
const char* dni_sqlite_rag(const char* query);

/* HTTP loopback route (served by dni_http_start's server):
 *   GET /rag?q=<url-encoded query>  -> text/event-stream of  data: {index,text,final}
 *   (the same SSE frame as the legacy showcase stream).                       */

/* ---- Pattern 3: boundary instrumentation (additive, FFI showcase) ------- */
/* Diagnostics that make the interop boundary itself visible. The two getters
 * return heap UTF-8 JSON (or NULL on failure); copy the text, then release it
 * with dni_string_free. */
const char* dni_ffi_echo(const char* utf8, int32_t len); /* {bytesHex,len,decoded,managedThreadId,executeUs,ptrIn} */
const char* dni_ffi_throw(void);                          /* {caught,type,message,status} — managed exception contained */

/*
 * Extended per-token callback: like dni_token_cb, but also carries the managed
 * thread id the callback runs on and elapsed microseconds since stream start, so
 * the UI can visualize the off-UI-thread callback hop with real numbers. Fires on
 * a .NET background thread; `text` is UTF-8, valid ONLY during the call (copy it).
 */
typedef void (*dni_trace_cb)(void* user_data,
                                     int32_t index,
                                     const char* text,
                                     int32_t is_final,
                                     int64_t managed_thread_id,
                                     int64_t elapsed_us);

/* Session id (>0) on success, or a negative DNI_* status. Stop with
 * dni_session_cancel / dni_session_free (the production lifecycle exports). */
int64_t dni_ffi_stream_start(const char* prompt, int32_t max_tokens,
                             dni_trace_cb cb, void* user_data);

/* ---- Wave B — boundary legibility (additive) --------------------------- */
/* Three new exports; the frozen production surface above is unchanged. */

/* In-process tracing. Drains the engine's bounded span ring (512 spans,
 * drop-oldest) as heap UTF-8 JSON, or NULL on failure; copy then release with
 * dni_string_free. Shape:
 *   { "nowUs": <double>,           // engine µs-since-boot at drain time
 *     "dropped": <int>,            // spans lost to ring overflow since last drain (disclosed)
 *     "capacity": 512,
 *     "spans": [ { "name": "pb.execute", "startUs": <double>, "durUs": <double>,
 *                  "requestId": <string|null>, "status": <string|null> }, ... ] }
 * Engine spans carry µs offsets from engine boot; align to a client clock using
 * one offset per drain (nowUs) — cross-side accuracy is ±(drain round-trip). */
const char* dni_trace_drain(void);

/* Framed-protobuf transport (the 4th transport): length-prefixed Google.Protobuf
 * frames over a 127.0.0.1 loopback socket ([u32 little-endian length][Envelope];
 * see proto/dni_frame.proto). start() returns the bound port (> 0) or a negative
 * DNI_* status. flags bit 0 (flags & 1) requires an ML-KEM-768 / ML-DSA-65
 * handshake per connection, after which every frame is AES-256-GCM encrypted. */
int32_t dni_pb_start(int32_t flags);
void    dni_pb_stop(void);

/* Command-grammar additions (no ABI change) served by dni_feature_run:
 *   "trust~posture" -> per-transport security posture JSON (HTTP is reported as
 *                      plaintext; the binary transport reports live negotiated PQ
 *                      params { kem, sig, cipher, key sizes, handshakeUs } when up).
 *   "trace~stats"   -> span-ring snapshot { capacity, occupancy, droppedSinceDrain,
 *                      recordedTotal, droppedTotal }.                          */

#ifdef __cplusplus
}
#endif

#endif /* DNI_H */
