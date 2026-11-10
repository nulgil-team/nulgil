param([string]$JavaHome = 'C:\Program Files\Java\jdk-22')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runPath = Join-Path $projectRoot ('evidence\presentation-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$classesPath = Join-Path $runPath 'classes'
New-Item -ItemType Directory -Path $classesPath | Out-Null
$sourcePaths = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core\src\main\java') -Filter '*.java' -Recurse | ForEach-Object FullName)
$sourcePaths += Join-Path $PSScriptRoot 'PresentationChecks.java'
$compileOutput = & (Join-Path $JavaHome 'bin\javac.exe') '-J-Xmx128m' '--release' '17' '-encoding' 'UTF-8' '-d' $classesPath $sourcePaths 2>&1
$compileCode = $LASTEXITCODE
$compileOutput | Set-Content -LiteralPath (Join-Path $runPath 'compile.log') -Encoding utf8
if ($compileCode -ne 0) { $compileOutput | Write-Output; throw "javac failed: $compileCode" }
$testOutput = & (Join-Path $JavaHome 'bin\java.exe') '-Xmx128m' '-Dfile.encoding=UTF-8' '-cp' $classesPath 'PresentationChecks' (Join-Path $runPath 'fixtures') 2>&1
$testCode = $LASTEXITCODE
$testOutput | Set-Content -LiteralPath (Join-Path $runPath 'test.log') -Encoding utf8
$testOutput | Write-Output
Write-Output "Evidence: $runPath"
if ($testCode -ne 0) { throw "Presentation checks failed: $testCode" }
