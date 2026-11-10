param([string]$JdkPath = 'C:\Program Files\Java\jdk-22')
$ErrorActionPreference = 'Stop'
$outputRoot = Join-Path $PSScriptRoot ('evidence\desktop_' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff'))
$classes = Join-Path $outputRoot 'classes'
[System.IO.Directory]::CreateDirectory($classes) | Out-Null
$jarPath = & (Join-Path $PSScriptRoot 'scripts\Resolve-RoutineJar.ps1') -Component desktop
& (Join-Path $JdkPath 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $jarPath -d $classes (Join-Path $PSScriptRoot 'desktop\checks\DesktopUiCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Desktop check compilation failed' }
& (Join-Path $JdkPath 'bin\java.exe') '-Djava.awt.headless=true' -cp ($classes + ';' + $jarPath) kr.routine.desktop.DesktopUiCheck $outputRoot 2>&1 | Tee-Object -FilePath (Join-Path $outputRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Desktop regression failed' }
Write-Output $outputRoot
