param(
    [string]$JavaHome,
    [string]$AndroidSdk,
    [switch]$Release,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) {
    $candidate = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools\jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($candidate) { $JavaHome = $candidate.FullName } else { $JavaHome = $env:JAVA_HOME }
}
if (-not $AndroidSdk) {
    $localSdk = Join-Path $projectRoot '.tools\android-sdk'
    if (Test-Path -LiteralPath $localSdk) { $AndroidSdk = $localSdk } else { $AndroidSdk = $env:ANDROID_HOME }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'JDK 17+ necesar. Specifică -JavaHome sau JAVA_HOME.' }
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-35\android.jar'))) { throw 'Android SDK Platform 35 necesar. Specifică -AndroidSdk sau ANDROID_HOME.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-cache'
$env:TEMP = Join-Path $projectRoot '.tools\tmp'
$env:TMP = $env:TEMP
# Keep malformed quote characters from the host PATH out of Gradle's test JVM arguments.
$env:Path = (($env:Path -split ';' | ForEach-Object { $_.Trim().Trim('"') } | Where-Object { $_ }) -join ';')
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
$variant = if ($Release) { 'release' } else { 'debug' }
$taskVariant = $variant.Substring(0, 1).ToUpperInvariant() + $variant.Substring(1)
$gradleArgs = @('--no-daemon', '--console=plain', ":app:assemble$taskVariant", ":app:lint$taskVariant", ":app:test${taskVariant}UnitTest")
if ($Offline) { $gradleArgs += '--offline' }
if ($Release) {
    $keyStore = Join-Path $env:ANDROID_USER_HOME 'debug.keystore'
    if (-not (Test-Path -LiteralPath $keyStore -PathType Leaf)) { throw 'Lipsește cheia Android existentă necesară pentru actualizare.' }
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
    & $apkSigner sign --ks $keyStore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $destination $apk
    if ($LASTEXITCODE -ne 0) { throw "Semnarea APK-ului a eșuat ($LASTEXITCODE)." }
    & $apkSigner verify $destination
    if ($LASTEXITCODE -ne 0) { throw "Verificarea semnăturii a eșuat ($LASTEXITCODE)." }
} else {
    $destination = Join-Path $releaseDirectory "camara-$($artifact.versionName)-debug.apk"
    Copy-Item -LiteralPath $apk -Destination $destination
}
Write-Output $destination
