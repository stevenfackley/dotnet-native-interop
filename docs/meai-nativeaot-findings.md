# Microsoft.Extensions.AI.Abstractions under NativeAOT — clean pass

**Date:** 2026-07-05
**Outcome:** ✅ **Works, cleanly.** A hand-implemented `IChatClient` plus a `DelegatingChatClient`
middleware layer both AOT-publish and run under NativeAOT with **zero** trim/AOT warnings —
unsurprising once you notice the package is pure interfaces/POCOs with no reflection or codegen of
its own, but worth confirming rather than assuming.

**Why this doc exists:** gates exposing the engine's `ILanguageModel` (the interface already
wrapping `LlamaLanguageModel`/`ExtractiveLanguageModel`, see `llama-nativeaot-ios-findings.md`) as
an `IChatClient`, so a future function-calling agent layer (`Microsoft.Extensions.AI`'s
`FunctionInvokingChatClient`, `ChatClientBuilder` middleware pipeline, etc.) can sit on top of it
without the engine itself taking a hard dependency on any specific agent framework.

## TL;DR

- ✅ `Microsoft.Extensions.AI.Abstractions` **10.7.0** (latest stable) AOT-publishes and runs with
  **no warnings at all** — not even the roll-up `IL2104`/`IL3053` assembly warnings seen on
  protobuf-net, ML.NET, and EF Core in this same batch.
- ✅ Exercised the full shape the repo would actually use: a custom `IChatClient` (`GetResponseAsync`,
  `GetStreamingResponseAsync` as an `IAsyncEnumerable<ChatResponseUpdate>`, `GetService`, `Dispose`)
  wrapped by a `DelegatingChatClient`-derived middleware that transforms both the non-streaming and
  streaming paths, plus `GetService` delegation through the middleware to the inner client.
- **Why it's clean:** `Microsoft.Extensions.AI.Abstractions` is a leaf abstractions package —
  interfaces, records, and small POCOs (`ChatMessage`, `ChatResponse`, `ChatResponseUpdate`,
  `ChatOptions`) with no runtime reflection, no `Expression.Compile()`, no dynamic proxies. It's the
  same shape as the repo's own `ILanguageModel`, which is exactly why it composes with it easily.

## The spike

`spike/MeaiGate/MeaiGate.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <PublishAot>true</PublishAot>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.Extensions.AI.Abstractions" Version="10.7.0" />
  </ItemGroup>
</Project>
```

`Program.cs`:
- `EchoChatClient : IChatClient` — a trivial in-process client. `GetResponseAsync` echoes the last
  message; `GetStreamingResponseAsync` is a real `async IAsyncEnumerable<ChatResponseUpdate>`
  ([`EnumeratorCancellation`]-annotated) that yields word-by-word with a `Task.Yield()` between
  pieces, so the streaming path is genuinely asynchronous, not a fake `Task.FromResult` wrapped in
  an enumerable. `GetService` resolves itself when asked for `typeof(EchoChatClient)`.
- `UpperCaseDelegatingChatClient(IChatClient inner) : DelegatingChatClient(inner)` — overrides both
  `GetResponseAsync` and `GetStreamingResponseAsync`, calling `base.*` and uppercasing the result,
  matching the repo's eventual need for a middleware layer (logging, rate limiting, etc.) between
  the agent framework and the engine's model.
- `Main` calls both response modes through the middleware-wrapped client and calls
  `client.GetService(typeof(EchoChatClient))` to confirm `DelegatingChatClient`'s default
  `GetService` correctly forwards through to the inner client without any extra plumbing.

**Command:**
```powershell
dotnet publish -r win-x64 -c Release -p:PublishAot=true -p:IlcUseEnvironmentalTools=true
```
(same MSVC-toolset workaround as the sibling gates in this batch.)

**Publish output:** clean — restore, `Generating native code`, publish. **Zero warnings, zero errors.**

**Run output (verbatim):**
```
GetResponseAsync: "ECHO: HELLO FROM DNI"
GetStreamingResponseAsync: 4 updates, joined="ECHO: STREAM THIS BACK "
GetService(typeof(EchoChatClient)) resolved through middleware = True
PASS: IChatClient + DelegatingChatClient middleware ran end to end
```
(4 updates = the 4 words in `"echo: stream this back"`, each yielded across a real `await Task.Yield()`
boundary — confirms the streaming path isn't optimized away or buffered into one synchronous call.)

## Mobile-RID caveat

Local NativeAOT can't target `ios-arm64`/`linux-bionic-arm64` on this Windows box, so this
`win-x64` `PublishAot=true` probe is the accepted first-order signal (same ILC, same trimming/
reflection constraints as the mobile RIDs). Given the package's shape here (no reflection, no
codegen, pure interfaces/POCOs), there's no plausible AOT/trim failure mode that would be
Windows-specific or platform-specific — this result should transfer to `ios-arm64` and
`linux-bionic-arm64` without surprises.

## Verdict

**PASS — safe to build on.** `Microsoft.Extensions.AI.Abstractions` is a green light for wiring
`ILanguageModel` (or a small adapter over it) up as `IChatClient`, and layering
`ChatClientBuilder`/`DelegatingChatClient` middleware (logging, caching, rate limiting) between the
engine and any future function-calling agent code. One thing intentionally **not** covered by this
gate: `Microsoft.Extensions.AI` (the non-`Abstractions` package with `FunctionInvokingChatClient`,
`OpenTelemetryChatClient`, JSON-schema-based function calling, etc.) is a heavier dependency that
does use `System.Text.Json` reflection-based (de)serialization for function-call arguments by
default — if function calling is adopted later, that package deserves its own follow-up spike
rather than assuming this gate's clean result extends to it.
