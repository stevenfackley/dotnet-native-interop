# Cross-language PQ proof driver (Wave B Plan C wire-compat audit residual).
#   .NET (BouncyCastle.Cryptography 2.6.2)  --public material-->  Java (bcprov-jdk18on 1.84)
#   Java verifies the ML-DSA-65 signature + encapsulates ML-KEM-768  --ciphertext-->  .NET decapsulates
#   PASS iff the signature verifies cross-language AND the two shared secrets are byte-identical.
#
# Usage: pwsh spike/PqcCrossLang/run.ps1  [-BcprovJar <path>]
[CmdletBinding()]
param(
    [string]$BcprovJar = "C:\Users\steve\scoop\apps\gradle\current\.gradle\caches\modules-2\files-2.1\org.bouncycastle\bcprov-jdk18on\1.84\2d5651789941d2f8ae9b8771f23356de6b61e96b\bcprov-jdk18on-1.84.jar"
)

$ErrorActionPreference = "Stop"
$dir = $PSScriptRoot
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { throw "JAVA_HOME is not set" }
$javac = Join-Path $javaHome "bin\javac.exe"
$java = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path $BcprovJar)) { throw "bcprov jar not found: $BcprovJar" }

$work = Join-Path ([System.IO.Path]::GetTempPath()) "dni-pqc-crosslang"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null

Write-Host "== compiling CrossVerify.java (bcprov 1.84 on classpath) =="
& $javac -cp $BcprovJar -d $dir (Join-Path $dir "CrossVerify.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== running .NET orchestrator (BouncyCastle 2.6.2) =="
dotnet run --project $dir -c Release -- $work $java $BcprovJar $dir
exit $LASTEXITCODE
