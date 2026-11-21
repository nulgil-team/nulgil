param([string]$JdkPath = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-22' }))
$ErrorActionPreference = 'Stop'
$jarPath = & (Join-Path $PSScriptRoot 'scripts\Resolve-RoutineJar.ps1') -Component desktop
Push-Location $PSScriptRoot
try { & (Join-Path $JdkPath 'bin\java.exe') '-Dfile.encoding=UTF-8' -jar $jarPath }
finally { Pop-Location }
