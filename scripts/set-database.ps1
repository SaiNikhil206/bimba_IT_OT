<#
.SYNOPSIS
  Points Django (and the scripts that configure Boomi) at an external PostgreSQL database.
  Writes BIMBA_DB_* into .env. The password is read with a hidden prompt and never shown
  or passed on the command line.
  All BIMBA objects live in their own schema (BIMBA_DB_SCHEMA, default "bimba"), so
  existing tables in the database are never touched.
.EXAMPLE
  .\scripts\set-database.ps1
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
function Ask($name, $label, $default) {
    $current = Get-Value $name
    if ([string]::IsNullOrWhiteSpace($current)) { $current = $default }
    $v = Read-Host "$label [$current]"
    if ([string]::IsNullOrWhiteSpace($v)) { $v = $current }
    Set-Value $name $v.Trim()
}

Ask "BIMBA_DB_HOST"   "Host"                         "103.120.176.105"
Ask "BIMBA_DB_PORT"   "Port"                         "6433"
Ask "BIMBA_DB_NAME"   "Database"                     "bimba_chromalab"
Ask "BIMBA_DB_USER"   "User"                         "admin"
Ask "BIMBA_DB_SCHEMA" "Schema for BIMBA objects"     "bimba"

$secure = Read-Host "Password (Enter = keep current)" -AsSecureString
$pw = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if (-not [string]::IsNullOrEmpty($pw)) { Set-Value "BIMBA_DB_PASSWORD" $pw }
elseif ([string]::IsNullOrEmpty((Get-Value "BIMBA_DB_PASSWORD"))) { throw "Password is empty." }
Remove-Variable pw, secure

[IO.File]::WriteAllLines($envFile, $lines, (New-Object Text.UTF8Encoding $false))
Write-Host "Saved to .env. Recreate the Django services to apply: docker compose up -d django django-es-consumer" -ForegroundColor Green
