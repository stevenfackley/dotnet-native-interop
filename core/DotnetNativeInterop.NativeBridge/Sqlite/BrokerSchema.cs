namespace DotnetNativeInterop.NativeBridge.Sqlite;

/// <summary>
/// DDL and pragma strings for the SQLite WAL broker database.
/// Exact schema is frozen in docs/INTEROP_CONTRACT.md — do not diverge.
/// </summary>
internal static class BrokerSchema
{
    /// <summary>
    /// Switch the journal to WAL so the native UI reader and the .NET writer
    /// do not block each other (WAL allows one writer + N concurrent readers).
    /// </summary>
    internal const string PragmaWal = "PRAGMA journal_mode=WAL;";

    /// <summary>
    /// Inbound request queue.  Status lifecycle: pending → running → done | error | canceled.
    /// </summary>
    internal const string CreateRequests =
        """
        CREATE TABLE IF NOT EXISTS requests (
          id           INTEGER PRIMARY KEY AUTOINCREMENT,
          prompt       TEXT    NOT NULL,
          max_tokens   INTEGER NOT NULL DEFAULT 256,
          temperature  REAL    NOT NULL DEFAULT 0.8,
          status       TEXT    NOT NULL DEFAULT 'pending',
          created_utc  TEXT    NOT NULL
        );
        """;

    /// <summary>
    /// Per-token output rows.  Primary key (request_id, idx) lets the native host
    /// poll with "WHERE request_id=? AND idx > ?" without a full-table scan.
    /// </summary>
    internal const string CreateResponseTokens =
        """
        CREATE TABLE IF NOT EXISTS response_tokens (
          request_id  INTEGER NOT NULL,
          idx         INTEGER NOT NULL,
          text        TEXT    NOT NULL,
          is_final    INTEGER NOT NULL DEFAULT 0,
          created_utc TEXT    NOT NULL,
          PRIMARY KEY (request_id, idx)
        );
        """;
}
