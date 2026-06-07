# ONNX-on-Android Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ONNX Runtime run inside the NativeAOT `linux-bionic-arm64` image so the engine's `dni_search` returns real ranked results on the arm64 emulator, with the 86 MB model delivered to the device and the NNAPI EP (CPU fallback) enabled.

**Architecture:** Ship `libonnxruntime.so` from the ORT `.aar` into `jniLibs` (e_sqlcipher pattern; dynamic P/Invoke). Deliver the model: bundle it in the APK, extract to `filesDir/dni-assets/` on first run, and point the engine there via a new `dni_set_assets_dir` export (which also flips on NNAPI, Android-only). Prove via the real `dni_search`.

**Tech Stack:** .NET 10 NativeAOT, Microsoft.ML.OnnxRuntime 1.20.1, NDK; Kotlin/Compose host; Gradle; arm64 emulator on the Mac mini.

**Spec:** `docs/superpowers/specs/2026-06-07-onnx-android-gate-design.md`

---

## Conventions (read once)

**This cycle rebuilds `libdni.so`** (new `dni_set_assets_dir` export + `OnnxTextEncoder` change) — unlike SP1. The native build runs on the Mac.

**Mac overlay + build.** Edit on Windows, sync changed paths, build/test on the Mac:
```bash
# Sync (from repo root):
#   tar -cf - <paths...> | ssh steve-mac-mini "cd /Users/steve/dni-rag-build && tar -xf -"
# Remote prelude (android):
PRELUDE='export ANDROID_HOME=$HOME/Library/Android/sdk ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/27.2.12479018 JAVA_HOME=$HOME/Library/Java/jdk; export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH; GR=$HOME/toolchain/gradle-8.9/bin/gradle'
# .NET host build (compile-check C#):  cd /Users/steve/dni-rag-build && dotnet build -c Release
# Rebuild the gate .so + extract native .so's:  cd /Users/steve/dni-rag-build && export ANDROID_NDK_HOME=... CMAKE=$ANDROID_HOME/cmake/3.22.1/bin/cmake && bash build/build-android-so.sh
# APK compile:           $PRELUDE; cd /Users/steve/dni-rag-build/android && $GR --no-daemon :app:assembleDebug
# Instrumented test:     $PRELUDE; cd .../android && $GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.OnnxGateTest
```
zsh gotcha: never start an `echo` arg with `=`. Conventional commits, no AI attribution, branch `feat/onnx-android-gate` only. `-Xexplicit-api=strict` on the Kotlin module.

---

## File structure

```
core/DotnetNativeInterop.Engine/Ai/OnnxRuntimeConfig.cs        # NEW: static UseNnapi flag
core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs           # MODIFY: assets-dir override
core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs          # MODIFY: SessionOptions + NNAPI
core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ai.cs        # MODIFY: dni_set_assets_dir export
core/DotnetNativeInterop.NativeBridge/abi/dni.h                # MODIFY: declare dni_set_assets_dir
core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj  # MODIFY: bionic ORT restore ref
build/build-android-so.sh                                      # MODIFY: extract libonnxruntime.so
android/app/src/main/cpp/jni_bridge.c                          # MODIFY: nativeSetAssetsDir binding
android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt   # MODIFY: extern
android/app/build.gradle.kts                                   # MODIFY: copyAiAssets + noCompress onnx
android/app/src/main/kotlin/io/dotnetnativeinterop/AssetExtractor.kt           # NEW
android/app/src/main/kotlin/io/dotnetnativeinterop/DotnetNativeInteropApp.kt   # MODIFY: extract + setAssetsDir
.github/workflows/ci-android.yml                              # MODIFY: lfs: true
android/app/src/androidTest/kotlin/io/dotnetnativeinterop/OnnxGateTest.kt      # NEW
```

Untouched: all iOS ORT wiring (RID `StartsWith('ios')`), the existing transports, SP1 UI.

---

## Task 1: Engine — assets-dir override, NNAPI config, NNAPI session, dni_set_assets_dir export

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/OnnxRuntimeConfig.cs`
- Modify: `core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs`, `core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs`, `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ai.cs`, `core/DotnetNativeInterop.NativeBridge/abi/dni.h`

- [ ] **Step 1: Create `OnnxRuntimeConfig.cs`**

```csharp
namespace DotnetNativeInterop.Engine;

/// <summary>
/// Process-wide ONNX Runtime configuration, set by the platform host before the first inference.
/// </summary>
public static class OnnxRuntimeConfig
{
    /// <summary>
    /// When true, ORT sessions append the NNAPI execution provider (CPU fallback stays enabled).
    /// The Android host sets this via <c>dni_set_assets_dir</c>; it stays false on iOS, whose ORT
    /// framework has no NNAPI symbol.
    /// </summary>
    public static bool UseNnapi { get; set; }
}
```

- [ ] **Step 2: Modify `SemanticSearch.cs`** — replace the `ResolveAssetsDir()` method (lines 81-91) and add an override setter. Replace from the comment above `ResolveAssetsDir` through the method's closing brace with:

```csharp
    // Assets land in different layouts: the .NET build output preserves "Ai/assets/", while the iOS app
    // bundle copies the folder reference to "assets/" at the bundle root, and the Android host extracts
    // them to a filesDir path supplied via SetAssetsDirOverride. Probe the known spots for vocab.
    // Public so other engine components (e.g. the llama GGUF loader) resolve the same bundled-assets dir.
    private static string? _assetsDirOverride;

    /// <summary>Platform host (Android) points the engine at the extracted on-device assets dir.</summary>
    public static void SetAssetsDirOverride(string path) => _assetsDirOverride = path;

    public static string ResolveAssetsDir()
    {
        var ov = _assetsDirOverride;
        if (!string.IsNullOrEmpty(ov) && File.Exists(Path.Combine(ov, "vocab.txt")))
        {
            return ov;
        }

        var baseDir = AppContext.BaseDirectory;
        string[] candidates =
        [
            Path.Combine(baseDir, "Ai", "assets"),
            Path.Combine(baseDir, "assets"),
            baseDir,
        ];
        return Array.Find(candidates, c => File.Exists(Path.Combine(c, "vocab.txt"))) ?? candidates[0];
    }
```

- [ ] **Step 3: Modify `OnnxTextEncoder.cs`** — replace the constructor (lines 22-27) so the session is built through a helper that adds NNAPI when enabled:

```csharp
    public OnnxTextEncoder(string modelPath, WordPieceTokenizer tokenizer)
    {
        _session = CreateSession(modelPath);
        _tokenizer = tokenizer;
        _outputName = _session.OutputMetadata.Keys.First();
    }

    // NNAPI is appended only when the Android host enabled it (OnnxRuntimeConfig.UseNnapi). The CPU EP is
    // left enabled, so unsupported ops and the emulator's no-accelerator case fall back to CPU. iOS keeps
    // the plain CPU session (its ORT framework has no NNAPI symbol; appending would throw).
    private static OrtSession CreateSession(string modelPath)
    {
        if (!OnnxRuntimeConfig.UseNnapi)
        {
            return new OrtSession(modelPath);
        }

        try
        {
            var options = new SessionOptions();
            options.AppendExecutionProvider_Nnapi();   // default flags: NNAPI with CPU fallback
            return new OrtSession(modelPath, options);
        }
        catch (Exception)
        {
            return new OrtSession(modelPath);          // NNAPI unavailable -> CPU
        }
    }
```

(`SessionOptions` and `AppendExecutionProvider_Nnapi` are in `Microsoft.ML.OnnxRuntime`, already imported on line 1.)

- [ ] **Step 4: Modify `Exports.Ai.cs`** — add the export inside `ExportsAi` (after the `Search` method, before the closing brace):

```csharp
    /// <summary>
    /// Points the engine at an on-device assets dir (model.onnx/vocab.txt/corpus.txt/manuals) and enables
    /// the Android NNAPI EP. Call before the first dni_search/RAG. Returns 0 on success, -1 on a bad path.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_set_assets_dir")]
    public static unsafe int SetAssetsDir(byte* path)
    {
        try
        {
            var dir = NativeText.Read((nint)path);
            if (string.IsNullOrEmpty(dir) || !System.IO.File.Exists(System.IO.Path.Combine(dir, "vocab.txt")))
            {
                return -1;
            }

            SemanticSearch.SetAssetsDirOverride(dir);
            OnnxRuntimeConfig.UseNnapi = true;
            return 0;
        }
        catch (Exception)
        {
            return -1;
        }
    }
```

- [ ] **Step 5: Modify `abi/dni.h`** — add under the semantic-search section (after the `dni_search` declaration, line ~93):

```c
/* Point the engine at an on-device assets dir (model.onnx/vocab.txt/corpus.txt/manuals) and enable the
 * Android NNAPI execution provider. Call once before the first dni_search/RAG. 0 = ok, -1 = bad path. */
int32_t dni_set_assets_dir(const char* path);
```

- [ ] **Step 6: Verify the C# compiles (host build on the Mac)**

Sync the five files, then:
```
ssh steve-mac-mini 'zsh -lc "export JAVA_HOME=\$HOME/Library/Java/jdk; export PATH=\$JAVA_HOME/bin:\$PATH; cd /Users/steve/dni-rag-build; dotnet build core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj -c Release 2>&1 | tail -15"'
```
Expected: `Build succeeded`. (Host build resolves the managed ORT; it confirms `AppendExecutionProvider_Nnapi`, `SetAssetsDirOverride`, `OnnxRuntimeConfig`, and the new export all compile.)

- [ ] **Step 7: Commit**

```bash
git add core/DotnetNativeInterop.Engine/Ai/OnnxRuntimeConfig.cs core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Ai.cs core/DotnetNativeInterop.NativeBridge/abi/dni.h
git commit -m "feat(engine): assets-dir override + NNAPI session + dni_set_assets_dir export"
```

> If the host `dotnet build` can't append NNAPI because the managed wrapper gates `AppendExecutionProvider_Nnapi` behind the android TFM (compile error on Step 6), switch `CreateSession` to call the C API directly via a P/Invoke and report it as DONE_WITH_CONCERNS:
> ```csharp
> [System.Runtime.InteropServices.DllImport("onnxruntime")]
> private static extern nint OrtSessionOptionsAppendExecutionProvider_Nnapi(nint options, uint flags);
> ```
> ...applied to the raw `SessionOptions.Handle` with `flags: 0`. Only do this if the managed call fails to compile.

---

## Task 2: build-android-so.sh — extract libonnxruntime.so; csproj bionic ORT restore ref

**Files:**
- Modify: `build/build-android-so.sh`, `core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj`

- [ ] **Step 1: Add the ORT restore-only PackageReference** to the csproj, next to the bionic llama ItemGroup (so a clean CI cache has the `.aar`):

```xml
  <!-- ONNX Runtime native for Android: restored only so build-android-so.sh can extract
       jni/arm64-v8a/libonnxruntime.so from this package's .aar into jniLibs (dynamic .so, like
       e_sqlcipher). Contributes no build assets (the managed ORT comes from the Engine's reference). -->
  <ItemGroup Condition="$(RuntimeIdentifier.StartsWith('linux-bionic'))">
    <PackageReference Include="Microsoft.ML.OnnxRuntime" Version="1.20.1"
                      GeneratePathProperty="true" ExcludeAssets="all" PrivateAssets="all" />
  </ItemGroup>
```

- [ ] **Step 2: Add the extraction to `build-android-so.sh`** — after the `libe_sqlcipher.so` `echo "OK: ..."` line, add:

```bash
# ONNX Runtime (semantic search + RAG retrieval): ship libonnxruntime.so so the bionic image's dynamic
# P/Invoke ("onnxruntime") resolves at runtime. The .so lives inside the ORT android .aar (no loose .so).
ORT_AAR="$(ls "${HOME}"/.nuget/packages/microsoft.ml.onnxruntime/*/runtimes/android/native/onnxruntime.aar 2>/dev/null | sort -V | tail -1)"
[ -n "${ORT_AAR}" ] || { echo "ERROR: onnxruntime.aar not found in nuget cache (Microsoft.ML.OnnxRuntime)" >&2; exit 1; }
unzip -o -j "${ORT_AAR}" "jni/arm64-v8a/libonnxruntime.so" -d "${JNILIB_DIR}" >/dev/null
[ -f "${JNILIB_DIR}/libonnxruntime.so" ] || { echo "ERROR: libonnxruntime.so not extracted from ${ORT_AAR}" >&2; exit 1; }
echo "OK: ${JNILIB_DIR}/libonnxruntime.so ($(du -h "${JNILIB_DIR}/libonnxruntime.so" | cut -f1))"
```

- [ ] **Step 3: Commit** (verified together with the .so rebuild in Task 4)

```bash
git add build/build-android-so.sh core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj
git commit -m "build(android): ship libonnxruntime.so for bionic (extract from ORT .aar)"
```

---

## Task 3: JNI shim + Kotlin facade — nativeSetAssetsDir

**Files:**
- Modify: `android/app/src/main/cpp/jni_bridge.c`, `android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt`

- [ ] **Step 1: Add the handler to `jni_bridge.c`** — after `j_search` (before the `kMethods` table):

```c
/* AI: point the engine at the on-device assets dir + enable NNAPI (0 ok, -1 bad path). */
static jint j_set_assets_dir(JNIEnv* e, jobject o, jstring path) {
    (void)o; if (path == NULL) return -1;
    const char* p = (*e)->GetStringUTFChars(e, path, NULL);
    int32_t r = p ? dni_set_assets_dir(p) : -1;
    if (p) (*e)->ReleaseStringUTFChars(e, path, p);
    return r;
}
```

- [ ] **Step 2: Register it** — add to the `kMethods[]` table (after the `nativeSearch` entry):

```c
    {"nativeSetAssetsDir",   "(Ljava/lang/String;)I",                                                    (void*)j_set_assets_dir},
```

- [ ] **Step 3: Add the extern to `NativeBridge.kt`** — in the "Introspection + onboard AI" group, after `nativeSearch`:

```kotlin
    // Point the engine at the on-device assets dir (extracted model/vocab/corpus) + enable NNAPI. 0 = ok.
    public external fun nativeSetAssetsDir(path: String): Int
```

- [ ] **Step 4: Commit** (verified in Task 4)

```bash
git add android/app/src/main/cpp/jni_bridge.c android/app/src/main/kotlin/io/dotnetnativeinterop/transport/NativeBridge.kt
git commit -m "feat(android): bind nativeSetAssetsDir through the JNI shim"
```

---

## Task 4: Rebuild the gate .so + integration compile

**Files:** none (build only).

- [ ] **Step 1: Rebuild `libdni.so` + extract the native .so's** on the Mac:

```
ssh steve-mac-mini 'zsh -lc "export ANDROID_HOME=\$HOME/Library/Android/sdk ANDROID_NDK_HOME=\$HOME/Library/Android/sdk/ndk/27.2.12479018; export CMAKE=\$ANDROID_HOME/cmake/3.22.1/bin/cmake PATH=\$HOME/Library/Java/jdk/bin:\$PATH; cd /Users/steve/dni-rag-build; bash build/build-android-so.sh 2>&1 | tail -12"'
```
Expected: `OK: ...libdni.so`, `OK: ...libe_sqlcipher.so`, `OK: ...libonnxruntime.so`.

- [ ] **Step 2: Verify the new export + the ORT .so**

```
ssh steve-mac-mini 'zsh -lc "NDK=\$HOME/Library/Android/sdk/ndk/27.2.12479018; READELF=\$(ls \$NDK/toolchains/llvm/prebuilt/*/bin/llvm-readelf|head -1); SO=/Users/steve/dni-rag-build/android/app/src/main/jniLibs/arm64-v8a; \$READELF --dyn-syms \$SO/libdni.so | grep dni_set_assets_dir; ls -la \$SO/libonnxruntime.so"'
```
Expected: a `dni_set_assets_dir` GLOBAL FUNC line; `libonnxruntime.so` present (~17 MB).

- [ ] **Step 3: APK compiles (JNI shim + Kotlin facade)** — sync nothing new; force-clean cxx so the shim relinks against the new libdni.so:
```
ssh steve-mac-mini 'zsh -lc "export ANDROID_HOME=\$HOME/Library/Android/sdk ANDROID_NDK_HOME=\$HOME/Library/Android/sdk/ndk/27.2.12479018 JAVA_HOME=\$HOME/Library/Java/jdk; export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$PATH; GR=\$HOME/toolchain/gradle-8.9/bin/gradle; cd /Users/steve/dni-rag-build/android; rm -rf app/.cxx app/build/intermediates/cxx; \$GR --no-daemon :app:assembleDebug 2>&1 | tail -15"'
```
Expected: `BUILD SUCCESSFUL`.

(No commit — artifacts are gitignored; the script/source commits were Tasks 2-3.)

---

## Task 5: Gradle — bundle the model assets

**Files:**
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add the copy task + don't compress the model** — after the existing `copyProto` block, and add `androidResources` inside the `android { }` block:

```kotlin
// (inside android { } block)
    androidResources {
        noCompress += "onnx"   // store model.onnx uncompressed so ORT can mmap it from the extracted copy
    }
```
```kotlin
// (top level, near copyPatternsJson/copyProto)
tasks.register<Copy>("copyAiAssets") {
    from("${rootDir.parentFile.absolutePath}/core/DotnetNativeInterop.Engine/Ai/assets") {
        include("model.onnx", "vocab.txt", "corpus.txt", "manuals/**")
    }
    into("${projectDir}/src/main/assets/dni-assets")
}
tasks.named("preBuild") { dependsOn("copyAiAssets") }
```

- [ ] **Step 2: Verify the assets stage** (the model is Git-LFS; the Mac snapshot has it):
```
ssh steve-mac-mini 'zsh -lc "export ANDROID_HOME=\$HOME/Library/Android/sdk JAVA_HOME=\$HOME/Library/Java/jdk; export PATH=\$JAVA_HOME/bin:\$PATH; GR=\$HOME/toolchain/gradle-8.9/bin/gradle; cd /Users/steve/dni-rag-build/android; \$GR --no-daemon :app:copyAiAssets 2>&1 | tail -4; ls -lah app/src/main/assets/dni-assets/"'
```
Expected: `model.onnx` (~86M), `vocab.txt`, `corpus.txt`, `manuals/` present.

- [ ] **Step 3: Commit** (the staged assets live under gitignored `src/main/jniLibs`? No — assets are NOT gitignored; but model.onnx is 86 MB and is the LFS-tracked source copied from the engine. Do NOT commit the copied 86 MB into android/ — it's a build-staged duplicate. Add `android/app/src/main/assets/dni-assets/` to `.gitignore`, then commit only the gradle change.)

```bash
echo "android/app/src/main/assets/dni-assets/" >> .gitignore
git add android/app/build.gradle.kts .gitignore
git commit -m "build(android): stage AI model assets into the APK (copyAiAssets, noCompress onnx)"
```

---

## Task 6: Kotlin AssetExtractor + app wiring

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/AssetExtractor.kt`
- Modify: `android/app/src/main/kotlin/io/dotnetnativeinterop/DotnetNativeInteropApp.kt`

- [ ] **Step 1: Create `AssetExtractor.kt`**

```kotlin
package io.dotnetnativeinterop

import android.content.Context
import java.io.File

/** Copies bundled AI assets (dni-assets/) from the APK to filesDir so ORT can open them by path. */
internal object AssetExtractor {
    private const val DIR = "dni-assets"
    private val ROOT_FILES = listOf("model.onnx", "vocab.txt", "corpus.txt")

    /** Idempotent extraction (skips files that already exist non-empty). Returns the destination dir. */
    public fun ensure(context: Context): File {
        val out = File(context.filesDir, DIR).apply { mkdirs() }
        ROOT_FILES.forEach { name -> copyIfMissing(context, "$DIR/$name", File(out, name)) }
        val manualsOut = File(out, "manuals").apply { mkdirs() }
        context.assets.list("$DIR/manuals")?.forEach { m ->
            copyIfMissing(context, "$DIR/manuals/$m", File(manualsOut, m))
        }
        return out
    }

    private fun copyIfMissing(context: Context, assetPath: String, dest: File) {
        if (dest.exists() && dest.length() > 0L) return
        context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }
}
```

- [ ] **Step 2: Wire it into `DotnetNativeInteropApp.kt`** — replace the engine-init coroutine block (the `appScope.launch { ... }` inside `loadNativeLibraries`, lines 50-61) with one that extracts assets and sets the dir before init:

```kotlin
        // Initialize the engine on a background coroutine — never block the main thread.
        appScope.launch {
            runCatching {
                val assetsDir = AssetExtractor.ensure(this@DotnetNativeInteropApp)
                val setRc = NativeBridge.nativeSetAssetsDir(assetsDir.absolutePath)
                Log.i(TAG, "assets dir set rc=$setRc (${assetsDir.absolutePath})")
                val status = NativeBridge.nativeInitialize()
                if (status == 0) {
                    Log.i(TAG, "Engine initialized successfully")
                } else {
                    Log.e(TAG, "Engine initialization failed: status $status")
                }
            }.onFailure {
                Log.e(TAG, "engine init / asset extraction threw", it)
            }
        }
```

- [ ] **Step 3: Verify it compiles** — sync both files:
```
ssh steve-mac-mini 'zsh -lc "export ANDROID_HOME=\$HOME/Library/Android/sdk JAVA_HOME=\$HOME/Library/Java/jdk; export PATH=\$JAVA_HOME/bin:\$PATH; GR=\$HOME/toolchain/gradle-8.9/bin/gradle; cd /Users/steve/dni-rag-build/android; \$GR --no-daemon :app:assembleDebug 2>&1 | tail -12"'
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/AssetExtractor.kt android/app/src/main/kotlin/io/dotnetnativeinterop/DotnetNativeInteropApp.kt
git commit -m "feat(android): extract AI assets to filesDir + set engine assets dir before init"
```

---

## Task 7: CI — fetch Git-LFS for ci-android

**Files:**
- Modify: `.github/workflows/ci-android.yml`

- [ ] **Step 1: Add `lfs: true` to the checkout** — change the `actions/checkout@v4` step to:

```yaml
      - name: Checkout
        uses: actions/checkout@v4
        with:
          lfs: true   # the AI model (model.onnx) is Git-LFS; copyAiAssets needs the real file
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci-android.yml
git commit -m "ci(android): fetch Git-LFS so the AI model is present for the APK build"
```

---

## Task 8: Instrumented proof — OnnxGateTest (real dni_search on the emulator)

**Files:**
- Create: `android/app/src/androidTest/kotlin/io/dotnetnativeinterop/OnnxGateTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package io.dotnetnativeinterop

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dotnetnativeinterop.transport.NativeBridge
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class OnnxGateTest {

    public companion object {
        @JvmStatic
        @BeforeClass
        public fun setUp() {
            System.loadLibrary("dni")
            System.loadLibrary("dni_jni")
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            val dir = AssetExtractor.ensure(app)
            check(NativeBridge.nativeSetAssetsDir(dir.absolutePath) == 0) { "set assets dir failed" }
            check(NativeBridge.nativeInitialize() == 0) { "init failed" }
        }
    }

    @Test
    public fun semanticSearchReturnsRankedResults() {
        val json = requireNotNull(NativeBridge.nativeSearch("how do I reset the device", "facts")) {
            "nativeSearch returned null"
        }
        val arr = JSONArray(json)
        assertTrue("results non-empty (json=$json)", arr.length() > 0)
        // scores descending
        var prev = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val score = arr.getJSONObject(i).getDouble("score")
            assertTrue("scores descending", score <= prev)
            prev = score
        }
        android.util.Log.i("OnnxGate", "PASS: dni_search -> ${arr.length()} ranked results; top=${arr.getJSONObject(0)}")
    }
}
```

- [ ] **Step 2: Ensure the emulator is up, then run** (sync the test first):
```
cd "C:/Users/steve/projects/dotnet-ios-android-poc-native-frontend" && tar -cf - android/app/src/androidTest/kotlin/io/dotnetnativeinterop/OnnxGateTest.kt | ssh steve-mac-mini "cd /Users/steve/dni-rag-build && tar -xf - && echo SYNCED"
```
```
ssh steve-mac-mini 'zsh -lc "export ANDROID_HOME=\$HOME/Library/Android/sdk JAVA_HOME=\$HOME/Library/Java/jdk; export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH; GR=\$HOME/toolchain/gradle-8.9/bin/gradle; cd /Users/steve/dni-rag-build/android; adb devices | grep -q emulator || (nohup emulator -avd dni_arm64 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect >/tmp/emu.log 2>&1 &); adb wait-for-device; adb logcat -c; \$GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.OnnxGateTest 2>&1 | tail -25; echo LOGCAT:; adb logcat -d -s OnnxGate:I | tail -5"'
```
Expected: `BUILD SUCCESSFUL`, 1 test passed, logcat `PASS: dni_search -> N ranked results`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/androidTest/kotlin/io/dotnetnativeinterop/OnnxGateTest.kt
git commit -m "test(android): ONNX gate — real dni_search returns ranked results on device"
```

---

## Task 9: PR

- [ ] **Step 1: Push + open the PR**
```bash
git push -u origin feat/onnx-android-gate
gh pr create --base main --title "ONNX-on-Android gate: dni_search runs on device (NNAPI + CPU fallback)" --body "<summary: libonnxruntime.so shipped; model bundled+extracted; dni_set_assets_dir; NNAPI w/ CPU fallback; real dni_search proven on emulator. Out of scope: AI tab UI, Manuals/EVS. Note: NNAPI on the emulator falls back to CPU.>"
```
- [ ] **Step 2: Watch `ci-android`** (build-only; LFS now fetched). The instrumented gate runs locally on the emulator.

---

## Self-Review (against the spec)

- **Spec coverage:** native link (Task 2, verified Task 4); bionic restore ref (Task 2); `dni_set_assets_dir` export + dni.h (Task 1) + JNI/Kotlin (Task 3); SemanticSearch override (Task 1); NNAPI w/ CPU fallback gated Android (Task 1, via `OnnxRuntimeConfig.UseNnapi` set by the export); model bundling (Task 5); Kotlin extraction + wiring (Task 6); ci-android lfs (Task 7); real-`dni_search` proof (Task 8). ✔
- **Type consistency:** `OnnxRuntimeConfig.UseNnapi`, `SemanticSearch.SetAssetsDirOverride`, `dni_set_assets_dir`/`nativeSetAssetsDir`, `AssetExtractor.ensure` used consistently across tasks. The export both sets the assets dir AND flips `UseNnapi=true` (deterministic Android gating — no reliance on `OperatingSystem.IsAndroid()`).
- **Placeholder scan:** the only conditional is the Task 1 fallback note (managed NNAPI compile failure → C-API P/Invoke), gated on an observed build error, not a TODO.
