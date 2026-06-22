# FFI Boundary — Native ABI Batch (Plan A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three additive NativeAOT FFI exports (`dni_ffi_echo`, `dni_ffi_throw`, `dni_ffi_stream_start`) plus the `dni_trace_cb` callback so the iOS/Android Boundary screens (Plans B/C) can show marshalling, error containment, and the off-UI-thread callback hop with native-truth numbers.

**Architecture:** Logic lives in a plain, unit-testable `BoundaryDiagnostics` class in `DotnetNativeInterop.Engine`; the `[UnmanagedCallersOnly]` exports in `DotnetNativeInterop.NativeBridge` are thin marshalling wrappers over it (the exports themselves can only be called via function pointer from native, so the testable logic must sit outside them). Strings cross as heap UTF-8 freed by the existing `dni_string_free`; JSON uses the existing source-generated `FeaturesJsonContext` (reflection-free, AOT-safe). Verification follows the repo's documented convention — a throwaway console probe in `%TEMP%` referencing `Engine.csproj`, deleted after use (no permanent test project added).

**Tech Stack:** C# 14 / .NET 10, NativeAOT, `System.Text.Json` source generation, function-pointer ABI (`delegate* unmanaged[Cdecl]`).

**Spec:** `docs/superpowers/specs/2026-06-21-ffi-boundary-showcase-design.md`. This is Plan A of three (B = iOS screen, C = Android screen + JNI). The native-artifact rebuild (`build-ios-framework.sh` / `build-android-so.sh`) is the first step of Plans B/C, which consume the library this plan freezes — Plan A is fully verifiable on Windows with no device/Mac build.

**Branch:** continue on `feat/ffi-boundary-showcase` (spec already committed there).

---

## File Structure

| File | Create/Modify | Responsibility |
|------|---------------|----------------|
| `core/DotnetNativeInterop.Engine/BoundaryDiagnostics.cs` | Create | The `BoundaryEcho`/`BoundaryThrow` records + the testable `Echo`/`Throw`/`Contain` logic. |
| `core/DotnetNativeInterop.NativeBridge/FeaturesJsonContext.cs` | Modify | Register the two new records for source-gen JSON. |
| `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Boundary.cs` | Create | The three `[UnmanagedCallersOnly]` exports + the traced drain/invoke helpers. |
| `core/DotnetNativeInterop.NativeBridge/abi/dni.h` | Modify | Additive C declarations + the `dni_trace_cb` typedef. |
| `%TEMP%/dni-boundary-probe/` | Create then delete | Throwaway verification console (not committed). |

---

## Task 1: Engine logic + records (`BoundaryDiagnostics`)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/BoundaryDiagnostics.cs`
- Test (throwaway): `%TEMP%/dni-boundary-probe/Program.cs`

- [ ] **Step 1: Write the failing test (throwaway probe)**

Run (PowerShell):
```powershell
$probe = "$env:TEMP\dni-boundary-probe"
dotnet new console -o $probe
dotnet add $probe reference "core\DotnetNativeInterop.Engine\DotnetNativeInterop.Engine.csproj"
```
Then overwrite `$probe\Program.cs` with:
```csharp
using System.Text;
using DotnetNativeInterop.Engine;

// Echo round-trip
var input = Encoding.UTF8.GetBytes("Hello");
var echo = BoundaryDiagnostics.Echo(input);
if (echo.Decoded != "Hello") throw new Exception($"decoded={echo.Decoded}");
if (echo.BytesHex != "48656C6C6F") throw new Exception($"hex={echo.BytesHex}");
if (echo.Len != 5) throw new Exception($"len={echo.Len}");
if (echo.ManagedThreadId <= 0) throw new Exception("thread id not captured");
if (echo.ExecuteUs < 0) throw new Exception($"executeUs={echo.ExecuteUs}");
Console.WriteLine($"echo OK: {echo}");

// Throw + Contain
try
{
    BoundaryDiagnostics.Throw();
    throw new Exception("Throw() did not throw");
}
catch (InvalidOperationException ex)
{
    var c = BoundaryDiagnostics.Contain(ex);
    if (!c.Caught) throw new Exception("not marked caught");
    if (c.Status != -5) throw new Exception($"status={c.Status}");
    if (!c.Type.Contains("InvalidOperationException")) throw new Exception($"type={c.Type}");
    Console.WriteLine($"throw/contain OK: {c}");
}

Console.WriteLine("ALL BOUNDARY DIAGNOSTICS PASS");
```

- [ ] **Step 2: Run the probe to verify it fails**

Run: `dotnet run --project $env:TEMP\dni-boundary-probe -c Release`
Expected: FAIL — compile error `CS0103: The name 'BoundaryDiagnostics' does not exist` (the type isn't written yet). If `Engine.csproj` multi-targets, the net10.0 build is resolved for the console automatically.

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/BoundaryDiagnostics.cs`:
```csharp
using System;
using System.Diagnostics;
using System.Text;

namespace DotnetNativeInterop.Engine;

/// <summary>Result of the FFI echo round-trip (boundary showcase).</summary>
/// <remarks><c>PtrIn</c> is filled by the export layer (the native input pointer); the engine leaves it empty.</remarks>
public sealed record BoundaryEcho(
    string BytesHex, int Len, string Decoded, long ManagedThreadId, double ExecuteUs, string PtrIn = "");

/// <summary>Describes a managed exception contained at the FFI boundary.</summary>
public sealed record BoundaryThrow(bool Caught, string Type, string Message, int Status);

/// <summary>
/// Plain managed logic behind the FFI Boundary-showcase exports. Lives here (not inside the
/// [UnmanagedCallersOnly] exports, which are only callable via function pointer from native) so the
/// behavior is unit-testable from a managed probe.
/// </summary>
public static class BoundaryDiagnostics
{
    /// <summary>Echoes UTF-8 input: hex, decoded text, the managed thread id, and managed execute time.</summary>
    public static BoundaryEcho Echo(ReadOnlySpan<byte> utf8)
    {
        var sw = Stopwatch.StartNew();
        long threadId = Environment.CurrentManagedThreadId;
        string decoded = Encoding.UTF8.GetString(utf8);
        // Convert.ToHexString: .NET 5+. Pre-.NET 5 alternative: BitConverter.ToString(utf8.ToArray()).Replace("-", "").
        string hex = Convert.ToHexString(utf8);
        sw.Stop();
        // Stopwatch.Elapsed.TotalMicroseconds: .NET 7+. Pre-.NET 7 alternative:
        // sw.ElapsedTicks * (1_000_000.0 / Stopwatch.Frequency).
        double executeUs = sw.Elapsed.TotalMicroseconds;
        return new BoundaryEcho(hex, utf8.Length, decoded, threadId, executeUs);
    }

    /// <summary>Throws on purpose so the export's catch can demonstrate boundary containment.</summary>
    public static void Throw() =>
        throw new InvalidOperationException("Boundary demo: managed exception crossing prevented.");

    /// <summary>Describes a contained exception for the native side. status mirrors NativeStatus.Internal (-5).</summary>
    public static BoundaryThrow Contain(Exception ex) =>
        new(true, ex.GetType().FullName ?? ex.GetType().Name, ex.Message, -5);
}
```

- [ ] **Step 4: Run the probe to verify it passes**

Run: `dotnet run --project $env:TEMP\dni-boundary-probe -c Release`
Expected: PASS — prints `echo OK: …`, `throw/contain OK: …`, then `ALL BOUNDARY DIAGNOSTICS PASS`.

- [ ] **Step 5: Delete the probe and commit the implementation**

Run:
```powershell
Remove-Item -Recurse -Force $env:TEMP\dni-boundary-probe
git add core/DotnetNativeInterop.Engine/BoundaryDiagnostics.cs
git commit -m "feat(ffi-boundary): add BoundaryDiagnostics engine logic + records"
```

---

## Task 2: Register the new records for source-gen JSON

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/FeaturesJsonContext.cs`

- [ ] **Step 1: Add the two `[JsonSerializable]` attributes**

In `FeaturesJsonContext.cs`, add these two lines to the existing attribute list (immediately below `[JsonSerializable(typeof(FeatureRun))]`):
```csharp
[JsonSerializable(typeof(BoundaryEcho))]
[JsonSerializable(typeof(BoundaryThrow))]
```
The file already has `using DotnetNativeInterop.Engine;`, so the record types resolve. Result (for reference) — the attribute block becomes:
```csharp
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(IReadOnlyList<FeatureDescriptor>))]
[JsonSerializable(typeof(FeatureDescriptor))]
[JsonSerializable(typeof(FeatureRun))]
[JsonSerializable(typeof(BoundaryEcho))]
[JsonSerializable(typeof(BoundaryThrow))]
internal sealed partial class FeaturesJsonContext : JsonSerializerContext
{
}
```

- [ ] **Step 2: Build to verify source-gen accepts the new types**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: PASS — 0 errors, 0 source-generation warnings. (The generator now emits camelCase metadata for `boundaryEcho`/`boundaryThrow` shapes.)

- [ ] **Step 3: Commit**

Run:
```powershell
git add core/DotnetNativeInterop.NativeBridge/FeaturesJsonContext.cs
git commit -m "feat(ffi-boundary): register BoundaryEcho/BoundaryThrow for source-gen JSON"
```

---

## Task 3: The three `[UnmanagedCallersOnly]` exports

**Files:**
- Create: `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Boundary.cs`

- [ ] **Step 1: Create the exports file**

Create `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Boundary.cs`:
```csharp
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
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
```

- [ ] **Step 2: Build to verify the exports compile**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: PASS — 0 errors. This compiles the `[UnmanagedCallersOnly]` entrypoints, the extended `delegate* unmanaged[Cdecl]<void*,int,byte*,int,long,long,void>` function-pointer type, and the source-gen serialize calls. (`EngineHost`, `SessionRegistry`, `FfiState`, `InferenceRequest`, `InferenceSession` resolve via the parent `DotnetNativeInterop.NativeBridge` namespace + the `Engine` using, exactly as in `Exports.Ffi.cs`.)

- [ ] **Step 3: Commit**

Run:
```powershell
git add core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Boundary.cs
git commit -m "feat(ffi-boundary): add dni_ffi_echo/throw/stream_start exports"
```

---

## Task 4: Declare the exports in the C ABI header

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/abi/dni.h`

- [ ] **Step 1: Add the additive section**

Append this block to `abi/dni.h`, after the existing `dni_string_free` declaration (keep the file's existing `int32_t`/`int64_t` style — `stdint.h` is already in use by the existing typedefs):
```c
/* ---- Pattern 3: boundary instrumentation (additive, FFI showcase) ------- */
/* Diagnostics that make the interop boundary itself visible. The two getters
 * return heap UTF-8 JSON (or NULL on failure); copy the text, then release it
 * with dni_string_free. */
const char* dni_ffi_echo(const char* utf8, int32_t len); /* {bytesHex,len,decoded,managedThreadId,executeUs,ptrIn} */
const char* dni_ffi_throw(void);                          /* {caught,type,message,status} — managed exception contained */

/*
 * Extended per-token callback: like dni_token_cb, but also carries the managed
 * thread id the callback runs on and elapsed microseconds since stream start, so
 * the UI can visualize the off-UI-thread callback hop with real numbers. Fires on
 * a .NET background thread; `text` is UTF-8, valid ONLY during the call (copy it).
 */
typedef void (*dni_trace_cb)(void* user_data,
                                     int32_t index,
                                     const char* text,
                                     int32_t is_final,
                                     int64_t managed_thread_id,
                                     int64_t elapsed_us);

/* Session id (>0) on success, or a negative DNI_* status. Stop with
 * dni_session_cancel / dni_session_free (the production lifecycle exports). */
int64_t dni_ffi_stream_start(const char* prompt, int32_t max_tokens,
                             dni_trace_cb cb, void* user_data);
```

- [ ] **Step 2: Verify the header matches the exports**

Confirm by inspection that each C declaration's name + arity matches the `EntryPoint` strings and signatures in `Exports.Boundary.cs`:
- `dni_ffi_echo(const char*, int32_t)` ↔ `Echo(byte*, int)`
- `dni_ffi_throw(void)` ↔ `Throw()`
- `dni_ffi_stream_start(const char*, int32_t, dni_trace_cb, void*)` ↔ `StreamStart(byte*, int, delegate*…, void*)`
- `dni_trace_cb` params `(void*, int32_t, const char*, int32_t, int64_t, int64_t)` ↔ `<void*, int, byte*, int, long, long, void>`

Expected: all four line up. (The Android JNI build in Plan C is the compile-time validator of this header against the `.so`.)

- [ ] **Step 3: Commit**

Run:
```powershell
git add core/DotnetNativeInterop.NativeBridge/abi/dni.h
git commit -m "feat(ffi-boundary): declare boundary exports + dni_trace_cb in dni.h"
```

---

## Task 5: Final managed-build gate

**Files:** none (verification only)

- [ ] **Step 1: Full release build**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: PASS — 0 errors, 0 warnings introduced by this plan. The ABI is now frozen for Plans B/C.

- [ ] **Step 2: Confirm AOT-safety (no reflection JSON)**

Confirm the build log shows no `System.Text.Json` reflection-fallback or trim warnings for `BoundaryEcho`/`BoundaryThrow` (they go through `FeaturesJsonContext`). Expected: none.

- [ ] **Step 3: Tag the handoff point (no commit needed)**

The native artifact rebuilds (`build/build-ios-framework.sh`, `build/build-android-so.sh`) are the first steps of Plans B and C, which consume this library. Plan A is complete: managed build green, ABI frozen, three exports + `dni_trace_cb` declared.

---

## Self-Review

**Spec coverage:**
- `dni_ffi_echo` (bytes/thread/µs/ptrIn) → Task 1 (logic) + Task 3 (export) + Task 4 (header). ✓
- `dni_ffi_throw` (contained exception) → Task 1 + Task 3 + Task 4. ✓
- `dni_ffi_stream_start` + extended `dni_trace_cb` (threadId + elapsedUs) → Task 3 + Task 4. ✓
- Source-gen JSON, no reflection → Task 2 + Task 5 Step 2. ✓
- "Managed unit-test the logic behind the exports" → Task 1 throwaway probe (repo convention; no permanent test project, per anti-bloat). ✓
- Reuse `dni_string_free` / `dni_engine_stats` / `dni_feature_run` unchanged → not modified by any task. ✓
- Version-gated comments (`Convert.ToHexString` .NET 5+, `TotalMicroseconds` .NET 7+, `[UnmanagedCallersOnly]`/`delegate* unmanaged` .NET 5+/C# 9+) → inline in Task 1 + Task 3 code. ✓
- Leak demo is frontend-only (no native export) → correctly absent here; lands in Plans B/C. ✓

**Placeholder scan:** No TBD/TODO; every code/command step shows full content. ✓

**Type consistency:** `BoundaryEcho`/`BoundaryThrow` defined in Task 1 are used identically in Tasks 2–3; the function-pointer type `<void*,int,byte*,int,long,long,void>` is identical in `StreamStart` and `InvokeTraced`; the `dni_trace_cb` C typedef matches it; `Contain` returns status `-5` and the probe asserts `-5`. ✓

**Note:** the exports (`[UnmanagedCallersOnly]`) are verified for *compilation* here and for *runtime behavior* on-device in Plans B/C (they cannot be invoked from managed code). The probe covers the runtime behavior of the underlying `BoundaryDiagnostics` logic, which is where the real branching lives.
