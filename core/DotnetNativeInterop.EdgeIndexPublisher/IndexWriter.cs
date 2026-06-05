using Microsoft.Data.Sqlite;

namespace DotnetNativeInterop.EdgeIndexPublisher;

/// One row to index: identifiers, display text, the 384-d embedding, and the filter metadata.
public sealed record IndexRow(
    string ChunkId, string DocumentId, string SectionTitle, string ContentText,
    float[] Embedding, string[] ErrorCodes, string[] ToolsRequired);

/// Writes the `Chunks` table. Embedding is stored as a raw little-endian float32[384] BLOB; metadata is
/// JSON ({ "error_codes":[…], "tools_required":[…] }) for the client's filter facets.
public static class IndexWriter
{
    public static void Write(string dbPath, IReadOnlyList<IndexRow> rows)
    {
        if (File.Exists(dbPath))
        {
            File.Delete(dbPath);
        }

        using var conn = new SqliteConnection($"Data Source={dbPath}");
        conn.Open();

        using (var create = conn.CreateCommand())
        {
            create.CommandText =
                """
                CREATE TABLE Chunks (
                  ChunkId       TEXT PRIMARY KEY,
                  DocumentId    TEXT NOT NULL,
                  SectionTitle  TEXT NOT NULL,
                  ContentText   TEXT NOT NULL,
                  Embedding     BLOB NOT NULL,
                  Metadata      TEXT NOT NULL
                );
                """;
            create.ExecuteNonQuery();
        }

        using var tx = conn.BeginTransaction();
        foreach (var row in rows)
        {
            var blob = new byte[row.Embedding.Length * sizeof(float)];
            Buffer.BlockCopy(row.Embedding, 0, blob, 0, blob.Length);
            var meta = System.Text.Json.JsonSerializer.Serialize(new
            {
                error_codes = row.ErrorCodes,
                tools_required = row.ToolsRequired,
            });

            using var cmd = conn.CreateCommand();
            cmd.CommandText =
                "INSERT INTO Chunks (ChunkId, DocumentId, SectionTitle, ContentText, Embedding, Metadata) " +
                "VALUES ($id, $doc, $title, $text, $emb, $meta)";
            cmd.Parameters.AddWithValue("$id", row.ChunkId);
            cmd.Parameters.AddWithValue("$doc", row.DocumentId);
            cmd.Parameters.AddWithValue("$title", row.SectionTitle);
            cmd.Parameters.AddWithValue("$text", row.ContentText);
            cmd.Parameters.AddWithValue("$emb", blob);
            cmd.Parameters.AddWithValue("$meta", meta);
            cmd.ExecuteNonQuery();
        }

        tx.Commit();
    }
}
