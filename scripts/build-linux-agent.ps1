# Builds the BIMBA Site Agent for Linux / Raspberry Pi into site-agent/dist (next to the Windows installers, so the
# Setu console offers them under "Download installer"):
#   bimba-site-agent_all.deb         Debian, Ubuntu, Raspberry Pi OS (arm64 + armhf)    sudo apt install ./bimba-site-agent_all.deb
#   bimba-site-agent-linux.tar.gz    any systemd Linux (RHEL, SUSE, ...)                 sudo ./bimba-site-agent/install.sh ...
#   release-linux.json               version + SHA-256 for the console
# Everything runs in Setu's Gradle image (gradle:9.2-jdk21), so no local JDK or Linux is needed.
# Source: bimba-setu/agent (Java) + bimba-setu/agent/packaging.
param(
    [switch]$SkipTests
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gw = Join-Path $root "bimba-setu"
$dist = Join-Path $root "site-agent\dist"
New-Item -ItemType Directory -Force $dist | Out-Null

$tasks = if ($SkipTests) { ":agent:agentDist" } else { ":agent:test :agent:agentDist" }
$script = "gradle $tasks --no-daemon -q && sh /src/agent/packaging/build-packages.sh /src /dist"
docker run --rm -v "${gw}:/src" -v "${dist}:/dist" -v bimba-gradle-cache:/home/gradle/.gradle -w /src gradle:9.2-jdk21 sh -c $script
if ($LASTEXITCODE -ne 0) { throw "Linux agent build failed" }
Get-Content (Join-Path $dist "release-linux.json")
