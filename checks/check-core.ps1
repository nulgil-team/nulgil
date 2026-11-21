param([string]$JdkPath = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-22' }))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runRoot = Join-Path $PSScriptRoot ('evidence\run-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$classRoot = Join-Path $runRoot 'classes'
[System.IO.Directory]::CreateDirectory($classRoot) | Out-Null
$javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core\src\main\java\kr\routine\core') -Filter '*.java' | ForEach-Object FullName)
$javaFiles += Join-Path $PSScriptRoot 'CoreChecks.java'
$javaFiles += Join-Path $PSScriptRoot 'TimezoneChecks.java'
$javaFiles += Join-Path $PSScriptRoot 'ActiveRouteChecks.java'
$javaFiles += Join-Path $PSScriptRoot 'HistoryCoreChecks.java'
$javaFiles += Join-Path $PSScriptRoot 'StopChecks.java'
$javaFiles += Join-Path $PSScriptRoot 'InviteChecks.java'
$javaFiles += Join-Path $PSScriptRoot 'PhoneChecks.java'
& (Join-Path $JdkPath 'bin\javac.exe') -J-Xmx128m --release 17 -encoding UTF-8 -d $classRoot $javaFiles
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed.' }
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classRoot CoreChecks (Join-Path $runRoot 'data') 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'checks.log')
$checkExit = $LASTEXITCODE
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classRoot TimezoneChecks (Join-Path $runRoot 'timezone-data') 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'timezone-checks.log')
if ($LASTEXITCODE -ne 0) { $checkExit = $LASTEXITCODE }
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classRoot ActiveRouteChecks (Join-Path $runRoot 'active-route-data') 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'active-route-checks.log')
if ($LASTEXITCODE -ne 0) { $checkExit = $LASTEXITCODE }
Write-Output ('Evidence: ' + $runRoot)
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classRoot HistoryCoreChecks (Join-Path $runRoot 'history-data') 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'history-checks.log')
if ($LASTEXITCODE -ne 0) { $checkExit = $LASTEXITCODE }
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classRoot StopChecks (Join-Path $runRoot 'stop-data') 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'stop-checks.log')
if ($LASTEXITCODE -ne 0) { $checkExit = $LASTEXITCODE }
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classRoot InviteChecks (Join-Path $runRoot 'invite-data') 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'invite-checks.log')
if ($LASTEXITCODE -ne 0) { $checkExit = $LASTEXITCODE }
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classRoot PhoneChecks 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'phone-checks.log')
if ($LASTEXITCODE -ne 0) { $checkExit = $LASTEXITCODE }
exit $checkExit
