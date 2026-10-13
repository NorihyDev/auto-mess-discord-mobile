$ErrorActionPreference = 'Stop'
$tokenFile = Join-Path $env:LOCALAPPDATA 'DiscordMessageAutoMobile\admin-token.dpapi'
if (-not (Test-Path -LiteralPath $tokenFile)) { throw 'No local admin token found on this Windows account.' }
$secure = Get-Content -LiteralPath $tokenFile | ConvertTo-SecureString
[Net.NetworkCredential]::new('', $secure).Password
