$ErrorActionPreference = 'Stop'
if (-not $env:DMA_LICENSE_URL -or -not $env:DMA_LICENSE_URL.StartsWith('https://')) { throw 'Set DMA_LICENSE_URL to your deployed HTTPS license server before building a distributable APK.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDir = Join-Path $env:LOCALAPPDATA 'DiscordMessageAutoMobile\signing'
$storeFile = Join-Path $signingDir 'mobile.jks'
$passwordFile = Join-Path $signingDir 'password.dpapi'
New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
if (-not (Test-Path -LiteralPath $storeFile)) {
    if (Test-Path -LiteralPath $passwordFile) { throw 'Signing password exists without its key. Restore the original key before building an update.' }
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = [Convert]::ToBase64String($bytes)
    ConvertTo-SecureString $password -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordFile
} else {
    if (-not (Test-Path -LiteralPath $passwordFile)) { throw 'Signing key exists without its password. Restore the original password.' }
    $secure = Get-Content -LiteralPath $passwordFile | ConvertTo-SecureString
    $password = [Net.NetworkCredential]::new('', $secure).Password
}
$env:DMA_STORE_FILE = $storeFile
$env:DMA_STORE_PASSWORD = $password
try {
    if (-not (Test-Path -LiteralPath $storeFile)) {
        & keytool -genkeypair -keystore $storeFile -storetype JKS -storepass:env DMA_STORE_PASSWORD -keypass:env DMA_STORE_PASSWORD -alias mobile -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Discord Message Auto Mobile'
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
    }
    Push-Location $projectRoot
    try {
        & .\gradlew.bat testDebugUnitTest lintRelease assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
        New-Item -ItemType Directory -Path artifacts -Force | Out-Null
        Copy-Item -LiteralPath app\build\outputs\apk\release\app-release.apk -Destination artifacts\discord-message-auto-1.4.0.apk
        Get-FileHash -Algorithm SHA256 -LiteralPath artifacts\discord-message-auto-1.4.0.apk
    } finally { Pop-Location }
} finally {
    Remove-Item Env:DMA_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:DMA_STORE_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}
