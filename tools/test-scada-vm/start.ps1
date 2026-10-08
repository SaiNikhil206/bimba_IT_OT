# Starts the TEST ONLY Windows Server VM ("test-scada" compose profile) for trying the BIMBA Site Agent installer.
#  - loads KVM inside Docker Desktop's Linux VM (needed by QEMU; lost whenever Docker Desktop restarts),
#  - copies the current installers from site-agent/dist into the VM's Shared folder,
#  - starts the container. Watch the screen at http://localhost:8006
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."
Set-Location $root

Write-Host "Loading KVM in Docker's Linux VM..."
docker run --rm --privileged --pid=host alpine:latest nsenter -t 1 -m -- sh -c "modprobe kvm_intel 2>/dev/null || modprobe kvm_amd; ls /dev/kvm"
if ($LASTEXITCODE -ne 0) { throw "KVM is not available in Docker Desktop (nested virtualization off?)." }

# Official Windows Server 2022 evaluation ISO from Microsoft (the image's own downloader falls back to unofficial mirrors)
$isoUrl = "https://software-static.download.prss.microsoft.com/sg/download/888969d5-f34g-4e03-ac9d-1f9786c66749/SERVER_EVAL_x64FRE_en-us.iso"
$isoSize = 5044094976
$iso = Join-Path $PSScriptRoot "iso\windows-server-2022-eval.iso"
New-Item -ItemType Directory -Force (Split-Path $iso) | Out-Null
for ($i = 1; -not ((Test-Path $iso) -and (Get-Item $iso).Length -eq $isoSize); $i++) {
    if ($i -gt 30) { throw "Could not download the Windows Server 2022 evaluation ISO." }
    Write-Host "Downloading the Windows Server 2022 evaluation ISO from Microsoft (5 GB, resumes)..."
    curl.exe -sSL -C - --retry 5 --retry-delay 5 -o $iso $isoUrl
}

$shared = Join-Path $PSScriptRoot "shared"
New-Item -ItemType Directory -Force $shared | Out-Null
Copy-Item "$root\site-agent\dist\*" $shared -Force
Write-Host "Installers copied to the VM's Shared folder:" (Get-ChildItem $shared -Name) -Separator " "

docker compose --profile test-scada up -d test-scada
Write-Host "`nOpen http://localhost:8006 . The first start downloads and installs Windows Server 2022 (about 20-30 min)."
