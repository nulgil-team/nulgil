param([string]$JdkPath = 'C:\Program Files\Java\jdk-22')
$ErrorActionPreference = 'Stop'
$outputRoot = Join-Path $PSScriptRoot ('evidence\bridge_' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff'))
$classes = Join-Path $outputRoot 'classes'
[System.IO.Directory]::CreateDirectory($classes) | Out-Null
$jarPath = & (Join-Path $PSScriptRoot 'scripts\Resolve-RoutineJar.ps1') -Component bridge
& (Join-Path $JdkPath 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $jarPath -d $classes (Join-Path $PSScriptRoot 'bridge\checks\BridgeChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Bridge check compilation failed' }
& (Join-Path $JdkPath 'bin\java.exe') '-Xmx128m' -cp ($classes + ';' + $jarPath) kr.routine.bridge.BridgeChecks --evidence-dir $outputRoot 2>&1 | Tee-Object -FilePath (Join-Path $outputRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Bridge checks failed' }
Write-Output $outputRoot
