# Onboard AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-device AI — a .NET-driven semantic search (real ONNX, spike-gated) over the app's features + a bundled fact corpus, plus an Apple Foundation Models chat — under a new "AI" tab.

**Architecture:** A feasibility **spike** (Task 1) decides the encoder: if ONNX runs under NativeAOT, ship `OnnxTextEncoder`; if not, **stop, write the findings doc, and the pure-.NET transformer fallback becomes its own plan**. Everything else (WordPiece tokenizer, corpus, cosine ranking, the new `dni_search` export, Swift UI, Apple chat) is encoder-independent. One framework rebuild (ABI changed).

**Tech Stack:** .NET 10 / C# 14 (NativeAOT, `Microsoft.ML.OnnxRuntime`, `TensorPrimitives`), all-MiniLM-L6-v2 (384-d), SwiftUI + `FoundationModels` (iOS 26).

---

## File Structure

**Engine (C#):**
- `core/DotnetNativeInterop.Engine/Ai/AiModels.cs` — `SearchResult` record, `ITextEncoder`, source-gen `AiJsonContext`.
- `core/DotnetNativeInterop.Engine/Ai/WordPieceTokenizer.cs` — BERT WordPiece tokenizer.
- `core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs` — all-MiniLM via ONNX Runtime (ONNX path).
- `core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs` — corpora + cosine ranking + `Initialize`.
- `core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj` — add `Microsoft.ML.OnnxRuntime` ref + bundle the model/vocab/corpus as content.
- `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ai.cs` — `dni_search` export.
- `core/DotnetNativeInterop.NativeBridge/abi/dni.h` — declare `dni_search`.

**Assets:** `core/DotnetNativeInterop.Engine/Ai/assets/{model.onnx, vocab.txt, corpus.txt}` (downloaded in Task 1).

**iOS (Swift) — new `ios/Shared/Ai/`:**
- `SearchResult.swift`, `SemanticSearchService.swift`, `AiViewModel.swift`, `SemanticSearchView.swift`, `AppleChatView.swift`, `AiHubView.swift`.

**iOS (modified, additive):** `ios/Shared/RootTabView.swift`, `ios/Apps/Unified/UnifiedApp.swift`.

**Docs:** `docs/onnx-nativeaot-ios-findings.md` (spike outcome — success writeup or failure proof).

---

### Task 1: ONNX feasibility spike — the gate (Windows AOT)

**Files:** Download assets; throwaway `%TEMP%\dni-onnx-spike` console.

- [ ] **Step 1: Fetch the model + vocab into the engine assets**

Run (PowerShell):
```powershell
$ai = "C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.Engine\Ai\assets"
New-Item -ItemType Directory -Force $ai | Out-Null
$base = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main"
Invoke-WebRequest "$base/onnx/model.onnx" -OutFile "$ai\model.onnx"
Invoke-WebRequest "$base/vocab.txt"        -OutFile "$ai\vocab.txt"
"$((Get-Item "$ai\model.onnx").Length) bytes model; $((Get-Item "$ai\vocab.txt").Length) bytes vocab"
```
Expected: a ~90 MB `model.onnx` and a ~230 KB `vocab.txt`.

- [ ] **Step 2: Build a NativeAOT spike that runs one inference**

Create `%TEMP%\dni-onnx-spike\spike.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <PublishAot>true</PublishAot>
    <InvariantGlobalization>true</InvariantGlobalization>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.ML.OnnxRuntime" Version="1.20.1" />
  </ItemGroup>
</Project>
```
Create `%TEMP%\dni-onnx-spike\Program.cs`:
```csharp
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

var model = @"C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.Engine\Ai\assets\model.onnx";
using var session = new InferenceSession(model);
foreach (var i in session.InputMetadata) Console.WriteLine($"in:  {i.Key} {string.Join('x', i.Value.Dimensions)}");
foreach (var o in session.OutputMetadata) Console.WriteLine($"out: {o.Key} {string.Join('x', o.Value.Dimensions)}");

// [CLS] hello world [SEP]  (ids are illustrative; real ids come from the tokenizer later)
long[] ids = [101, 7592, 2088, 102];
long[] mask = [1, 1, 1, 1];
var len = ids.Length;
var inputs = new List<NamedOnnxValue>
{
    NamedOnnxValue.CreateFromTensor("input_ids", new DenseTensor<long>(ids, [1, len])),
    NamedOnnxValue.CreateFromTensor("attention_mask", new DenseTensor<long>(mask, [1, len])),
    NamedOnnxValue.CreateFromTensor("token_type_ids", new DenseTensor<long>(new long[len], [1, len])),
};
using var results = session.Run(inputs);
var hidden = results.First().AsTensor<float>();
Console.WriteLine($"PASS: ran inference, output dims = {string.Join('x', hidden.Dimensions.ToArray())}");
```

- [ ] **Step 3: Publish NativeAOT and run**

Run: `dotnet publish "$env:TEMP\dni-onnx-spike\spike.csproj" -c Release -r win-x64 -p:PublishAot=true -o "$env:TEMP\dni-onnx-spike\out"`
Then: `& "$env:TEMP\dni-onnx-spike\out\spike.exe"`
Expected (PASS): prints the input/output metadata and `PASS: ran inference, output dims = 1x4x384`.

- [ ] **Step 4: GATE — record the outcome and branch**

- **If the publish + run PASS:** ONNX is AOT-viable. Note the exact input names (`input_ids` / `attention_mask` / `token_type_ids`) and the output name from Step 2's metadata dump — Task 6 uses them. Proceed to Task 2.
- **If it FAILS** (publish trim/AOT error, or a runtime error): **STOP.** Capture the full error output verbatim. Skip to **Task 12** (write `docs/onnx-nativeaot-ios-findings.md` with the exact error + diagnosis), then report **BLOCKED-BY-GATE** to the controller: the pure-.NET all-MiniLM transformer fallback is a separate subsystem that needs its own spec→plan. Do not improvise it here.

> The iOS static-link half of the spike (linking `onnxruntime` ios-arm64 into `dni.dylib`) is validated later by the Task 13 framework build; Windows AOT is the fast first signal that decides whether to invest further.

- [ ] **Step 5: Commit the assets (model is large — confirm Git LFS or accept the size)**

```powershell
git add core/DotnetNativeInterop.Engine/Ai/assets/vocab.txt
git commit -m "feat: add all-MiniLM vocab for on-device tokenizer"
```
(Defer committing `model.onnx` until Task 6 wires it; flag its ~90 MB size to the user — may warrant Git LFS or the quantized int8 model.)

---

### Task 2: WordPiece tokenizer (engine)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/WordPieceTokenizer.cs`
- Probe: `%TEMP%\dni-probe\Program.cs` (recreate the harness as in earlier phases if absent)

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var vocabPath = @"C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.Engine\Ai\assets\vocab.txt";
var tok = new WordPieceTokenizer(System.IO.File.ReadAllLines(vocabPath));
var (ids, mask) = tok.Encode("hello world", maxLen: 16);
if (ids.Length != mask.Length) throw new Exception("FAIL: ids/mask length mismatch");
if (ids[0] != tok.ClsId || ids[^1] != tok.SepId && mask[^1] == 1) { /* CLS first; SEP after content */ }
if (ids[0] != tok.ClsId) throw new Exception($"FAIL: first id not [CLS]: {ids[0]}");
Console.WriteLine($"PASS: ids={string.Join(',', ids[..5])}… mask sum={System.Linq.Enumerable.Sum(mask)}");
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → FAIL (`WordPieceTokenizer` not found).

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/Ai/WordPieceTokenizer.cs`:
```csharp
using System.Globalization;
using System.Text;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// A minimal BERT WordPiece tokenizer (uncased) — AOT-safe, no external dependency. Lower-cases, strips
/// accents, splits on whitespace/punctuation, then greedy longest-match WordPiece against the vocab.
/// Produces padded <c>input_ids</c> + <c>attention_mask</c> wrapped with [CLS]/[SEP].
/// </summary>
public sealed class WordPieceTokenizer
{
    private readonly Dictionary<string, long> _vocab;
    public long ClsId { get; }
    public long SepId { get; }
    private readonly long _unkId;
    private readonly long _padId;

    public WordPieceTokenizer(IReadOnlyList<string> vocabLines)
    {
        _vocab = new Dictionary<string, long>(vocabLines.Count, StringComparer.Ordinal);
        for (var i = 0; i < vocabLines.Count; i++)
        {
            _vocab[vocabLines[i]] = i;
        }

        ClsId = _vocab["[CLS]"];
        SepId = _vocab["[SEP]"];
        _unkId = _vocab["[UNK]"];
        _padId = _vocab["[PAD]"];
    }

    /// <summary>Encodes one string into padded ids + mask of length <paramref name="maxLen"/>.</summary>
    public (long[] Ids, long[] Mask) Encode(string text, int maxLen = 64)
    {
        var pieces = new List<long> { ClsId };
        foreach (var word in BasicTokenize(text))
        {
            foreach (var piece in WordPiece(word))
            {
                if (pieces.Count >= maxLen - 1)
                {
                    break;
                }

                pieces.Add(piece);
            }
        }

        pieces.Add(SepId);

        var ids = new long[maxLen];
        var mask = new long[maxLen];
        for (var i = 0; i < maxLen; i++)
        {
            if (i < pieces.Count)
            {
                ids[i] = pieces[i];
                mask[i] = 1;
            }
            else
            {
                ids[i] = _padId;
                mask[i] = 0;
            }
        }

        return (ids, mask);
    }

    // Lower-case, strip accents, split on whitespace, peel punctuation into its own tokens.
    private static IEnumerable<string> BasicTokenize(string text)
    {
        var normalized = text.ToLowerInvariant().Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder();
        var words = new List<string>();
        void Flush()
        {
            if (sb.Length > 0) { words.Add(sb.ToString()); sb.Clear(); }
        }

        foreach (var ch in normalized)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) == UnicodeCategory.NonSpacingMark)
            {
                continue; // drop accents
            }

            if (char.IsWhiteSpace(ch))
            {
                Flush();
            }
            else if (char.IsPunctuation(ch) || char.IsSymbol(ch))
            {
                Flush();
                words.Add(ch.ToString());
            }
            else
            {
                sb.Append(ch);
            }
        }

        Flush();
        return words;
    }

    // Greedy longest-match WordPiece; unknown words → [UNK].
    private IEnumerable<long> WordPiece(string word)
    {
        var start = 0;
        var output = new List<long>();
        while (start < word.Length)
        {
            var end = word.Length;
            long matched = -1;
            while (start < end)
            {
                var sub = (start > 0 ? "##" : string.Empty) + word[start..end];
                if (_vocab.TryGetValue(sub, out var id))
                {
                    matched = id;
                    break;
                }

                end--;
            }

            if (matched < 0)
            {
                return [_unkId];
            }

            output.Add(matched);
            start = end;
        }

        return output;
    }
}
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → Expected `PASS: ids=101,7592,2088,102,0… mask sum=4` (ids will reflect the real vocab).

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/Ai/WordPieceTokenizer.cs
git commit -m "feat: add AOT-safe BERT WordPiece tokenizer"
```

---

### Task 3: AI models + JSON contract (engine)

**Files:** Create `core/DotnetNativeInterop.Engine/Ai/AiModels.cs`. Probe: `%TEMP%\dni-probe\Program.cs`.

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var json = AiJson.Serialize([ new SearchResult("AES-GCM encryption", 0.83), new SearchResult("Brotli", 0.41) ]);
if (!json.Contains("\"text\":\"AES-GCM encryption\"") || !json.Contains("\"score\":0.83")) throw new Exception($"FAIL: {json}");
Console.WriteLine("PASS: " + json);
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → FAIL (`SearchResult`/`AiJson` not found).

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/Ai/AiModels.cs`:
```csharp
using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine;

/// <summary>One ranked corpus entry: the text and its cosine similarity to the query (0..1).</summary>
public sealed record SearchResult(string Text, double Score);

/// <summary>A text → fixed-width embedding encoder. The ONNX and pure-.NET impls are interchangeable.</summary>
public interface ITextEncoder
{
    /// <summary>Returns an L2-normalized sentence embedding for <paramref name="text"/>.</summary>
    float[] Encode(string text);
}

/// <summary>Source-generated JSON metadata for the search results (AOT-safe).</summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(SearchResult[]))]
internal sealed partial class AiJsonContext : JsonSerializerContext;

/// <summary>Serializes ranked results to camelCase JSON via the source-gen context.</summary>
public static class AiJson
{
    public static string Serialize(SearchResult[] results) =>
        JsonSerializer.Serialize(results, AiJsonContext.Default.SearchResultArray);
}
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → Expected `PASS: [{"text":"AES-GCM encryption","score":0.83},{"text":"Brotli","score":0.41}]`.

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/Ai/AiModels.cs
git commit -m "feat: add AI search-result model + JSON contract"
```

---

### Task 4: Semantic search + cosine ranking (engine)

**Files:** Create `core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs` and `core/DotnetNativeInterop.Engine/Ai/assets/corpus.txt`. Probe: `%TEMP%\dni-probe\Program.cs`.

- [ ] **Step 1: Create the fact corpus**

Create `core/DotnetNativeInterop.Engine/Ai/assets/corpus.txt` (one fact per line, ~30 lines):
```text
NativeAOT compiles .NET ahead-of-time to a native binary with no JIT or runtime.
The garbage collector reclaims unused managed memory automatically.
Span<T> enables zero-allocation slicing of arrays and strings.
TensorPrimitives provides SIMD-accelerated vector math like cosine similarity.
AES-GCM provides authenticated encryption with a 16-byte tag.
SQLCipher encrypts an entire SQLite database at rest with a PRAGMA key.
async and await express asynchronous code without blocking threads.
Records are immutable reference types with value-based equality.
The field keyword exposes a property's backing field in C# 14.
Source-generated JSON avoids reflection so it works under NativeAOT.
gRPC is a high-performance RPC framework using HTTP/2 and Protocol Buffers.
P/Invoke calls native C functions from managed code across the ABI.
Swift Charts renders native charts declaratively in SwiftUI.
The iOS Simulator runs apps on a Mac without a physical device.
Metal is Apple's low-level GPU graphics and compute API.
A Mandelbrot set is a fractal defined by an escape-time iteration.
Ray marching renders 3D scenes by stepping along signed distance fields.
BigInteger represents arbitrary-precision integers in .NET.
Brotli is a compression algorithm with strong ratios for text.
Channels provide a thread-safe producer-consumer pipeline with backpressure.
A foreign function interface calls across a native-to-managed boundary.
WordPiece tokenization splits text into subword units for transformers.
An embedding maps text to a vector so similarity becomes geometry.
Cosine similarity measures the angle between two vectors.
Apple Foundation Models run a language model on-device in iOS 26.
ONNX is an open format for interchangeable machine-learning models.
Quantization shrinks a model by storing weights in lower precision.
The CLS token's output is often used as a sentence representation.
Mean pooling averages token embeddings into one sentence vector.
SIMD executes one instruction across multiple data lanes at once.
```

- [ ] **Step 2: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;

// A stub encoder so ranking is testable without the real model: bag-of-words over a tiny vocab.
sealed class StubEncoder : ITextEncoder {
    static readonly string[] V = ["encrypt","aes","compress","brotli","vector","cosine","fractal"];
    public float[] Encode(string t) {
        var v = new float[V.Length]; var lo = t.ToLowerInvariant();
        for (var i=0;i<V.Length;i++) v[i] = lo.Contains(V[i]) ? 1f : 0f;
        var n = MathF.Sqrt(System.Linq.Enumerable.Sum(v, x=>x*x)); if (n>0) for (var i=0;i<v.Length;i++) v[i]/=n;
        return v;
    }
}
var corpus = new[] { "AES-GCM provides authenticated encryption", "Brotli is a compression algorithm", "Cosine similarity measures vector angle" };
var search = new SemanticSearch(new StubEncoder());
search.SetCorpus("test", corpus);
var top = search.Search("how do I encrypt with aes", "test", 3);
if (top[0].Text != "AES-GCM provides authenticated encryption") throw new Exception($"FAIL: top={top[0].Text}");
Console.WriteLine($"PASS: top='{top[0].Text}' score={top[0].Score:F2}");
```

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs`:
```csharp
using System.Numerics.Tensors;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Embeds a corpus once, then ranks it by cosine similarity to a query embedding (SIMD via
/// <see cref="TensorPrimitives"/>). Encoder-agnostic: works with the ONNX or pure-.NET encoder.
/// </summary>
public sealed class SemanticSearch(ITextEncoder encoder)
{
    private readonly Dictionary<string, (string[] Texts, float[][] Embeddings)> _corpora = new(StringComparer.Ordinal);

    /// <summary>Embeds and stores a named corpus.</summary>
    public void SetCorpus(string id, IReadOnlyList<string> texts)
    {
        var embeddings = new float[texts.Count][];
        for (var i = 0; i < texts.Count; i++)
        {
            embeddings[i] = encoder.Encode(texts[i]);
        }

        _corpora[id] = (texts.ToArray(), embeddings);
    }

    /// <summary>Ranks corpus <paramref name="corpusId"/> by cosine similarity to <paramref name="query"/>.</summary>
    public SearchResult[] Search(string query, string corpusId, int topK)
    {
        if (!_corpora.TryGetValue(corpusId, out var corpus))
        {
            return [];
        }

        var q = encoder.Encode(query);
        var scored = new List<SearchResult>(corpus.Texts.Length);
        for (var i = 0; i < corpus.Texts.Length; i++)
        {
            var score = TensorPrimitives.CosineSimilarity(q, corpus.Embeddings[i]);
            scored.Add(new SearchResult(corpus.Texts[i], Math.Round(score, 4)));
        }

        scored.Sort((a, b) => b.Score.CompareTo(a.Score));
        return scored.Take(topK).ToArray();
    }
}
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → Expected `PASS: top='AES-GCM provides authenticated encryption' score=…`.

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs core/DotnetNativeInterop.Engine/Ai/assets/corpus.txt
git commit -m "feat: add semantic search (cosine ranking) + fact corpus"
```

---

### Task 5: ONNX text encoder (engine) — ONNX path only

> Only if Task 1 PASSED. Use the exact input/output names the spike's metadata dump printed.

**Files:**
- Modify: `core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj` (add the ONNX package + asset content)
- Create: `core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs`
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Reference ONNX Runtime + bundle the assets**

In `core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj`, add inside a new `<ItemGroup>`:
```xml
    <PackageReference Include="Microsoft.ML.OnnxRuntime" Version="1.20.1" />
    <None Include="Ai/assets/model.onnx" CopyToOutputDirectory="PreserveNewest" />
    <None Include="Ai/assets/vocab.txt" CopyToOutputDirectory="PreserveNewest" />
    <None Include="Ai/assets/corpus.txt" CopyToOutputDirectory="PreserveNewest" />
```

- [ ] **Step 2: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var dir = @"C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.Engine\Ai\assets";
var tok = new WordPieceTokenizer(System.IO.File.ReadAllLines($"{dir}\\vocab.txt"));
using var enc = new OnnxTextEncoder($"{dir}\\model.onnx", tok);
var related   = System.Numerics.Tensors.TensorPrimitives.CosineSimilarity(enc.Encode("encrypt my data"), enc.Encode("AES-GCM authenticated encryption"));
var unrelated = System.Numerics.Tensors.TensorPrimitives.CosineSimilarity(enc.Encode("encrypt my data"), enc.Encode("a ray-marched 3D fractal"));
if (related <= unrelated) throw new Exception($"FAIL: related {related:F3} !> unrelated {unrelated:F3}");
Console.WriteLine($"PASS: related={related:F3} > unrelated={unrelated:F3}, dim={enc.Encode(\"x\").Length}");
```

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs`:
```csharp
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;
using System.Numerics.Tensors;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// all-MiniLM-L6-v2 sentence encoder via ONNX Runtime: tokenize → run the transformer → mean-pool token
/// embeddings over the attention mask → L2-normalize to a 384-d vector. (Input/output names match the
/// model metadata confirmed by the feasibility spike.)
/// </summary>
public sealed class OnnxTextEncoder : ITextEncoder, IDisposable
{
    private const int MaxLen = 64;
    private readonly InferenceSession _session;
    private readonly WordPieceTokenizer _tokenizer;
    private readonly string _outputName;

    public OnnxTextEncoder(string modelPath, WordPieceTokenizer tokenizer)
    {
        _session = new InferenceSession(modelPath);
        _tokenizer = tokenizer;
        _outputName = _session.OutputMetadata.Keys.First();
    }

    public float[] Encode(string text)
    {
        var (ids, mask) = _tokenizer.Encode(text, MaxLen);
        var len = ids.Length;
        var inputs = new List<NamedOnnxValue>
        {
            NamedOnnxValue.CreateFromTensor("input_ids", new DenseTensor<long>(ids, [1, len])),
            NamedOnnxValue.CreateFromTensor("attention_mask", new DenseTensor<long>(mask, [1, len])),
            NamedOnnxValue.CreateFromTensor("token_type_ids", new DenseTensor<long>(new long[len], [1, len])),
        };

        using var results = _session.Run(inputs);
        var hidden = results.First(r => r.Name == _outputName).AsTensor<float>(); // [1, len, 384]
        var dim = hidden.Dimensions[2];

        // Mean-pool over masked tokens.
        var pooled = new float[dim];
        long count = 0;
        for (var t = 0; t < len; t++)
        {
            if (mask[t] == 0)
            {
                continue;
            }

            count++;
            for (var d = 0; d < dim; d++)
            {
                pooled[d] += hidden[0, t, d];
            }
        }

        if (count > 0)
        {
            for (var d = 0; d < dim; d++)
            {
                pooled[d] /= count;
            }
        }

        // L2-normalize so cosine == dot product.
        var norm = MathF.Sqrt(TensorPrimitives.Dot(pooled, pooled));
        if (norm > 0)
        {
            TensorPrimitives.Divide(pooled, norm, pooled);
        }

        return pooled;
    }

    public void Dispose() => _session.Dispose();
}
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → Expected `PASS: related=… > unrelated=…, dim=384`.

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs core/DotnetNativeInterop.Engine/Ai/assets/model.onnx
git commit -m "feat: add all-MiniLM ONNX text encoder (mean-pooled, normalized)"
```
(If the model size makes the commit unwieldy, set up Git LFS for `*.onnx` first and re-add.)

---

### Task 6: Wire the search into the engine + `dni_search` export

**Files:**
- Modify: `core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs` (add a static `Default` initialized from the catalog + corpus.txt)
- Create: `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ai.cs`
- Modify: `core/DotnetNativeInterop.NativeBridge/abi/dni.h`
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Add a lazily-initialized default search instance**

Append to `core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs` (inside the `SemanticSearch` class):
```csharp
    private static SemanticSearch? _default;
    private static readonly object Gate = new();

    /// <summary>Process-wide search over two corpora ("features", "facts"), built once from bundled assets.</summary>
    public static SemanticSearch Default
    {
        get
        {
            lock (Gate)
            {
                if (_default is not null)
                {
                    return _default;
                }

                var dir = Path.Combine(AppContext.BaseDirectory, "Ai", "assets");
                var tokenizer = new WordPieceTokenizer(File.ReadAllLines(Path.Combine(dir, "vocab.txt")));
                var search = new SemanticSearch(new OnnxTextEncoder(Path.Combine(dir, "model.onnx"), tokenizer));
                search.SetCorpus("features",
                    LanguageFeatureCatalog.Descriptors.Select(d => $"{d.Title}: {d.Version}").ToArray());
                search.SetCorpus("facts", File.ReadAllLines(Path.Combine(dir, "corpus.txt"))
                    .Where(l => l.Trim().Length > 0).ToArray());
                _default = search;
                return _default;
            }
        }
    }

    /// <summary>Searches the default instance; returns JSON top-K for the native export.</summary>
    public static string SearchJson(string query, string corpusId, int topK = 5) =>
        AiJson.Serialize(Default.Search(query, corpusId, topK));
```

- [ ] **Step 2: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var json = SemanticSearch.SearchJson("how do I encrypt data", "features", 3);
if (!json.Contains("AES-GCM")) throw new Exception($"FAIL: encrypt query didn't surface AES-GCM: {json}");
var facts = SemanticSearch.SearchJson("what shrinks a model", "facts", 3);
if (!facts.ToLower().Contains("quantiz")) throw new Exception($"FAIL: facts: {facts}");
Console.WriteLine("PASS: features+facts search return sensible top-K");
```

- [ ] **Step 3: Run probe to verify it fails, then passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"` — first FAIL (`SearchJson` missing), then after Step 1 PASS once the encoder + assets resolve.

- [ ] **Step 4: Write the export**

Create `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ai.cs`:
```csharp
using System.Runtime.InteropServices;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>Semantic-search export (FFI): ranks a corpus by cosine similarity to a free-text query.</summary>
internal static class ExportsAi
{
    /// <summary>Ranks corpus ("features"|"facts") by similarity to query; JSON [{text,score}]. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_search")]
    public static unsafe nint Search(byte* query, byte* corpus)
    {
        try
        {
            var q = NativeText.Read((nint)query);
            var c = NativeText.Read((nint)corpus);
            return NativeText.Allocate(SemanticSearch.SearchJson(q, c, 5));
        }
        catch (Exception)
        {
            return 0;
        }
    }
}
```

- [ ] **Step 5: Declare it in the ABI header**

In `core/DotnetNativeInterop.NativeBridge/abi/dni.h`, add before the closing `#ifdef __cplusplus`:
```c
/* ---- Onboard AI: semantic search --------------------------------------- */
/* Ranks `corpus` ("features" | "facts") by cosine similarity to free-text `query`;
 * returns heap UTF-8 JSON [{text,score}] (top-K). Copy then release with dni_string_free. */
const char* dni_search(const char* query, const char* corpus);
```

- [ ] **Step 6: Managed build + commit**

Run: `dotnet build DotnetNativeInterop.slnx -c Release` → `0 Error(s)` (the 2 pre-existing CS1668 env warnings aside).
```powershell
git add core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ai.cs core/DotnetNativeInterop.NativeBridge/abi/dni.h
git commit -m "feat: add dni_search export over features + facts corpora"
```

---

### Task 7: iOS search model + service (swift)

**Files:** Create `ios/Shared/Ai/SearchResult.swift`, `ios/Shared/Ai/SemanticSearchService.swift`.

- [ ] **Step 1: Write the model + service**

Create `ios/Shared/Ai/SearchResult.swift`:
```swift
import Foundation

/// One ranked corpus entry from `dni_search` (camelCase JSON).
struct SearchResult: Codable, Sendable, Identifiable {
    let text: String
    let score: Double
    var id: String { text }
}
```

Create `ios/Shared/Ai/SemanticSearchService.swift`:
```swift
import Foundation

/// Runs on-device semantic search over the in-process C ABI (`dni_search`). FFI-only — the engine owns
/// the model and corpora. Copies + frees the returned heap UTF-8 JSON, then decodes it.
struct SemanticSearchService: Sendable {
    func search(_ query: String, corpus: String) async throws -> [SearchResult] {
        let json = try await Task.detached(priority: .userInitiated) { () throws -> String in
            let ptr: UnsafePointer<CChar>? = query.withCString { q in
                corpus.withCString { c in dni_search(q, c) }
            }
            guard let ptr else { throw FeatureServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return String(cString: ptr)
        }.value
        return try JSONDecode.decode([SearchResult].self, from: json)
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Ai/SearchResult.swift ios/Shared/Ai/SemanticSearchService.swift
git commit -m "feat: add iOS semantic-search model + FFI service"
```

---

### Task 8: Semantic search view (swift)

**Files:** Create `ios/Shared/Ai/AiViewModel.swift`, `ios/Shared/Ai/SemanticSearchView.swift`.

- [ ] **Step 1: Write the view model**

Create `ios/Shared/Ai/AiViewModel.swift`:
```swift
import Foundation

/// Backs the semantic-search screen: holds the query, selected corpus, and ranked results.
@MainActor
final class AiViewModel: ObservableObject {
    @Published var query = ""
    @Published var corpus = "features"
    @Published var results: [SearchResult] = []
    @Published var searching = false
    @Published var errorMessage: String?

    private let service: SemanticSearchService
    init(service: SemanticSearchService) { self.service = service }

    func run() async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        searching = true
        defer { searching = false }
        do {
            results = try await service.search(q, corpus: corpus)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
```

- [ ] **Step 2: Write the view**

Create `ios/Shared/Ai/SemanticSearchView.swift`:
```swift
import SwiftUI

/// On-device semantic search powered entirely by the .NET engine: the query is embedded by an
/// all-MiniLM model running in NativeAOT and cosine-ranked against the chosen corpus.
struct SemanticSearchView: View {
    @StateObject private var model: AiViewModel

    init(service: SemanticSearchService) {
        _model = StateObject(wrappedValue: AiViewModel(service: service))
    }

    var body: some View {
        List {
            Section {
                Picker("Corpus", selection: $model.corpus) {
                    Text("App features").tag("features")
                    Text("Facts").tag("facts")
                }
                .pickerStyle(.segmented)
                HStack {
                    TextField("Search… e.g. \u{201C}encrypt my data\u{201D}", text: $model.query)
                        .textInputAutocapitalization(.never)
                        .onSubmit { Task { await model.run() } }
                    Button {
                        Task { await model.run() }
                    } label: {
                        if model.searching { ProgressView() } else { Image(systemName: "magnifyingglass") }
                    }
                    .disabled(model.searching)
                }
                Text("The query is embedded by an all-MiniLM model running in the NativeAOT .NET engine, "
                     + "then cosine-ranked against the corpus — all in-process, no cloud.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if let error = model.errorMessage {
                Section { Text(error).foregroundStyle(.red) }
            }

            if !model.results.isEmpty {
                Section("Results") {
                    ForEach(model.results) { result in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(result.text)
                            ProgressView(value: max(0, min(1, result.score)))
                                .tint(.blue)
                            Text(String(format: "similarity %.3f", result.score))
                                .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
        }
        .navigationTitle("Semantic search")
    }
}
```

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Ai/AiViewModel.swift ios/Shared/Ai/SemanticSearchView.swift
git commit -m "feat: add semantic search screen (.NET-driven)"
```

---

### Task 9: Apple Foundation Models chat (swift)

**Files:** Create `ios/Shared/Ai/AppleChatView.swift`.

- [ ] **Step 1: Write the view**

Create `ios/Shared/Ai/AppleChatView.swift`:
```swift
import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Apple's on-device language model (iOS 26 `FoundationModels`), for comparison with the .NET search.
/// Gated behind availability — degrades to an explanatory card on ineligible devices. Pure Swift, no engine.
struct AppleChatView: View {
    @State private var prompt = ""
    @State private var answer = ""
    @State private var thinking = false
    @State private var unavailableReason: String?

    var body: some View {
        List {
            Section {
                Text("Apple's on-device model (Foundation Models), shown for contrast — this one is driven "
                     + "from Swift, not the .NET engine.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let reason = unavailableReason {
                Section {
                    ContentUnavailableView("Apple Intelligence unavailable", systemImage: "sparkles.slash",
                                           description: Text(reason))
                }
            } else {
                Section {
                    HStack {
                        TextField("Ask Apple's model…", text: $prompt)
                            .onSubmit { Task { await ask() } }
                        Button {
                            Task { await ask() }
                        } label: {
                            if thinking { ProgressView() } else { Image(systemName: "paperplane.fill") }
                        }
                        .disabled(thinking || prompt.isEmpty)
                    }
                }
                if !answer.isEmpty {
                    Section("Response") { Text(answer) }
                }
            }
        }
        .navigationTitle("Apple chat")
        .task { checkAvailability() }
    }

    private func checkAvailability() {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: unavailableReason = nil
            case .unavailable(let reason): unavailableReason = "\(reason)"
            @unknown default: unavailableReason = "Unknown availability."
            }
        } else {
            unavailableReason = "Requires iOS 26 or later."
        }
        #else
        unavailableReason = "FoundationModels isn't available in this build."
        #endif
    }

    private func ask() async {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            thinking = true
            defer { thinking = false }
            answer = ""
            do {
                let session = LanguageModelSession()
                let stream = session.streamResponse(to: prompt)
                for try await partial in stream {
                    answer = partial.content
                }
            } catch {
                answer = "Error: \(error.localizedDescription)"
            }
        }
        #endif
    }
}
```

> The `FoundationModels` streaming API surface is verified on-device during the Task 13 build; if the
> `streamResponse`/`content` names differ in the installed SDK, adjust to the SDK's signatures (the
> availability gating + `LanguageModelSession` entry point are stable).

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Ai/AppleChatView.swift
git commit -m "feat: add gated Apple Foundation Models chat view"
```

---

### Task 10: AI hub + app wiring (swift)

**Files:** Create `ios/Shared/Ai/AiHubView.swift`; modify `ios/Shared/RootTabView.swift`, `ios/Apps/Unified/UnifiedApp.swift`.

- [ ] **Step 1: Write the hub**

Create `ios/Shared/Ai/AiHubView.swift`:
```swift
import SwiftUI

/// The AI tab: on-device semantic search driven by the .NET engine, plus Apple's model for contrast.
struct AiHubView: View {
    let search: SemanticSearchService

    var body: some View {
        NavigationStack {
            List {
                Section("On-device, in the .NET engine") {
                    NavigationLink { SemanticSearchView(service: search) } label: {
                        Label("Semantic search", systemImage: "magnifyingglass")
                    }
                }
                Section("Apple, for comparison") {
                    NavigationLink { AppleChatView() } label: {
                        Label("Apple chat", systemImage: "apple.logo")
                    }
                }
            }
            .navigationTitle("AI")
        }
    }
}
```

- [ ] **Step 2: Insert the AI tab in `RootTabView`**

In `ios/Shared/RootTabView.swift`, add the property and the tab (after About, or before it). Add to the struct's stored properties:
```swift
    let search: SemanticSearchService
```
And add the tab inside the `TabView` (after the About tab):
```swift
            AiHubView(search: search)
                .tabItem { Label("AI", systemImage: "sparkles") }
```

- [ ] **Step 3: Build the service in `UnifiedApp`**

In `ios/Apps/Unified/UnifiedApp.swift`, add the property and pass it to `RootTabView`:
```swift
    private let search = SemanticSearchService()
```
And update the `RootTabView(...)` call to pass `search: search` alongside the existing arguments.

- [ ] **Step 4: Commit**

```powershell
git add ios/Shared/Ai/AiHubView.swift ios/Shared/RootTabView.swift ios/Apps/Unified/UnifiedApp.swift
git commit -m "feat: add AI tab (semantic search + Apple chat) to the app"
```

---

### Task 11: Findings doc (the spike outcome — written either way)

**Files:** Create `docs/onnx-nativeaot-ios-findings.md`.

- [ ] **Step 1: Write the findings**

Create `docs/onnx-nativeaot-ios-findings.md` capturing the spike outcome:
- **If the spike PASSED:** document *how* — the package version, `PublishAot` settings, the exact model input/output names, and (after Task 13) how the `onnxruntime` ios-arm64 lib was linked into the dylib. Title it "Running ONNX Runtime under NativeAOT on iOS — what worked."
- **If the spike FAILED:** paste the **exact** `dotnet publish` / runtime error verbatim, the diagnosis (what AOT/trim/link step broke), what was tried, and what a fix would likely require. Title it "ONNX Runtime under NativeAOT on iOS — where it breaks (open problem)." End with an explicit invitation for community solutions.

Use the real command output captured in Task 1 (and Task 13 for the iOS link) — no paraphrasing.

- [ ] **Step 2: Commit**

```powershell
git add docs/onnx-nativeaot-ios-findings.md
git commit -m "docs: record ONNX-on-NativeAOT-iOS spike findings"
```

---

### Task 12: Fallback gate (only if Task 1 FAILED)

If the spike failed: Tasks 5, 6's encoder wiring, and the on-device ONNX path are **not** built. Instead:
- Task 11's findings doc is written (failure proof, kept in the repo).
- The ONNX-specific scaffolding (`OnnxTextEncoder.cs`, the csproj package ref) is left in place under `Ai/` but **excluded from the build** (wrap in `#if ONNX_SPIKE` or move to `Ai/Onnx/` with `<Compile Remove>`), mirroring how `Grpc/` is kept-but-excluded — so the attempt is preserved.
- **Report BLOCKED-BY-GATE to the controller:** the pure-.NET all-MiniLM transformer fallback (token/position embeddings → 6× attention/FFN/layernorm → mean-pool) is a substantial subsystem that needs its own spec→plan. The shared pieces (tokenizer, search, export, Swift UI, Apple chat) are done and will light up as soon as a `ManagedTextEncoder : ITextEncoder` is supplied to `SemanticSearch.Default`.

(No code in this task — it's the decision/handoff if the gate fails.)

---

### Task 13: Native rebuild (ABI changed) + on-device verification (Mac mini)

> The C ABI changed (`dni_search` added) **and** the engine now links ONNX Runtime, so the framework MUST be rebuilt — and this is where the **iOS half of the spike** is validated (linking `onnxruntime` ios-arm64 into `dni.dylib`).

**Files:** none (build + verification).

- [ ] **Step 1: Sync the clean branch to a fresh Mac dir**

```powershell
ssh steve-mac-mini "zsh -lc 'rm -rf ~/dni-ai-build && mkdir -p ~/dni-ai-build'"
git archive --format=tar HEAD | ssh steve-mac-mini "tar -xf - -C /Users/steve/dni-ai-build"
```
(If `model.onnx` is in Git LFS, fetch it on the Mac or copy it over separately so the build can bundle it.)

- [ ] **Step 2: Build the framework — watch the ONNX iOS link**

On the Mac: `cd ~/dni-ai-build && bash build/build-ios-framework-device.sh`.
- **If the publish links** (`onnxruntime` ios-arm64 resolves into `dni.dylib`): continue. Record how (any `DirectPInvoke`/`NativeLibrary`/linker flags needed) for the findings doc.
- **If the link fails:** capture the exact linker error → it goes in `docs/onnx-nativeaot-ios-findings.md` (iOS half), and the engine falls back per Task 12.

- [ ] **Step 3: xcodegen, build, install** (reuse the `~/run-lab-build.sh` pattern: keychain unlock inline → `xcodegen generate` → signed `xcodebuild` → `xcrun devicectl device install`).

- [ ] **Step 4: Verify on device**

- [ ] AI tab present (7 tabs).
- [ ] **Semantic search:** "encrypt my data" over *features* surfaces AES-GCM near the top; "what shrinks a model" over *facts* surfaces the quantization line; scores shown and sensibly ordered.
- [ ] **Apple chat:** responds with streamed text on the eligible iPad, or shows the graceful "unavailable" card.
- [ ] **No regressions:** Dashboard, Features, Lab, Compare, Latency, About all still work.

---

## Self-Review

**Spec coverage:** spike gate → Task 1; tokenizer → Task 2; models/JSON → Task 3; search+cosine+corpus → Task 4; ONNX encoder → Task 5; `dni_search` export + ABI → Task 6; iOS service/views → Tasks 7–8; Apple FM → Task 9; AI tab wiring → Task 10; findings doc (success or failure proof) → Task 11; failure-preservation branch → Task 12; ABI rebuild + iOS link validation + on-device → Task 13. Both corpora (features + facts) → Task 4/6. ✅

**Placeholder scan:** The pure-.NET transformer fallback is intentionally **decomposed** to its own spec→plan (Task 12 handoff), not stubbed — it's a large independent subsystem and only built if the gate fails. All built-here tasks have complete code.

**Type consistency:** `SearchResult(Text, Score)` matches the Swift `SearchResult{text,score}`; `ITextEncoder.Encode`, `SemanticSearch.SetCorpus/Search/Default/SearchJson`, `WordPieceTokenizer.Encode/ClsId/SepId`, and `dni_search(query, corpus)` ↔ `SemanticSearchService.search(_:corpus:)` are consistent across tasks. ✅

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-05-onboard-ai.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks. Note: **Task 1 (the spike) is a hard gate** — its result determines whether Tasks 5/6/13 proceed on the ONNX path or branch to the fallback handoff.

**2. Inline Execution** — execute in this session with checkpoints (sensible here, since the spike gate needs a human-aware decision before the heavy ONNX/Mac work).

**Which approach?**
