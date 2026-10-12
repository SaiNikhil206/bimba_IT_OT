package com.bimba.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/** systemd helpers for install / uninstall / status */
final class Platform {
    private static final String[] UNIT_DIRS = {"/etc/systemd/system", "/lib/systemd/system", "/usr/lib/systemd/system"};

    private Platform() {
    }

    /** true when systemd is the running init system */
    static boolean systemd() {
        return Files.isDirectory(Path.of("/run/systemd/system"));
    }

    static boolean unitInstalled() {
        for (String d : UNIT_DIRS) {
            if (Files.isRegularFile(Path.of(d, Paths.SERVICE + ".service"))) {
                return true;
            }
        }
        return false;
    }

    /** systemctl ... (throws with its message when it fails) */
    static void systemctl(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "systemctl";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        }
    }

    /** a command's output whatever its exit code, or null when it cannot run */
    static String output(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
