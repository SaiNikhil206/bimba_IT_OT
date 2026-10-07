<#
.SYNOPSIS
  Configures the Django Event Streams consumer (service django-es-consumer): writes the
  subscription consume URL and a consume-permission environment token into .env (hidden
  prompt, never on the command line), then (re)starts the consumer.
.EXAMPLE
  .\scripts\set-eventstreams-consumer.ps1
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) { Copy-Item (Join-Path $root ".env.example") $envFile }
$lines = [System.Collections.Generic.List[string]](Get-Content $envFile)

function Get-Value($name) { ($lines | Where-Object { $_ -like "$name=*" } | Select-Object -First 1) -replace "^$name=", "" }
function Set-Value($name, $value) {
    $i = $lines.FindIndex([Predicate[string]] { param($l) $l -like "$name=*" })
    if ($i -ge 0) { $lines[$i] = "$name=$value" } else { $lines.Add("$name=$value") }
}

$current = Get-Value "BOOMI_ES_CONSUME_URL"
Write-Host "Current consume URL: $current"
$url = Read-Host "Consume URL (Event Streams > topic > subscription > View REST; Enter = keep)"
if ([string]::IsNullOrWhiteSpace($url)) { $url = $current }
if ($url -notmatch '^https://.+/rest/consume/') { throw "Expected a https://.../rest/consume/... URL" }
$secure = Read-Host "Environment token with consume permission" -AsSecureString
$token = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ([string]::IsNullOrWhiteSpace($token)) { throw "Token is empty." }

Set-Value "BOOMI_ES_CONSUME_URL" $url
Set-Value "BOOMI_ES_CONSUME_TOKEN" $token
[IO.File]::WriteAllLines($envFile, $lines, (New-Object Text.UTF8Encoding $false))
Remove-Variable token, secure

Push-Location $root
docker compose up -d django-es-consumer
Pop-Location
Write-Host "Consumer started. Follow it with: docker compose logs -f django-es-consumer" -ForegroundColor Green
