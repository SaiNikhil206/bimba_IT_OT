#!/bin/sh
# BIMBA Site Agent for Linux: installs from this unpacked folder on any systemd Linux (RHEL, Rocky, Alma, SUSE, Debian,
# Ubuntu, Raspberry Pi OS, industrial Debian images). Debian-based systems can use bimba-site-agent_all.deb instead.
#
#   sudo ./install.sh --server https://<setu>:8443 --token <enrollment token> [--site "<site>"] [--trust-cert <sha256>]
#   sudo ./install.sh                       files + service only; register later with: sudo bimba-site-agent install ...
# Upgrade: unpack the newer version and run sudo ./install.sh again (the registration is kept). From 1.10 on, the agent
# updates itself when Setu offers a newer installer (bimba-site-agent-update.path runs this script for it).
# Remove:  sudo /opt/bimba-site-agent/uninstall.sh
set -e

if [ "$(id -u)" != "0" ]; then
    echo "run as root: sudo $0 $*" >&2
    exit 5
fi
SRC=$(cd "$(dirname "$0")" && pwd)
DEST=/opt/bimba-site-agent
SYSTEMD=0
[ -d /run/systemd/system ] && SYSTEMD=1

if [ "$SYSTEMD" = 1 ]; then
    systemctl stop bimba-site-agent >/dev/null 2>&1 || true
fi

echo "Installing the BIMBA Site Agent $(cat "$SRC/VERSION" 2>/dev/null) into $DEST"
mkdir -p "$DEST"
rm -rf "$DEST/lib" "$DEST/bin"
cp -R "$SRC/bin" "$SRC/lib" "$DEST/"
if [ -d "$SRC/runtime" ]; then
    rm -rf "$DEST/runtime"
    cp -R "$SRC/runtime" "$DEST/"
fi
cp "$SRC/uninstall.sh" "$SRC/VERSION" "$DEST/"
chmod 755 "$DEST/bin/bimba-site-agent" "$DEST/uninstall.sh"
ln -sf "$DEST/bin/bimba-site-agent" /usr/local/bin/bimba-site-agent

if ! getent passwd bimba-agent >/dev/null 2>&1; then
    if command -v useradd >/dev/null 2>&1; then
        nologin=/usr/sbin/nologin
        [ -x "$nologin" ] || nologin=/sbin/nologin
        useradd --system --user-group --home-dir /var/lib/bimba-site-agent --no-create-home --shell "$nologin" \
            --comment "BIMBA Site Agent" bimba-agent
    else
        addgroup -S bimba-agent && adduser -S -D -H -h /var/lib/bimba-site-agent -s /sbin/nologin -G bimba-agent bimba-agent
    fi
fi
install -d -m 0700 -o bimba-agent -g bimba-agent /etc/bimba-site-agent /var/lib/bimba-site-agent
[ -f /etc/default/bimba-site-agent ] || install -m 0644 "$SRC/default/bimba-site-agent" /etc/default/bimba-site-agent

if [ "$SYSTEMD" = 1 ]; then
    install -m 0644 "$SRC/systemd/bimba-site-agent.service" /etc/systemd/system/bimba-site-agent.service
    install -m 0644 "$SRC/systemd/bimba-site-agent-update.path" /etc/systemd/system/bimba-site-agent-update.path
    install -m 0644 "$SRC/systemd/bimba-site-agent-update.service" /etc/systemd/system/bimba-site-agent-update.service
    systemctl daemon-reload
    systemctl enable bimba-site-agent-update.path >/dev/null 2>&1 || true
    systemctl start bimba-site-agent-update.path >/dev/null 2>&1 || true
fi

if ! "$DEST/bin/bimba-site-agent" version >/dev/null; then
    exit 1                                       # no Java 17+: the launcher said what to install
fi

if [ $# -gt 0 ]; then
    exec "$DEST/bin/bimba-site-agent" install "$@"
fi
if [ -f /etc/bimba-site-agent/agent.json ] && [ ! -f /var/lib/bimba-site-agent/uninstall-notified ]; then
    "$DEST/bin/bimba-site-agent" updater-setup >/dev/null 2>&1 || true
    if [ "$SYSTEMD" = 1 ]; then
        systemctl enable bimba-site-agent >/dev/null 2>&1 || true
        systemctl restart bimba-site-agent
    fi
    echo "Upgraded; the registration was kept (sudo bimba-site-agent status)."
else
    echo "Installed. Register this computer with your organization:"
    echo "  sudo bimba-site-agent install --server https://<setu>:8443 --token <enrollment token> [--site \"<site>\"]"
fi
if [ "$SYSTEMD" = 0 ]; then
    echo "No systemd here: start the agent with your init system as user bimba-agent: $DEST/bin/bimba-site-agent run"
fi
