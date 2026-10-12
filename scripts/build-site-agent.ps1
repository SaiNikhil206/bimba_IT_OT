# Builds the BIMBA Site Agent into site-agent/dist:
#   BIMBA-Site-Agent-Setup.exe   setup wizard + silent installer + Windows service (.NET Framework 4.8, no extra runtime);
#                                carries the edge collector (Java 17 runtime + OPC UA / Modbus drivers + the 32-bit OPC DA
#                                bridge site-agent/opcda) as edge.zip
#   BIMBA-Site-Agent.msi         for Intune / SCCM / Group Policy (WiX Toolset 5, restored from NuGet on first build)
#   release.json                 version, SHA-256 and signer of both files (shown in the Setu console)
# Setu serves this folder for "Download installer" (docker-compose mounts it at /installers).
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
    [switch]$RequireSigning,    # fail instead of building unsigned (use in release builds)
    [switch]$SkipEdge           # reuse site-agent/edge/edge.zip from the previous build
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

# ------------------------------------------------------------------ edge collector (embedded in the exe as edge.zip)
# Jars: bimba-setu/edge (gradle :edge:edgeDist, run in Setu's Gradle image, so no local Gradle is needed).
# Runtime: jlink of a Windows JDK 17+ (BIMBA_JDK, else JAVA_HOME, else a Gradle-provisioned Temurin 17), trimmed to the
# modules the edge needs (about 45 MB unpacked).
$edgeOut = Join-Path $agent "edge"
$edgeZip = Join-Path $edgeOut "edge.zip"
if (-not $SkipEdge) {
    $gw = Join-Path $root "bimba-setu"
    Write-Host "edge collector: building jars"
    docker run --rm -v "${gw}:/src" -v bimba-gradle-cache:/home/gradle/.gradle -w /src gradle:9.2-jdk21 gradle :edge:edgeDist --no-daemon -q
    if ($LASTEXITCODE -ne 0) { throw "edge collector build failed" }
    $jdk = @($env:BIMBA_JDK, $env:JAVA_HOME) + @(Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -Filter "*17*windows*" -ErrorAction SilentlyContinue | ForEach-Object FullName) |
        Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\jlink.exe")) -and (Test-Path (Join-Path $_ "jmods")) } | Select-Object -First 1
    if (-not $jdk) { throw "no Windows JDK 17+ with jlink found: set BIMBA_JDK" }
    $stage = Join-Path $edgeOut "stage"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    New-Item -ItemType Directory -Force $stage | Out-Null
    Write-Host "edge collector: Java runtime from $jdk"
    $modules = "java.base,java.compiler,java.desktop,java.logging,java.management,java.naming,java.sql,jdk.crypto.ec,jdk.unsupported"
    & (Join-Path $jdk "bin\jlink.exe") --add-modules $modules --strip-debug --no-header-files --no-man-pages --compress=2 `
        --output (Join-Path $stage "runtime")
    if ($LASTEXITCODE -ne 0) { throw "jlink failed" }
    Copy-Item (Join-Path $gw "edge\build\edge\lib") (Join-Path $stage "lib") -Recurse
    Write-Host "edge collector: OPC DA bridge (x86)"
    dotnet build (Join-Path $agent "opcda\BimbaOpcDa.csproj") -c Release -nologo -v quiet -o (Join-Path $agent "bin\opcda")
    if ($LASTEXITCODE -ne 0) { throw "OPC DA bridge build failed" }
    New-Item -ItemType Directory -Force (Join-Path $stage "opcda") | Out-Null
    Copy-Item (Join-Path $agent "bin\opcda\bimba-opcda.exe"), (Join-Path $agent "bin\opcda\bimba-opcda.exe.config") (Join-Path $stage "opcda")
    Sign-File (Join-Path $stage "opcda\bimba-opcda.exe")
    [IO.File]::WriteAllText((Join-Path $stage "version.txt"), $version, (New-Object Text.UTF8Encoding $false))
    if (Test-Path $edgeZip) { Remove-Item $edgeZip -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($stage, $edgeZip, [IO.Compression.CompressionLevel]::Optimal, $false)
    Remove-Item $stage -Recurse -Force
    Write-Host ("edge collector: {0:N1} MB" -f ((Get-Item $edgeZip).Length / 1MB))
}
if (-not (Test-Path $edgeZip)) { Write-Warning "no edge.zip: the agent is built without data collection" }

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
