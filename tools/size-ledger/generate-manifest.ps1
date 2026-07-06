#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Regenerates docs/size-ledger.json — the committed binary-size cost ledger for this NativeAOT
  mobile-interop POC.

.DESCRIPTION
  Measures whatever is actually measurable on the box this runs on, and merges it with a small set
  of hand-entered rows for numbers that are NOT reproducible here (mobile-RID native libs need a
  Mac/CI build; see docs/binary-size-ledger-findings.md for the full narrative).

  Measured (when the artifacts exist):
    - win-x64 NativeAOT publish of core/DotnetNativeInterop.NativeBridge (dni.dll + its
      side-by-side native DLLs: onnxruntime.dll, onnxruntime_providers_shared.dll, e_sqlcipher.dll)
    - FP32 model.onnx (core/DotnetNativeInterop.Engine/Ai/assets/model.onnx) — only if the real LFS
      bytes are checked out (not a pointer stub)
    - INT8 model.int8.onnx (tools/int8-quant/model.int8.onnx) — only if regenerated locally
      (gitignored artifact; see tools/int8-quant/quantize.py)
    - onnxruntime.xcframework ios-arm64 / ios-arm64_x86_64-simulator slices (ios/Frameworks) — only
      if the real LFS bytes are checked out
    - any *.gguf under the repo (there normally isn't one — the Llama GGUF is fetched at build time,
      never committed)

  Documented (hand-entered, NOT reproducible on this box — no Mac, no NDK, no C/C++ toolchain here):
    - iOS ios-arm64 dni.dylib, before/after ONNX static-link (docs/onnx-nativeaot-ios-findings.md,
      2026-06-05 — predates the llama.cpp/BouncyCastle/Google.Protobuf additions, see the findings
      doc's "stale" caveat)
    - Android linux-bionic-arm64 libdni.so — no byte size was ever recorded in
      docs/nativeaot-android-gate-findings.md; marked pending
    - Llama-3.2-1B-Instruct Q4_K_M GGUF — approximate size from docs/llama-nativeaot-ios-findings.md;
      never committed, never independently measured

.PARAMETER Publish
  If set, runs the win-x64 NativeAOT publish first (sourcing spike/set-aot-env.ps1 for the MSVC
  toolchain workaround). Without this switch, the script only measures whatever publish output
  already exists and notes what's missing.

.EXAMPLE
  pwsh tools/size-ledger/generate-manifest.ps1 -Publish
#>
[CmdletBinding()]
param(
    [switch]$Publish
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repoRoot

function Get-HumanSize([long]$Bytes) {
    return "{0:N2} MiB" -f ($Bytes / 1MB)
}

function Test-IsLfsPointer([string]$Path) {
    if (-not (Test-Path $Path)) { return $true }
    try {
        $first = Get-Content -Path $Path -TotalCount 1 -ErrorAction Stop
        return ($first -like 'version https://git-lfs.github.com/spec/*')
    } catch {
        return $true
    }
}

$rows = New-Object System.Collections.Generic.List[object]

function Add-Row {
    param(
        [string]$Id, [string]$Component, [string]$Category, [string]$Platform,
        [Nullable[long]]$Bytes, [string]$Status, [string]$Source, [string]$AsOf, [string]$Note
    )
    $rows.Add([ordered]@{
        id         = $Id
        component  = $Component
        category   = $Category
        platform   = $Platform
        bytes      = $Bytes
        humanSize  = if ($Bytes) { Get-HumanSize $Bytes } else { $null }
        status     = $Status
        source     = $Source
        asOf       = if ([string]::IsNullOrEmpty($AsOf)) { $null } else { $AsOf }
        note       = $Note
    }) | Out-Null
}

# ---------------------------------------------------------------------------
# 1. win-x64 NativeAOT publish of the real NativeBridge (measured, if present)
# ---------------------------------------------------------------------------
$nativeBridgeProj = 'core/DotnetNativeInterop.NativeBridge'
$publishDir = Join-Path $nativeBridgeProj 'bin/Release/net10.0/win-x64/publish'

if ($Publish) {
    Write-Host "Publishing $nativeBridgeProj -r win-x64 -c Release (NativeAOT)..." -ForegroundColor Cyan
    . (Join-Path $repoRoot 'spike/set-aot-env.ps1')
    dotnet publish $nativeBridgeProj -r win-x64 -c Release /p:PublishAot=true /p:IlcUseEnvironmentalTools=true
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed with exit code $LASTEXITCODE" }
}

$winFiles = @(
    @{ Id = 'dni-dll-win-x64';                Component = 'dni.dll (win-x64 NativeAOT)';                 Category = 'native-lib'; File = 'dni.dll' },
    @{ Id = 'onnxruntime-dll-win-x64';        Component = 'onnxruntime.dll (win-x64 side-by-side)';       Category = 'framework';  File = 'onnxruntime.dll' },
    @{ Id = 'onnxruntime-providers-shared-win-x64'; Component = 'onnxruntime_providers_shared.dll (win-x64)'; Category = 'framework'; File = 'onnxruntime_providers_shared.dll' },
    @{ Id = 'e-sqlcipher-dll-win-x64';        Component = 'e_sqlcipher.dll (win-x64 side-by-side)';       Category = 'framework';  File = 'e_sqlcipher.dll' }
)

foreach ($wf in $winFiles) {
    $path = Join-Path $publishDir $wf.File
    if (Test-Path $path) {
        $bytes = (Get-Item $path).Length
        Add-Row -Id $wf.Id -Component $wf.Component -Category $wf.Category -Platform 'win-x64' `
            -Bytes $bytes -Status 'measured' -Source 'measured-win-x64' `
            -AsOf (Get-Date -Format 'yyyy-MM-dd') `
            -Note 'From dotnet publish core/DotnetNativeInterop.NativeBridge -r win-x64 -c Release /p:PublishAot=true /p:IlcUseEnvironmentalTools=true. Windows links ONNX/SQLCipher as side-by-side DLLs (dynamic), NOT statically into dni.dll — unlike iOS/Android, which bake them into one dylib/so. See findings doc for why this is not apples-to-apples with the mobile RIDs.'
    } else {
        Add-Row -Id $wf.Id -Component $wf.Component -Category $wf.Category -Platform 'win-x64' `
            -Bytes $null -Status 'pending' -Source 'pending-local-publish' -AsOf $null `
            -Note "Not found at $path. Run with -Publish, or: dotnet publish $nativeBridgeProj -r win-x64 -c Release /p:PublishAot=true /p:IlcUseEnvironmentalTools=true"
    }
}

# ---------------------------------------------------------------------------
# 2. Model assets (measured only if real LFS bytes are checked out, not pointers)
# ---------------------------------------------------------------------------
$fp32Path = 'core/DotnetNativeInterop.Engine/Ai/assets/model.onnx'
if (-not (Test-IsLfsPointer $fp32Path)) {
    $bytes = (Get-Item $fp32Path).Length
    Add-Row -Id 'model-onnx-fp32' -Component 'model.onnx (all-MiniLM-L6-v2, FP32)' -Category 'model' -Platform 'n/a' `
        -Bytes $bytes -Status 'measured' -Source 'lfs' -AsOf (Get-Date -Format 'yyyy-MM-dd') `
        -Note 'Real Git LFS bytes checked out at core/DotnetNativeInterop.Engine/Ai/assets/model.onnx. Embedded at launch by the in-engine NativeAOT AI tab and used as the EVS publisher''s source model.'
} else {
    Add-Row -Id 'model-onnx-fp32' -Component 'model.onnx (all-MiniLM-L6-v2, FP32)' -Category 'model' -Platform 'n/a' `
        -Bytes 90405214 -Status 'documented' -Source 'documented-from-int8-minilm-quant-findings.md' -AsOf '2026-07-06' `
        -Note "LFS pointer only in this checkout (git lfs pull to get real bytes). Falling back to the last-recorded exact size from docs/int8-minilm-quant-findings.md."
}

$int8Path = 'tools/int8-quant/model.int8.onnx'
if (Test-Path $int8Path) {
    $bytes = (Get-Item $int8Path).Length
    Add-Row -Id 'model-onnx-int8' -Component 'model.int8.onnx (all-MiniLM-L6-v2, INT8 dynamic quant)' -Category 'model' -Platform 'n/a' `
        -Bytes $bytes -Status 'measured' -Source 'measured-local-regen' -AsOf (Get-Date -Format 'yyyy-MM-dd') `
        -Note 'Regenerated locally via tools/int8-quant/quantize.py (gitignored artifact, not committed).'
} else {
    Add-Row -Id 'model-onnx-int8' -Component 'model.int8.onnx (all-MiniLM-L6-v2, INT8 dynamic quant)' -Category 'model' -Platform 'n/a' `
        -Bytes 22903734 -Status 'documented' -Source 'documented-from-int8-minilm-quant-findings.md' -AsOf '2026-07-06' `
        -Note 'Not regenerated for this run (gitignored, build-time artifact — see tools/int8-quant/quantize.py to reproduce). Exact value from docs/int8-minilm-quant-findings.md: 90,405,214 -> 22,903,734 bytes, 3.95x smaller. NOT yet wired into either shipping path (EVS publisher or the in-engine AI tab) as of this ledger.'
}

# ---------------------------------------------------------------------------
# 3. onnxruntime.xcframework slices (measured only if real LFS bytes are checked out)
# ---------------------------------------------------------------------------
$xcSlices = @(
    @{ Id = 'onnxruntime-xcframework-ios-arm64';               Platform = 'ios-arm64';               Rel = 'ios/Frameworks/onnxruntime.xcframework/ios-arm64/onnxruntime.framework/onnxruntime' },
    @{ Id = 'onnxruntime-xcframework-iossimulator-arm64-x86_64'; Platform = 'ios-arm64_x86_64-simulator'; Rel = 'ios/Frameworks/onnxruntime.xcframework/ios-arm64_x86_64-simulator/onnxruntime.framework/onnxruntime' }
)
foreach ($slice in $xcSlices) {
    if (-not (Test-IsLfsPointer $slice.Rel)) {
        $bytes = (Get-Item $slice.Rel).Length
        Add-Row -Id $slice.Id -Component "onnxruntime.xcframework/$($slice.Platform) slice (Swift-side EVS Core ML path)" -Category 'framework' -Platform $slice.Platform `
            -Bytes $bytes -Status 'measured' -Source 'lfs' -AsOf (Get-Date -Format 'yyyy-MM-dd') `
            -Note 'Real Git LFS bytes checked out. This is the Swift-side ONNX Runtime + Core ML EP framework used by the EVS (Manuals) tab, vendored under ios/Frameworks — a SEPARATE copy from the ONNX Runtime NuGet package the .NET engine statically links into dni.dylib (Phase 3 AI tab). Both ship in the same app.'
    } else {
        Add-Row -Id $slice.Id -Component "onnxruntime.xcframework/$($slice.Platform) slice (Swift-side EVS Core ML path)" -Category 'framework' -Platform $slice.Platform `
            -Bytes $null -Status 'pending' -Source 'pending-lfs-pull' -AsOf $null `
            -Note "LFS pointer only in this checkout. Run: git lfs pull -I `"$($slice.Rel)`""
    }
}

# ---------------------------------------------------------------------------
# 4. GGUF (never committed — fetched at build time; measure if one happens to be present)
# ---------------------------------------------------------------------------
$ggufFiles = @(Get-ChildItem -Path $repoRoot -Recurse -Filter '*.gguf' -ErrorAction SilentlyContinue)
if ($ggufFiles.Count -gt 0) {
    foreach ($g in $ggufFiles) {
        Add-Row -Id "gguf-$($g.BaseName)" -Component "$($g.Name) (Llama GGUF, on disk)" -Category 'gguf' -Platform 'n/a' `
            -Bytes $g.Length -Status 'measured' -Source 'measured-local-disk' -AsOf (Get-Date -Format 'yyyy-MM-dd') `
            -Note "Found on disk at $($g.FullName.Substring($repoRoot.Length)). Not Git-tracked bytes (the *.gguf LFS pattern in .gitattributes has no matching committed file) -- this is whatever a prior manual build-time fetch left behind."
    }
} else {
    Add-Row -Id 'gguf-llama-3.2-1b-instruct-q4' -Component 'Llama-3.2-1B-Instruct, Q4_K_M GGUF' -Category 'gguf' -Platform 'n/a' `
        -Bytes $null -Status 'documented' -Source 'documented-from-llama-nativeaot-ios-findings.md' -AsOf '2026-06-06' `
        -Note 'No GGUF present in this checkout or committed to Git (the *.gguf LFS pattern exists in .gitattributes but no file is tracked -- it is fetched at build time onto the build host and bundled into Ai/assets/, never committed; see docs/llama-nativeaot-ios-findings.md "Asset shipping note" analog). Doc states "~0.77 GB" for Llama-3.2-1B-Instruct Q4_K_M -- approximate, never independently byte-measured in this repo. Powers on-device RAG generation on iOS; Android RAG is still extractive (GGUF bundling pending per README).'
}

# ---------------------------------------------------------------------------
# 5. Mobile-RID native libs — documented / pending (need a Mac + Android NDK; not available here)
# ---------------------------------------------------------------------------
Add-Row -Id 'dni-dylib-ios-arm64-pre-onnx' -Component 'dni.dylib (ios-arm64, before ONNX static-link)' -Category 'native-lib' -Platform 'ios-arm64' `
    -Bytes $null -Status 'documented' -Source 'documented-from-onnx-nativeaot-ios-findings.md' -AsOf '2026-06-05' `
    -Note 'Approx. 6.9 MB per docs/onnx-nativeaot-ios-findings.md -- the doc gives only a rounded figure, no exact byte count, so bytes is left null here rather than fabricating precision. SQLCipher was ALREADY statically linked at this point; only ONNX was added next. STALE: predates llama.cpp (added the next day, 2026-06-06), BouncyCastle + Google.Protobuf (added 2026-07-05). Needs a fresh ios-arm64 build to reflect the current dylib.'

Add-Row -Id 'dni-dylib-ios-arm64-with-onnx-historical' -Component 'dni.dylib (ios-arm64, with ONNX static-linked)' -Category 'native-lib' -Platform 'ios-arm64' `
    -Bytes $null -Status 'documented' -Source 'documented-from-onnx-nativeaot-ios-findings.md' -AsOf '2026-06-05' `
    -Note 'Approx. 23 MB (+17 MB __TEXT for the statically-linked onnxruntime.xcframework archive) per docs/onnx-nativeaot-ios-findings.md -- rounded figure only, no exact byte count given, so bytes is left null rather than fabricating precision. STALE, same caveat as the row above: this is the LAST KNOWN real ios-arm64 dylib size, from BEFORE llama.cpp/ggml static libs, BouncyCastle, and Google.Protobuf were added to the same dylib. The current ios-arm64 dni.dylib is unmeasured and almost certainly larger. PENDING a fresh Mac build to get a current, exact number.'

Add-Row -Id 'libdni-so-android-arm64' -Component 'libdni.so (linux-bionic-arm64, full native gate: NativeAOT + SQLCipher client + llama.cpp statically linked)' -Category 'native-lib' -Platform 'linux-bionic-arm64' `
    -Bytes $null -Status 'pending' -Source 'pending-mac-ci' -AsOf $null `
    -Note 'NEVER MEASURED. docs/nativeaot-android-gate-findings.md proves the SP0 native gate links and runs on-device (NativeAOT + SQLCipher + llama.cpp -> libdni.so) but records no byte size anywhere. Unlike iOS, Android links llama.cpp/ggml statically into libdni.so but ships ONNX Runtime and SQLCipher as SEPARATE dynamic .so files in jniLibs/ (extracted from their .aar packages by build-android-so.sh) -- so libdni.so''s composition differs from the iOS dylib''s (which bakes in ONNX+SQLCipher+llama all together). Needs the NDK + a build-android-so.sh run (Mac mini or macos-latest CI runner) to measure. This Windows box has no C/C++ toolchain (no cmake, no clang) -- confirmed absent, see docs/llama-nativeaot-ios-findings.md.'

# ---------------------------------------------------------------------------
# Write the manifest
# ---------------------------------------------------------------------------
$manifest = [ordered]@{
    '$schema'    = 'https://json-schema.org/draft/2020-12/schema'
    description  = 'Binary-size cost ledger for the .NET 10 NativeAOT mobile-interop POC. Regenerate with tools/size-ledger/generate-manifest.ps1 -Publish. See docs/binary-size-ledger-findings.md for the narrative.'
    generatedAt  = (Get-Date -Format 'o')
    generator    = 'tools/size-ledger/generate-manifest.ps1'
    schemaNotes  = 'category: native-lib | model | framework | gguf. status: measured (this run measured real bytes) | documented (hand-entered from a findings doc, not reproducible here) | pending (needs Mac/CI or an LFS pull; bytes is null). source labels the origin precisely -- measured-win-x64, lfs (real Git LFS bytes on disk), measured-local-regen/measured-local-disk (a local build-time artifact), or documented-from-<file>.md.'
    components   = $rows
}

$outPath = 'docs/size-ledger.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $outPath -Encoding utf8

Write-Host ""
Write-Host "Wrote $outPath ($($rows.Count) rows)" -ForegroundColor Green
$rows | ForEach-Object {
    $sizeStr = if ($_.bytes) { $_.humanSize } elseif ($_.status -eq 'documented') { '(approx, see note)' } else { '(pending)' }
    Write-Host ("  [{0,-11}] {1,-70} {2,20}  ({3})" -f $_.status, $_.component, $sizeStr, $_.source)
}
