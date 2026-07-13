using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.HttpRaw;

/// <summary>
/// A minimal HTTP/1.1 server built directly on System.Net.Sockets — no ASP.NET (which has no mobile
/// runtime pack). Routes:
///   GET /features            → JSON array of feature descriptors
///   GET /feature/run/{id}    → JSON {id,result,elapsedMs,ok} for one feature
///   (anything else)          → the legacy SSE showcase stream
/// </summary>
internal static class RawHttpServer
{
    private static readonly object Gate = new();
    private static TcpListener? _listener;
    private static CancellationTokenSource? _cts;

    internal static int Start()
    {
        lock (Gate)
        {
            if (_listener is not null)
            {
                return ((IPEndPoint)_listener.LocalEndpoint).Port;
            }

            EngineHost.Initialize();

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
        // try/catch INSIDE the loop so a transient accept error (a backlog RST → SocketException) doesn't
        // permanently kill the accept loop while Start() keeps reporting the server as live. Only a real
        // Stop() (cancellation / disposed listener) ends the loop. Mirrors PbFrameServer.AcceptLoopAsync.
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
                var (path, requestId) = await ReadRequestAsync(stream, cancellationToken).ConfigureAwait(false);

                // One span per request; server-side stage spans (http.execute) nest under it via
                // Activity.Current. requestId comes from the optional X-Dni-Request-Id correlation header.
                using var requestSpan = EngineTrace.StartSpan("http.request", requestId);
                EngineTrace.RecordRequest(EngineTrace.Transports.Http);
                if (requestSpan is not null)
                {
                    requestSpan.SetTag("dni.path", path);
                }

                if (path == "/features")
                {
                    using (EngineTrace.StartSpan("http.execute", requestId))
                    {
                        var json = JsonSerializer.Serialize(
                            LanguageFeatureCatalog.Descriptors,
                            typeof(IReadOnlyList<FeatureDescriptor>),
                            FeaturesJsonContext.Default);
                        await WriteJsonAsync(stream, json, cancellationToken).ConfigureAwait(false);
                    }

                    return;
                }

                if (path.StartsWith("/feature/run/", StringComparison.Ordinal))
                {
                    using (EngineTrace.StartSpan("http.execute", requestId))
                    {
                        var id = Uri.UnescapeDataString(path["/feature/run/".Length..]);
                        var run = LanguageFeatureCatalog.Run(id);
                        var json = JsonSerializer.Serialize(run, typeof(FeatureRun), FeaturesJsonContext.Default);
                        await WriteJsonAsync(stream, json, cancellationToken).ConfigureAwait(false);
                    }

                    return;
                }

                if (path.StartsWith("/rag?q=", StringComparison.Ordinal))
                {
                    var query = Uri.UnescapeDataString(path["/rag?q=".Length..]);

                    await WriteAsync(
                        stream,
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n",
                        cancellationToken).ConfigureAwait(false);

                    using var execute = EngineTrace.StartSpan("http.execute", requestId);
                    var ragSession = InferenceSession.Start(
                        EngineHost.RagOrchestrator, new InferenceRequest(query),
                        cancellationToken: cancellationToken);

                    await using (ragSession.ConfigureAwait(false))
                    {
                        await foreach (var token in ragSession.Reader
                            .ReadAllAsync(cancellationToken).ConfigureAwait(false))
                        {
                            var sse = $"data: {{\"index\":{token.Index},\"text\":{JsonString(token.Text)},\"final\":{(token.IsFinal ? "true" : "false")}}}\n\n";
                            await WriteAsync(stream, sse, cancellationToken).ConfigureAwait(false);
                            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                        }
                    }

                    return;
                }

                // Legacy: stream the showcase as Server-Sent Events.
                await WriteAsync(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n",
                    cancellationToken).ConfigureAwait(false);

                using var legacyExecute = EngineTrace.StartSpan("http.execute", requestId);
                var session = InferenceSession.Start(
                    EngineHost.Orchestrator, new InferenceRequest(string.Empty), cancellationToken: cancellationToken);

                await using (session.ConfigureAwait(false))
                {
                    await foreach (var token in session.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false))
                    {
                        var sse = $"data: {{\"index\":{token.Index},\"text\":{JsonString(token.Text)},\"final\":{(token.IsFinal ? "true" : "false")}}}\n\n";
                        await WriteAsync(stream, sse, cancellationToken).ConfigureAwait(false);
                        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                    }
                }
            }
        }
        catch (Exception)
        {
            // Client disconnected or cancelled.
        }
    }

    /// <summary>
    /// Reads the request head and returns the request-target path (e.g. "/features") plus the optional
    /// <c>X-Dni-Request-Id</c> correlation header (null when absent) that ties this request's server-side
    /// spans to the client's own spans in the boundary waterfall.
    /// </summary>
    private static async Task<(string Path, string? RequestId)> ReadRequestAsync(
        NetworkStream stream, CancellationToken cancellationToken)
    {
        var buffer = new byte[2048];
        var seen = 0;
        while (seen < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(seen), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            seen += read;
            var text = Encoding.ASCII.GetString(buffer, 0, seen);
            // Wait for the FULL header block (\r\n\r\n) so ParseRequest sees the X-Dni-Request-Id header, not
            // just the request line. The buffer-full / EOF fallback below handles headerless or partial cases.
            if (text.Contains("\r\n\r\n", StringComparison.Ordinal))
            {
                return ParseRequest(text);
            }
        }

        return seen > 0 ? ParseRequest(Encoding.ASCII.GetString(buffer, 0, seen)) : ("/", null);
    }

    /// <summary>Extracts the path from the request line and the optional X-Dni-Request-Id header.</summary>
    private static (string Path, string? RequestId) ParseRequest(string head)
    {
        string path = "/";
        string? requestId = null;

        var lines = head.Split("\r\n");
        if (lines.Length > 0)
        {
            var parts = lines[0].Split(' ');
            if (parts.Length >= 2)
            {
                path = parts[1];
            }
        }

        for (var i = 1; i < lines.Length; i++)
        {
            const string headerName = "X-Dni-Request-Id:";
            if (lines[i].StartsWith(headerName, StringComparison.OrdinalIgnoreCase))
            {
                requestId = lines[i][headerName.Length..].Trim();
                break;
            }
        }

        return (path, requestId);
    }

    private static async Task WriteJsonAsync(NetworkStream stream, string json, CancellationToken cancellationToken)
    {
        var body = Encoding.UTF8.GetBytes(json);
        var head = $"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n";
        await stream.WriteAsync(Encoding.UTF8.GetBytes(head), cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(body, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async Task WriteAsync(NetworkStream stream, string value, CancellationToken cancellationToken)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        await stream.WriteAsync(bytes, cancellationToken).ConfigureAwait(false);
    }

    // AOT-safe JSON string escaping for the legacy SSE path (avoids the reflection serializer).
    private static string JsonString(string value)
    {
        var sb = new StringBuilder(value.Length + 2);
        sb.Append('"');
        foreach (var c in value)
        {
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (c < 0x20)
                    {
                        sb.Append("\\u").Append(((int)c).ToString("x4"));
                    }
                    else
                    {
                        sb.Append(c);
                    }

                    break;
            }
        }

        sb.Append('"');
        return sb.ToString();
    }
}

/// <summary>HTTP loopback exports (Pattern 1) — raw System.Net.Sockets, no ASP.NET.</summary>
internal static class ExportsHttpRaw
{
    [UnmanagedCallersOnly(EntryPoint = "dni_http_start")]
    public static int Start()
    {
        try
        {
            return RawHttpServer.Start();
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    [UnmanagedCallersOnly(EntryPoint = "dni_http_stop")]
    public static int Stop()
    {
        try
        {
            RawHttpServer.Stop();
            return NativeStatus.Ok;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }
}
