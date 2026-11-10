param([Parameter(Mandatory)][ValidateSet('bridge','desktop')][string]$Component)
$projectRoot = Split-Path -Parent $PSScriptRoot
$name = if ($Component -eq 'bridge') { 'routine-backend.jar' } else { 'routine-demo.jar' }
$build = Join-Path $projectRoot "$Component\build"
if (Test-Path -LiteralPath $build) {
    $latest = Get-ChildItem -LiteralPath $build -Recurse -File -Filter $name |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($latest) { return $latest.FullName }
}
$initial = Join-Path $projectRoot "dist\$name"
if (Test-Path -LiteralPath $initial -PathType Leaf) { return $initial }
throw "Build $Component before starting the demo."
