param(
    [string]$JavaHome,
    [string]$AndroidSdk,
    [switch]$Release,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
if (-not (Test-Path -LiteralPath $toolsRoot)) {
    $shared = Join-Path (Split-Path -Parent $projectRoot) '.tools'
    if (Test-Path -LiteralPath (Join-Path $shared 'android-sdk')) { $toolsRoot = $shared }
}
if (-not $JavaHome) {
    $candidate = Get-ChildItem -LiteralPath (Join-Path $toolsRoot 'jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($candidate) { $JavaHome = $candidate.FullName } else { $JavaHome = $env:JAVA_HOME }
}
if (-not $AndroidSdk) {
    $localSdk = Join-Path $toolsRoot 'android-sdk'
    if (Test-Path -LiteralPath $localSdk) { $AndroidSdk = $localSdk } else { $AndroidSdk = $env:ANDROID_HOME }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'JDK 17+ necesar. Specifică -JavaHome sau JAVA_HOME.' }
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-35\android.jar'))) { throw 'Android SDK Platform 35 necesar. Specifică -AndroidSdk sau ANDROID_HOME.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_USER_HOME = Join-Path $toolsRoot 'android-user'
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-cache'
$env:TEMP = Join-Path $toolsRoot 'tmp'
$env:TMP = $env:TEMP
# Keep malformed quote characters from the host PATH out of Gradle's test JVM arguments.
$env:Path = (($env:Path -split ';' | ForEach-Object { $_.Trim().Trim('"') } | Where-Object { $_ }) -join ';')
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
$variant = if ($Release) { 'release' } else { 'debug' }
$taskVariant = $variant.Substring(0, 1).ToUpperInvariant() + $variant.Substring(1)
$gradleArgs = @('--console=plain', ":app:assemble$taskVariant", ":app:lint$taskVariant", ":app:test${taskVariant}UnitTest")
if ($Offline) { $gradleArgs += '--offline' }
if ($Release) {
    $keyStoreConfig = Join-Path $projectRoot 'android\keystore.properties'
    if (-not (Test-Path -LiteralPath $keyStoreConfig -PathType Leaf)) { throw 'Configurează cheia release în android/keystore.properties.' }
    $apkSigner = Join-Path $AndroidSdk 'build-tools\35.0.0\apksigner.bat'
    if (-not (Test-Path -LiteralPath $apkSigner -PathType Leaf)) { throw 'Android SDK Build-Tools 35.0.0 necesar pentru semnare.' }
}
Push-Location (Join-Path $projectRoot 'android')
try {
    & '.\gradlew.bat' @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Build Android eșuat ($LASTEXITCODE)." }
} finally { Pop-Location }
$releaseDirectory = Join-Path $projectRoot 'releases'
New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null
$apkDirectory = Join-Path $projectRoot "android\app\build\outputs\apk\$variant"
$artifact = (Get-Content -Raw -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') | ConvertFrom-Json).elements | Select-Object -First 1
$apk = Join-Path $apkDirectory $artifact.outputFile
if ($Release) {
    $destination = Join-Path $releaseDirectory "camara-$($artifact.versionName).apk"
    & $apkSigner verify $apk
    if ($LASTEXITCODE -ne 0) { throw "Verificarea semnăturii a eșuat ($LASTEXITCODE)." }
    Copy-Item -LiteralPath $apk -Destination $destination
} else {
    $destination = Join-Path $releaseDirectory "camara-$($artifact.versionName)-debug.apk"
    Copy-Item -LiteralPath $apk -Destination $destination
}
Write-Output $destination
