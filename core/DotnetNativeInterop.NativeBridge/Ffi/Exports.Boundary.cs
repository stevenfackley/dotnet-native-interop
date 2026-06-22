using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// FFI Boundary-showcase exports: a string echo (byte/thread/timing inspector), a contained managed
/// exception, and a streaming variant whose callback also carries the worker thread id and per-token
/// elapsed microseconds. Additive — the production session/feature exports are untouched.
/// [UnmanagedCallersOnly]: .NET 5+. delegate* unmanaged[Cdecl]: C# 9+ / .NET 5+. Pre-.NET 5 alternative
/// is a [UnmanagedFunctionPointer] delegate marshalled with Marshal.GetDelegateForFunctionPointer.
/// </summary>
internal static class ExportsBoundary
{
    /// <summary>Echo UTF-8 input as JSON {bytesHex,len,decoded,managedThreadId,executeUs,ptrIn}. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_ffi_echo")]
    public static unsafe nint Echo(byte* utf8, int len)
    {
        try
        {
            EngineHost.Initialize(); // idempotent
            if (utf8 == null || len < 0)
            {
                return 0;
            }

            var span = new ReadOnlySpan<byte>(utf8, len);
            var result = BoundaryDiagnostics.Echo(span) with { PtrIn = "0x" + ((nint)utf8).ToString("x") };
            var json = JsonSerializer.Serialize(result, typeof(BoundaryEcho), FeaturesJsonContext.Default);
            return NativeText.Allocate(json);
        }
        catch (Exception)
        {
            return 0;
        }
    }

    /// <summary>Throws inside managed code and returns the contained exception as JSON. Proves no crash crosses the ABI.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_ffi_throw")]
    public static unsafe nint Throw()
    {
        try
        {
            EngineHost.Initialize();
            BoundaryDiagnostics.Throw();
            return 0; // unreachable: Throw() always throws.
        }
        catch (Exception ex)
        {
            try
            {
                var json = JsonSerializer.Serialize(
                    BoundaryDiagnostics.Contain(ex), typeof(BoundaryThrow), FeaturesJsonContext.Default);
                return NativeText.Allocate(json);
            }
            catch (Exception)
            {
                return 0;
            }
        }
    }

    /// <summary>
    /// Like dni_session_start, but the callback also carries the managed thread id and elapsed µs, so the
    /// native UI can visualize the off-UI-thread callback hop with real numbers. Session id (&gt;0) or negative status.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_ffi_stream_start")]
    public static unsafe long StreamStart(
        byte* prompt,
        int maxTokens,
        delegate* unmanaged[Cdecl]<void*, int, byte*, int, long, long, void> callback,
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

            if (maxTokens <= 0)
            {
                return NativeStatus.InvalidArgument;
            }

            var promptText = NativeText.Read((nint)prompt);
            var request = new InferenceRequest(promptText, maxTokens);
            var session = InferenceSession.Start(EngineHost.Orchestrator, request);
            var id = SessionRegistry.Add(session);
            FfiState.AllocatedIds.TryAdd(id, 0);

            // Function pointers can't be captured in a lambda or cross an await boundary; round-trip through nint.
            var callbackAsNint = (nint)callback;
            var userDataAsNint = (nint)userData;
            _ = Task.Run(() => DrainTracedAsync(session, callbackAsNint, userDataAsNint));

            return id;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    private static async Task DrainTracedAsync(InferenceSession session, nint callback, nint userData)
    {
        var sw = Stopwatch.StartNew();
        try
        {
            await foreach (var token in session.Reader.ReadAllAsync().ConfigureAwait(false))
            {
                long threadId = Environment.CurrentManagedThreadId;
                // Stopwatch.Elapsed.TotalMicroseconds: .NET 7+. Pre-.NET 7: sw.ElapsedTicks * (1_000_000.0 / Stopwatch.Frequency).
                long elapsedUs = (long)sw.Elapsed.TotalMicroseconds;
                InvokeTraced(callback, userData, token.Index, token.Text, token.IsFinal, threadId, elapsedUs);
            }
        }
        catch (Exception)
        {
            // Cancelled/faulted: stop calling back. The absence of is_final=1 is the native-side failure signal
            // (same contract as the production DrainAsync).
        }
    }

    private static unsafe void InvokeTraced(
        nint callback, nint userData, int index, string text, bool isFinal, long managedThreadId, long elapsedUs)
    {
        var cb = (delegate* unmanaged[Cdecl]<void*, int, byte*, int, long, long, void>)callback;
        var nativeText = NativeText.Allocate(text);
        try
        {
            cb((void*)userData, index, (byte*)nativeText, isFinal ? 1 : 0, managedThreadId, elapsedUs);
        }
        finally
        {
            NativeText.Free(nativeText);
        }
    }
}
