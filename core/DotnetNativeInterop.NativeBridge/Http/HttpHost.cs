using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Http;

/// <summary>
/// Manages the lifetime of the Kestrel HTTP loopback server for Pattern 1.
/// <para>
/// Loopback (127.0.0.1) avoids the iOS Local Network permission prompt because
/// the traffic never leaves the device. Caveat: iOS suspends the listener when the
/// app moves to the background — the Swift host must call <c>dni_http_start</c>
/// again on foreground-resume to obtain a (possibly new) port.
/// </para>
/// </summary>
internal static class HttpHost
{
    // Protects _app and _port against concurrent Start/Stop calls.
    private static readonly SemaphoreSlim Gate = new(1, 1);

    private static WebApplication? _app;
    private static int _port;

    /// <summary>
    /// Starts Kestrel on <c>127.0.0.1:0</c> (OS-assigned port) and returns the bound port.
    /// Idempotent: if the server is already running the existing port is returned immediately.
    /// </summary>
    internal static async Task<int> StartAsync()
    {
        await Gate.WaitAsync().ConfigureAwait(false);
        try
        {
            // Idempotent: already running — return cached port.
            if (_app is not null)
            {
                return _port;
            }

            EngineHost.Initialize();

            var builder = WebApplication.CreateSlimBuilder();

            // Bind exclusively to loopback on an OS-assigned port.
            // 127.0.0.1 (not ::1) keeps us off the IPv6 path on Android and
            // avoids the iOS Local Network entitlement entirely.
            builder.WebHost.ConfigureKestrel(opts =>
            {
                opts.ListenLocalhost(0); // port 0 → OS picks
            });

            // Source-generated JSON options — required for NativeAOT; no reflection.
            builder.Services.ConfigureHttpJsonOptions(opts =>
            {
                opts.SerializerOptions.TypeInfoResolverChain.Insert(0, HttpJsonContext.Default);
            });

            var app = builder.Build();

            app.MapPost("/v1/infer", HandleInferAsync);

            await app.StartAsync().ConfigureAwait(false);

            // Read back the OS-assigned port from the bound address.
            var server = app.Services.GetRequiredService<IServer>();
            var addressFeature = server.Features.Get<IServerAddressesFeature>()
                ?? throw new InvalidOperationException("IServerAddressesFeature not available.");

            // Addresses look like "http://127.0.0.1:NNNNN"; parse the last segment.
            var address = addressFeature.Addresses.First();
            var uri = new Uri(address);
            _port = uri.Port;
            _app = app;

            return _port;
        }
        finally
        {
            Gate.Release();
        }
    }

    /// <summary>Gracefully shuts down the server. Safe to call when not running.</summary>
    internal static async Task StopAsync()
    {
        await Gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_app is null)
            {
                return;
            }

            await _app.StopAsync().ConfigureAwait(false);
            await _app.DisposeAsync().ConfigureAwait(false);
            _app = null;
            _port = 0;
        }
        finally
        {
            Gate.Release();
        }
    }

    // -----------------------------------------------------------------------
    // POST /v1/infer handler
    // -----------------------------------------------------------------------

    private static async Task HandleInferAsync(HttpContext ctx)
    {
        // 1. Deserialize — uses source-generated context; no reflection.
        InferRequest? req;
        try
        {
            req = await ctx.Request.ReadFromJsonAsync(
                HttpJsonContext.Default.InferRequest,
                ctx.RequestAborted).ConfigureAwait(false);
        }
        catch (JsonException)
        {
            ctx.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        if (req is null || string.IsNullOrWhiteSpace(req.Prompt))
        {
            ctx.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        // 2. Set up SSE response headers before writing any body bytes.
        ctx.Response.StatusCode = StatusCodes.Status200OK;
        ctx.Response.ContentType = "text/event-stream";
        ctx.Response.Headers.CacheControl = "no-cache";
        ctx.Response.Headers.Connection = "keep-alive";
        // Disable response buffering so tokens reach the client immediately.
        ctx.Response.Headers["X-Accel-Buffering"] = "no";

        var ct = ctx.RequestAborted;
        var inferRequest = new InferenceRequest(req.Prompt, req.MaxTokens, req.Temperature);
        var session = InferenceSession.Start(EngineHost.Orchestrator, inferRequest, capacity: 64, ct);

        await using (session.ConfigureAwait(false))
        {
            // 3. Drain the channel and emit one SSE event per token.
            await foreach (var token in session.Reader.ReadAllAsync(ct).ConfigureAwait(false))
            {
                var frame = new SseToken
                {
                    Index = token.Index,
                    Text = token.Text,
                    Final = token.IsFinal,
                };

                var json = JsonSerializer.Serialize(frame, HttpJsonContext.Default.SseToken);

                // SSE wire format: "data: <json>\n\n"
                await ctx.Response.WriteAsync("data: ", ct).ConfigureAwait(false);
                await ctx.Response.WriteAsync(json, ct).ConfigureAwait(false);
                await ctx.Response.WriteAsync("\n\n", ct).ConfigureAwait(false);
                await ctx.Response.Body.FlushAsync(ct).ConfigureAwait(false);
            }
        }
    }
}
