# Builds the BIMBA Site Agent into site-agent/dist:
#   BIMBA-Site-Agent-Setup.exe   setup wizard + silent installer + Windows service (.NET Framework 4.8, no extra runtime)
#   BIMBA-Site-Agent.msi         for Intune / SCCM / Group Policy (WiX Toolset 5, restored from NuGet on first build)
#   release.json                 version, SHA-256 and signer of both files (shown in the gateway console)
# The gateway serves this folder for "Download installer" (docker-compose mounts it at /installers).
#
# Code signing (Authenticode, SHA-256, RFC 3161 timestamp). Set ONE of:
#   BIMBA_SIGN_THUMBPRINT                    certificate in Cert:\CurrentUser\My or Cert:\LocalMachine\My
#                                            (also USB tokens / EV certificates whose key stays on the device)
#   BIMBA_SIGN_PFX + BIMBA_SIGN_PFX_PASSWORD certificate file
#   BIMBA_SIGN_DLIB + BIMBA_SIGN_DMDF        Azure Trusted Signing: path of Azure.CodeSigning.Dlib.dll + metadata.json
# BIMBA_SIGN_TIMESTAMP  timestamp server (default http://timestamp.digicert.com; "none" = no timestamp, test only).
# Without signing settings the files are built unsigned (development; Windows SmartScreen warns about them).
# A test certificate for trying the pipeline: scripts/new-test-signing-cert.ps1
param(
    [switch]$RequireSigning     # fail instead of building unsigned (use in release builds)
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$agent = Join-Path $root "site-agent"
$dist = Join-Path $agent "dist"
$exe = Join-Path $dist "BIMBA-Site-Agent-Setup.exe"
$msi = Join-Path $dist "BIMBA-Site-Agent.msi"

$version = ([xml](Get-Content (Join-Path $agent "BimbaSiteAgent.csproj"))).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1
if (-not $version) { throw "no <Version> in BimbaSiteAgent.csproj" }

# ------------------------------------------------------------------ signing
$signMode = $null
if ($env:BIMBA_SIGN_THUMBPRINT) { $signMode = "store" }
elseif ($env:BIMBA_SIGN_PFX) { $signMode = "pfx" }
elseif ($env:BIMBA_SIGN_DLIB) { $signMode = "azure" }
if (-not $signMode -and $RequireSigning) { throw "-RequireSigning: set BIMBA_SIGN_THUMBPRINT, BIMBA_SIGN_PFX or BIMBA_SIGN_DLIB" }

$signtool = $null
if ($signMode) {
    $signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Directory.Parent.Name -replace '[^0-9.]', '') } -Descending | Select-Object -First 1
    if (-not $signtool) { throw "signtool.exe not found: install the Windows SDK (Signing Tools for Desktop Apps)" }
}

function Sign-File([string]$path) {
    if (-not $signMode) { return }
    $a = @("sign", "/fd", "SHA256", "/d", "BIMBA Site Agent")
    $ts = if ($env:BIMBA_SIGN_TIMESTAMP) { $env:BIMBA_SIGN_TIMESTAMP } else { "http://timestamp.digicert.com" }
    if ($ts -ne "none") { $a += @("/tr", $ts, "/td", "SHA256") }
    switch ($signMode) {
        "store" { $a += @("/sha1", $env:BIMBA_SIGN_THUMBPRINT); if (-not (Test-Path "Cert:\CurrentUser\My\$($env:BIMBA_SIGN_THUMBPRINT)")) { $a += "/sm" } }
        "pfx" { $a += @("/f", $env:BIMBA_SIGN_PFX); if ($env:BIMBA_SIGN_PFX_PASSWORD) { $a += @("/p", $env:BIMBA_SIGN_PFX_PASSWORD) } }
        "azure" { $a += @("/dlib", $env:BIMBA_SIGN_DLIB, "/dmdf", $env:BIMBA_SIGN_DMDF) }
    }
    & $signtool.FullName @a $path | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "signing $path failed (signtool exit $LASTEXITCODE)" }
}

# ------------------------------------------------------------------ build
Write-Host "BIMBA Site Agent $version"
dotnet build (Join-Path $agent "BimbaSiteAgent.csproj") -c Release -nologo -v quiet -o (Join-Path $agent "bin/out")
if ($LASTEXITCODE -ne 0) { throw "site agent build failed" }
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item (Join-Path $agent "bin/out/BIMBA-Site-Agent-Setup.exe") $exe -Force
Sign-File $exe                                       # before packing: the MSI carries the signed exe

dotnet build (Join-Path $agent "installer/BimbaSiteAgent.wixproj") -c Release -nologo -v quiet -p:Version=$version `
    -o (Join-Path $agent "installer/bin/out")
if ($LASTEXITCODE -ne 0) { throw "MSI build failed" }
Copy-Item (Join-Path $agent "installer/bin/out/BIMBA-Site-Agent.msi") $msi -Force
Sign-File $msi

# ------------------------------------------------------------------ release.json
function Describe([string]$path) {
    $sig = Get-AuthenticodeSignature $path
    [ordered]@{
        name      = Split-Path -Leaf $path
        size      = (Get-Item $path).Length
        sha256    = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
        signed    = $null -ne $sig.SignerCertificate
        signer    = if ($sig.SignerCertificate) { $sig.SignerCertificate.Subject } else { $null }
        signerThumbprint = if ($sig.SignerCertificate) { $sig.SignerCertificate.Thumbprint } else { $null }
        signerNotAfter   = if ($sig.SignerCertificate) { $sig.SignerCertificate.NotAfter.ToUniversalTime().ToString("o") } else { $null }
        timestamped      = $null -ne $sig.TimeStamperCertificate
        # Valid = chains to a root this build machine trusts; UnknownError/NotTrusted = e.g. a test certificate
        status    = "$($sig.Status)"
    }
}
$release = [ordered]@{
    product = "BIMBA Site Agent"
    version = $version
    builtAt = (Get-Date).ToUniversalTime().ToString("o")
    files   = @((Describe $exe), (Describe $msi))
}
[IO.File]::WriteAllText((Join-Path $dist "release.json"), ($release | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding $false))
$release.files | ForEach-Object { [pscustomobject]$_ } | Format-Table name, size, signed, status, signer -AutoSize
