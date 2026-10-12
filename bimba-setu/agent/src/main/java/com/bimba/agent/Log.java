package com.bimba.agent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Log lines on stderr. Under systemd (JOURNAL_STREAM set) each line carries its syslog priority ("&lt;4&gt;...") so
 * journalctl shows warnings and errors as such and adds its own time stamp; elsewhere lines are time-stamped.
 * Read with: journalctl -u bimba-site-agent
 */
final class Log {
    private static final boolean JOURNAL = System.getenv("JOURNAL_STREAM") != null;

    private Log() {
    }

    static void info(String msg) {
        write(6, "INFO ", msg);
    }

    static void warn(String msg) {
        write(4, "WARN ", msg);
    }

    static void error(String msg) {
        write(3, "ERROR", msg);
    }

    private static void write(int priority, String level, String msg) {
        String line = JOURNAL ? "<" + priority + ">" + msg : Instant.now().truncatedTo(ChronoUnit.MILLIS) + " " + level + " " + msg;
        synchronized (System.err) {
            System.err.println(line);
            System.err.flush();
        }
    }
}
