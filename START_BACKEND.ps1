param(
  [int]$Port = 8791,
  [string[]]$Origin = @('http://localhost:5173','http://127.0.0.1:5173'),
  [string]$JdkPath = 'C:\Program Files\Java\jdk-22',
  [string]$DataPath = '',
  [string]$RuntimeRoot = ''
)
$ErrorActionPreference = 'Stop'
$jarPath = & (Join-Path $PSScriptRoot 'scripts\Resolve-RoutineJar.ps1') -Component bridge
if (-not $DataPath) { $DataPath = Join-Path $PSScriptRoot 'demo-data\bridge.properties' }
if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path $PSScriptRoot 'runs' }
$launchArgs = @('-Xmx128m','-jar',$jarPath,'--port',[string]$Port,'--data',$DataPath,'--runtime-root',$RuntimeRoot)
foreach ($item in $Origin) { $launchArgs += @('--origin',$item) }
& (Join-Path $JdkPath 'bin\java.exe') @launchArgs
exit $LASTEXITCODE

