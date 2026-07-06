using System.Runtime.InteropServices;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>FFI streaming exports (Pattern 3): session start, cancel, and free.</summary>
internal static class ExportsFfi
{
    /// <summary>
    /// Starts an inference session and begins draining tokens on a background task.
    /// The supplied <paramref name="callback"/> is invoked once per token on that background
    /// thread; the native side is responsible for hopping to its UI thread.
    /// </summary>
    /// <param name="prompt">NUL-terminated UTF-8 prompt. Must not be null.</param>
    /// <param name="maxTokens">Maximum tokens to generate. Must be &gt; 0.</param>
    /// <param name="temperature">Sampling temperature. Must be &gt;= 0.</param>
    /// <param name="callback">Per-token callback. Must not be null.</param>
    /// <param name="userData">Opaque pointer forwarded verbatim to every callback invocation.</param>
    /// <returns>Session id (&gt; 0) on success; a negative <see cref="NativeStatus"/> on failure.</returns>
    [UnmanagedCallersOnly(EntryPoint = "dni_session_start")]
    public static unsafe long SessionStart(
        byte* prompt,
        int maxTokens,
        float temperature,
        delegate* unmanaged[Cdecl]<void*, int, byte*, int, void> callback,
        void* userData)
    {
        try
        {
            if (!EngineHost.IsInitialized)
            {
                return NativeStatus.NotInitialized;
            }

            if (prompt == null || callback == null)
            {
                return NativeStatus.InvalidArgument;
            }

            if (maxTokens <= 0 || temperature < 0f)
            {
                return NativeStatus.InvalidArgument;
            }

            // An empty prompt is valid: the active payload (FeatureShowcaseModel) ignores the
            // prompt, and the C ABI only forbids a NULL pointer (checked above), not "".
            var promptText = NativeText.Read((nint)prompt);

            // Span the synchronous session setup (the token stream runs on the background DrainAsync task).
            using var span = EngineTrace.StartSpan("ffi.session_start");
            var request = new InferenceRequest(promptText, maxTokens, temperature);
            var session = InferenceSession.Start(EngineHost.Orchestrator, request);
            var id = SessionRegistry.Add(session);
            FfiState.AllocatedIds.TryAdd(id, 0);

            // Function pointers can't be captured in a lambda (CS1944) or cross an await
            // boundary (CS4005). Round-trip through nint (blittable, pointer-sized); DrainAsync
            // re-materializes the pointer only at the synchronous call site.
            var callbackAsNint = (nint)callback;
            var userDataAsNint = (nint)userData;

            _ = Task.Run(() => DrainAsync(session, callbackAsNint, userDataAsNint));

            return id;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    /// <summary>
    /// Like <see cref="SessionStart"/>, but the prompt is a free-text question answered by the RAG
    /// orchestrator (manuals retrieval + grounded generation). Streams grounded-answer fragments via
    /// the same callback; cancel/free with <c>dni_session_cancel</c> / <c>dni_session_free</c>.
    /// </summary>
    /// <param name="query">NUL-terminated UTF-8 question. Must not be null.</param>
    /// <param name="maxTokens">Maximum tokens to generate. Must be &gt; 0.</param>
    /// <param name="temperature">Sampling temperature. Must be &gt;= 0.</param>
    /// <param name="callback">Per-token callback. Must not be null.</param>
    /// <param name="userData">Opaque pointer forwarded verbatim to every callback invocation.</param>
    /// <returns>Session id (&gt; 0) on success; a negative <see cref="NativeStatus"/> on failure.</returns>
    [UnmanagedCallersOnly(EntryPoint = "dni_rag_session_start")]
    public static unsafe long RagSessionStart(
        byte* query,
        int maxTokens,
        float temperature,
        delegate* unmanaged[Cdecl]<void*, int, byte*, int, void> callback,
        void* userData)
    {
        try
        {
            if (!EngineHost.IsInitialized)
            {
                return NativeStatus.NotInitialized;
            }

            if (query == null || callback == null)
            {
                return NativeStatus.InvalidArgument;
            }

            if (maxTokens <= 0 || temperature < 0f)
            {
                return NativeStatus.InvalidArgument;
            }

            var queryText = NativeText.Read((nint)query);

            using var span = EngineTrace.StartSpan("ffi.rag_session_start");
            var request = new InferenceRequest(queryText, maxTokens, temperature);
            var session = InferenceSession.Start(EngineHost.RagOrchestrator, request);
            var id = SessionRegistry.Add(session);
            FfiState.AllocatedIds.TryAdd(id, 0);

            var callbackAsNint = (nint)callback;
            var userDataAsNint = (nint)userData;

            _ = Task.Run(() => DrainAsync(session, callbackAsNint, userDataAsNint));

            return id;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    /// <summary>
    /// Requests cancellation of a running session. The background drain task will complete
    /// asynchronously; call <c>dni_session_free</c> afterward to release resources.
    /// </summary>
    /// <param name="sessionId">Handle returned by <c>dni_session_start</c>.</param>
    /// <returns><see cref="NativeStatus.Ok"/> or a negative status.</returns>
    [UnmanagedCallersOnly(EntryPoint = "dni_session_cancel")]
    public static int SessionCancel(long sessionId)
    {
        try
        {
            if (!SessionRegistry.TryGet(sessionId, out var session))
            {
                return NativeStatus.UnknownSession;
            }

            session.Cancel();
            return NativeStatus.Ok;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    /// <summary>
    /// Removes a session from the registry and disposes it. Blocks briefly while the
    /// background pump task drains; call after <c>dni_session_cancel</c> or after the
    /// final-token callback fires.
    /// </summary>
    /// <param name="sessionId">Handle returned by <c>dni_session_start</c>.</param>
    /// <returns><see cref="NativeStatus.Ok"/> or a negative status.</returns>
    [UnmanagedCallersOnly(EntryPoint = "dni_session_free")]
    public static int SessionFree(long sessionId)
    {
        try
        {
            FfiState.AllocatedIds.TryRemove(sessionId, out _);

            var removed = SessionRegistry.RemoveAsync(sessionId).AsTask().GetAwaiter().GetResult();
            return removed ? NativeStatus.Ok : NativeStatus.UnknownSession;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static async Task DrainAsync(InferenceSession session, nint callback, nint userData)
    {
        try
        {
            await foreach (var token in session.Reader.ReadAllAsync().ConfigureAwait(false))
            {
                // Pointer work is isolated in a synchronous helper: a pointer local may not
                // cross an await boundary, so DrainAsync itself stays pointer-free.
                Invoke(callback, userData, token.Index, token.Text, token.IsFinal);
            }
        }
        catch (Exception)
        {
            // The session may have been cancelled or the channel may have faulted.
            // Either way: do not surface into unobserved Task land — swallow here.
            // The native side will not receive further callbacks, which is the correct
            // signal (no is_final=1) telling it something went wrong; it can free/cancel.
        }
    }

    private static unsafe void Invoke(nint callback, nint userData, int index, string text, bool isFinal)
    {
        var cb = (delegate* unmanaged[Cdecl]<void*, int, byte*, int, void>)callback;
        var nativeText = NativeText.Allocate(text);
        try
        {
            cb((void*)userData, index, (byte*)nativeText, isFinal ? 1 : 0);
        }
        finally
        {
            NativeText.Free(nativeText);
        }
    }
}
