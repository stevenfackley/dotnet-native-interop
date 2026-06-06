using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading.Channels;

namespace DotnetNativeInterop.Engine.Llama;

/// <summary>
/// A raw on-device text generator backed by llama.cpp via the <c>dni_llama</c> shim. Loads the GGUF
/// once; each <see cref="GenerateAsync"/> runs a single-shot decode on a background thread and bridges
/// the native per-piece callback into an async stream through a bounded channel. Implements
/// <see cref="ILanguageModel"/> so <see cref="RagLanguageModel"/> can wrap it unchanged.
/// </summary>
public sealed unsafe class LlamaLanguageModel : ILanguageModel, IDisposable
{
    private readonly nint _handle;
    private static readonly ConcurrentDictionary<nint, ChannelWriter<string>> Writers = new();
    private static long _next;

    public LlamaLanguageModel(string ggufPath)
    {
        _handle = LlamaNative.dni_llama_load(ggufPath);
        if (_handle == 0)
        {
            throw new InvalidOperationException($"llama.cpp failed to load GGUF at {ggufPath}");
        }
    }

    /// <inheritdoc/>
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var channel = Channel.CreateBounded<string>(new BoundedChannelOptions(256)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
            SingleWriter = true,
        });

        var key = (nint)Interlocked.Increment(ref _next);
        Writers[key] = channel.Writer;

        var task = Task.Run(() =>
        {
            try
            {
                GenerateUnsafe(_handle, request.Prompt, request.MaxTokens, request.Temperature, key);
            }
            finally
            {
                channel.Writer.TryComplete();
                Writers.TryRemove(key, out _);
            }
        }, cancellationToken);

        await foreach (var piece in channel.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false))
        {
            yield return piece;
        }

        await task.ConfigureAwait(false);
    }

    // Unsafe trampoline: async iterators cannot contain unsafe code directly, so the
    // pointer-typed arguments are isolated here and called from the Task.Run lambda above.
    private static unsafe void GenerateUnsafe(nint handle, string prompt, int maxTokens, float temp, nint key)
    {
        LlamaNative.dni_llama_generate(handle, prompt, maxTokens, temp, &OnPiece, (void*)key);
    }

    // Non-capturing unmanaged callback: recover the writer from the key passed as user_data.
    [UnmanagedCallersOnly(CallConvs = [typeof(System.Runtime.CompilerServices.CallConvCdecl)])]
    private static void OnPiece(void* userData, byte* text)
    {
        if (text == null)
        {
            return;
        }

        var key = (nint)userData;
        if (Writers.TryGetValue(key, out var writer))
        {
            var s = Marshal.PtrToStringUTF8((nint)text);
            if (!string.IsNullOrEmpty(s))
            {
                writer.TryWrite(s);
            }
        }
    }

    public void Dispose()
    {
        if (_handle != 0)
        {
            LlamaNative.dni_llama_free(_handle);
        }
    }
}
