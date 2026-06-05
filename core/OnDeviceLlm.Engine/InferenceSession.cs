using System.Threading.Channels;

namespace OnDeviceLlm.Engine;

/// <summary>
/// A running inference decoupled from its consumer by a bounded channel. The producer
/// (the model, on a background thread) blocks when the UI lags — backpressure, never a
/// dropped token — while the consumer drains <see cref="Reader"/> at its own pace.
/// This is the primitive the NativeAOT boundary wraps behind an opaque session handle.
/// </summary>
public sealed class InferenceSession : IAsyncDisposable
{
    private readonly Channel<InferenceToken> _channel;
    private readonly CancellationTokenSource _cts;
    private readonly Task _pump;

    private InferenceSession(Channel<InferenceToken> channel, CancellationTokenSource cts, Task pump)
    {
        _channel = channel;
        _cts = cts;
        _pump = pump;
    }

    /// <summary>Drains generated tokens; completes after the final marker or on cancellation.</summary>
    public ChannelReader<InferenceToken> Reader => _channel.Reader;

    /// <summary>Starts generation on a background task and returns immediately.</summary>
    /// <param name="orchestrator">The shared token producer.</param>
    /// <param name="request">The prompt and decoding parameters.</param>
    /// <param name="capacity">Bounded buffer depth; the producer waits when it is full.</param>
    /// <param name="cancellationToken">Outer token; cancellation also propagates via <see cref="Cancel"/>.</param>
    public static InferenceSession Start(
        InferenceOrchestrator orchestrator,
        InferenceRequest request,
        int capacity = 64,
        CancellationToken cancellationToken = default)
    {
        var channel = Channel.CreateBounded<InferenceToken>(new BoundedChannelOptions(capacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
            SingleWriter = true,
        });

        var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var pump = PumpAsync(orchestrator, request, channel.Writer, cts.Token);
        return new InferenceSession(channel, cts, pump);
    }

    private static async Task PumpAsync(
        InferenceOrchestrator orchestrator,
        InferenceRequest request,
        ChannelWriter<InferenceToken> writer,
        CancellationToken cancellationToken)
    {
        try
        {
            await foreach (var token in orchestrator.RunAsync(request, cancellationToken).ConfigureAwait(false))
            {
                await writer.WriteAsync(token, cancellationToken).ConfigureAwait(false);
            }

            writer.TryComplete();
        }
        catch (OperationCanceledException)
        {
            writer.TryComplete();
        }
        catch (Exception ex)
        {
            // Surface engine faults to the consumer instead of hanging the reader.
            writer.TryComplete(ex);
        }
    }

    /// <summary>Requests cancellation of the underlying generation.</summary>
    public void Cancel() => _cts.Cancel();

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        await _cts.CancelAsync().ConfigureAwait(false);

        try
        {
            await _pump.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // Expected when disposing a live session.
        }

        _cts.Dispose();
    }
}
