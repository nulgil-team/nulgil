$ErrorActionPreference = 'Stop'
$jarPath = & (Join-Path $PSScriptRoot 'scripts\Resolve-RoutineJar.ps1') -Component desktop
Push-Location $PSScriptRoot
try { & 'C:\Program Files\Java\jdk-22\bin\java.exe' '-Dfile.encoding=UTF-8' -jar $jarPath }
finally { Pop-Location }
