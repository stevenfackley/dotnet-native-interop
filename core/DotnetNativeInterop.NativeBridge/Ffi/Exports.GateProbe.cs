using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Tasks;
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.Engine.Llama;
using Microsoft.Data.Sqlite;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// SP0 gate-only native probes. NOT part of the frozen app ABI (declared in abi/dni_gate_probe.h, which
/// only the Android JNI shim includes). They verify that dynamically-/hand-linked native dependencies
/// actually load and function inside the NativeAOT image on a real device, isolated from the app's data
/// paths (which need Android asset extraction + a writable temp dir, deferred to SP1/SP2).
/// </summary>
internal static class ExportsGateProbe
{
    /// <summary>
    /// Opens a SQLCipher database at <paramref name="path"/>, confirms the cipher is live (PRAGMA
    /// cipher_version is empty when plain e_sqlite3 is linked instead of e_sqlcipher), and round-trips a
    /// row. Returns JSON {"ok":bool,"cipher":str,"roundtrip":str} or {"ok":false,"error":str}; 0 only if
    /// the result can't be allocated. Takes a caller-supplied path because Path.GetTempPath() is not
    /// reliably writable on Android.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_sqlite_probe")]
    public static unsafe nint SqliteProbe(byte* path)
    {
        ProbeResult result;
        try
        {
            result = RunSqliteProbe(NativeText.Read((nint)path));
        }
        catch (Exception ex)
        {
            result = new ProbeResult(false, Error: ex.Message);
        }

        return NativeText.Allocate(
            JsonSerializer.Serialize(result, GateProbeJsonContext.Default.ProbeResult));
    }

    private static ProbeResult RunSqliteProbe(string dbPath)
    {
        if (string.IsNullOrEmpty(dbPath))
        {
            return new ProbeResult(false, Error: "empty path");
        }

        try
        {
            if (File.Exists(dbPath))
            {
                File.Delete(dbPath);
            }
        }
        catch
        {
            // Best effort — a stale file just means the probe reuses it.
        }

        // Microsoft.Data.Sqlite.Core ships no provider; register e_sqlcipher explicitly (AOT-safe).
        SQLitePCL.Batteries_V2.Init();

        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = dbPath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Password = "dni-gate-key",   // PRAGMA key => SQLCipher encrypts the file at rest
            Pooling = false,
        }.ToString();

        using var conn = new SqliteConnection(connectionString);
        conn.Open();

        // The decisive signal: cipher_version is non-empty only when e_sqlcipher (not e_sqlite3) is the
        // statically/dynamically linked provider actually serving this connection.
        var cipher = Scalar(conn, "PRAGMA cipher_version;");
        if (string.IsNullOrEmpty(cipher))
        {
            return new ProbeResult(false, Error: "cipher_version empty — e_sqlcipher not linked");
        }

        const string token = "sqlcipher-roundtrip";
        Execute(conn, "CREATE TABLE IF NOT EXISTS probe (k TEXT PRIMARY KEY, v TEXT NOT NULL);");
        using (var insert = conn.CreateCommand())
        {
            insert.CommandText = "INSERT OR REPLACE INTO probe (k, v) VALUES ('k', $v);";
            insert.Parameters.AddWithValue("$v", token);
            insert.ExecuteNonQuery();
        }

        var readBack = Scalar(conn, "SELECT v FROM probe WHERE k = 'k';");
        return readBack == token
            ? new ProbeResult(true, Cipher: cipher, Roundtrip: readBack)
            : new ProbeResult(false, Error: $"roundtrip mismatch: '{readBack}'");
    }

    /// <summary>
    /// Loads a GGUF model at <paramref name="path"/> via the real <see cref="LlamaLanguageModel"/>
    /// (proving the C# → DirectPInvoke → static llama/ggml chain links inside the NativeAOT image),
    /// generates a few tokens on CPU, and frees it. Returns JSON {"ok":bool,"tokens":int,"sample":str}
    /// or {"ok":false,"error":str}; 0 only if the result can't be allocated.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_llama_probe")]
    public static unsafe nint LlamaProbe(byte* path)
    {
        ProbeResult result;
        try
        {
            result = RunLlamaProbe(NativeText.Read((nint)path));
        }
        catch (Exception ex)
        {
            result = new ProbeResult(false, Error: ex.Message);
        }

        return NativeText.Allocate(
            JsonSerializer.Serialize(result, GateProbeJsonContext.Default.ProbeResult));
    }

    private static ProbeResult RunLlamaProbe(string ggufPath)
    {
        if (string.IsNullOrEmpty(ggufPath))
        {
            return new ProbeResult(false, Error: "empty path");
        }

        if (!File.Exists(ggufPath))
        {
            return new ProbeResult(false, Error: $"gguf not found: {ggufPath}");
        }

        using var model = new LlamaLanguageModel(ggufPath);   // throws if llama.cpp fails to load
        var sample = new StringBuilder();
        var pieces = 0;
        var request = new InferenceRequest("The capital of France is", MaxTokens: 8, Temperature: 0.2f);

        // Drain the async stream synchronously — the probe is a single blocking native call.
        Task.Run(async () =>
        {
            await foreach (var piece in model.GenerateAsync(request).ConfigureAwait(false))
            {
                sample.Append(piece);
                if (++pieces >= 8)
                {
                    break;
                }
            }
        }).GetAwaiter().GetResult();

        return pieces > 0
            ? new ProbeResult(true, Tokens: pieces, Sample: sample.ToString())
            : new ProbeResult(false, Error: "model loaded but generated 0 tokens");
    }

    private static string Scalar(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        return cmd.ExecuteScalar() as string ?? string.Empty;
    }

    private static void Execute(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }
}

internal sealed record ProbeResult(
    bool Ok,
    string? Cipher = null,
    string? Roundtrip = null,
    string? Error = null,
    int? Tokens = null,
    string? Sample = null);

[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(ProbeResult))]
internal sealed partial class GateProbeJsonContext : JsonSerializerContext
{
}
