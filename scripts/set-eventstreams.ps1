<#
.SYNOPSIS
  Stores the Boomi Event Streams topic REST URL and environment token in the plugin config
  (ignition/bimba/config.json, destination "boomi-eventstreams"). The token is read as a
  secure prompt, so it never appears in the command line, shell history or chat.
  The BIMBA IT/OT Agent hot-reloads the file within ~10 seconds.

.EXAMPLE
  .\scripts\set-eventstreams.ps1
#>
$ErrorActionPreference = "Stop"
$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) "ignition\bimba\config.json"

$config = Get-Content $configPath -Raw | ConvertFrom-Json
$dest = $config.destinations | Where-Object { $_.name -eq "boomi-eventstreams" }
if (-not $dest) { throw "Destination 'boomi-eventstreams' not found in $configPath" }

Write-Host "Current topic REST URL: $($dest.url)"
$url = Read-Host "New topic REST URL (Enter = keep current)"
if ([string]::IsNullOrWhiteSpace($url)) { $url = $dest.url }
if ($url -notmatch '^https://') { throw "Expected an https:// URL copied from 'View REST'." }
$secure = Read-Host "Environment token (produce permission)" -AsSecureString
$token = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
# tolerate a pasted "Bearer " prefix, quotes and line breaks
$token = ($token -replace '^\s*Bearer\s+', '' -replace '[\s"'']', '')
if ([string]::IsNullOrWhiteSpace($token)) { throw "Token is empty." }

$dest.url = $url
$dest.enabled = $true
$dest.auth.type = "bearer"
$dest.auth.token = $token

# UTF-8 without BOM so the gateway's JSON parser reads it cleanly
$json = $config | ConvertTo-Json -Depth 10
[IO.File]::WriteAllText($configPath, $json, (New-Object Text.UTF8Encoding $false))
Remove-Variable token, secure

Write-Host "Saved. Waiting for the plugin to reload and retry..." -ForegroundColor Green
for ($i = 0; $i -lt 8; $i++) {
    Start-Sleep -Seconds 5
    try {
        $st = Invoke-RestMethod -Uri "http://localhost:8000/api/plugin-status/" -TimeoutSec 5
        $es = $st.delivery.destinations | Where-Object { $_.name -eq "boomi-eventstreams" }
        if ($es.state -eq "up") { Write-Host "boomi-eventstreams is UP ($($es.messages) messages sent)." -ForegroundColor Green; break }
        Write-Host "  state=$($es.state)  lastError=$($es.lastError)"
    } catch { Write-Host "  status endpoint not reachable yet" }
}
if ($es.lastError -match '401|403') {
    Write-Host "Still rejected. The token must be an Event Streams ENVIRONMENT token with PRODUCE permission," -ForegroundColor Yellow
    Write-Host "created in the same environment as the topic in the URL (the consume token used by the listener is not enough)." -ForegroundColor Yellow
}
Write-Host "Check:"
Write-Host "  http://localhost:8088/system/bimba-status   (boomi-eventstreams should become 'up')"
Write-Host "  http://localhost:3080                        (chart: 'Boomi - Event Streams')"
Write-Host "Keep ignition/bimba/config.json out of git now that it contains the token."
