#!/bin/sh
# Removes the BIMBA Site Agent installed with install.sh: tells Setu, deletes the registration, buffered
# readings, program files and the service account.   sudo /opt/bimba-site-agent/uninstall.sh
set -e
if [ "$(id -u)" != "0" ]; then
    echo "run as root: sudo $0" >&2
    exit 5
fi
DEST=/opt/bimba-site-agent
"$DEST/bin/bimba-site-agent" uninstall || true
if [ -d /run/systemd/system ]; then
    systemctl disable --now bimba-site-agent-update.path >/dev/null 2>&1 || true
    rm -f /etc/systemd/system/bimba-site-agent.service /etc/systemd/system/bimba-site-agent-update.path \
        /etc/systemd/system/bimba-site-agent-update.service
    systemctl daemon-reload || true
fi
rm -f /usr/local/bin/bimba-site-agent /etc/default/bimba-site-agent
userdel bimba-agent >/dev/null 2>&1 || deluser bimba-agent >/dev/null 2>&1 || true
rm -rf "$DEST" /var/lib/bimba-site-agent-update
echo "BIMBA Site Agent removed."
