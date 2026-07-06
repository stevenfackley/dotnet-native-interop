using System.Net;
using System.Net.Sockets;
using Google.Protobuf;
using DotnetNativeInterop.Engine;

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
    internal const int ErrorHandshakeRequired = 2;
    internal const int ErrorTamper = 3;
    internal const int ErrorInternal = 4;

    private static readonly object Gate = new();
    private static TcpListener? _listener;
    private static CancellationTokenSource? _cts;

    // Whether this server instance requires the post-quantum handshake before serving requests.
    private static bool _requirePq;

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
                return ((IPEndPoint)_listener.LocalEndpoint).Port;
            }

            EngineHost.Initialize();
            _requirePq = (flags & 1) != 0;

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
        }
    }

    private static async Task AcceptLoopAsync(TcpListener listener, CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var client = await listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
                _ = HandleClientAsync(client, cancellationToken);
            }
        }
        catch (Exception)
        {
            // Listener stopped or cancelled.
        }
    }

    private static async Task HandleClientAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            using (client)
            {
                var stream = client.GetStream();
                var connection = new FrameConnection(stream);

                // The PQ handshake (when required) is performed here in the secure-channel slice; on the
                // plain channel there is nothing to negotiate and we go straight to serving frames.

                while (!cancellationToken.IsCancellationRequested)
                {
                    var frame = await connection.ReadFrameAsync(cancellationToken).ConfigureAwait(false);
                    if (frame is null)
                    {
                        break; // clean EOF
                    }

                    Envelope request;
                    using (EngineTrace.StartSpan("pb.decode"))
                    {
                        request = Envelope.Parser.ParseFrom(frame);
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

    private static async Task DispatchAsync(FrameConnection connection, Envelope request, CancellationToken cancellationToken)
    {
        var requestId = request.RequestId;

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
