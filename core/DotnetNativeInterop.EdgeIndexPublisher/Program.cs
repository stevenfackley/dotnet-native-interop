using System.Text.Json;
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.EdgeIndexPublisher;

// Defaults assume `dotnet run` from the repo root. Override via args: [corpusDir] [outputDb] [assetsDir].
var corpusDir = args.Length > 0 ? args[0]
    : Path.Combine("core", "DotnetNativeInterop.EdgeIndexPublisher", "corpus");
var outputDb = args.Length > 1 ? args[1]
    : Path.Combine("core", "DotnetNativeInterop.EdgeIndexPublisher", "edge-index.db");
var assetsDir = args.Length > 2 ? args[2]
    : Path.Combine("core", "DotnetNativeInterop.Engine", "Ai", "assets");

var vocabPath = Path.Combine(assetsDir, "vocab.txt");
var modelPath = Path.Combine(assetsDir, "model.onnx");
Console.WriteLine($"corpus={corpusDir}  out={outputDb}  model={modelPath}");

var tokenizer = new WordPieceTokenizer(File.ReadAllLines(vocabPath));
using var encoder = new OnnxTextEncoder(modelPath, tokenizer);

var rows = new List<IndexRow>();
foreach (var md in Directory.EnumerateFiles(corpusDir, "*.md", SearchOption.AllDirectories).OrderBy(p => p))
{
    var (front, body) = Frontmatter.Parse(File.ReadAllText(md));
    var chunks = MarkdownChunker.Chunk(body);
    for (var i = 0; i < chunks.Count; i++)
    {
        var c = chunks[i];
        var embedded = encoder.Encode($"{c.SectionTitle}. {c.Body}");
        rows.Add(new IndexRow(
            ChunkId: $"{front.DocumentId}#{i}",
            DocumentId: front.DocumentId,
            SectionTitle: c.SectionTitle,
            ContentText: c.Body,
            Embedding: embedded,
            ErrorCodes: front.ErrorCodes,
            ToolsRequired: front.ToolsRequired));
    }
}

IndexWriter.Write(outputDb, rows);
Console.WriteLine($"Wrote {rows.Count} chunks to {outputDb}");

// Fixtures for the on-device cross-runtime agreement test (Task 9).
const string sampleQuery = "compressor won't start";
var (ids, _) = tokenizer.Encode(sampleQuery);
var queryVec = encoder.Encode(sampleQuery);
var ranked = rows
    .Select(r => (r.ChunkId, Score: System.Numerics.Tensors.TensorPrimitives.CosineSimilarity(queryVec, r.Embedding)))
    .OrderByDescending(x => x.Score).First();
var fixturesPath = Path.Combine(Path.GetDirectoryName(outputDb)!, "edge-fixtures.json");
File.WriteAllText(fixturesPath, JsonSerializer.Serialize(new
{
    query = sampleQuery,
    ids = ids,
    queryVector = queryVec,
    expectedTopChunkId = ranked.ChunkId,
}, new JsonSerializerOptions { WriteIndented = false }));
Console.WriteLine($"Wrote fixtures ({ranked.ChunkId} top) to {fixturesPath}");
