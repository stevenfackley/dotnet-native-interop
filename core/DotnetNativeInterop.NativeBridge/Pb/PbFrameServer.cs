using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Google.Protobuf;
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.NativeBridge.Pqc;

namespace DotnetNativeInterop.NativeBridge.Pb;

/// <summary>
/// The 4th interop transport: framed <see cref="Google.Protobuf"/> messages over a loopback socket —
/// structured binary RPC with no ASP.NET/gRPC runtime. Follows the same <see cref="System.Net.Sockets"/>
/// accept-loop shape as the raw-HTTP server (a house precedent copied deliberately, not a shared
/// abstraction — no third socket transport exists yet, so rule-of-three says do not generalize).
///
/// The <see cref="Envelope"/> oneof mirrors the HTTP endpoints (features / run / rag / ping / bench).
/// Wire framing lives in <see cref="FrameConnection"/>. When started with the require-PQ flag the server
/// runs the ML-KEM/ML-DSA handshake and encrypts every subsequent frame (see the secure-channel slice).
/// </summary>
internal static class PbFrameServer
{
    /// <summary>ErrorFrame codes surfaced to the client as a typed payload (never a silent close).</summary>
    internal const int ErrorUnknownRequest = 1;
    internal const int ErrorHandshakeFailed = 2;
    internal const int ErrorTamper = 3;
    internal const int ErrorInternal = 4;

    private static readonly object Gate = new();
    private static TcpListener? _listener;
    private static CancellationTokenSource? _cts;

    // Whether this server instance requires the post-quantum handshake before serving requests, plus the
    // per-boot PQ provider + server identity (KEM + DSA keypairs), created once when PQ is required.
    private static bool _requirePq;

    // Deliberately typed as the interface, not the concrete BouncyCastle type: this IS the provider seam
    // the OS MLKem/MLDsa backend is meant to drop into. CA1859 (prefer the concrete type) is the wrong
    // call here — the indirection is the feature.
#pragma warning disable CA1859
    private static IPqcProvider? _pqcProvider;
#pragma warning restore CA1859
    private static IPqcServerIdentity? _pqcIdentity;

    /// <summary>
    /// Starts the loopback framed-protobuf server. Idempotent: a second call returns the live port.
    /// <paramref name="flags"/> bit 0 (<c>flags &amp; 1</c>) requires the PQ handshake on every connection.
    /// </summary>
    /// <returns>The bound 127.0.0.1 port.</returns>
    internal static int Start(int flags)
    {
        lock (Gate)
        {
            if (_listener is not null)
            {
                // The PQ posture is fixed for the singleton server's lifetime. A second Start with a
                // DIFFERENT flag can't flip it silently — that would desync every client (a PQ client would
                // hand-shake a plaintext server, or vice-versa, and both would block on read). Reject so the
                // caller must Stop() first (which the mobile clients already do on a plaintext<->PQ switch).
                if (((flags & 1) != 0) != _requirePq)
                {
                    return NativeStatus.InvalidArgument;
                }

                return ((IPEndPoint)_listener.LocalEndpoint).Port;
            }

            EngineHost.Initialize();
            _requirePq = (flags & 1) != 0;

            if (_requirePq)
            {
                // Per-boot ML-KEM + ML-DSA identity (the KEM keypair is reused across connections this boot;
                // an ephemeral-per-connection KEM key would be stronger but the spec fixes it per boot).
                _pqcProvider = new BouncyCastlePqcProvider();
                _pqcIdentity = _pqcProvider.CreateServerIdentity();
            }

            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;

            var cts = new CancellationTokenSource();
            _ = AcceptLoopAsync(listener, cts.Token);

            _listener = listener;
            _cts = cts;
            return port;
        }
    }

    /// <summary>Stops the server and tears down the accept loop. Idempotent.</summary>
    internal static void Stop()
    {
        lock (Gate)
        {
            _cts?.Cancel();
            _listener?.Stop();
            _cts?.Dispose();
            _listener = null;
            _cts = null;
            _pqcProvider = null;
            _pqcIdentity = null;

            // The secure channel is gone with the server — the Trust inspector must stop reporting live params.
            TrustPosture.SetBinaryPqChannel(null);
        }
    }

    private static async Task AcceptLoopAsync(TcpListener listener, CancellationToken cancellationToken)
    {
        // The try/catch is INSIDE the loop: a transient accept error (e.g. a client that RST-closes in the
        // backlog surfaces as a SocketException from AcceptTcpClientAsync) must NOT kill the accept loop for
        // good — otherwise the whole transport silently dies while Start() keeps handing back the cached
        // port as if healthy. Only a real Stop() (cancellation / disposed listener) ends the loop.
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break; // Stop() cancelled the token.
            }
            catch (ObjectDisposedException)
            {
                break; // Stop() disposed the listener.
            }
            catch (SocketException)
            {
                continue; // Transient accept failure — keep serving.
            }

            _ = HandleClientAsync(client, cancellationToken);
        }
    }

    private static async Task HandleClientAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            using (client)
            {
                var stream = client.GetStream();
                using var connection = new FrameConnection(stream); // disposes the AEAD cipher on every exit path

                // Opt-in PQ handshake over the still-plaintext connection; on success every later frame is
                // AES-256-GCM. A handshake failure is surfaced as a typed ErrorFrame, never a silent close.
                if (_requirePq && !await TryHandshakeAsync(connection, cancellationToken).ConfigureAwait(false))
                {
                    return;
                }

                while (!cancellationToken.IsCancellationRequested)
                {
                    byte[]? frame;
                    try
                    {
                        frame = await connection.ReadFrameAsync(cancellationToken).ConfigureAwait(false);
                    }
                    catch (AuthenticationTagMismatchException)
                    {
                        // A tampered/forged frame on the encrypted channel. Reject with a typed error (sent
                        // on the intact server→client cipher) and tear the connection down — post-AEAD-failure
                        // nothing on this socket can be trusted again.
                        await TrySendErrorAsync(connection, ErrorTamper, "frame authentication failed", cancellationToken)
                            .ConfigureAwait(false);
                        break;
                    }

                    if (frame is null)
                    {
                        break; // clean EOF
                    }

                    Envelope request;
                    try
                    {
                        using (EngineTrace.StartSpan("pb.decode"))
                        {
                            request = Envelope.Parser.ParseFrom(frame);
                        }
                    }
                    catch (InvalidProtocolBufferException)
                    {
                        // A frame that passed length-framing but isn't a valid Envelope. Surface the typed
                        // ErrorInternal (previously dead code) instead of the outer silent close, honoring the
                        // "never a silent close" contract, then tear down (the stream position is now unknown).
                        await TrySendErrorAsync(connection, ErrorInternal, "malformed request frame", cancellationToken)
                            .ConfigureAwait(false);
                        break;
                    }

                    await DispatchAsync(connection, request, cancellationToken).ConfigureAwait(false);
                }
            }
        }
        catch (Exception)
        {
            // Client disconnected, cancelled, or sent a malformed frame — the connection is done.
        }
    }

    // Runs the server handshake and, on success, switches the connection to the encrypted cipher and
    // publishes the negotiated params for the Trust inspector. Returns false (after sending a typed error)
    // when the handshake fails, so the caller drops the connection.
    private static async Task<bool> TryHandshakeAsync(FrameConnection connection, CancellationToken cancellationToken)
    {
        var provider = _pqcProvider;
        var identity = _pqcIdentity;
        if (provider is null || identity is null)
        {
            await TrySendErrorAsync(connection, ErrorHandshakeFailed, "server PQ identity unavailable", cancellationToken)
                .ConfigureAwait(false);
            return false;
        }

        try
        {
            var stopwatch = Stopwatch.StartNew();
            ServerHandshakeResult result;
            using (EngineTrace.StartSpan("pb.handshake"))
            {
                result = await PqSecureChannel.ServerHandshakeAsync(connection, provider, identity, cancellationToken)
                    .ConfigureAwait(false);
            }

            stopwatch.Stop();
            connection.UseCipher(result.Cipher);
            // Publish the live-channel posture under Gate, and only if this connection's server hasn't been
            // stopped mid-handshake. Otherwise a handshake finishing just after Stop() (which nulls the
            // posture under Gate) would resurrect a "secure channel up" readout for a server that is already
            // down — the Trust inspector must stay honest. Stop() cancels this connection's token.
            lock (Gate)
            {
                if (!cancellationToken.IsCancellationRequested)
                {
                    TrustPosture.SetBinaryPqChannel(new PqChannelParams(
                        provider.KemAlgorithm, provider.SigAlgorithm, AeadFrameCipher.Algorithm,
                        result.KemPublicKeyBytes, result.CiphertextBytes, result.SharedSecretBytes,
                        Math.Round(stopwatch.Elapsed.TotalMicroseconds, 1)));
                }
            }
            return true;
        }
        catch (PqcHandshakeException ex)
        {
            // Echoing ex.Message is a demo-only affordance (this is a loopback teaching artifact) — it is a
            // mild error oracle. A production server would return a single uniform "handshake failed" reason.
            await TrySendErrorAsync(connection, ErrorHandshakeFailed, ex.Message, cancellationToken).ConfigureAwait(false);
            return false;
        }
        catch (Exception)
        {
            // A malformed handshake reply (wrong-length KEM ciphertext, non-protobuf bytes) throws a
            // BouncyCastle/protobuf exception, NOT PqcHandshakeException — which would otherwise escape to
            // HandleClientAsync's catch and close the socket SILENTLY, breaking the "a handshake failure is a
            // typed ErrorFrame, never a silent close" contract. Still fail-closed; send a UNIFORM reason (no
            // ex.Message — don't turn an unexpected exception into an error oracle).
            await TrySendErrorAsync(connection, ErrorHandshakeFailed, "handshake failed", cancellationToken).ConfigureAwait(false);
            return false;
        }
    }

    // Best-effort typed error to the peer; swallows write failures (the socket may already be gone).
    private static async Task TrySendErrorAsync(
        FrameConnection connection, int code, string message, CancellationToken cancellationToken)
    {
        try
        {
            await WriteEnvelopeAsync(connection, ErrorEnvelope(string.Empty, code, message), string.Empty, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Peer already gone — nothing more to do.
        }
    }

    private static async Task DispatchAsync(FrameConnection connection, Envelope request, CancellationToken cancellationToken)
    {
        var requestId = request.RequestId;

        // Counted once per successfully decoded+dispatched envelope (a frame that fails AEAD
        // authentication never reaches here — see ReadFrameAsync's catch above — so a rejected/tampered
        // frame is correctly NOT counted as a request).
        EngineTrace.RecordRequest(EngineTrace.Transports.Pb);

        switch (request.BodyCase)
        {
            case Envelope.BodyOneofCase.Features:
            {
                Envelope response;
                using (EngineTrace.StartSpan("pb.execute", requestId))
                {
                    var features = new FeaturesResponse();
                    foreach (var descriptor in LanguageFeatureCatalog.Descriptors)
                    {
                        features.Features.Add(ToPb(descriptor));
                    }

                    response = new Envelope { RequestId = requestId, FeaturesResponse = features };
                }

                await WriteEnvelopeAsync(connection, response, requestId, cancellationToken).ConfigureAwait(false);
                break;
            }

            case Envelope.BodyOneofCase.Run:
            {
                var response = RunToEnvelope(requestId, request.Run.Id);
                await WriteEnvelopeAsync(connection, response, requestId, cancellationToken).ConfigureAwait(false);
                break;
            }

            case Envelope.BodyOneofCase.Bench:
            {
                // bench-* / gclab command ids ride the same catalog Run path as any other feature id.
                var response = RunToEnvelope(requestId, request.Bench.Command);
                await WriteEnvelopeAsync(connection, response, requestId, cancellationToken).ConfigureAwait(false);
                break;
            }

            case Envelope.BodyOneofCase.Ping:
            {
                Envelope response;
                using (EngineTrace.StartSpan("pb.execute", requestId))
                {
                    var run = LanguageFeatureCatalog.Run("ping");
                    response = new Envelope { RequestId = requestId, PingResponse = new PingResponse { Reply = run.Result } };
                }

                await WriteEnvelopeAsync(connection, response, requestId, cancellationToken).ConfigureAwait(false);
                break;
            }

            case Envelope.BodyOneofCase.Rag:
            {
                await StreamRagAsync(connection, requestId, request.Rag.Query, cancellationToken).ConfigureAwait(false);
                break;
            }

            default:
            {
                var error = ErrorEnvelope(requestId, ErrorUnknownRequest, $"Unsupported request: {request.BodyCase}.");
                await WriteEnvelopeAsync(connection, error, requestId, cancellationToken).ConfigureAwait(false);
                break;
            }
        }
    }

    // Runs one feature/command id through the shared catalog and wraps the result as a RunResponse envelope.
    private static Envelope RunToEnvelope(string requestId, string id)
    {
        using (EngineTrace.StartSpan("pb.execute", requestId))
        {
            var run = LanguageFeatureCatalog.Run(id);
            return new Envelope { RequestId = requestId, RunResponse = new RunResponse { Run = ToPb(run) } };
        }
    }

    // Streams a grounded RAG answer as a sequence of RagChunk frames, the last carrying Final=true.
    private static async Task StreamRagAsync(
        FrameConnection connection, string requestId, string query, CancellationToken cancellationToken)
    {
        using var execute = EngineTrace.StartSpan("pb.execute", requestId);
        var session = InferenceSession.Start(
            EngineHost.RagOrchestrator, new InferenceRequest(query), cancellationToken: cancellationToken);

        await using (session.ConfigureAwait(false))
        {
            await foreach (var token in session.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false))
            {
                var chunk = new Envelope
                {
                    RequestId = requestId,
                    RagChunk = new RagChunk { Index = token.Index, Text = token.Text, Final = token.IsFinal },
                };
                await WriteEnvelopeAsync(connection, chunk, requestId, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    private static async Task WriteEnvelopeAsync(
        FrameConnection connection, Envelope envelope, string requestId, CancellationToken cancellationToken)
    {
        byte[] bytes;
        using (EngineTrace.StartSpan("pb.encode", requestId))
        {
            bytes = envelope.ToByteArray();
        }

        await connection.WriteFrameAsync(bytes, cancellationToken).ConfigureAwait(false);
    }

    private static Envelope ErrorEnvelope(string requestId, int code, string message) =>
        new() { RequestId = requestId, Error = new ErrorFrame { Code = code, Message = message } };

    private static FeatureDescriptorPb ToPb(FeatureDescriptor descriptor) => new()
    {
        Id = descriptor.Id,
        Title = descriptor.Title,
        Version = descriptor.Version,
        Code = descriptor.Code,
        Expected = descriptor.Expected,
    };

    private static FeatureRunPb ToPb(FeatureRun run) => new()
    {
        Id = run.Id,
        Result = run.Result,
        ElapsedMs = run.ElapsedMs,
        Ok = run.Ok,
    };
}
