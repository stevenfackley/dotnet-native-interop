# Ask the Manuals — Managed RAG + UI (Phase 5, Plan A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship grounded "Ask the Manuals" RAG end-to-end — real in-engine retrieval over the manuals corpus + a deterministic extractive generator — streamed over FFI and raw-HTTP(SSE) and round-tripped over SQLCipher, with a new AI-tab screen that compares the engine answer against Apple Foundation Models over identical retrieved context.

**Architecture:** Additive. The engine gains a third `manuals` corpus and an `ExtractiveLanguageModel : ILanguageModel` (retrieve top-K → stream a grounded answer). `EngineHost` gains a second `RagOrchestrator` so the existing showcase stream is untouched. Three additive ABI surfaces carry it: `dni_rag_session_start` (FFI streaming, mirrors `dni_session_start`), `GET /rag?q=` (raw-HTTP SSE), and `dni_sqlite_rag` (SQLCipher JSON round-trip). iOS adds an "Ask the Manuals" screen with an Engine pane (honoring the transport picker) and an Apple FM pane. Plan B later swaps the extractive generator for llama.cpp behind the same seam.

**Tech Stack:** .NET 10 / C# 14, NativeAOT, `System.Numerics.Tensors`, ONNX Runtime (existing encoder), Microsoft.Data.Sqlite + SQLCipher, Swift 6 / SwiftUI, FoundationModels, xcodegen.

**Build/test note:** Engine + NativeBridge tasks are TDD'd with a throwaway probe console at `%TEMP%\dni-rag-probe` (mirrors the Phase 3/4 pattern) and `dotnet build DotnetNativeInterop.slnx -c Release`. iOS tasks build on the Mac mini as `steve` (see `docs/ios-build-deploy-runbook.md`). The ABI changes mean the **xcframework MUST be rebuilt** before the iOS tasks.

---

## File Structure

**Engine (`core/DotnetNativeInterop.Engine/`)**
- Create `Ai/assets/manuals/*.md` — copies of the 5 corpus docs, bundled via the existing `Ai/assets` folder reference.
- Create `Ai/ManualsCorpus.cs` — loads + chunks the manuals markdown.
- Create `ExtractiveLanguageModel.cs` — `ILanguageModel` that retrieves + streams a grounded answer.
- Modify `Ai/SemanticSearch.cs` — register the `manuals` corpus in `Default`.
- Modify `Ai/AiModels.cs` — add `RagAnswer` record + JSON.
- Modify `DotnetNativeInterop.Engine.csproj` — copy `Ai/assets/manuals/*.md` to output.

**NativeBridge (`core/DotnetNativeInterop.NativeBridge/`)**
- Modify `EngineHost.cs` — add `RagOrchestrator`.
- Modify `Ffi/Exports.Ffi.cs` — add `dni_rag_session_start` (reuses the existing drain/invoke/session machinery).
- Modify `HttpRaw/Exports.HttpRaw.cs` — add the `GET /rag?q=` SSE route.
- Create `Sqlite/RagSqlite.cs` — `dni_sqlite_rag` SQLCipher round-trip.
- Modify `abi/dni.h` — declare the new exports + document the route.

**iOS (`ios/`)**
- Create `Shared/Ai/Rag/RagModels.swift`, `RagError.swift`, `EngineRagService.swift`, `FFIRagService.swift`, `HTTPRagService.swift`, `SQLiteRagService.swift`, `AppleRagService.swift`, `RagViewModel.swift`, `AskManualsView.swift`.
- Modify `Shared/Ai/AiHubView.swift` — add a navigation link.
- Modify `Apps/Unified/UnifiedApp.swift` — build the RAG services and pass them to the AI hub.

**Docs**
- Modify `docs/INTEROP_CONTRACT.md` — document the new exports.
- Modify `README.md` — status line.

---

## Milestone 1 — Engine: manuals corpus + extractive RAG generator (Windows-testable)

### Task 1: Bundle the manuals corpus into the engine assets

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/assets/manuals/hvac-airflow.md` (+ `hvac-rooftop.md`, `it-network.md`, `it-overheat.md`, `it-server-psu.md`)
- Modify: `core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj`

- [ ] **Step 1: Copy the 5 corpus docs into the engine assets folder**

Run (PowerShell, repo root):
```powershell
$src = "core/DotnetNativeInterop.EdgeIndexPublisher/corpus"
$dst = "core/DotnetNativeInterop.Engine/Ai/assets/manuals"
New-Item -ItemType Directory -Force $dst | Out-Null
Copy-Item "$src/*.md" $dst
```
Expected: `Ai/assets/manuals/` contains 5 `.md` files.

- [ ] **Step 2: Copy the manuals to build output**

In `core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj`, inside the existing `<ItemGroup>` that has the `Ai/assets/*.txt` / `model.onnx` `<None Update=...>` entries, add:
```xml
    <None Update="Ai/assets/manuals/hvac-airflow.md" CopyToOutputDirectory="PreserveNewest" />
    <None Update="Ai/assets/manuals/hvac-rooftop.md" CopyToOutputDirectory="PreserveNewest" />
    <None Update="Ai/assets/manuals/it-network.md" CopyToOutputDirectory="PreserveNewest" />
    <None Update="Ai/assets/manuals/it-overheat.md" CopyToOutputDirectory="PreserveNewest" />
    <None Update="Ai/assets/manuals/it-server-psu.md" CopyToOutputDirectory="PreserveNewest" />
```

- [ ] **Step 3: Verify the build copies them**

Run: `dotnet build core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj -c Release`
Expected: build succeeds; `core/DotnetNativeInterop.Engine/bin/Release/net10.0/Ai/assets/manuals/` contains 5 `.md` files.

- [ ] **Step 4: Commit**

```bash
git add core/DotnetNativeInterop.Engine/Ai/assets/manuals core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj
git commit -m "feat: bundle manuals corpus into engine assets for in-engine RAG retrieval"
```

---

### Task 2: `ManualsCorpus` — load + chunk the manuals markdown

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/ManualsCorpus.cs`
- Test: throwaway probe at `%TEMP%\dni-rag-probe`

- [ ] **Step 1: Create the probe console (one-time)**

Run (PowerShell):
```powershell
$p = "$env:TEMP\dni-rag-probe"
New-Item -ItemType Directory -Force $p | Out-Null
dotnet new console -o $p --force | Out-Null
dotnet add $p reference (Resolve-Path "core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj")
```

- [ ] **Step 2: Write the failing test in the probe**

Set `%TEMP%\dni-rag-probe\Program.cs` to:
```csharp
using DotnetNativeInterop.Engine;

var assets = Path.Combine(
    AppContext.BaseDirectory, "..", "..", "..", "..", "..",
    "core", "DotnetNativeInterop.Engine", "Ai", "assets");
assets = Path.GetFullPath(assets);

var chunks = ManualsCorpus.Load(assets);
Console.WriteLine($"chunks: {chunks.Count}");

// 5 docs × ~3 sections each → expect a healthy chunk count, every chunk title-prefixed.
if (chunks.Count < 10) throw new Exception($"too few chunks: {chunks.Count}");
if (!chunks.Any(c => c.Contains("airflow", StringComparison.OrdinalIgnoreCase) ||
                     c.Contains("coil", StringComparison.OrdinalIgnoreCase)))
    throw new Exception("expected an HVAC airflow chunk");
if (chunks.Any(c => c.Contains("document_id", StringComparison.Ordinal)))
    throw new Exception("frontmatter leaked into a chunk");
Console.WriteLine("PASS: ManualsCorpus.Load");
```

- [ ] **Step 3: Run it to verify it fails**

Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: FAIL — `ManualsCorpus` does not exist (compile error).

- [ ] **Step 4: Implement `ManualsCorpus`**

Create `core/DotnetNativeInterop.Engine/Ai/ManualsCorpus.cs`:
```csharp
namespace DotnetNativeInterop.Engine;

/// <summary>
/// Loads the bundled maintenance manuals (markdown) and splits each into retrievable passage chunks
/// for <see cref="SemanticSearch"/>. These same files are the grounding corpus for the "Ask the
/// Manuals" RAG feature; Phase 4's EVS module consumes them as a prebuilt Swift-side index, while the
/// engine re-embeds them here at launch with its own encoder.
/// </summary>
public static class ManualsCorpus
{
    /// <summary>
    /// Reads every <c>*.md</c> under <paramref name="assetsDir"/>/manuals and returns one chunk per
    /// "## section", each prefixed with the document title so a retrieved chunk is self-describing.
    /// Returns an empty list when the folder is absent (graceful — search just has no manuals).
    /// </summary>
    public static IReadOnlyList<string> Load(string assetsDir)
    {
        var dir = Path.Combine(assetsDir, "manuals");
        if (!Directory.Exists(dir))
        {
            return [];
        }

        var chunks = new List<string>();
        foreach (var file in Directory.EnumerateFiles(dir, "*.md")
                     .OrderBy(p => p, StringComparer.Ordinal))
        {
            var text = File.ReadAllText(file);
            var title = ExtractTitle(text) ?? Path.GetFileNameWithoutExtension(file);
            foreach (var section in SplitSections(text))
            {
                chunks.Add($"{title} — {section}");
            }
        }

        return chunks;
    }

    // The "title:" line inside the leading YAML frontmatter (--- … ---).
    private static string? ExtractTitle(string markdown)
    {
        var t = markdown.TrimStart();
        if (!t.StartsWith("---", StringComparison.Ordinal))
        {
            return null;
        }

        var end = t.IndexOf("\n---", 3, StringComparison.Ordinal);
        if (end < 0)
        {
            return null;
        }

        foreach (var raw in t[3..end].Split('\n'))
        {
            var line = raw.Trim();
            if (line.StartsWith("title:", StringComparison.OrdinalIgnoreCase))
            {
                return line["title:".Length..].Trim();
            }
        }

        return null;
    }

    // One chunk per "## " heading; frontmatter dropped; each section flattened to a single line.
    private static IEnumerable<string> SplitSections(string markdown)
    {
        foreach (var part in StripFrontmatter(markdown).Split("\n## "))
        {
            var section = part.Trim().TrimStart('#', ' ').Trim();
            if (section.Length == 0)
            {
                continue;
            }

            yield return string.Join(' ', section.Split(
                '\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
        }
    }

    private static string StripFrontmatter(string markdown)
    {
        var t = markdown.TrimStart();
        if (!t.StartsWith("---", StringComparison.Ordinal))
        {
            return markdown;
        }

        var end = t.IndexOf("\n---", 3, StringComparison.Ordinal);
        if (end < 0)
        {
            return markdown;
        }

        var afterFence = t.IndexOf('\n', end + 1);
        return afterFence >= 0 ? t[(afterFence + 1)..] : string.Empty;
    }
}
```

- [ ] **Step 5: Run the probe to verify it passes**

Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: `PASS: ManualsCorpus.Load`.

- [ ] **Step 6: Commit**

```bash
git add core/DotnetNativeInterop.Engine/Ai/ManualsCorpus.cs
git commit -m "feat: ManualsCorpus loads and chunks bundled maintenance manuals"
```

---

### Task 3: Register the `manuals` corpus in `SemanticSearch.Default`

**Files:**
- Modify: `core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs`
- Test: `%TEMP%\dni-rag-probe`

- [ ] **Step 1: Write the failing retrieval test in the probe**

Set `%TEMP%\dni-rag-probe\Program.cs` to:
```csharp
using DotnetNativeInterop.Engine;

// SemanticSearch.Default resolves assets from AppContext.BaseDirectory; the probe's output dir won't
// have them, so copy the engine assets next to the probe binary for this run.
var engineAssets = Path.GetFullPath(Path.Combine(
    AppContext.BaseDirectory, "..", "..", "..", "..", "..",
    "core", "DotnetNativeInterop.Engine", "bin", "Release", "net10.0", "Ai", "assets"));
var localAssets = Path.Combine(AppContext.BaseDirectory, "Ai", "assets");
CopyDir(engineAssets, localAssets);

var hits = SemanticSearch.Default.Search("compressor won't start", "manuals", 3);
foreach (var h in hits) Console.WriteLine($"{h.Score:F3}  {h.Text}");
if (hits.Length == 0) throw new Exception("no manuals results");
if (!hits[0].Text.Contains("HVAC", StringComparison.OrdinalIgnoreCase) &&
    !hits[0].Text.Contains("compressor", StringComparison.OrdinalIgnoreCase) &&
    !hits[0].Text.Contains("coil", StringComparison.OrdinalIgnoreCase))
    throw new Exception($"top hit not HVAC-related: {hits[0].Text}");
Console.WriteLine("PASS: manuals retrieval");

static void CopyDir(string from, string to)
{
    Directory.CreateDirectory(to);
    foreach (var f in Directory.EnumerateFiles(from, "*", SearchOption.AllDirectories))
    {
        var rel = Path.GetRelativePath(from, f);
        var dest = Path.Combine(to, rel);
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        File.Copy(f, dest, overwrite: true);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: FAIL — `Search(..., "manuals", ...)` returns empty (corpus not registered).

- [ ] **Step 3: Register the corpus**

In `core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs`, in the `Default` getter, immediately after the existing `search.SetCorpus("facts", …);` line, add:
```csharp
                search.SetCorpus("manuals", ManualsCorpus.Load(dir));
```

- [ ] **Step 4: Run the probe to verify it passes**

Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: `PASS: manuals retrieval` with an HVAC chunk ranked first.

- [ ] **Step 5: Commit**

```bash
git add core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs
git commit -m "feat: register manuals corpus in the engine semantic search"
```

---

### Task 4: `ExtractiveLanguageModel` — stream a grounded answer

**Files:**
- Create: `core/DotnetNativeInterop.Engine/ExtractiveLanguageModel.cs`
- Test: `%TEMP%\dni-rag-probe`

- [ ] **Step 1: Write the failing test in the probe**

Set `%TEMP%\dni-rag-probe\Program.cs` to:
```csharp
using DotnetNativeInterop.Engine;

// Deterministic, no encoder needed: drive Compose directly + stream over a stub corpus result.
var hits = new[]
{
    new SearchResult("Weak Airflow and Frozen Coil — Replace the filter and clean the blower wheel.", 0.81),
    new SearchResult("Rooftop Unit — Check the contactor.", 0.55),
};
var answer = ExtractiveLanguageModel.Compose(hits);
Console.WriteLine(answer);
if (!answer.Contains("filter", StringComparison.OrdinalIgnoreCase))
    throw new Exception("answer not grounded in the top hit");
if (!ExtractiveLanguageModel.Compose([]).Contains("couldn't find", StringComparison.OrdinalIgnoreCase))
    throw new Exception("empty retrieval should say nothing found");

// And the streaming contract: fragments concatenate back to the same answer.
var model = new ExtractiveLanguageModel(perWordDelay: TimeSpan.Zero);
var sb = new System.Text.StringBuilder();
await foreach (var f in model.GenerateAsync(new InferenceRequest("compressor won't start")))
    sb.Append(f);
if (sb.Length == 0) throw new Exception("no streamed fragments");
Console.WriteLine("PASS: ExtractiveLanguageModel");
```
(Streaming over the real corpus requires the assets copy from Task 3; keep that `CopyDir` call at the top of `Program.cs`.)

- [ ] **Step 2: Run it to verify it fails**

Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: FAIL — `ExtractiveLanguageModel` does not exist.

- [ ] **Step 3: Implement `ExtractiveLanguageModel`**

Create `core/DotnetNativeInterop.Engine/ExtractiveLanguageModel.cs`:
```csharp
using System.Runtime.CompilerServices;
using System.Text;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// A fully-managed, deterministic RAG generator: retrieves the top manuals passages for the prompt
/// and streams a grounded answer stitched from them — no neural model, no weights. This is the
/// guaranteed (and first shippable) generator for "Ask the Manuals"; the llama.cpp-backed
/// <c>RagLanguageModel</c> (Plan B) replaces it behind the same <see cref="ILanguageModel"/> seam
/// once the native gate passes. Honest by construction: every sentence is quoted from a source.
/// </summary>
public sealed class ExtractiveLanguageModel(
    SemanticSearch? search = null,
    int topK = 3,
    TimeSpan? perWordDelay = null) : ILanguageModel
{
    private readonly TimeSpan _delay = perWordDelay ?? TimeSpan.FromMilliseconds(18);

    /// <inheritdoc/>
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        // Deferred so the heavy SemanticSearch.Default (ONNX model + corpora) builds lazily.
        var engine = search ?? SemanticSearch.Default;
        var answer = Compose(engine.Search(request.Prompt, "manuals", topK));

        var words = answer.Split(' ');
        var limit = Math.Min(words.Length, request.MaxTokens);
        for (var i = 0; i < limit; i++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (_delay > TimeSpan.Zero)
            {
                await Task.Delay(_delay, cancellationToken).ConfigureAwait(false);
            }

            yield return i == 0 ? words[i] : " " + words[i];
        }
    }

    /// <summary>
    /// Builds a grounded answer from retrieved passages. The wording/format is a deliberate product
    /// choice (how to present manual excerpts as an answer) — a good spot for human input during
    /// execution; this is a sensible default.
    /// </summary>
    public static string Compose(SearchResult[] hits)
    {
        if (hits.Length == 0)
        {
            return "I couldn't find anything about that in the manuals.";
        }

        var sb = new StringBuilder("Based on the manuals: ");
        sb.Append(hits[0].Text);
        for (var i = 1; i < hits.Length; i++)
        {
            sb.Append(" Related: ").Append(hits[i].Text);
        }

        return sb.ToString();
    }
}
```

- [ ] **Step 4: Run the probe to verify it passes**

Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: `PASS: ExtractiveLanguageModel`.

- [ ] **Step 5: Commit**

```bash
git add core/DotnetNativeInterop.Engine/ExtractiveLanguageModel.cs
git commit -m "feat: ExtractiveLanguageModel streams a grounded answer from retrieved manuals"
```

---

### Task 5: `RagAnswer` DTO + source-gen JSON

**Files:**
- Modify: `core/DotnetNativeInterop.Engine/Ai/AiModels.cs`
- Test: `%TEMP%\dni-rag-probe`

- [ ] **Step 1: Write the failing test in the probe**

Set `%TEMP%\dni-rag-probe\Program.cs` to:
```csharp
using DotnetNativeInterop.Engine;

var json = AiJson.Serialize(new RagAnswer("Based on the manuals: replace the filter."));
Console.WriteLine(json);
if (!json.Contains("\"answer\"", StringComparison.Ordinal))
    throw new Exception("expected camelCase 'answer' property");
Console.WriteLine("PASS: RagAnswer JSON");
```

- [ ] **Step 2: Run it to verify it fails**

Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: FAIL — `RagAnswer` does not exist.

- [ ] **Step 3: Add the DTO + JSON wiring**

In `core/DotnetNativeInterop.Engine/Ai/AiModels.cs`:

Add the record next to `SearchResult`:
```csharp
/// <summary>A complete grounded RAG answer (the SQLCipher round-trip payload).</summary>
public sealed record RagAnswer(string Answer);
```

Add `RagAnswer` to the source-gen context (add the attribute line to `AiJsonContext`):
```csharp
[JsonSerializable(typeof(SearchResult[]))]
[JsonSerializable(typeof(RagAnswer))]
internal sealed partial class AiJsonContext : JsonSerializerContext;
```

Add the serializer overload to `AiJson`:
```csharp
    public static string Serialize(RagAnswer answer) =>
        JsonSerializer.Serialize(answer, AiJsonContext.Default.RagAnswer);
```

- [ ] **Step 4: Run the probe to verify it passes**

Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: `PASS: RagAnswer JSON` with `{"answer":"…"}`.

- [ ] **Step 5: Verify the managed solution still builds Release-clean**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: Build succeeded, 0 warnings (the repo treats warnings as errors).

- [ ] **Step 6: Commit**

```bash
git add core/DotnetNativeInterop.Engine/Ai/AiModels.cs
git commit -m "feat: RagAnswer DTO + source-gen JSON for the SQLCipher RAG round-trip"
```

---

## Milestone 2 — NativeBridge: RagOrchestrator + three transport surfaces

### Task 6: `EngineHost.RagOrchestrator` (additive second orchestrator)

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/EngineHost.cs`

- [ ] **Step 1: Add the RAG orchestrator alongside the showcase one**

Replace the body of `EngineHost` so it holds **both** orchestrators (the existing showcase stream is untouched; the RAG one is lazy via `ExtractiveLanguageModel`'s deferred `SemanticSearch.Default`). Edit `core/DotnetNativeInterop.NativeBridge/EngineHost.cs`:

Add the field after `_orchestrator`:
```csharp
    private static InferenceOrchestrator? _ragOrchestrator;
```

Add the property after the `Orchestrator` property:
```csharp
    /// <summary>The shared RAG orchestrator (manuals retrieval + grounded generation).</summary>
    public static InferenceOrchestrator RagOrchestrator =>
        _ragOrchestrator ?? throw new InvalidOperationException("EngineHost.Initialize() has not been called.");
```

Change `IsInitialized` to require both:
```csharp
    public static bool IsInitialized => _orchestrator is not null && _ragOrchestrator is not null;
```

Add the RAG wiring inside `Initialize()`'s `lock (Gate)` block, right after the existing `_orchestrator ??= …;` line:
```csharp
            _ragOrchestrator ??= new InferenceOrchestrator(new ExtractiveLanguageModel());
```

Also change the early-return guard at the top of `Initialize()` to require both:
```csharp
        if (_orchestrator is not null && _ragOrchestrator is not null)
        {
            return;
        }
```

- [ ] **Step 2: Build the NativeBridge (managed RID) to verify it compiles**

Run: `dotnet build core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj -c Release`
Expected: Build succeeded, 0 warnings.

- [ ] **Step 3: Commit**

```bash
git add core/DotnetNativeInterop.NativeBridge/EngineHost.cs
git commit -m "feat: EngineHost gains a RAG orchestrator (showcase stream untouched)"
```

---

### Task 7: `dni_rag_session_start` (FFI streaming)

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ffi.cs`

> Mirrors `dni_session_start` exactly but uses `EngineHost.RagOrchestrator`. Adding it to the same class reuses the existing private `DrainAsync` / `Invoke` helpers and the shared `dni_session_cancel` / `dni_session_free` (which are session-id based and transport-agnostic).

- [ ] **Step 1: Add the export**

In `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ffi.cs`, add this method to the `ExportsFfi` class immediately after `SessionStart`:
```csharp
    /// <summary>
    /// Like <see cref="SessionStart"/>, but the prompt is a free-text question answered by the RAG
    /// orchestrator (manuals retrieval + grounded generation). Streams grounded-answer fragments via
    /// the same callback; cancel/free with <c>dni_session_cancel</c> / <c>dni_session_free</c>.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_rag_session_start")]
    public static unsafe long RagSessionStart(
        byte* query,
        int maxTokens,
        float temperature,
        delegate* unmanaged[Cdecl]<void*, int, byte*, int, void> callback,
        void* userData)
    {
        try
        {
            if (!EngineHost.IsInitialized)
            {
                return NativeStatus.NotInitialized;
            }

            if (query == null || callback == null)
            {
                return NativeStatus.InvalidArgument;
            }

            if (maxTokens <= 0 || temperature < 0f)
            {
                return NativeStatus.InvalidArgument;
            }

            var queryText = NativeText.Read((nint)query);
            var request = new InferenceRequest(queryText, maxTokens, temperature);
            var session = InferenceSession.Start(EngineHost.RagOrchestrator, request);
            var id = SessionRegistry.Add(session);
            FfiState.AllocatedIds.TryAdd(id, 0);

            var callbackAsNint = (nint)callback;
            var userDataAsNint = (nint)userData;
            _ = Task.Run(() => DrainAsync(session, callbackAsNint, userDataAsNint));

            return id;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }
```

- [ ] **Step 2: Build to verify it compiles**

Run: `dotnet build core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj -c Release`
Expected: Build succeeded.

- [ ] **Step 3: Verify the export is present in the win-x64 publish (probe the symbol)**

Run:
```powershell
dotnet publish core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj -c Release -r win-x64 -o "$env:TEMP\dni-rag-win"
& "$env:TEMP\dni-rag-win\..\nm-or-dumpbin.ps1" 2>$null  # optional
```
Then confirm the symbol via dumpbin (VS dev shell):
```powershell
dumpbin /exports "$env:TEMP\dni-rag-win\dni.dll" | Select-String "dni_rag_session_start"
```
Expected: `dni_rag_session_start` listed.

- [ ] **Step 4: Commit**

```bash
git add core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ffi.cs
git commit -m "feat: dni_rag_session_start FFI export streams grounded RAG answers"
```

---

### Task 8: `GET /rag?q=` raw-HTTP SSE route

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/HttpRaw/Exports.HttpRaw.cs`

- [ ] **Step 1: Add the route before the legacy SSE fallback**

In `RawHttpServer.HandleClientAsync`, immediately **before** the `// Legacy: stream the showcase as Server-Sent Events.` comment, insert:
```csharp
                if (path.StartsWith("/rag?q=", StringComparison.Ordinal))
                {
                    var query = Uri.UnescapeDataString(path["/rag?q=".Length..]);

                    await WriteAsync(
                        stream,
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n",
                        cancellationToken).ConfigureAwait(false);

                    var ragSession = InferenceSession.Start(
                        EngineHost.RagOrchestrator, new InferenceRequest(query),
                        cancellationToken: cancellationToken);

                    await using (ragSession.ConfigureAwait(false))
                    {
                        await foreach (var token in ragSession.Reader
                            .ReadAllAsync(cancellationToken).ConfigureAwait(false))
                        {
                            var sse = $"data: {{\"index\":{token.Index},\"text\":{JsonString(token.Text)},\"final\":{(token.IsFinal ? "true" : "false")}}}\n\n";
                            await WriteAsync(stream, sse, cancellationToken).ConfigureAwait(false);
                            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                        }
                    }

                    return;
                }
```
(`path` is the full request target including the query string — confirmed by `ParsePath`, which returns `parts[1]` of the request line — so `/rag?q=…` matches and `UnescapeDataString` decodes `%20` etc.)

- [ ] **Step 2: Build to verify it compiles**

Run: `dotnet build core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj -c Release`
Expected: Build succeeded.

- [ ] **Step 3: Smoke-test the route on Windows (loopback)**

Create `%TEMP%\dni-rag-http\Program.cs` as a console that references the NativeBridge project, calls `EngineHost.Initialize()` + `RawHttpServer.Start()` indirectly is internal — instead test via the published dll using a tiny C# host that P/Invokes `dni_initialize`, `dni_http_start`, then HTTP-GETs `/rag?q=compressor%20won't%20start`. Minimal version:
```csharp
using System.Runtime.InteropServices;
[DllImport("dni")] static extern int dni_initialize();
[DllImport("dni")] static extern int dni_http_start();
dni_initialize();
var port = dni_http_start();
using var http = new HttpClient();
using var resp = await http.GetAsync($"http://127.0.0.1:{port}/rag?q=compressor%20won't%20start",
    HttpCompletionOption.ResponseHeadersRead);
await using var s = await resp.Content.ReadAsStreamAsync();
using var r = new StreamReader(s);
var text = "";
for (var i = 0; i < 40 && !r.EndOfStream; i++) text += r.ReadLine() + "\n";
Console.WriteLine(text);
if (!text.Contains("data:")) throw new Exception("no SSE frames");
Console.WriteLine("PASS: /rag SSE");
```
Copy the published `dni.dll` (from `%TEMP%\dni-rag-win`) next to this console's output and run it.
Expected: several `data: {…}` lines, then `PASS: /rag SSE`.

- [ ] **Step 4: Commit**

```bash
git add core/DotnetNativeInterop.NativeBridge/HttpRaw/Exports.HttpRaw.cs
git commit -m "feat: raw-HTTP GET /rag?q= streams grounded RAG answers as SSE"
```

---

### Task 9: `dni_sqlite_rag` (SQLCipher round-trip, non-streaming)

**Files:**
- Create: `core/DotnetNativeInterop.NativeBridge/Sqlite/RagSqlite.cs`

> Mirrors `FeatureSqlite` (Pattern 4, SQLCipher): generate the full grounded answer, persist it to a key-encrypted db, read it back, return JSON `{answer}`. Honest contrast — SQLCipher is the slowest transport and returns the whole answer at once (matching `dni_sqlite_run`).

- [ ] **Step 1: Create the export class**

Create `core/DotnetNativeInterop.NativeBridge/Sqlite/RagSqlite.cs`:
```csharp
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Data.Sqlite;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Sqlite;

/// <summary>
/// SQLCipher RAG round-trip (Pattern 4): runs the RAG orchestrator to completion, persists the
/// grounded answer to a key-encrypted (PRAGMA key) db, reads it back, and returns JSON {answer}.
/// Non-streaming by design — the slowest transport returns the whole answer, like dni_sqlite_run.
/// </summary>
internal static class RagSqliteExports
{
    private const string Key = "dni-showcase-key";
    private static readonly object Gate = new();
    private static SqliteConnection? _conn;

    /// <summary>Answers <paramref name="query"/> over the manuals, round-tripped through the
    /// encrypted db; returns heap UTF-8 JSON {answer} (0 on failure). Release with dni_string_free.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_sqlite_rag")]
    public static unsafe nint Rag(byte* query)
    {
        try
        {
            EngineHost.Initialize();
            var q = NativeText.Read((nint)query);
            lock (Gate)
            {
                var conn = EnsureConnection();
                var answer = GenerateFull(q);
                WriteAnswer(conn, q, answer);
                var stored = ReadAnswer(conn, q) ?? answer;
                return NativeText.Allocate(AiJson.Serialize(new RagAnswer(stored)));
            }
        }
        catch (Exception)
        {
            return 0;
        }
    }

    // Drain the RAG orchestrator synchronously into one string (the export is sync).
    private static string GenerateFull(string query)
    {
        var sb = new StringBuilder();

        async Task DrainAsync()
        {
            await foreach (var token in EngineHost.RagOrchestrator
                .RunAsync(new InferenceRequest(query)).ConfigureAwait(false))
            {
                if (!token.IsFinal)
                {
                    sb.Append(token.Text);
                }
            }
        }

        DrainAsync().GetAwaiter().GetResult();
        return sb.ToString();
    }

    // Must be called holding Gate.
    private static SqliteConnection EnsureConnection()
    {
        if (_conn is not null)
        {
            return _conn;
        }

        SQLitePCL.Batteries_V2.Init();

        var path = Path.Combine(Path.GetTempPath(), "dni-rag.db");
        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Password = Key,   // => PRAGMA key: SQLCipher encrypts the file at rest
            Pooling = false,
        }.ToString();

        var conn = new SqliteConnection(connectionString);
        conn.Open();
        Execute(conn, CreateAnswers);
        _conn = conn;
        return conn;
    }

    private static void WriteAnswer(SqliteConnection conn, string query, string answer)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText =
            """
            INSERT INTO rag_answers (query, answer, created_utc)
            VALUES ($q, $a, $ts)
            ON CONFLICT(query) DO UPDATE SET answer = $a, created_utc = $ts;
            """;
        cmd.Parameters.AddWithValue("$q", query);
        cmd.Parameters.AddWithValue("$a", answer);
        cmd.Parameters.AddWithValue("$ts", DateTime.UtcNow.ToString("O"));
        cmd.ExecuteNonQuery();
    }

    private static string? ReadAnswer(SqliteConnection conn, string query)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT answer FROM rag_answers WHERE query = $q";
        cmd.Parameters.AddWithValue("$q", query);
        using var reader = cmd.ExecuteReader();
        return reader.Read() ? reader.GetString(0) : null;
    }

    private static void Execute(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    private const string CreateAnswers =
        """
        CREATE TABLE IF NOT EXISTS rag_answers (
          query       TEXT PRIMARY KEY,
          answer      TEXT NOT NULL,
          created_utc TEXT NOT NULL
        );
        """;
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `dotnet build core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj -c Release`
Expected: Build succeeded.

- [ ] **Step 3: Smoke-test on Windows**

Extend the `%TEMP%\dni-rag-http` console (or a new one) with:
```csharp
[DllImport("dni")] static extern nint dni_sqlite_rag(byte[] q);
[DllImport("dni")] static extern void dni_string_free(nint s);
var bytes = System.Text.Encoding.UTF8.GetBytes("compressor won't start\0");
var p = dni_sqlite_rag(bytes);
if (p == 0) throw new Exception("dni_sqlite_rag failed");
var json = Marshal.PtrToStringUTF8(p)!;
dni_string_free(p);
Console.WriteLine(json);
if (!json.Contains("\"answer\"")) throw new Exception("no answer");
Console.WriteLine("PASS: dni_sqlite_rag");
```
Expected: `{"answer":"Based on the manuals: …"}`, then `PASS`.

- [ ] **Step 4: Commit**

```bash
git add core/DotnetNativeInterop.NativeBridge/Sqlite/RagSqlite.cs
git commit -m "feat: dni_sqlite_rag round-trips a grounded answer through SQLCipher"
```

---

### Task 10: Declare the new exports in the C ABI header

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/abi/dni.h`

- [ ] **Step 1: Add the declarations**

In `core/DotnetNativeInterop.NativeBridge/abi/dni.h`, immediately after the `dni_search` block (before `#ifdef __cplusplus` closing), add:
```c
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
```

- [ ] **Step 2: Verify the header still copies (build NativeBridge)**

Run: `dotnet build core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj -c Release`
Expected: Build succeeded (the header is a `None` copy item; this just confirms nothing broke).

- [ ] **Step 3: Commit**

```bash
git add core/DotnetNativeInterop.NativeBridge/abi/dni.h
git commit -m "docs(abi): declare dni_rag_session_start, dni_sqlite_rag, and the /rag SSE route"
```

---

### Task 11: Document the new surfaces in INTEROP_CONTRACT.md

**Files:**
- Modify: `docs/INTEROP_CONTRACT.md`

- [ ] **Step 1: Add a RAG section**

Open `docs/INTEROP_CONTRACT.md`, find the section documenting `dni_search` / the AI exports, and add after it:
```markdown
### Ask the Manuals — RAG (Phase 5)

Grounded generation over the bundled `manuals` corpus. Retrieval is in-engine (same encoder as
`dni_search`); generation is the managed extractive generator (Plan A) — swappable for llama.cpp
(Plan B) behind `ILanguageModel` with no ABI change.

| Transport | Surface | Shape |
|-----------|---------|-------|
| FFI | `dni_rag_session_start(query, max_tokens, temperature, cb, user_data)` | streams fragments via `dni_token_cb`; cancel/free via `dni_session_cancel` / `dni_session_free` |
| raw-HTTP | `GET /rag?q=<query>` (on `dni_http_start`'s port) | `text/event-stream` of `data: {index,text,final}` |
| SQLCipher | `dni_sqlite_rag(query)` | JSON `{"answer":"…"}`, round-tripped through a key-encrypted db (non-streaming) |

The shared "Sources" shown in the UI come from `dni_search(query, "manuals")`.
```

- [ ] **Step 2: Commit**

```bash
git add docs/INTEROP_CONTRACT.md
git commit -m "docs: document the RAG exports in the interop contract"
```

---

## Milestone 3 — iOS: "Ask the Manuals" screen (Engine ×3 transports vs Apple)

> **Prerequisite:** the ABI changed, so the framework MUST be rebuilt before these compile on device. Build as `steve` on the Mac mini (Task 20 covers the full build/deploy). The Swift files below can be authored first; the app links them in Task 20.

### Task 12: RAG models + error type

**Files:**
- Create: `ios/Shared/Ai/Rag/RagModels.swift`
- Create: `ios/Shared/Ai/Rag/RagError.swift`

- [ ] **Step 1: Create the models**

Create `ios/Shared/Ai/Rag/RagModels.swift`:
```swift
import Foundation

/// The SQLCipher round-trip payload from `dni_sqlite_rag` ({"answer": "…"}).
struct RagAnswer: Codable, Sendable {
    let answer: String
}

/// One SSE frame from the raw-HTTP `/rag` route (data: {index,text,final}).
struct RagSSEFrame: Decodable, Sendable {
    let index: Int
    let text: String
    let final: Bool
}
```
(`SearchResult` — the shared "Sources" model — already exists at `ios/Shared/Ai/SearchResult.swift`.)

- [ ] **Step 2: Create the error type**

Create `ios/Shared/Ai/Rag/RagError.swift`:
```swift
import Foundation

enum RagError: LocalizedError {
    case startFailed(Int)
    case nullResult
    case badURL
    case http(Int)
    case appleUnavailable(String)

    var errorDescription: String? {
        switch self {
        case .startFailed(let code): return "The engine couldn't start the RAG session (status \(code))."
        case .nullResult:            return "The native library returned no data."
        case .badURL:                return "Couldn't form the loopback RAG URL."
        case .http(let code):        return "RAG HTTP request failed (status \(code))."
        case .appleUnavailable(let why): return why
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add ios/Shared/Ai/Rag/RagModels.swift ios/Shared/Ai/Rag/RagError.swift
git commit -m "feat(ios): RAG models and error type"
```

---

### Task 13: `EngineRagService` protocol + FFI streaming implementation

**Files:**
- Create: `ios/Shared/Ai/Rag/EngineRagService.swift`
- Create: `ios/Shared/Ai/Rag/FFIRagService.swift`

> All three engine transports expose the same shape: an async stream of answer **deltas** (FFI/HTTP yield token deltas; SQLCipher yields the whole answer as one delta). The view model just appends.

- [ ] **Step 1: Create the protocol**

Create `ios/Shared/Ai/Rag/EngineRagService.swift`:
```swift
import Foundation

/// Streams a grounded answer from the .NET engine over one transport. Each yielded string is a
/// fragment to APPEND to the running answer.
protocol EngineRagService: Sendable {
    func answer(to query: String) -> AsyncThrowingStream<String, Error>
}
```

- [ ] **Step 2: Create the FFI implementation**

Create `ios/Shared/Ai/Rag/FFIRagService.swift`:
```swift
import Foundation

/// FFI engine RAG: calls `dni_rag_session_start` and bridges the per-token C callback into an async
/// stream. The continuation is carried across the C ABI inside an Unmanaged box passed as user_data.
/// The session is cancelled+freed on stream termination (never inside the callback, which runs on the
/// .NET drain thread — `dni_session_free` blocks on that pump and would deadlock).
final class FFIRagService: EngineRagService, @unchecked Sendable {
    private final class Box {
        let continuation: AsyncThrowingStream<String, Error>.Continuation
        init(_ c: AsyncThrowingStream<String, Error>.Continuation) { self.continuation = c }
    }

    func answer(to query: String) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let box = Box(continuation)
            let userData = Unmanaged.passRetained(box).toOpaque()

            let callback: @convention(c) (UnsafeMutableRawPointer?, Int32, UnsafePointer<CChar>?, Int32) -> Void = { ud, _, text, isFinal in
                guard let ud else { return }
                let box = Unmanaged<Box>.fromOpaque(ud).takeUnretainedValue()
                if isFinal != 0 {
                    box.continuation.finish()
                } else if let text {
                    box.continuation.yield(String(cString: text))
                }
            }

            let sessionId = query.withCString { q in
                dni_rag_session_start(q, 256, 0.8, callback, userData)
            }

            guard sessionId > 0 else {
                continuation.finish(throwing: RagError.startFailed(Int(sessionId)))
                Unmanaged<Box>.fromOpaque(userData).release()
                return
            }

            continuation.onTermination = { _ in
                _ = dni_session_cancel(sessionId)
                _ = dni_session_free(sessionId)
                Unmanaged<Box>.fromOpaque(userData).release()
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add ios/Shared/Ai/Rag/EngineRagService.swift ios/Shared/Ai/Rag/FFIRagService.swift
git commit -m "feat(ios): EngineRagService protocol + FFI streaming via dni_rag_session_start"
```

---

### Task 14: HTTP (SSE) engine RAG implementation

**Files:**
- Create: `ios/Shared/Ai/Rag/HTTPRagService.swift`

- [ ] **Step 1: Create it**

Create `ios/Shared/Ai/Rag/HTTPRagService.swift`:
```swift
import Foundation

/// raw-HTTP engine RAG: GETs `/rag?q=…` on the loopback server (`dni_http_start`) and parses the
/// `data: {index,text,final}` SSE frames, yielding each token's text until the final marker.
final class HTTPRagService: EngineRagService, @unchecked Sendable {
    func answer(to query: String) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let port = Int(dni_http_start())
                    guard port > 0 else { throw RagError.startFailed(port) }

                    let encoded = query.addingPercentEncoding(
                        withAllowedCharacters: .urlQueryAllowed) ?? query
                    guard let url = URL(string: "http://127.0.0.1:\(port)/rag?q=\(encoded)") else {
                        throw RagError.badURL
                    }

                    let (bytes, response) = try await URLSession.shared.bytes(from: url)
                    if let code = (response as? HTTPURLResponse)?.statusCode, code != 200 {
                        throw RagError.http(code)
                    }

                    for try await line in bytes.lines {
                        guard line.hasPrefix("data: ") else { continue }
                        let json = Data(line.dropFirst(6).utf8)
                        guard let frame = try? JSONDecoder().decode(RagSSEFrame.self, from: json) else {
                            continue
                        }
                        if frame.final { break }
                        continuation.yield(frame.text)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/Ai/Rag/HTTPRagService.swift
git commit -m "feat(ios): HTTP (SSE) engine RAG over the /rag loopback route"
```

---

### Task 15: SQLCipher (round-trip) engine RAG implementation

**Files:**
- Create: `ios/Shared/Ai/Rag/SQLiteRagService.swift`

- [ ] **Step 1: Create it**

Create `ios/Shared/Ai/Rag/SQLiteRagService.swift`:
```swift
import Foundation

/// SQLCipher engine RAG: calls `dni_sqlite_rag`, which round-trips the grounded answer through a
/// key-encrypted db and returns JSON {answer}. Non-streaming — yields the whole answer once.
final class SQLiteRagService: EngineRagService, @unchecked Sendable {
    func answer(to query: String) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task.detached(priority: .userInitiated) {
                let json: String? = query.withCString { q -> String? in
                    guard let ptr = dni_sqlite_rag(q) else { return nil }
                    defer { dni_string_free(ptr) }
                    return String(cString: ptr)
                }
                guard let json,
                      let result = try? JSONDecoder().decode(RagAnswer.self, from: Data(json.utf8))
                else {
                    continuation.finish(throwing: RagError.nullResult)
                    return
                }
                continuation.yield(result.answer)
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/Ai/Rag/SQLiteRagService.swift
git commit -m "feat(ios): SQLCipher engine RAG via dni_sqlite_rag round-trip"
```

---

### Task 16: Apple Foundation Models RAG implementation

**Files:**
- Create: `ios/Shared/Ai/Rag/AppleRagService.swift`

> Apple's `streamResponse` yields **cumulative** content (the whole answer-so-far each time), unlike the engine deltas — so this service yields cumulative snapshots and the view model REPLACES (not appends) the Apple pane. Kept explicit to make the difference obvious.

- [ ] **Step 1: Create it**

Create `ios/Shared/Ai/Rag/AppleRagService.swift`:
```swift
import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Apple Foundation Models RAG (the contrast): the SAME retrieved manual chunks are stuffed into a
/// grounded prompt and answered by Apple's on-device model in Swift. Yields CUMULATIVE snapshots.
struct AppleRagService: Sendable {
    /// Yields the whole answer-so-far on each step (cumulative); the view model replaces.
    func answer(to query: String, sources: [SearchResult]) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                #if canImport(FoundationModels)
                if #available(iOS 26.0, *) {
                    do {
                        let context = sources.map { "- \($0.text)" }.joined(separator: "\n")
                        let prompt = """
                        Answer the question using ONLY these maintenance-manual excerpts. \
                        If they don't cover it, say so.

                        \(context)

                        Question: \(query)
                        """
                        let session = LanguageModelSession()
                        for try await partial in session.streamResponse(to: prompt) {
                            continuation.yield(partial.content)
                        }
                        continuation.finish()
                    } catch {
                        continuation.finish(throwing: error)
                    }
                    return
                }
                #endif
                continuation.finish(throwing: RagError.appleUnavailable(
                    "Apple Intelligence requires iOS 26 on an eligible device."))
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Whether Apple's model is usable on this device (for the pane's empty state).
    static func availabilityMessage() -> String? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: return nil
            case .unavailable(let reason): return "Apple model unavailable: \(reason)"
            @unknown default: return "Apple model availability unknown."
            }
        } else {
            return "Requires iOS 26 or later."
        }
        #else
        return "FoundationModels isn't available in this build."
        #endif
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/Ai/Rag/AppleRagService.swift
git commit -m "feat(ios): Apple Foundation Models RAG over identical retrieved context"
```

---

### Task 17: `RagViewModel` — retrieve sources, drive both panes, time them

**Files:**
- Create: `ios/Shared/Ai/Rag/RagViewModel.swift`

- [ ] **Step 1: Create it**

Create `ios/Shared/Ai/Rag/RagViewModel.swift`:
```swift
import Foundation

@MainActor
final class RagViewModel: ObservableObject {
    @Published var query = ""
    @Published var transport: TransportKind = .ffi
    @Published var sources: [SearchResult] = []
    @Published var engineAnswer = ""
    @Published var appleAnswer = ""
    @Published var engineRunning = false
    @Published var appleRunning = false
    @Published var engineFirstTokenMs: Double?
    @Published var engineTotalMs: Double?
    @Published var errorMessage: String?
    @Published var appleUnavailable: String? = AppleRagService.availabilityMessage()

    private let search: SemanticSearchService
    private let engineServices: [TransportKind: EngineRagService]
    private let apple = AppleRagService()
    private var engineTask: Task<Void, Never>?
    private var appleTask: Task<Void, Never>?

    init(search: SemanticSearchService, engineServices: [TransportKind: EngineRagService]) {
        self.search = search
        self.engineServices = engineServices
    }

    func ask() async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }

        cancel()
        errorMessage = nil
        engineAnswer = ""
        appleAnswer = ""
        engineFirstTokenMs = nil
        engineTotalMs = nil

        // 1) Shared retrieval — feeds BOTH panes identical context.
        do {
            sources = try await search.search(q, corpus: "manuals")
        } catch {
            errorMessage = error.localizedDescription
            return
        }

        // 2) Engine pane over the selected transport (appends deltas).
        if let service = engineServices[transport] {
            engineRunning = true
            let start = Date()
            engineTask = Task { @MainActor in
                do {
                    for try await delta in service.answer(to: q) {
                        if engineFirstTokenMs == nil {
                            engineFirstTokenMs = Date().timeIntervalSince(start) * 1000
                        }
                        engineAnswer += delta
                    }
                } catch {
                    errorMessage = error.localizedDescription
                }
                engineTotalMs = Date().timeIntervalSince(start) * 1000
                engineRunning = false
            }
        }

        // 3) Apple pane over the SAME sources (replaces with cumulative snapshots).
        if appleUnavailable == nil {
            appleRunning = true
            let snapshot = sources
            appleTask = Task { @MainActor in
                do {
                    for try await whole in apple.answer(to: q, sources: snapshot) {
                        appleAnswer = whole
                    }
                } catch {
                    if appleUnavailable == nil { appleUnavailable = error.localizedDescription }
                }
                appleRunning = false
            }
        }
    }

    func cancel() {
        engineTask?.cancel(); engineTask = nil
        appleTask?.cancel(); appleTask = nil
        engineRunning = false
        appleRunning = false
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/Ai/Rag/RagViewModel.swift
git commit -m "feat(ios): RagViewModel drives shared retrieval + engine/Apple panes with timing"
```

---

### Task 18: `AskManualsView` — the screen

**Files:**
- Create: `ios/Shared/Ai/Rag/AskManualsView.swift`

- [ ] **Step 1: Create it**

Create `ios/Shared/Ai/Rag/AskManualsView.swift`:
```swift
import SwiftUI

struct AskManualsView: View {
    @StateObject private var model: RagViewModel

    init(search: SemanticSearchService, engineServices: [TransportKind: EngineRagService]) {
        _model = StateObject(wrappedValue: RagViewModel(search: search, engineServices: engineServices))
    }

    var body: some View {
        List {
            Section {
                Picker("Engine transport", selection: $model.transport) {
                    ForEach(TransportKind.allCases) { Text($0.displayName).tag($0) }
                }
                .pickerStyle(.segmented)
                HStack {
                    TextField("Ask the manuals… e.g. “compressor won’t start”", text: $model.query)
                        .textInputAutocapitalization(.never)
                        .onSubmit { Task { await model.ask() } }
                    Button {
                        Task { await model.ask() }
                    } label: {
                        if model.engineRunning || model.appleRunning { ProgressView() }
                        else { Image(systemName: "paperplane.fill") }
                    }
                    .disabled(model.query.isEmpty)
                }
                Text("Retrieval runs in the .NET engine over the manuals corpus; the engine answer "
                     + "streams over the selected transport, shown beside Apple’s on-device model "
                     + "answering the same retrieved context.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if let error = model.errorMessage {
                Section { Text(error).foregroundStyle(.red) }
            }

            if !model.sources.isEmpty {
                Section("Sources (retrieved)") {
                    ForEach(model.sources) { source in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(source.text).font(.callout)
                            Text(String(format: "similarity %.3f", source.score))
                                .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            if !model.engineAnswer.isEmpty || model.engineRunning {
                Section(engineHeader) {
                    Text(model.engineAnswer.isEmpty ? "…" : model.engineAnswer)
                }
            }

            Section("Apple Foundation Models") {
                if let why = model.appleUnavailable {
                    ContentUnavailableView("Apple model unavailable",
                                           systemImage: "sparkles.slash", description: Text(why))
                } else {
                    Text(model.appleAnswer.isEmpty ? (model.appleRunning ? "…" : "Ask to compare.")
                                                   : model.appleAnswer)
                }
            }
        }
        .navigationTitle("Ask the Manuals")
    }

    private var engineHeader: String {
        var parts = ["Engine (\(model.transport.displayName))"]
        if let f = model.engineFirstTokenMs { parts.append(String(format: "first %.0f ms", f)) }
        if let t = model.engineTotalMs { parts.append(String(format: "total %.0f ms", t)) }
        return parts.joined(separator: " · ")
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/Ai/Rag/AskManualsView.swift
git commit -m "feat(ios): Ask the Manuals screen — engine vs Apple over shared sources"
```

---

### Task 19: Wire the screen into the AI hub + app composition root

**Files:**
- Modify: `ios/Shared/Ai/AiHubView.swift`
- Modify: `ios/Apps/Unified/UnifiedApp.swift`

- [ ] **Step 1: Extend `AiHubView` to receive the RAG services and add a link**

In `ios/Shared/Ai/AiHubView.swift`, add a stored property and a navigation link.

Add the property next to `let search: SemanticSearchService`:
```swift
    let engineRagServices: [TransportKind: EngineRagService]
```

Add a new section before the closing of the `List` (e.g. after the "On-device, in the .NET engine" section):
```swift
                Section("Grounded Q&A") {
                    NavigationLink {
                        AskManualsView(search: search, engineServices: engineRagServices)
                    } label: {
                        Label("Ask the Manuals (RAG)", systemImage: "text.book.closed")
                    }
                }
```

- [ ] **Step 2: Build the RAG services in `UnifiedApp` and pass them in**

In `ios/Apps/Unified/UnifiedApp.swift`:

Add a stored property for the services (near where `search`/services are held):
```swift
    private let engineRagServices: [TransportKind: EngineRagService] = [
        .ffi: FFIRagService(),
        .http: HTTPRagService(),
        .sqlite: SQLiteRagService(),
    ]
```

Find where `AiHubView(search: …)` is constructed (it's passed `search` from `RootTabView`). Update the call site to also pass the services. If `RootTabView` constructs `AiHubView`, thread the property through: pass `engineRagServices` from `UnifiedApp` into `RootTabView`, then into `AiHubView`.

Concretely, in `RootTabView.swift` add a property:
```swift
    let engineRagServices: [TransportKind: EngineRagService]
```
and change the AI tab to:
```swift
            AiHubView(search: search, engineRagServices: engineRagServices)
                .tabItem { Label("AI", systemImage: "sparkles") }
```
Then in `UnifiedApp`'s `body`, pass `engineRagServices: engineRagServices` into the `RootTabView(...)` initializer.

- [ ] **Step 3: Commit**

```bash
git add ios/Shared/Ai/AiHubView.swift ios/Shared/RootTabView.swift ios/Apps/Unified/UnifiedApp.swift
git commit -m "feat(ios): wire Ask the Manuals into the AI hub and app composition root"
```

---

### Task 20: Rebuild the framework, build + deploy the app, verify on device

> Runs on the Mac mini as `steve`. The ABI changed → full framework rebuild is mandatory. Use a clean tree synced via `git archive` (the in-place Mac tree may be stale). See `docs/ios-build-deploy-runbook.md`.

- [ ] **Step 1: Sync a clean tree + rebuild framework + build + install (one SSH block)**

From the Windows dev box, push the branch first:
```bash
git push -u origin feat/manuals-rag-engine-vs-apple
```
Then build on the Mac mini (device-only fast path):
```bash
ssh steve-mac-mini "zsh -lc '
  set -e
  rm -rf ~/dni-rag-build && mkdir -p ~/dni-rag-build
  cd /Users/steve/dotnet-ios-android-poc-native-frontend && git fetch origin && \
    git archive --format=tar feat/manuals-rag-engine-vs-apple | tar -x -C ~/dni-rag-build
  cd ~/dni-rag-build
  security unlock-keychain -p \"$(cat ~/.sign_kc_pw)\" ~/Library/Keychains/login.keychain-db
  bash build/build-ios-framework-device.sh
  cd ios && xcodegen generate
  xcodebuild -project DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropUnified \
    -configuration Debug -destination \"generic/platform=iOS\" -derivedDataPath build/dd \
    -allowProvisioningUpdates DEVELOPMENT_TEAM=QJW4S8BDFX build
  xcrun devicectl device install app --device 00008101-0019092E1EFA601E \
    build/dd/Build/Products/Debug-iphoneos/*.app
'" 2>&1 | tee rag-build.log
```
Expected tail: `** BUILD SUCCEEDED **` then `App installed`.

- [ ] **Step 2: Manual verification on the iPad (golden path + edge cases)**

Open the app → **AI** tab → **Ask the Manuals (RAG)**. Confirm:
  - Ask "compressor won't start" → **Sources** populate with HVAC chunks (similarity shown).
  - **Engine** pane streams a grounded answer that references the top source; header shows first-token + total ms.
  - Switch the **Engine transport** segmented control to **HTTP** then **SQLite** and re-ask: FFI and HTTP stream token-by-token; SQLite shows the whole answer at once; all three reference the same Sources.
  - **Apple** pane streams its own answer over the same sources (or shows the graceful unavailable card on an ineligible device).
  - Empty/odd query (e.g. "asdfgh") → engine answers "couldn't find anything in the manuals" rather than crashing.
  - **No regressions:** Dashboard, Features, Lab, Compare, Latency, About, Manuals (EVS), and the existing AI screens (Semantic search, Apple chat) all still work.

- [ ] **Step 3: If verification passes, note it; if not, debug before proceeding**

Record any device-only issues (e.g. callback threading, SSE buffering) and fix in the relevant task's file. Re-run Step 1.

---

## Milestone 4 — Verify + document

### Task 21: Full managed build + probe sanity

- [ ] **Step 1: Release build of the whole solution**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: Build succeeded, 0 warnings.

- [ ] **Step 2: Re-run the engine probe end-to-end**

Set `%TEMP%\dni-rag-probe\Program.cs` to retrieve + extract for three queries (one HVAC, one IT, one nonsense) and print answers; run it and eyeball that answers are grounded / the nonsense query says "couldn't find".
Run: `dotnet run --project $env:TEMP\dni-rag-probe -c Release`
Expected: sensible grounded answers; nonsense → not-found message.

- [ ] **Step 3: Clean up the throwaway probe**

Run: `Remove-Item -Recurse -Force $env:TEMP\dni-rag-probe, $env:TEMP\dni-rag-win, $env:TEMP\dni-rag-http -ErrorAction SilentlyContinue`
(No repo changes — nothing to commit.)

---

### Task 22: Update the README status

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a status line**

In `README.md`'s **Status** section, add:
```markdown
- ✅ **Ask the Manuals (RAG)** — in-engine manuals retrieval + a grounded answer streamed over FFI /
  HTTP-SSE and round-tripped over SQLCipher, compared side-by-side with Apple Foundation Models
  (Plan A: managed extractive generator; the llama.cpp generator lands in Plan B behind the same seam).
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: README status — Ask the Manuals (RAG), Plan A"
```

---

## Self-Review (filled in by the author of this plan)

- **Spec coverage:** engine retrieval (Tasks 2–3) ✓; generation (Task 4) ✓; FFI/HTTP/SQLCipher surfaces (Tasks 7–9) ✓; additive `RagOrchestrator`, showcase untouched (Task 6) ✓; UI engine-vs-Apple over shared context (Tasks 12–19) ✓; ABI + contract docs (Tasks 10–11) ✓; spec's "extractive fallback ships" = this whole plan ✓. **Deferred to Plan B:** the llama.cpp gate + findings doc + `RagLanguageModel` neural prompt (the spec's gate-pass branch).
- **Type consistency:** `RagAnswer{answer}` matches across `AiModels.cs`, `dni_sqlite_rag`, and Swift `RagAnswer`; `dni_rag_session_start` signature matches `dni_token_cb`; `EngineRagService.answer(to:)` is uniform across the three Swift transports; `transport: TransportKind` reuses the existing enum.
- **Placeholder scan:** none — every step has concrete code/commands.

---

## Note on Plan B (next plan, the gate)

Plan A ships a complete, honest RAG feature with the managed extractive generator. **Plan B** (`docs/superpowers/plans/2026-06-06-manuals-rag-llama-gate.md`, to be written) covers the spec's native gate: a thin C shim over llama.cpp, static-linked into `dni` (gate #3), a `LlamaLanguageModel : ILanguageModel`, swapping `EngineHost`'s RAG generator from `ExtractiveLanguageModel` to a `RagLanguageModel(llama, SemanticSearch.Default)` (with the user-authored grounded-prompt builder), the GGUF bundled via LFS, and `docs/llama-nativeaot-ios-findings.md`. Nothing in Plan A changes except that one generator wiring line.

