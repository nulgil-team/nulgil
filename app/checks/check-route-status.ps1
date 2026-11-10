param(
    [string]$JdkPath = 'C:\Program Files\Java\jdk-22',
    [string]$GradleHome = 'D:\00_HYUNDAI_CONTEST\90_TOOLS\gradle_home',
    [string]$SdkClasspath = ''
)
$ErrorActionPreference = 'Stop'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$out = Join-Path $project ('evidence\native-route-map-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
[System.IO.Directory]::CreateDirectory($out) | Out-Null

if (-not $SdkClasspath) {
    function Find-CachedJar([string]$directory, [string]$pattern) {
        $file = Get-ChildItem -LiteralPath $directory -File -Recurse -Filter $pattern |
            Sort-Object FullName -Descending | Select-Object -First 1
        if (-not $file) { throw "Required cached jar missing: $pattern. Pass -SdkClasspath with the SDK runtime dependencies." }
        return $file.FullName
    }
    $modules = Join-Path $GradleHome 'caches\modules-2\files-2.1'
    $jars = @(
        Find-CachedJar (Join-Path $GradleHome 'caches\8.9\transforms') 'navi-data-3.0.4-runtime.jar'
        Find-CachedJar (Join-Path $modules 'org.jetbrains.kotlin\kotlin-stdlib') 'kotlin-stdlib-1.9.25.jar'
        Find-CachedJar (Join-Path $modules 'org.jetbrains.kotlinx\kotlinx-serialization-core-jvm') 'kotlinx-serialization-core-jvm-*.jar'
    )
    $SdkClasspath = $jars -join [System.IO.Path]::PathSeparator
}

$sources = @(
    (Join-Path $project 'core\src\main\java\kr\routine\core\Model.java'),
    (Join-Path $project 'app\src\main\java\kr\routine\app\platform\PleosRouteStatus.java'),
    (Join-Path $PSScriptRoot 'PleosRouteStatusCheck.java')
)
& (Join-Path $JdkPath 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $SdkClasspath -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'SDK mapping check compilation failed' }
& (Join-Path $JdkPath 'bin\java.exe') -cp ($out + [System.IO.Path]::PathSeparator + $SdkClasspath) kr.routine.app.platform.PleosRouteStatusCheck |
    Tee-Object -FilePath (Join-Path $out 'result.log')
if ($LASTEXITCODE -ne 0) { throw 'SDK mapping checks failed' }
Write-Output $out
