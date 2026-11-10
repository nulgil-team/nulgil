param([string]$JdkPath = 'C:\Program Files\Java\jdk-22')
$ErrorActionPreference = 'Stop'
$runId = Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$outputRoot = Join-Path $PSScriptRoot "bridge\build\$runId"
$classesRoot = Join-Path $outputRoot 'classes'
[System.IO.Directory]::CreateDirectory($classesRoot) | Out-Null
$sources = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'core\src\main\java') -Filter '*.java' -Recurse).FullName)
$sources += @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'bridge\src\main\java') -Filter '*.java' -Recurse).FullName)
$sourceList = Join-Path $outputRoot 'sources.txt'
[System.IO.File]::WriteAllLines($sourceList, ($sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }), [System.Text.UTF8Encoding]::new($false))
& (Join-Path $JdkPath 'bin\javac.exe') '-J-Xmx128m' --release 17 -encoding UTF-8 -d $classesRoot "@$sourceList" 2>&1 | Tee-Object -FilePath (Join-Path $outputRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Backend compilation failed' }
$jarPath = Join-Path $outputRoot 'routine-backend.jar'
& (Join-Path $JdkPath 'bin\jar.exe') "-J-Djava.io.tmpdir=$outputRoot" --create --file $jarPath --main-class kr.routine.bridge.BridgeMain -C $classesRoot .
if ($LASTEXITCODE -ne 0) { throw 'Backend packaging failed' }
Write-Output $jarPath

