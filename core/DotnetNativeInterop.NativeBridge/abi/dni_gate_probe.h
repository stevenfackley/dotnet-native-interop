#ifndef DNI_GATE_PROBE_H
#define DNI_GATE_PROBE_H

/*
 * SP0 gate-only probes — NOT part of the frozen app ABI (dni.h). Included only by the Android JNI
 * shim's gate build to verify that hand-/dynamically-linked native dependencies load and function
 * inside the NativeAOT image on a real device, isolated from the app data paths (which need Android
 * asset extraction + a writable temp dir). Each returns heap UTF-8 JSON; free with dni_string_free
 * (declared in dni.h, which the shim includes first).
 */

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Open a SQLCipher database at `path`, confirm the cipher is live, and round-trip a row.
 * Returns {"ok":true,"cipher":str,"roundtrip":str} or {"ok":false,"error":str}; NULL on alloc failure.
 */
const char* dni_sqlite_probe(const char* path);

/*
 * Load a GGUF model at `path` (CPU), generate a few tokens, and free it — proves llama.cpp + ggml
 * link and a model loads/runs inside the NativeAOT image.
 * Returns {"ok":true,"tokens":int,"sample":str} or {"ok":false,"error":str}; NULL on alloc failure.
 */
const char* dni_llama_probe(const char* path);

#ifdef __cplusplus
}
#endif

#endif /* DNI_GATE_PROBE_H */
