# Test computer for the Linux site agent: Debian 12 (the base of Raspberry Pi OS 12) with systemd as PID 1.
# Build per CPU:  docker build --platform linux/amd64|linux/arm64|linux/arm/v7 -t bimba-agent-test:<arch> .
# Run:            docker run -d --privileged --cgroupns=host -v /sys/fs/cgroup:/sys/fs/cgroup:rw --network bimba_default \
#                     -v <site-agent/dist>:/dist:ro --name agent-test bimba-agent-test:<arch>
# then inside:    apt-get update && apt-get install -y /dist/bimba-site-agent_all.deb   (pulls Java 17 as a dependency)
# See run-tests.sh.
FROM debian:bookworm
RUN apt-get update && apt-get install -y --no-install-recommends systemd systemd-sysv dbus ca-certificates procps \
 && rm -rf /var/lib/apt/lists/* \
 && systemctl mask systemd-logind.service getty.target console-getty.service
STOPSIGNAL SIGRTMIN+3
CMD ["/lib/systemd/systemd"]
