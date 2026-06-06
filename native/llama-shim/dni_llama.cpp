#include "dni_llama.h"
#include "llama.h"
#include <vector>
#include <string>

namespace {
struct DniLlama {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
};
} // namespace

extern "C" void* dni_llama_load(const char* gguf_path) {
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU baseline; Metal is an optional later stretch.
    llama_model* model = llama_model_load_from_file(gguf_path, mp);
    if (!model) { llama_backend_free(); return nullptr; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx   = 2048;
    cp.n_batch = 512;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); llama_backend_free(); return nullptr; }

    auto* h = new DniLlama{ model, ctx, llama_model_get_vocab(model) };
    return h;
}

extern "C" int dni_llama_generate(void* handle, const char* prompt, int max_tokens, float temp,
                                  dni_llama_token_cb cb, void* user_data) {
    auto* h = static_cast<DniLlama*>(handle);
    if (!h || !prompt || !cb) return -1;

    llama_memory_clear(llama_get_memory(h->ctx), true); // stateless: clear KV between calls

    const int n_prompt = -llama_tokenize(h->vocab, prompt, (int)std::char_traits<char>::length(prompt),
                                         nullptr, 0, true, true);
    std::vector<llama_token> toks(n_prompt);
    if (llama_tokenize(h->vocab, prompt, (int)std::char_traits<char>::length(prompt),
                       toks.data(), (int)toks.size(), true, true) < 0) return -2;

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_get_one(toks.data(), (int)toks.size());
    int decoded = 0;
    char buf[512];
    int rc = 0;
    while (decoded < max_tokens) {
        if (llama_decode(h->ctx, batch) != 0) { rc = -3; break; }
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int n = llama_token_to_piece(h->vocab, tok, buf, (int)sizeof(buf) - 1, 0, true);
        if (n < 0) { rc = -4; break; }
        buf[n] = '\0';
        cb(user_data, buf);
        batch = llama_batch_get_one(&tok, 1);
        decoded++;
    }

    llama_sampler_free(smpl);
    return rc;
}

extern "C" void dni_llama_free(void* handle) {
    auto* h = static_cast<DniLlama*>(handle);
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    llama_backend_free();
    delete h;
}
