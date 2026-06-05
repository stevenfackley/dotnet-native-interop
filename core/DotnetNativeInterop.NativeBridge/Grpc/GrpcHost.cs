#nullable enable

// AOT binary-size note: hosting full Kestrel + Grpc.AspNetCore inside a NativeAOT shared
// library adds roughly 8–12 MB to the stripped .dylib/.so compared to a minimal AOT binary.
// This is the cost of pulling in the ASP.NET Core middleware pipeline, HTTP/2 framing, and
// Google.Protobuf. Acceptable for a POC; a production library would expose only a thin C ABI
// and move the HTTP/2 stack to the host process.

using System.Diagnostics.CodeAnalysis;
using System.Net.Sockets;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;

namespace DotnetNativeInterop.NativeBridge.Grpc;

/// <summary>
/// Owns the ASP.NET Core / Kestrel host that serves the gRPC endpoint over a Unix Domain Socket.
/// Lifecycle is managed by the exported C functions in <see cref="GrpcExports"/>.
/// </summary>
internal static class GrpcHost
{
    private static readonly object Gate = new();
    private static WebApplication? _app;

    /// <summary>
    /// Idempotently starts the gRPC server on the given UDS path.
    /// Returns <see cref="NativeStatus.Ok"/> on success or an already-running server,
    /// <see cref="NativeStatus.InvalidArgument"/> for a null/empty path,
    /// <see cref="NativeStatus.Internal"/> for any other failure.
    /// </summary>
    [DynamicDependency(DynamicallyAccessedMemberTypes.All, typeof(InferenceService))]
    internal static int Start(string socketPath)
    {
        if (string.IsNullOrWhiteSpace(socketPath))
        {
            return NativeStatus.InvalidArgument;
        }

        lock (Gate)
        {
            if (_app is not null)
            {
                // Already running — idempotent.
                return NativeStatus.Ok;
            }

            try
            {
                EngineHost.Initialize();

                // Remove a stale socket file left by a previous crash or clean shutdown
                // that did not delete it in time. Kestrel refuses to bind if the path exists.
                if (File.Exists(socketPath))
                {
                    File.Delete(socketPath);
                }

                var builder = WebApplication.CreateSlimBuilder();

                builder.WebHost.ConfigureKestrel(kestrel =>
                {
                    kestrel.Listen(new UnixDomainSocketEndPoint(socketPath), listenOptions =>
                    {
                        listenOptions.Protocols = HttpProtocols.Http2;
                    });
                });

                builder.Services.AddGrpc();

                var app = builder.Build();
                app.MapGrpcService<InferenceService>();

                // Start on a background thread; the NativeAOT library has no managed
                // entry point so we must not block the native caller's thread.
                var startTask = Task.Run(async () => await app.StartAsync().ConfigureAwait(false));
                startTask.GetAwaiter().GetResult();

                _app = app;
                return NativeStatus.Ok;
            }
            catch
            {
                return NativeStatus.Internal;
            }
        }
    }

    /// <summary>
    /// Gracefully stops the gRPC server and deletes the socket file.
    /// No-op if the server was never started.
    /// </summary>
    internal static int Stop()
    {
        WebApplication? app;

        lock (Gate)
        {
            app  = _app;
            _app = null;
        }

        if (app is null)
        {
            return NativeStatus.Ok;
        }

        try
        {
            Task.Run(async () => await app.StopAsync().ConfigureAwait(false))
                .GetAwaiter().GetResult();
            return NativeStatus.Ok;
        }
        catch
        {
            return NativeStatus.Internal;
        }
    }
}
