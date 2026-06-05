# NativeAOT-on-mobile caveats & Phase 2 rename checklist

Research-backed (2026-06-05) unknowns for this repo's .NET 10 NativeAOT library on iOS/Android. Sources
are Microsoft Learn + `dotnet/runtime` issues (key ones linked inline).

## Part A — BCL/runtime APIs under NativeAOT (used by the showcase demos)

| API | Status on iOS/Android NativeAOT | Action taken / required |
|-----|----------------------------------|--------------------------|
| `AesGcm` | **Was `PlatformNotSupportedException` ≤ .NET 8**; works in **.NET 9+** via Apple **CryptoKit**. CryptoKit allows a **16-byte tag only** (12–15 → `ArgumentException`). Needs **Xcode 16+** (a .NET 9.0.13 Swift shim broke the classic linker, runtime #124609). | We use a 16-byte tag and a **`AesGcm.IsSupported` guard** so it fails *visibly*. Mac has Xcode 26.5.1. ✅ |
| `TensorPrimitives` (`System.Numerics.Tensors`) | **Clean** — purely managed, static `AdvSimd` dispatch, ships `IsAotCompatible`. | Just add the package. Verified it AOT-publishes for `ios-arm64`. ✅ |
| `BrotliStream` / `DeflateStream` | **Managed** implementation on mobile — **no** `e_brotli`/`e_zlib` native dep (unlike the `e_sqlite3` gap). | Nothing special. ✅ |
| `System.Text.Json` source-gen | **Safe**. Breaks only if a converter factory calls `Type.MakeGenericType()`, or if `IlcDisableReflection` is set. | We use `JsonSerializerContext`. ✅ |
| `[GeneratedRegex]`, `System.Threading.Channels` | **Unconditionally safe** under AOT. | ✅ |
| `InvariantGlobalization=true` | Kills `new CultureInfo("en-US")` (`CultureNotFoundException`); `ToUpper/ToLower` become ASCII-only; locale-specific `DateTime.Parse` breaks. **`TimeZoneInfo` with IANA IDs still works** (iOS/Android ship their own TZDB, independent of ICU). | All demos use `InvariantCulture` and avoid named cultures. ✅ (a "format across cultures" demo is intentionally excluded) |

Net: every shipped demo is AOT-safe; AES-GCM was the only real risk and is guarded. **On-device run is
still the final proof** (the framework now AOT-compiles for `ios-arm64`).

## Part B — `OnDeviceLlm` → `DotnetNativeInterop` rename checklist (completed 2026-06-05)

The SDK does **not** auto-propagate renames (runtime #100747 is "Future"), so each of these was done manually
and had to move **atomically** (C# namespace → `DotnetNativeInterop`; native lib/symbol/header token → `dni`;
reverse-DNS → `com.`/`io.dotnetnativeinterop`):

1. **`[UnmanagedCallersOnly]` EntryPoint symbols** (`ondevicellm_*` → `dni_*`): change the C# attribute
   strings **and** `abi/dni.h` (was `ondevicellm.h`) **and** the Swift bridging header **and** the xcodegen
   project in one pass — a mismatch is a link-time `Undefined symbol`.
2. **`.xcframework` rename** (`ondevicellm` → `dni`): `FRAMEWORK_SEARCH_PATHS` alone does **not** resolve
   `.xcframework` slices — the renamed bundle must be re-referenced in the (generated) project. Our
   `build-ios-framework*.sh` sets `install_name` + `CFBundleExecutable`; both carry the framework name and
   must change together.
3. **iOS bundle ID** (`com.ondevicellm.*` → `com.dotnetnativeinterop.*`): renaming **invalidates provisioning
   profiles + entitlements** — regenerate in the Apple Developer Portal (or let `-allowProvisioningUpdates`
   re-issue). Note: the renamed app installs **alongside** the old one on the device (different bundle id).
4. **Android `applicationId` + Kotlin package** (`io.ondevicellm` → `io.dotnetnativeinterop`): a package rename normally breaks JNI
   **static** name resolution (`Java_<pkg>_<class>_<method>`). **This repo already uses `JNI_OnLoad` +
   `RegisterNatives`** (see README threading model), which decouples C symbol names from the package — so the
   Android rename is lower-risk here. Still update `applicationId`, the package dirs, and the proto package.

Sources: AesGcm — runtime #91523, #124609; MS Learn cross-platform-cryptography & aesgcm-auth-tag-size.
TensorPrimitives — nuget System.Numerics.Tensors. Globalization — MS Learn runtime-config/globalization.
Rename — runtime #100747; Android NDK JNI tips (`RegisterNatives`); Apple "renaming Xcode project".
