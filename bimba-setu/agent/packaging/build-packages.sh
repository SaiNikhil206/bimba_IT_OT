#!/bin/sh
# Packages the Linux site agent (run inside Setu's Gradle image by scripts/build-linux-agent.ps1):
#   bimba-site-agent_all.deb         Debian 11+, Ubuntu 20.04+, Raspberry Pi OS (arm64 + armhf), industrial Debian images
#   bimba-site-agent-linux.tar.gz    any systemd Linux: RHEL / Rocky / Alma 8+, SUSE, ... (install.sh)
#   release-linux.json               version, size and SHA-256 (shown in the Setu console)
# The jars are plain Java, so one package serves every CPU (x86-64, aarch64, armv7); it needs Java 17+.
# usage: build-packages.sh <setu source dir> <output dir>
set -eu
SRC=$1
OUT=$2
PKG=$SRC/agent/packaging
LIB=$SRC/agent/build/agent/lib
VERSION=$(sed -n "s/^version = '\(.*\)'.*/\1/p" "$SRC/agent/build.gradle")
MAINTAINER=${BIMBA_DEB_MAINTAINER:-BIMBA <support@bimba.invalid>}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
[ -f "$LIB/bimba-site-agent.jar" ] || { echo "no $LIB: run gradle :agent:agentDist first" >&2; exit 1; }

lf() { sed 's/\r$//' "$1"; }            # sources may come from a Windows checkout

# ------------------------------------------------------------------ .deb
D=$WORK/deb
mkdir -p "$D/DEBIAN" "$D/opt/bimba-site-agent/bin" "$D/opt/bimba-site-agent/lib" "$D/lib/systemd/system" \
         "$D/etc/default" "$D/usr/bin" "$D/usr/share/doc/bimba-site-agent"
lf "$PKG/bin/bimba-site-agent" > "$D/opt/bimba-site-agent/bin/bimba-site-agent"
cp "$LIB"/*.jar "$D/opt/bimba-site-agent/lib/"
echo "$VERSION" > "$D/opt/bimba-site-agent/VERSION"
lf "$PKG/systemd/bimba-site-agent.service" > "$D/lib/systemd/system/bimba-site-agent.service"
lf "$PKG/systemd/bimba-site-agent-update.path" > "$D/lib/systemd/system/bimba-site-agent-update.path"
lf "$PKG/systemd/bimba-site-agent-update.service" > "$D/lib/systemd/system/bimba-site-agent-update.service"
lf "$PKG/default/bimba-site-agent" > "$D/etc/default/bimba-site-agent"
ln -s /opt/bimba-site-agent/bin/bimba-site-agent "$D/usr/bin/bimba-site-agent"
for s in postinst prerm postrm; do lf "$PKG/debian/$s" > "$D/DEBIAN/$s"; done
echo "/etc/default/bimba-site-agent" > "$D/DEBIAN/conffiles"
cat > "$D/usr/share/doc/bimba-site-agent/copyright" <<EOF
BIMBA Site Agent $VERSION. Includes Eclipse Milo (EPL-2.0), j2mod (Apache-2.0), Netty (Apache-2.0),
Bouncy Castle (MIT), Gson (Apache-2.0), Guava (Apache-2.0), SLF4J (MIT), JAXB (EDL-1.0).
EOF
SIZE=$(du -sk "$D/opt" | cut -f1)
cat > "$D/DEBIAN/control" <<EOF
Package: bimba-site-agent
Version: $VERSION
Architecture: all
Maintainer: $MAINTAINER
Section: net
Priority: optional
Installed-Size: $SIZE
Depends: openjdk-17-jre-headless | openjdk-21-jre-headless | openjdk-25-jre-headless | java17-runtime-headless | temurin-17-jre | temurin-21-jre | temurin-17-jdk | temurin-21-jdk, passwd
Description: BIMBA Site Agent - sends this computer's SCADA data to the BIMBA Setu
 Registers the computer with its organization on a BIMBA Setu (enrollment token or
 organization user), signs in with its own client certificate (mutual TLS), reports
 that it is online and collects the data sources configured in the Setu console
 (OPC UA, Modbus TCP) with a disk buffer for Setu outages. Updates itself
 when Setu offers a newer installer (bimba-site-agent-update.path).
 Runs on x86-64, arm64 and armhf (Raspberry Pi) with Java 17 or newer.
EOF
chmod 0755 "$D/DEBIAN/postinst" "$D/DEBIAN/prerm" "$D/DEBIAN/postrm" "$D/opt/bimba-site-agent/bin/bimba-site-agent"
chmod 0644 "$D/opt/bimba-site-agent/lib/"*.jar
dpkg-deb --root-owner-group -Zxz --build "$D" "$OUT/bimba-site-agent_all.deb" >/dev/null

# ------------------------------------------------------------------ .tar.gz
T=$WORK/tar/bimba-site-agent
mkdir -p "$T/bin" "$T/lib" "$T/systemd" "$T/default"
lf "$PKG/bin/bimba-site-agent" > "$T/bin/bimba-site-agent"
cp "$LIB"/*.jar "$T/lib/"
lf "$PKG/systemd/bimba-site-agent.service" > "$T/systemd/bimba-site-agent.service"
lf "$PKG/systemd/bimba-site-agent-update.path" > "$T/systemd/bimba-site-agent-update.path"
lf "$PKG/systemd/bimba-site-agent-update.service" > "$T/systemd/bimba-site-agent-update.service"
lf "$PKG/default/bimba-site-agent" > "$T/default/bimba-site-agent"
lf "$PKG/install.sh" > "$T/install.sh"
lf "$PKG/uninstall.sh" > "$T/uninstall.sh"
echo "$VERSION" > "$T/VERSION"
chmod 0755 "$T/bin/bimba-site-agent" "$T/install.sh" "$T/uninstall.sh"
tar -C "$WORK/tar" --owner=0 --group=0 -czf "$OUT/bimba-site-agent-linux.tar.gz" bimba-site-agent

# ------------------------------------------------------------------ release-linux.json
describe() {
    printf '{"name":"%s","size":%s,"sha256":"%s"}' "$(basename "$1")" "$(stat -c %s "$1")" "$(sha256sum "$1" | cut -d' ' -f1)"
}
printf '{"product":"BIMBA Site Agent for Linux","version":"%s","builtAt":"%s","java":"17+","architectures":["x86_64","aarch64","armv7l"],"files":[%s,%s]}\n' \
    "$VERSION" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(describe "$OUT/bimba-site-agent_all.deb")" \
    "$(describe "$OUT/bimba-site-agent-linux.tar.gz")" > "$OUT/release-linux.json"
ls -l "$OUT/bimba-site-agent_all.deb" "$OUT/bimba-site-agent-linux.tar.gz"
