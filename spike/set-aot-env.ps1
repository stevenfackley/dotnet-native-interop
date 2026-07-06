# Sets up the local win-x64 NativeAOT link environment for this box.
# See docs/onnx-nativeaot-ios-findings.md "Toolchain potholes" for why this is needed:
# VS 2026 (v18) prerelease auto-detection (findvcvarsall.bat / VsDevCmd.bat) can't resolve a usable
# MSVC toolset here, so ILC is handed the linker directly via IlcUseEnvironmentalTools=true.
# Usage: . .\spike\set-aot-env.ps1   (dot-source, then run dotnet publish with -p:IlcUseEnvironmentalTools=true)

$msvc = "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.42.34433"
$sdk  = "C:\Program Files (x86)\Windows Kits\10"
$sdkv = "10.0.22621.0"
$env:PATH    = "$msvc\bin\Hostx64\x64;$env:PATH"
$env:LIB     = "$msvc\lib\x64;$sdk\Lib\$sdkv\um\x64;$sdk\Lib\$sdkv\ucrt\x64"
$env:INCLUDE = "$msvc\include;$sdk\Include\$sdkv\ucrt;$sdk\Include\$sdkv\shared;$sdk\Include\$sdkv\um"
