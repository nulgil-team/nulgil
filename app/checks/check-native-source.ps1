param([string]$JdkPath = 'C:\Program Files\Java\jdk-22')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$run = Join-Path $project ('evidence\br02-source-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$classes = Join-Path $run 'classes'
[IO.Directory]::CreateDirectory($classes) | Out-Null
$sources = @((Get-ChildItem (Join-Path $project 'core\src\main\java\kr\routine\core') -Filter '*.java').FullName)
$sources += @((Get-ChildItem (Join-Path $project 'app\src\main\java\kr\routine\app\vehicle') -Filter '*.java').FullName)
$sources += Join-Path $project 'app\src\main\java\kr\routine\app\ui\VehicleVisuals.java'
$sources += Join-Path $PSScriptRoot 'VehicleSessionCheck.java'
$sources += Join-Path $PSScriptRoot 'VehicleVisualsCheck.java'
$sources += Join-Path $PSScriptRoot 'AndroidSyntaxCheck.java'
& (Join-Path $JdkPath 'bin\javac.exe') -J-Xmx128m --release 17 -encoding UTF-8 -d $classes $sources 2>&1 | Tee-Object (Join-Path $run 'compile.log')
if ($LASTEXITCODE) { throw 'Plain Java compilation failed' }
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classes VehicleSessionCheck (Join-Path $run 'fixtures') 2>&1 | Tee-Object (Join-Path $run 'session.log')
if ($LASTEXITCODE) { throw 'Session checks failed' }
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classes VehicleVisualsCheck 2>&1 | Tee-Object (Join-Path $run 'visuals.log')
if ($LASTEXITCODE) { throw 'Visual token checks failed' }
& (Join-Path $JdkPath 'bin\java.exe') -Xmx128m -cp $classes AndroidSyntaxCheck (Join-Path $project 'app\src\main\java') 2>&1 | Tee-Object (Join-Path $run 'syntax.log')
if ($LASTEXITCODE) { throw 'Native Java syntax check failed' }
[xml]$manifest = [IO.File]::ReadAllText((Join-Path $project 'app\src\main\AndroidManifest.xml'))
foreach ($resource in Get-ChildItem (Join-Path $project 'app\src\main\res') -Recurse -Filter '*.xml') {
    [xml]$resourceXml = [IO.File]::ReadAllText($resource.FullName)
}
if ($manifest.manifest.application.GetAttribute('name','http://schemas.android.com/apk/res/android') -ne '.RoutineApplication') { throw 'Session owner missing in manifest' }
Write-Output "Evidence: $run"
Write-Output 'Android SDK build, resource resolution, packaging, and device rendering were NOT executed.'
