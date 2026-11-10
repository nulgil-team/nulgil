param([string]$JdkPath = 'C:\Program Files\Java\jdk-22')
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$runId = Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$outputRoot = Join-Path $projectRoot "desktop\build\$runId"
$classesRoot = Join-Path $outputRoot 'classes'
[System.IO.Directory]::CreateDirectory($classesRoot) | Out-Null
$sources = @((Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core\src\main\java') -Filter '*.java' -Recurse).FullName)
$sources += @((Get-ChildItem -LiteralPath (Join-Path $projectRoot 'desktop\src') -Filter '*.java' -Recurse).FullName)
$sourceList = Join-Path $outputRoot 'sources.txt'
$sourceLines = $sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }
[System.IO.File]::WriteAllLines($sourceList, $sourceLines, [System.Text.UTF8Encoding]::new($false))
& (Join-Path $JdkPath 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classesRoot "@$sourceList" 2>&1 | Tee-Object -FilePath (Join-Path $outputRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'PC compilation failed' }
$jarPath = Join-Path $outputRoot 'routine-demo.jar'
& (Join-Path $JdkPath 'bin\jar.exe') "-J-Djava.io.tmpdir=$outputRoot" --create --file $jarPath --main-class kr.routine.desktop.DesktopApp -C $classesRoot .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed' }
Write-Output $jarPath
