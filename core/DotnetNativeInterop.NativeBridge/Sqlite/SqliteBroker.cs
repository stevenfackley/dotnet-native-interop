// NOTE: native e_sqlite3 linkage for ios-arm64 / linux-bionic-arm64 RIDs is
// handled by the build scripts (build/build-ios-framework.sh, build/build-android-so.sh)
// — nothing to do here.

using Microsoft.Data.Sqlite;
using DotnetNativeInterop.Engine;
using System.Globalization;

namespace DotnetNativeInterop.NativeBridge.Sqlite;

/// <summary>
/// Pattern 4 — SQLite WAL message broker.
///
/// The native UI host inserts rows into <c>requests</c>; this broker polls for
/// <c>status='pending'</c> rows, drives <see cref="InferenceOrchestrator"/>, and
/// streams results into <c>response_tokens</c>.
///
/// Tradeoff: ~50 ms poll latency + one row write per token (write amplification).
/// Advantage: full durability — the transcript survives an app kill and can be
/// resumed; WAL lets the UI read while the broker writes concurrently.
/// </summary>
internal sealed class SqliteBroker : IDisposable
{
    private const int PollIntervalMs = 50;

    private readonly string _dbPath;
    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;
    private SqliteConnection? _conn;
    private bool _disposed;

    internal SqliteBroker(string dbPath)
    {
        _dbPath = dbPath;
    }

    /// <summary>Opens the database, ensures the schema, and starts the background poll loop.</summary>
    internal void Start()
    {
        _conn = OpenAndInitialise(_dbPath);
        _loop = Task.Run(() => PollLoopAsync(_conn, EngineHost.Orchestrator, _cts.Token));
    }

    /// <summary>Cancels the poll loop and waits for it to drain before returning.</summary>
    internal void Stop()
    {
        _cts.Cancel();
        try { _loop?.GetAwaiter().GetResult(); } catch (OperationCanceledException) { }
    }

    // -------------------------------------------------------------------------
    // Schema helpers
    // -------------------------------------------------------------------------

    private static SqliteConnection OpenAndInitialise(string dbPath)
    {
        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = dbPath,
            Mode = SqliteOpenMode.ReadWriteCreate,
        }.ToString();

        var conn = new SqliteConnection(connectionString);
        conn.Open();

        // WAL must be enabled before DDL so readers are never blocked by schema writes.
        Execute(conn, BrokerSchema.PragmaWal);
        Execute(conn, BrokerSchema.CreateRequests);
        Execute(conn, BrokerSchema.CreateResponseTokens);

        return conn;
    }

    private static void Execute(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    // -------------------------------------------------------------------------
    // Poll loop
    // -------------------------------------------------------------------------

    private static async Task PollLoopAsync(
        SqliteConnection conn,
        InferenceOrchestrator orchestrator,
        CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            await ProcessNextPendingAsync(conn, orchestrator, ct).ConfigureAwait(false);

            // Avoid a tight spin when the queue is empty.
            await Task.Delay(PollIntervalMs, ct).ConfigureAwait(false);
        }
    }

    /// <summary>
    /// Selects the oldest pending request, processes it fully, then returns.
    /// Does nothing and returns immediately when the queue is empty.
    /// </summary>
    private static async Task ProcessNextPendingAsync(
        SqliteConnection conn,
        InferenceOrchestrator orchestrator,
        CancellationToken ct)
    {
        // --- 1. Claim the oldest pending request atomically --------------------
        long requestId;
        string prompt;
        int maxTokens;
        float temperature;

        using (var cmd = conn.CreateCommand())
        {
            cmd.CommandText =
                """
                SELECT id, prompt, max_tokens, temperature
                FROM   requests
                WHERE  status = 'pending'
                ORDER  BY id ASC
                LIMIT  1;
                """;

            using var reader = await cmd.ExecuteReaderAsync(ct).ConfigureAwait(false);
            if (!await reader.ReadAsync(ct).ConfigureAwait(false))
            {
                return; // Nothing to do.
            }

            requestId   = reader.GetInt64(0);
            prompt      = reader.GetString(1);
            maxTokens   = reader.GetInt32(2);
            temperature = reader.GetFloat(3);
        }

        MarkStatus(conn, requestId, "running");

        // Trace the claimed request end-to-end. The broker has no client-minted requestId column (the
        // schema is frozen in docs/INTEROP_CONTRACT.md), so the row id — which the client already holds,
        // having inserted the row — is the natural correlation key for this transport's waterfall.
        using var process = EngineTrace.StartSpan("broker.process", requestId.ToString(CultureInfo.InvariantCulture));
        EngineTrace.RecordRequest(EngineTrace.Transports.Sqlite);

        // --- 2. Run inference and stream tokens into response_tokens -----------
        try
        {
            var request = new InferenceRequest(prompt, maxTokens, temperature);

            using var execute = EngineTrace.StartSpan("broker.execute", requestId.ToString(CultureInfo.InvariantCulture));
            await foreach (var token in orchestrator.RunAsync(request, ct).ConfigureAwait(false))
            {
                InsertToken(conn, requestId, token);
            }

            MarkStatus(conn, requestId, "done");
        }
        catch (OperationCanceledException)
        {
            // The loop is shutting down; leave the row in 'running' state so
            // the host can detect a partial result or requeue on next start.
            throw;
        }
        catch
        {
            // Engine fault — mark the row so the host does not poll forever.
            MarkStatus(conn, requestId, "error");
        }
    }

    // -------------------------------------------------------------------------
    // Parameterised DML helpers
    // -------------------------------------------------------------------------

    private static void MarkStatus(SqliteConnection conn, long requestId, string status)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "UPDATE requests SET status = $status WHERE id = $id;";
        cmd.Parameters.AddWithValue("$status", status);
        cmd.Parameters.AddWithValue("$id", requestId);
        cmd.ExecuteNonQuery();
    }

    private static void InsertToken(SqliteConnection conn, long requestId, InferenceToken token)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText =
            """
            INSERT INTO response_tokens (request_id, idx, text, is_final, created_utc)
            VALUES ($request_id, $idx, $text, $is_final, $created_utc);
            """;
        cmd.Parameters.AddWithValue("$request_id", requestId);
        cmd.Parameters.AddWithValue("$idx",         token.Index);
        cmd.Parameters.AddWithValue("$text",        token.Text);
        cmd.Parameters.AddWithValue("$is_final",    token.IsFinal ? 1 : 0);
        cmd.Parameters.AddWithValue("$created_utc", DateTime.UtcNow.ToString("O"));
        cmd.ExecuteNonQuery();
    }

    // -------------------------------------------------------------------------
    // IDisposable
    // -------------------------------------------------------------------------

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _cts.Dispose();
        _conn?.Dispose();
    }
}
