#nullable enable

using Grpc.Core;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Grpc;

/// <summary>
/// gRPC server-streaming service implementation (Pattern 2).
/// Bridges a single <see cref="InferenceSession"/> to a gRPC response stream,
/// writing one <see cref="InferToken"/> proto message per <see cref="InferenceToken"/>.
/// Cancellation propagates bidirectionally: client disconnect cancels the session;
/// session completion closes the stream normally.
/// </summary>
internal sealed class InferenceService : Inference.InferenceBase
{
    public override async Task Infer(
        InferRequest request,
        IServerStreamWriter<InferToken> responseStream,
        ServerCallContext context)
    {
        // Validate — empty prompt is nonsensical; surface as a clean gRPC status rather
        // than letting the engine produce garbage tokens.
        if (string.IsNullOrWhiteSpace(request.Prompt))
        {
            throw new RpcException(new Status(StatusCode.InvalidArgument, "prompt must not be empty"));
        }

        var engineRequest = new InferenceRequest(
            Prompt: request.Prompt,
            MaxTokens: request.MaxTokens > 0 ? request.MaxTokens : 256,
            Temperature: request.Temperature > 0f ? request.Temperature : 0.8f);

        var ct = context.CancellationToken;

        await using var session = InferenceSession.Start(
            EngineHost.Orchestrator,
            engineRequest,
            capacity: 64,
            cancellationToken: ct);

        await foreach (var token in session.Reader.ReadAllAsync(ct).ConfigureAwait(false))
        {
            await responseStream.WriteAsync(new InferToken
            {
                Index = token.Index,
                Text  = token.Text,
                Final = token.IsFinal,
            }, ct).ConfigureAwait(false);
        }
    }
}
