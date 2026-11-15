param(
    [Parameter(Mandatory)][string]$UnsignedApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [Parameter(Mandatory)][string]$StoreFile,
    [Parameter(Mandatory)][string]$LineageFile,
    [string]$KeyAlias = 'camara-release',
    [string]$PasswordVariable = 'CAMARA_RELEASE_STORE_PASSWORD',
    [string]$Sdk = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
if (-not $Sdk -or -not $env:JAVA_HOME) { throw 'Configurează ANDROID_HOME și JAVA_HOME.' }
if ($PasswordVariable -notmatch '^[a-zA-Z_][a-zA-Z0-9_]*$' -or -not [Environment]::GetEnvironmentVariable($PasswordVariable)) {
    throw 'Pune parola cheii în variabila de mediu pentru procesul curent.'
}
if ($KeyAlias -ieq 'androiddebugkey') { throw 'Actualizarea cere cheia de release.' }
$signer = Join-Path $Sdk 'build-tools\35.0.0\apksigner.bat'
$inputPath = (Resolve-Path -LiteralPath $UnsignedApk).Path
$storePath = (Resolve-Path -LiteralPath $StoreFile).Path
$lineagePath = (Resolve-Path -LiteralPath $LineageFile).Path
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputApk)
if ($inputPath -ieq $outputPath -or (Test-Path -LiteralPath $outputPath)) {
    throw 'Alege un fișier de ieșire nou, diferit de APK-ul de intrare.'
}

# API 28+ understands the v3 signing lineage. The original key is not used to sign the APK.
$arguments = @('sign', '--in', $inputPath, '--out', $outputPath,
    '--ks', $storePath, '--ks-key-alias', $KeyAlias, '--ks-pass', "env:${PasswordVariable}",
    '--lineage', $lineagePath, '--rotation-min-sdk-version', '28', '--min-sdk-version', '28',
    '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'true')
& $signer @arguments
if ($LASTEXITCODE -ne 0) { throw 'Semnarea actualizării a eșuat.' }
& $signer verify --verbose --print-certs --min-sdk-version 28 $outputPath
if ($LASTEXITCODE -ne 0) { throw 'Verificarea actualizării a eșuat.' }
