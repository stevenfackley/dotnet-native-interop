#ifndef DNI_LLAMA_H
#define DNI_LLAMA_H
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Emitted once per detokenized piece during generation. `text` is a NUL-terminated UTF-8
 * fragment valid only for the call; copy it. */
typedef void (*dni_llama_token_cb)(void* user_data, const char* text);

/* Loads a GGUF model + context (CPU). Returns an opaque handle, or NULL on failure. */
void* dni_llama_load(const char* gguf_path);

/* Single-shot generation: resets KV state, tokenizes `prompt`, decodes up to `max_tokens`
 * with temperature `temp`, invoking `cb` per piece. Returns 0 on success, negative on error.
 *
 * `grammar` is an optional llama.cpp GBNF grammar (root rule "root"): when non-NULL and non-empty the
 * sampler is grammar-CONSTRAINED, so the model literally cannot emit a token that would violate the
 * grammar (used to force valid tool-call JSON on Foreman's llama brain). Pass NULL for unconstrained
 * generation (plain RAG/answer streaming). A malformed grammar makes this return a negative error. */
int dni_llama_generate(void* handle, const char* prompt, int max_tokens, float temp,
                       const char* grammar,
                       dni_llama_token_cb cb, void* user_data);

/* Frees the model + context. */
void dni_llama_free(void* handle);

#ifdef __cplusplus
}
#endif
#endif /* DNI_LLAMA_H */
