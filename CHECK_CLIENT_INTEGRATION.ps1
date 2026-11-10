param([string]$JdkPath = 'C:\Program Files\Java\jdk-22', [string]$NodePath = 'C:\Program Files\nodejs\node.exe')
$ErrorActionPreference = 'Stop'
$runId = Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$evidence = Join-Path $PSScriptRoot "evidence\integration_$runId"
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$jarPath = & (Join-Path $PSScriptRoot 'scripts\Resolve-RoutineJar.ps1') -Component bridge
$runtime = Join-Path $evidence 'private-runtime'
$data = Join-Path $evidence 'state.properties'
$launchArgs = @('-Xmx128m','-jar',('"' + $jarPath + '"'),'--port','0','--data',('"' + $data + '"'),'--runtime-root',('"' + $runtime + '"'))
$process = Start-Process -FilePath (Join-Path $JdkPath 'bin\java.exe') -ArgumentList $launchArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'server.log') -RedirectStandardError (Join-Path $evidence 'server-error.log')
try {
  $connection = $null
  for ($attempt = 0; $attempt -lt 80; $attempt++) {
    if ($process.HasExited) { throw 'Test server exited during startup; inspect server-error.log.' }
    if (Test-Path -LiteralPath $runtime) {
      $connection = Get-ChildItem -LiteralPath $runtime -Filter connection.json -Recurse | Select-Object -First 1 -ExpandProperty FullName
      if ($connection) { break }
    }
    Start-Sleep -Milliseconds 250
  }
  if (-not $connection) { throw 'Test server did not become ready in 20 seconds.' }
  & $NodePath --test (Join-Path $PSScriptRoot 'client\checks\client.test.mjs') 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'client.log')
  if ($LASTEXITCODE -ne 0) { throw 'Client tests failed.' }
  & $NodePath (Join-Path $PSScriptRoot 'client\checks\integration.mjs') $connection $evidence 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'integration.log')
  if ($LASTEXITCODE -ne 0) { throw 'Client integration failed.' }
} finally {
  if (-not $process.HasExited) { Stop-Process -Id $process.Id }
}
Write-Output $evidence

