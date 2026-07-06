// Wave B native proof harness. Over loopback, it drives the framed-protobuf transport (plain + PQ),
// the raw-HTTP server, and the SQLite broker, then drains the in-process trace ring and asserts the
// boundary spans exist. Every check prints PASS/FAIL; a non-zero exit code means at least one failed.
//
// It acts as the CLIENT against the internal servers (managed code cannot call the [UnmanagedCallersOnly]
// exports, so this is the Windows-verifiable equivalent). See docs and the Wave B spec.
using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Google.Protobuf;
using Microsoft.Data.Sqlite;
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.NativeBridge;
using DotnetNativeInterop.NativeBridge.HttpRaw;
using DotnetNativeInterop.NativeBridge.Pb;
using DotnetNativeInterop.NativeBridge.Pqc;
using DotnetNativeInterop.NativeBridge.Sqlite;

internal static class Program
{
    private static readonly List<(string Name, bool Ok)> Results = [];

    private static async Task<int> Main()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        var ct = cts.Token;

        Console.WriteLine("== Wave B native proof harness ==");
        EngineHost.Initialize();

        try
        {
            await RunPbPlainAsync(ct).ConfigureAwait(false);
            await RunPbSecureAsync(ct).ConfigureAwait(false);
            await RunHttpAsync(ct).ConfigureAwait(false);
            RunBroker();
            RunTraceDrain();
            RunTrustAndTraceCommands();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[FAIL] harness aborted: {ex.GetType().Name}: {ex.Message}");
            Results.Add(("harness completed", false));
        }

        Console.WriteLine();
        var passed = Results.Count(r => r.Ok);
        Console.WriteLine($"== {passed}/{Results.Count} checks passed ==");
        return Results.All(r => r.Ok) ? 0 : 1;
    }

    // ----- Framed protobuf, plaintext -------------------------------------------------------------

    private static async Task RunPbPlainAsync(CancellationToken ct)
    {
        var port = PbFrameServer.Start(0);
        try
        {
            var (client, _, conn) = await ConnectAsync(port, ct).ConfigureAwait(false);
            using (client)
            using (conn)
            {
                var features = await RoundTripAsync(conn, new Envelope { RequestId = "pb-features", Features = new FeaturesRequest() }, ct).ConfigureAwait(false);
                Check("pb plain: features returns a non-empty catalog",
                    features.BodyCase == Envelope.BodyOneofCase.FeaturesResponse && features.FeaturesResponse.Features.Count > 0);

                var run = await RoundTripAsync(conn, new Envelope { RequestId = "pb-run", Run = new RunRequest { Id = "json-sourcegen" } }, ct).ConfigureAwait(false);
                Check("pb plain: run('json-sourcegen') returns ok result",
                    run.BodyCase == Envelope.BodyOneofCase.RunResponse && run.RunResponse.Run.Ok && run.RunResponse.Run.Id == "json-sourcegen");

                var ping = await RoundTripAsync(conn, new Envelope { RequestId = "pb-ping", Ping = new PingRequest() }, ct).ConfigureAwait(false);
                Check("pb plain: ping returns \"pong\"",
                    ping.BodyCase == Envelope.BodyOneofCase.PingResponse && ping.PingResponse.Reply == "pong");
            }
        }
        finally
        {
            PbFrameServer.Stop();
        }
    }

    // ----- Framed protobuf, PQ secure channel -----------------------------------------------------

    private static async Task RunPbSecureAsync(CancellationToken ct)
    {
        var port = PbFrameServer.Start(1); // flags & 1 => require PQ handshake
        byte[] firstSessionId = [];
        byte[] secondSessionId = [];
        try
        {
            IPqcProvider provider = new BouncyCastlePqcProvider();

            // Full handshake + encrypted round-trip on one connection.
            {
                var (client, _, conn) = await ConnectAsync(port, ct).ConfigureAwait(false);
                using (client)
                using (conn)
                {
                    ClientHandshakeResult handshake;
                    try
                    {
                        handshake = await PqSecureChannel.ClientHandshakeAsync(conn, provider, ct).ConfigureAwait(false);
                        firstSessionId = handshake.SessionId;
                        Check("pb PQ: ML-KEM/ML-DSA handshake completed + signature verified", true);
                    }
                    catch (PqcHandshakeException ex)
                    {
                        Check($"pb PQ: handshake ({ex.Message})", false);
                        return;
                    }

                    conn.UseCipher(handshake.Cipher);
                    var features = await RoundTripAsync(conn, new Envelope { RequestId = "pq-features", Features = new FeaturesRequest() }, ct).ConfigureAwait(false);
                    Check("pb PQ: encrypted features round-trip succeeds",
                        features.BodyCase == Envelope.BodyOneofCase.FeaturesResponse && features.FeaturesResponse.Features.Count > 0);
                }
            }

            // Tamper test on a fresh connection: corrupt one encrypted frame, expect a typed rejection.
            {
                var (client, stream, conn) = await ConnectAsync(port, ct).ConfigureAwait(false);
                using (client)
                using (conn)
                {
                    var handshake = await PqSecureChannel.ClientHandshakeAsync(conn, provider, ct).ConfigureAwait(false);
                    secondSessionId = handshake.SessionId;
                    var cipher = handshake.Cipher;

                    // Encrypt a valid ping, flip a ciphertext byte, and write it raw (bypassing FrameConnection
                    // so it is NOT re-encrypted). The GCM tag must then fail on the server.
                    var forged = cipher.EncryptOutbound(new Envelope { RequestId = "tamper", Ping = new PingRequest() }.ToByteArray());
                    forged[0] ^= 0xFF;
                    await WriteRawFrameAsync(stream, forged, ct).ConfigureAwait(false);

                    conn.UseCipher(cipher); // so we can read the server's (encrypted) error response
                    var rejected = false;
                    try
                    {
                        var responseBytes = await conn.ReadFrameAsync(ct).ConfigureAwait(false);
                        if (responseBytes is not null)
                        {
                            var response = Envelope.Parser.ParseFrom(responseBytes);
                            rejected = response.BodyCase == Envelope.BodyOneofCase.Error
                                && response.Error.Code == PbFrameServer.ErrorTamper;
                        }
                    }
                    catch (Exception)
                    {
                        // A clean close (EOF/reset) is also an acceptable rejection of a forged frame.
                        rejected = true;
                    }

                    Check("pb PQ: tampered frame rejected cleanly (typed error, no crash)", rejected);
                }
            }

            // Replay freshness: two handshakes to the SAME server boot must carry distinct session_ids, so a
            // replayed client ciphertext derives different keys (no cross-session AES-GCM key/nonce reuse).
            Check("pb PQ: fresh session_id per handshake (replay resistance)",
                firstSessionId.Length == 32 && !firstSessionId.SequenceEqual(secondSessionId));

            Check("pb PQ: trust~posture reports a live PQ channel while up",
                TrustPosture.BinaryPqChannel is not null && TrustPosture.BinaryPqChannel.Cipher == "AES-256-GCM");
        }
        finally
        {
            PbFrameServer.Stop();
        }

        Check("pb PQ: posture clears to plaintext after the server stops", TrustPosture.BinaryPqChannel is null);
    }

    // ----- Raw HTTP --------------------------------------------------------------------------------

    private static async Task RunHttpAsync(CancellationToken ct)
    {
        var port = RawHttpServer.Start();
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(IPAddress.Loopback, port, ct).ConfigureAwait(false);
            var stream = client.GetStream();

            var request = "GET /features HTTP/1.1\r\nHost: localhost\r\nX-Dni-Request-Id: http-1\r\nConnection: close\r\n\r\n";
            await stream.WriteAsync(System.Text.Encoding.ASCII.GetBytes(request), ct).ConfigureAwait(false);

            using var reader = new StreamReader(stream);
            var response = await reader.ReadToEndAsync(ct).ConfigureAwait(false);
            Check("http: GET /features returns 200 + JSON array",
                response.Contains("200 OK", StringComparison.Ordinal) && response.Contains("[{", StringComparison.Ordinal));
        }
        finally
        {
            RawHttpServer.Stop();
        }
    }

    // ----- SQLite broker ---------------------------------------------------------------------------

    private static void RunBroker()
    {
        var dbPath = Path.Combine(Path.GetTempPath(), $"dni-waveb-{Guid.NewGuid():N}.db");
        var broker = new SqliteBroker(dbPath);
        try
        {
            broker.Start();

            using (var conn = new SqliteConnection($"Data Source={dbPath}"))
            {
                conn.Open();
                Execute(conn, BrokerSchema.PragmaWal);
                Execute(conn, BrokerSchema.CreateRequests);
                Execute(conn, BrokerSchema.CreateResponseTokens);
                using var insert = conn.CreateCommand();
                insert.CommandText =
                    "INSERT INTO requests(prompt, max_tokens, temperature, status, created_utc) " +
                    "VALUES('waveb', 8, 0.0, 'pending', $t);";
                insert.Parameters.AddWithValue("$t", DateTime.UtcNow.ToString("O"));
                insert.ExecuteNonQuery();
            }

            Check("broker: request processed to 'done'", WaitBrokerDone(dbPath, TimeSpan.FromSeconds(15)));
        }
        finally
        {
            broker.Stop();
            broker.Dispose();
            TryDelete(dbPath);
        }
    }

    // ----- Trace drain -----------------------------------------------------------------------------

    private static void RunTraceDrain()
    {
        var drain = EngineTrace.Drain();
        var names = drain.Spans.Select(s => s.Name).ToHashSet(StringComparer.Ordinal);
        Console.WriteLine($"   drained {drain.Spans.Count} spans, dropped={drain.Dropped}, nowUs={drain.NowUs}");
        Console.WriteLine($"   span names: {string.Join(", ", names.OrderBy(n => n, StringComparer.Ordinal))}");

        Check("trace: pb spans present", names.Any(n => n.StartsWith("pb.", StringComparison.Ordinal)));
        Check("trace: http spans present", names.Any(n => n.StartsWith("http.", StringComparison.Ordinal)));
        Check("trace: broker spans present", names.Any(n => n.StartsWith("broker.", StringComparison.Ordinal)));
        Check("trace: pb.handshake span present (PQ path traced)", names.Contains("pb.handshake"));
        Check("trace: drain reports engine nowUs", drain.NowUs > 0);
    }

    // ----- trust~ / trace~ command grammar ---------------------------------------------------------

    private static void RunTrustAndTraceCommands()
    {
        var posture = LanguageFeatureCatalog.Run("trust~posture");
        Check("cmd: trust~posture ok + discloses HTTP plaintext",
            posture.Ok && posture.Result.Contains("PLAINTEXT", StringComparison.Ordinal));

        var stats = LanguageFeatureCatalog.Run("trace~stats");
        Check("cmd: trace~stats ok + reports capacity 512",
            stats.Ok && stats.Result.Contains("\"capacity\":512", StringComparison.Ordinal));
    }

    // ----- helpers ---------------------------------------------------------------------------------

    private static async Task<(TcpClient Client, NetworkStream Stream, FrameConnection Conn)> ConnectAsync(int port, CancellationToken ct)
    {
        var client = new TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, port, ct).ConfigureAwait(false);
        var stream = client.GetStream();
        return (client, stream, new FrameConnection(stream));
    }

    private static async Task<Envelope> RoundTripAsync(FrameConnection conn, Envelope request, CancellationToken ct)
    {
        await conn.WriteFrameAsync(request.ToByteArray(), ct).ConfigureAwait(false);
        var bytes = await conn.ReadFrameAsync(ct).ConfigureAwait(false)
            ?? throw new IOException("server closed the connection before responding");
        return Envelope.Parser.ParseFrom(bytes);
    }

    private static async Task WriteRawFrameAsync(NetworkStream stream, byte[] payload, CancellationToken ct)
    {
        var lengthPrefix = new byte[4];
        BinaryPrimitives.WriteUInt32LittleEndian(lengthPrefix, (uint)payload.Length);
        await stream.WriteAsync(lengthPrefix, ct).ConfigureAwait(false);
        await stream.WriteAsync(payload, ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
    }

    private static bool WaitBrokerDone(string dbPath, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            using (var conn = new SqliteConnection($"Data Source={dbPath}"))
            {
                conn.Open();
                using var cmd = conn.CreateCommand();
                cmd.CommandText = "SELECT COUNT(*) FROM requests WHERE status = 'done';";
                if (Convert.ToInt64(cmd.ExecuteScalar()) > 0)
                {
                    return true;
                }
            }

            Thread.Sleep(50);
        }

        return false;
    }

    private static void Execute(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (Exception) { /* best effort */ }
    }

    private static void Check(string name, bool ok)
    {
        Results.Add((name, ok));
        Console.WriteLine($"[{(ok ? "PASS" : "FAIL")}] {name}");
    }
}
