// Wave B native proof harness. Over loopback, it drives the framed-protobuf transport (plain + PQ),
// the raw-HTTP server, the SQLite broker, and a Foreman agent turn (via ForemanHost.Agent directly —
// the internal path, since this harness cannot call the FFI exports either), then drains the in-process
// trace ring AND takes a metrics~snapshot, asserting both the boundary spans and the Dni.Engine meter's
// counters agree with the operations just driven. Every check prints PASS/FAIL; a non-zero exit code
// means at least one failed.
//
// It acts as the CLIENT against the internal servers (managed code cannot call the [UnmanagedCallersOnly]
// exports, so this is the Windows-verifiable equivalent). See docs and the Wave B spec.
using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using Google.Protobuf;
using Microsoft.Data.Sqlite;
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.Engine.Ai.Agent;
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
            await RunAgentTurnAsync(ct).ConfigureAwait(false);
            RunTraceDrain();
            RunTrustAndTraceCommands();
            RunMetricsSnapshot();
            // Last: it starts/stops its own pb servers and drives only FAILING requests (no successful
            // dispatch), so it stays clear of the calibrated trace/metrics assertions above.
            await RunPbErrorHandlingAsync(ct).ConfigureAwait(false);
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

    // ----- pb server error handling & idempotency (regression pins for the server-resilience fixes) ---

    private static async Task RunPbErrorHandlingAsync(CancellationToken ct)
    {
        // (a) The singleton server's PQ posture is fixed for its lifetime. A mismatched-flag second Start
        // must be REJECTED (not silently ignored — that would desync every client into a handshake hang).
        var port = PbFrameServer.Start(0);
        try
        {
            Check("pb start: mismatched-flag restart rejected (Start(1) while plaintext-up => InvalidArgument)",
                PbFrameServer.Start(1) == NativeStatus.InvalidArgument);
            Check("pb start: matching-flag restart returns the live port",
                PbFrameServer.Start(0) == port);

            // (b) A frame that passes length-framing but is not a valid Envelope must get a typed
            // ErrorInternal, not a silent close (0x0F = protobuf field 1 with invalid wire type 7).
            var (client, stream, conn) = await ConnectAsync(port, ct).ConfigureAwait(false);
            using (client)
            using (conn)
            {
                await WriteRawFrameAsync(stream, [0x0F], ct).ConfigureAwait(false);
                var bytes = await conn.ReadFrameAsync(ct).ConfigureAwait(false);
                var ok = bytes is not null
                    && Envelope.Parser.ParseFrom(bytes) is { BodyCase: Envelope.BodyOneofCase.Error } e
                    && e.Error.Code == PbFrameServer.ErrorInternal;
                Check("pb error: malformed frame gets a typed ErrorInternal (not a silent close)", ok);
            }
        }
        finally
        {
            PbFrameServer.Stop();
        }

        // (c) A malformed PQ handshake reply (a wrong-length KEM ciphertext throws a BouncyCastle
        // exception, NOT PqcHandshakeException) must still get a typed ErrorHandshakeFailed, not a silent
        // close (the case the previous PqcHandshakeException-only catch let escape).
        var pqPort = PbFrameServer.Start(1);
        try
        {
            var (client, stream, conn) = await ConnectAsync(pqPort, ct).ConfigureAwait(false);
            using (client)
            using (conn)
            {
                _ = await conn.ReadFrameAsync(ct).ConfigureAwait(false); // consume the server's HandshakeOffer
                var badReply = new Envelope { HandshakeReply = new HandshakeReply { Ciphertext = ByteString.CopyFrom(new byte[10]) } };
                await WriteRawFrameAsync(stream, badReply.ToByteArray(), ct).ConfigureAwait(false);
                var bytes = await conn.ReadFrameAsync(ct).ConfigureAwait(false);
                var ok = bytes is not null
                    && Envelope.Parser.ParseFrom(bytes) is { BodyCase: Envelope.BodyOneofCase.Error } e
                    && e.Error.Code == PbFrameServer.ErrorHandshakeFailed;
                Check("pb error: malformed handshake reply gets a typed ErrorHandshakeFailed (not a silent close)", ok);
            }
        }
        finally
        {
            PbFrameServer.Stop();
        }
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

        // Hardening: a robust IDisposable must be safe regardless of call order. Start a fresh broker and
        // Dispose it WITHOUT a preceding Stop() — the defensive Dispose stops the live poll loop before
        // disposing the connection it uses, so no ObjectDisposedException escapes.
        var noStopDb = Path.Combine(Path.GetTempPath(), $"dni-waveb-nostop-{Guid.NewGuid():N}.db");
        var noStop = new SqliteBroker(noStopDb);
        noStop.Start();
        var disposedCleanly = true;
        try { noStop.Dispose(); }
        catch (Exception) { disposedCleanly = false; }
        Check("broker: Dispose() without a preceding Stop() is safe (loop stopped before the connection is disposed)",
            disposedCleanly);
        TryDelete(noStopDb);
    }

    // ----- Foreman agent turn (internal path: ForemanHost.Agent directly, no FFI session wrapper) ---

    private static async Task RunAgentTurnAsync(CancellationToken ct)
    {
        // The exact calibrated query from ForemanHarness Task 15: with the real MiniLM encoder and no
        // GGUF bundled on this dev box, ForemanHost's RouterBrain routes it to search_manuals in exactly
        // one tool step, then answers grounded prose. Reused here (not re-derived) so this assertion
        // rests on an already-proven real routing result, not a fresh unverified guess.
        const string query = "the compressor is overheating and keeps tripping the rooftop unit, what should I check?";
        var answer = new System.Text.StringBuilder();
        var result = await ForemanHost.Agent.RunTurnAsync(query, s => answer.Append(s), ct).ConfigureAwait(false);
        Check("agent: turn answered", result.StopReason == ForemanStopReason.Answered);
        Check("agent: exactly one tool step (search_manuals)", result.ToolSteps == 1);
        Check("agent: produced a grounded answer", answer.Length > 0);
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

    // ----- metrics~ command grammar ------------------------------------------------------------------
    //
    // Asserts the Dni.Engine meter aggregator's counters against the EXACT operations this harness just
    // drove: 3 pb-plain dispatches (features/run/ping) + 1 pb-secure dispatch (features) = 4 pb requests;
    // 1 http request; 1 sqlite (broker) request; 0 ffi requests (this harness cannot call the
    // [UnmanagedCallersOnly] exports — see the file banner); 1 agent turn with exactly 1 tool call
    // (search_manuals, per RunAgentTurnAsync's calibrated query). Real assertions against known totals,
    // not tautologies (e.g. "count >= 0").
    private static void RunMetricsSnapshot()
    {
        var statsRun = LanguageFeatureCatalog.Run("trace~stats");
        var metricsRun = LanguageFeatureCatalog.Run("metrics~snapshot");
        Check("cmd: metrics~snapshot ok", metricsRun.Ok);

        using var statsDoc = JsonDocument.Parse(statsRun.Result);
        using var metricsDoc = JsonDocument.Parse(metricsRun.Result);
        var stats = statsDoc.RootElement;
        var metrics = metricsDoc.RootElement;

        var recordedTotal = stats.GetProperty("recordedTotal").GetInt64();
        var droppedTotal = stats.GetProperty("droppedTotal").GetInt64();
        var spansRecorded = metrics.GetProperty("spansRecorded").GetInt64();
        var spansDropped = metrics.GetProperty("spansDropped").GetInt64();
        Console.WriteLine(
            $"   metrics: spansRecorded={spansRecorded} spansDropped={spansDropped}" +
            $" (trace~stats: recordedTotal={recordedTotal} droppedTotal={droppedTotal})");
        Check("metrics: spansRecorded agrees with trace~stats.recordedTotal (metrics corroborate the trace ring)",
            spansRecorded == recordedTotal && spansRecorded > 0);
        Check("metrics: spansDropped agrees with trace~stats.droppedTotal (0 for this small run)",
            spansDropped == droppedTotal && droppedTotal == 0);

        var requests = metrics.GetProperty("requests");
        var ffiCount = requests.GetProperty("ffi").GetInt64();
        var httpCount = requests.GetProperty("http").GetInt64();
        var sqliteCount = requests.GetProperty("sqlite").GetInt64();
        var pbCount = requests.GetProperty("pb").GetInt64();
        Console.WriteLine($"   metrics: requests ffi={ffiCount} http={httpCount} sqlite={sqliteCount} pb={pbCount}");
        Check("metrics: pb requests == 4 (3 plain + 1 secure)", pbCount == 4);
        Check("metrics: http requests == 1", httpCount == 1);
        Check("metrics: sqlite requests == 1", sqliteCount == 1);
        Check("metrics: ffi requests == 0 (harness cannot call UnmanagedCallersOnly exports)", ffiCount == 0);

        Check("metrics: agentTurns == 1", metrics.GetProperty("agentTurns").GetInt64() == 1);

        var toolCallCounts = metrics.GetProperty("agentToolCalls").EnumerateArray()
            .ToDictionary(e => e.GetProperty("tool").GetString()!, e => e.GetProperty("count").GetInt64());
        Check("metrics: agentToolCalls[search_manuals] == 1",
            toolCallCounts.TryGetValue("search_manuals", out var searchManualsCalls) && searchManualsCalls == 1);

        var opNames = metrics.GetProperty("operationDurations").EnumerateArray()
            .Select(e => e.GetProperty("op").GetString())
            .ToHashSet(StringComparer.Ordinal);
        Console.WriteLine($"   metrics: operationDurations ops: {string.Join(", ", opNames.OrderBy(n => n, StringComparer.Ordinal))}");
        Check("metrics: operationDurations includes pb.execute", opNames.Contains("pb.execute"));
        Check("metrics: operationDurations includes agent.turn", opNames.Contains("agent.turn"));
        Check("metrics: operationDurations includes agent.tool.search_manuals", opNames.Contains("agent.tool.search_manuals"));
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
