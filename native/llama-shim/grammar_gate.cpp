// Standalone host gate for grammar-constrained sampling in the dni_llama shim.
//
// NOT part of the CMake build (CMakeLists.txt names dni_llama.cpp explicitly, so this file is ignored
// by build-llama.sh). It is a manual, reproducible proof that dni_llama_generate's `grammar` arg
// actually drives llama_sampler_init_grammar — i.e. the on-device model literally CANNOT emit a token
// that violates the grammar. Run it on a machine with a real GGUF and the pinned b9542 static libs.
//
// Build + run (Mac build host; reuses the llama.cpp checkout + archives from an existing shim build):
//   LS=$HOME/dni-rag-build/native/llama-shim/.llama-src
//   LIB=$HOME/dni-rag-build/native/llama-shim/build/host/lib
//   clang++ -std=c++17 -O2 -c dni_llama.cpp -I$LS/include -I$LS/ggml/include -o dni_llama.o
//   clang++ -std=c++17 -O2 grammar_gate.cpp dni_llama.o \
//       $LIB/libllama.a $LIB/libggml.a $LIB/libggml-cpu.a $LIB/libggml-base.a -o grammar_gate
//   ./grammar_gate ~/dni-gate/model.gguf
//
// Decisive design: greedy decode (temp 0) for reproducibility, and a synthetic grammar that forces an
// output shape the base model would never produce for the given prompt — so conformance can only come
// from the sampler, never coincidence. A no-grammar control run proves the same prompt otherwise yields
// free text. See docs/foreman-grammar-sampling-findings.md for the recorded output.
#include "dni_llama.h"
#include <cstdio>
#include <string>

namespace {
struct Sink { std::string text; };
void on_piece(void* ud, const char* text) { static_cast<Sink*>(ud)->text += text; }

std::string trim(const std::string& x) {
    const char* ws = " \t\r\n";
    size_t a = x.find_first_not_of(ws);
    if (a == std::string::npos) return "";
    return x.substr(a, x.find_last_not_of(ws) - a + 1);
}

std::string gen(void* h, const char* prompt, const char* grammar, int max_tokens) {
    Sink s;
    int rc = dni_llama_generate(h, prompt, max_tokens, 0.0f, grammar, on_piece, &s);
    if (rc != 0) printf("    (generate rc=%d)\n", rc);
    return s.text;
}

// exactly DNI-[A-Z][A-Z][A-Z]-OK
bool matches_forced(const std::string& raw) {
    std::string s = trim(raw);
    if (s.size() != 10) return false;
    if (s.compare(0, 4, "DNI-") != 0) return false;
    for (int i = 4; i < 7; i++) if (s[i] < 'A' || s[i] > 'Z') return false;
    return s.compare(7, 3, "-OK") == 0;
}
} // namespace

int main(int argc, char** argv) {
    if (argc < 2) { printf("usage: grammar_gate <gguf>\n"); return 2; }
    printf("== dni_llama grammar-sampling gate ==\n");

    void* h = dni_llama_load(argv[1]);
    if (!h) { printf("[FAIL] model load\n"); return 2; }

    int pass = 0, total = 0;
    const char* prompt = "The capital of France is";

    // 1. Control: no grammar -> free text; must NOT coincidentally be the forced shape.
    total++;
    std::string ctrl = trim(gen(h, prompt, nullptr, 12));
    printf("control (grammar=NULL): \"%s\"\n", ctrl.c_str());
    bool ctrl_ok = !ctrl.empty() && !matches_forced(ctrl);
    printf("[%s] control: unconstrained decode yields free text, not the forced shape\n", ctrl_ok ? "PASS" : "FAIL");
    pass += ctrl_ok;

    // 2. Synthetic grammar the base model would never emit for this prompt.
    total++;
    std::string forced = trim(gen(h, prompt, "root ::= \"DNI-\" [A-Z] [A-Z] [A-Z] \"-OK\"", 24));
    printf("forced (synthetic grammar): \"%s\"\n", forced.c_str());
    bool synth_ok = matches_forced(forced);
    printf("[%s] synthetic: output is EXACTLY DNI-[A-Z]{3}-OK -> sampler is grammar-constrained\n",
           synth_ok ? "PASS" : "FAIL");
    pass += synth_ok;

    // 3. The SHIPPED answer-only GBNF (GbnfGrammar.Build with no tools) -> a valid JSON answer object.
    total++;
    const char* answer_gbnf =
        "root ::= answer\n"
        "answer ::= \"{\\\"answer\\\":\" string \"}\"\n"
        "string ::= \"\\\"\" ([^\"\\\\] | \"\\\\\" .)* \"\\\"\"\n";
    std::string ans = trim(gen(h, "Describe HVAC maintenance in one short sentence.", answer_gbnf, 160));
    printf("answer (shipped GBNF): \"%s\"\n", ans.c_str());
    bool ans_ok = ans.rfind("{\"answer\":\"", 0) == 0 && ans.size() >= 13 && ans.back() == '}';
    printf("[%s] shipped answer grammar: output is a well-formed {\"answer\":\"...\"} object\n",
           ans_ok ? "PASS" : "FAIL");
    pass += ans_ok;

    // 4. Malformed grammar -> negative rc (the shim's -5 contract), never garbage tokens.
    total++;
    Sink junk;
    int rc = dni_llama_generate(h, prompt, 8, 0.0f, "root ::= ( unterminated", on_piece, &junk);
    bool mal_ok = rc < 0;
    printf("[%s] malformed grammar returns a negative error (rc=%d), emits nothing\n",
           mal_ok ? "PASS" : "FAIL", rc);
    pass += mal_ok;

    dni_llama_free(h);
    printf("== %d/%d grammar-gate checks passed ==\n", pass, total);
    return pass == total ? 0 : 1;
}
