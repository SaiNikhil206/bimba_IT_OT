# Builds the BIMBA IT/OT Agent Ignition module (.modl) inside a JDK 17 Gradle container.
# Output: ignition-module/build/modl/BIMBA-IT-OT-Agent-<version>.modl
# Then either restart the gateway container (module is mounted) or upload it in
# Gateway > Config > Modules > Install or Upgrade a Module.
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "ignition-module"
docker run --rm -v "${src}:/src" -v bimba-gradle-cache:/home/gradle/.gradle -w /src gradle:8.10-jdk17 gradle modl --no-daemon -q
if ($LASTEXITCODE -ne 0) { throw "module build failed" }
# Stable name mounted by docker-compose. Written via a temp file + move so the mounted file is never
# missing (Docker would otherwise create an empty directory in its place).
$out = Join-Path $src "build/modl"
$versioned = Get-ChildItem (Join-Path $out "BIMBA-IT-OT-Agent-*.modl") | Sort-Object LastWriteTime | Select-Object -Last 1
$stable = Join-Path $out "BIMBA-IT-OT-Agent.modl"
if (Test-Path $stable -PathType Container) { Remove-Item $stable -Recurse -Force }
Copy-Item $versioned.FullName "$stable.tmp" -Force
Move-Item "$stable.tmp" $stable -Force
Get-ChildItem (Join-Path $out "*.modl") | Select-Object Name, Length, LastWriteTime
