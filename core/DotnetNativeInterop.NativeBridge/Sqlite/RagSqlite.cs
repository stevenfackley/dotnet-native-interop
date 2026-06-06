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
