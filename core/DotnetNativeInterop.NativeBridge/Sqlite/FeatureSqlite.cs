using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Sqlite;

/// <summary>
/// SQLite feature transport (Pattern 4) backed by SQLCipher. The catalog and per-feature results are
/// round-tripped through an on-disk, key-encrypted (PRAGMA key) SQLCipher database, then returned as
/// JSON — so the data genuinely crosses through encrypted-at-rest storage, while the native side owns
/// the key (the Swift client can't read SQLCipher, so it just decodes the JSON). All access is
/// serialized through one cached connection.
/// </summary>
internal static class FeatureSqliteExports
{
    private const string Key = "dni-showcase-key";
    private static readonly object Gate = new();
    private static SqliteConnection? _conn;

    /// <summary>Reads the feature catalog from the encrypted db (seeding it on first use). 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_sqlite_features")]
    public static nint Features()
    {
        try
        {
            lock (Gate)
            {
                var conn = EnsureConnection();
                var descriptors = ReadDescriptors(conn);
                var json = JsonSerializer.Serialize(
                    descriptors, typeof(IReadOnlyList<FeatureDescriptor>), FeaturesJsonContext.Default);
                return NativeText.Allocate(json);
            }
        }
        catch (Exception)
        {
            return 0;
        }
    }

    /// <summary>Runs one feature, persists it to the encrypted db, reads it back, returns JSON. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_sqlite_run")]
    public static unsafe nint Run(byte* id)
    {
        try
        {
            var featureId = NativeText.Read((nint)id);
            lock (Gate)
            {
                var conn = EnsureConnection();
                var run = LanguageFeatureCatalog.Run(featureId);
                WriteRun(conn, run);
                var stored = ReadRun(conn, featureId) ?? run;
                var json = JsonSerializer.Serialize(stored, typeof(FeatureRun), FeaturesJsonContext.Default);
                return NativeText.Allocate(json);
            }
        }
        catch (Exception)
        {
            return 0;
        }
    }

    // Must be called holding Gate.
    private static SqliteConnection EnsureConnection()
    {
        if (_conn is not null)
        {
            return _conn;
        }

        // Microsoft.Data.Sqlite.Core ships no provider; register the statically-linked
        // e_sqlcipher bundle explicitly. Idempotent and AOT-safe (no reflection).
        SQLitePCL.Batteries_V2.Init();

        var path = Path.Combine(Path.GetTempPath(), "dni-features.db");
        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Password = Key,   // => PRAGMA key: SQLCipher encrypts the file at rest
            Pooling = false,
        }.ToString();

        var conn = new SqliteConnection(connectionString);
        conn.Open();
        Execute(conn, CreateFeatures);
        Execute(conn, CreateFeatureRuns);
        Seed(conn);
        _conn = conn;
        return conn;
    }

    private static void Seed(SqliteConnection conn)
    {
        using var tx = conn.BeginTransaction();
        foreach (var descriptor in LanguageFeatureCatalog.Descriptors)
        {
            using var cmd = conn.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText =
                """
                INSERT INTO features (id, title, version, code, expected)
                VALUES ($id, $title, $version, $code, $expected)
                ON CONFLICT(id) DO UPDATE SET
                  title = $title, version = $version, code = $code, expected = $expected;
                """;
            cmd.Parameters.AddWithValue("$id", descriptor.Id);
            cmd.Parameters.AddWithValue("$title", descriptor.Title);
            cmd.Parameters.AddWithValue("$version", descriptor.Version);
            cmd.Parameters.AddWithValue("$code", descriptor.Code);
            cmd.Parameters.AddWithValue("$expected", descriptor.Expected);
            cmd.ExecuteNonQuery();
        }

        tx.Commit();
    }

    private static List<FeatureDescriptor> ReadDescriptors(SqliteConnection conn)
    {
        var list = new List<FeatureDescriptor>();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT id, title, version, code, expected FROM features";
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            list.Add(new FeatureDescriptor(
                reader.GetString(0), reader.GetString(1), reader.GetString(2),
                reader.GetString(3), reader.GetString(4)));
        }

        return list;
    }

    private static void WriteRun(SqliteConnection conn, FeatureRun run)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText =
            """
            INSERT INTO feature_runs (id, result, elapsed_ms, ok, created_utc)
            VALUES ($id, $result, $elapsed, $ok, $ts)
            ON CONFLICT(id) DO UPDATE SET
              result = $result, elapsed_ms = $elapsed, ok = $ok, created_utc = $ts;
            """;
        cmd.Parameters.AddWithValue("$id", run.Id);
        cmd.Parameters.AddWithValue("$result", run.Result);
        cmd.Parameters.AddWithValue("$elapsed", run.ElapsedMs);
        cmd.Parameters.AddWithValue("$ok", run.Ok ? 1 : 0);
        cmd.Parameters.AddWithValue("$ts", DateTime.UtcNow.ToString("O"));
        cmd.ExecuteNonQuery();
    }

    private static FeatureRun? ReadRun(SqliteConnection conn, string id)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT id, result, elapsed_ms, ok FROM feature_runs WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        using var reader = cmd.ExecuteReader();
        if (reader.Read())
        {
            return new FeatureRun(
                reader.GetString(0), reader.GetString(1), reader.GetDouble(2), reader.GetInt64(3) != 0);
        }

        return null;
    }

    private static void Execute(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    private const string CreateFeatures =
        """
        CREATE TABLE IF NOT EXISTS features (
          id        TEXT PRIMARY KEY,
          title     TEXT NOT NULL,
          version   TEXT NOT NULL,
          code      TEXT NOT NULL,
          expected  TEXT NOT NULL
        );
        """;

    private const string CreateFeatureRuns =
        """
        CREATE TABLE IF NOT EXISTS feature_runs (
          id          TEXT PRIMARY KEY,
          result      TEXT NOT NULL,
          elapsed_ms  REAL NOT NULL,
          ok          INTEGER NOT NULL,
          created_utc TEXT NOT NULL
        );
        """;
}
