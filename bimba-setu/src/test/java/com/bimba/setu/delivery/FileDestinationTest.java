package com.bimba.setu.delivery;

import com.bimba.setu.Json;
import com.bimba.setu.config.BimbaConfig;
import com.google.gson.JsonObject;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-END-02: files over SFTP and FTP, naming, atomic rename, batching window, archive */
class FileDestinationTest {
    private SshServer sshd;
    private FtpServer ftpd;

    @AfterEach
    void stop() throws Exception {
        if (sshd != null) {
            sshd.stop(true);
        }
        if (ftpd != null) {
            ftpd.stop();
        }
    }

    static JsonObject env(String batchId, String... machines) {
        StringBuilder sb = new StringBuilder("{\"schemaVersion\":\"1.3\",\"batchId\":\"" + batchId
                + "\",\"plantId\":\"P1\",\"edgeId\":\"gw\",\"createdAt\":\"2026-10-09T08:00:00Z\",\"count\":" + machines.length + ",\"messages\":[");
        for (int i = 0; i < machines.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"messageId\":\"").append(batchId).append('-').append(i).append("\",\"plantId\":\"P1\",\"machineId\":\"")
                    .append(machines[i]).append("\",\"tagName\":\"T\",\"tagValue\":").append(i + 0.5)
                    .append(",\"dataType\":\"number\",\"unit\":\"\",\"quality\":\"GOOD\",\"timestamp\":\"2026-10-09T08:00:0").append(i).append("Z\"}");
        }
        return Json.GSON.fromJson(sb.append("]}").toString(), JsonObject.class);
    }

    private static byte[] bytes(JsonObject env) {
        return Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    private int sftp(Path root, Path dir) throws Exception {
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser"));
        keys.loadKeys(null);                                         // made now, not during the first handshake
        sshd.setKeyPairProvider(keys);
        sshd.setPasswordAuthenticator((u, p, s) -> "bimba".equals(u) && "secret".equals(p));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
        sshd.start();
        return sshd.getPort();
    }

    private String fingerprint() throws Exception {
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, sshd.getKeyPairProvider().loadKeys(null).iterator().next().getPublic());
    }

    private static BimbaConfig.Destination cfg(String transport, String url) {
        BimbaConfig.Destination d = new BimbaConfig.Destination();
        d.name = "files";
        d.transport = transport;
        d.url = url;
        d.username = "bimba";
        d.password = "secret";
        d.format = "csv";
        d.timeoutMs = 15_000;
        d.healthCheck = false;
        return d;
    }

    private static List<String> names(Path folder) throws Exception {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void sftpWritesOneFilePerBatchUnderItsFinalNameOnly(@TempDir Path root, @TempDir Path dir) throws Exception {
        int port = sftp(root, dir);
        BimbaConfig.Destination c = cfg("sftp", "sftp://127.0.0.1:" + port + "/out/bimba");
        try (FileDestination d = new FileDestination(c, "P1", null)) {
            d.send(bytes(env("b1", "PUMP-1", "TANK-2")));
            d.send(bytes(env("b1", "PUMP-1", "TANK-2")));             // sent again (a retry): replaces the file
            List<String> files = names(root.resolve("out/bimba"));
            assertEquals(List.of("bimba_P1_20261009T080000Z_b1.csv"), files, "created the folder, one file, no .part left");
            String csv = Files.readString(root.resolve("out/bimba").resolve(files.get(0)));
            assertTrue(csv.startsWith("plantId,machineId,tagName,timestamp,tagValue"), csv);
            assertEquals(3, csv.split("\r\n").length, "header + 2 readings");
            String test = d.test();
            assertTrue(test.contains("folder /out/bimba is there") && test.contains("SHA256:"), test);
        }
    }

    @Test
    void sftpHostKeyIsPinned(@TempDir Path root, @TempDir Path dir) throws Exception {
        int port = sftp(root, dir);
        BimbaConfig.Destination c = cfg("sftp", "sftp://127.0.0.1:" + port + "/");
        c.hostKey = "SHA256:" + "A".repeat(43);
        try (FileDestination d = new FileDestination(c, "P1", null)) {
            Exception e = assertThrows(Exception.class, () -> d.send(bytes(env("b1", "M"))));
            assertTrue(e.getMessage().contains("not the one entered under Host key"), e.getMessage());
        }
        c.hostKey = fingerprint();
        try (FileDestination d = new FileDestination(c, "P1", null)) {
            d.send(bytes(env("b2", "M")));
            assertTrue(d.test().contains("host key matches"));
        }
        assertEquals(List.of("bimba_P1_20261009T080000Z_b2.csv"), names(root), "nothing was written to the wrong server");
        c.password = "wrong";
        try (FileDestination d = new FileDestination(c, "P1", null)) {
            Exception e = assertThrows(Exception.class, () -> d.send(bytes(env("b3", "M"))));
            assertTrue(e.getMessage().contains("rejected the user name, password or key"), e.getMessage());
        }
    }

    @Test
    void windowCollectsBatchesIntoOneFileAndArchivesIt(@TempDir Path root, @TempDir Path dir, @TempDir File work) throws Exception {
        int port = sftp(root, dir);
        BimbaConfig.Destination c = cfg("sftp", "sftp://127.0.0.1:" + port + "/~/in");
        c.format = "json";
        c.windowSeconds = 60;
        c.archiveDays = 7;
        c.fileName = "w_{time:yyyyMMdd-HHmmss}_{count}.{ext}";
        try (FileDestination d = new FileDestination(c, "P1", work)) {
            long now = System.currentTimeMillis();
            d.stage(env("b1", "PUMP-1"), now);
            d.stage(env("b2", "PUMP-1", "TANK-2"), now);
            d.sendDue(now);
            assertTrue(names(root.resolve("in")).isEmpty(), "the window is still open");
            d.sendDue(now + 61_000);
            List<String> files = names(root.resolve("in"));
            assertEquals(1, files.size(), String.valueOf(files));
            assertTrue(files.get(0).startsWith("w_") && files.get(0).endsWith("_3.json"), "3 readings, the window's start in the name: " + files);
            JsonObject sent = Json.GSON.fromJson(Files.readString(root.resolve("in").resolve(files.get(0))), JsonObject.class);
            assertEquals(3, sent.getAsJsonArray("messages").size());
            assertEquals(3, sent.get("count").getAsInt());
            assertFalse(new File(work, "window").list().length > 0, "the window file is removed once sent");
            File[] days = new File(work, "archive").listFiles();
            assertEquals(1, days.length);
            assertEquals(files, List.of(days[0].list()));
            d.cleanArchive(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1));
            assertEquals(0, new File(work, "archive").listFiles().length, "copies past the retention are removed");
        }
    }

    @Test
    void fileNamesAreCheckedAndCleaned() {
        assertThrows(IllegalArgumentException.class, () -> FileDestination.checkFileName("a/{batchId}.csv"));
        assertThrows(IllegalArgumentException.class, () -> FileDestination.checkFileName("{machine}.csv"));
        assertThrows(IllegalArgumentException.class, () -> FileDestination.checkFileName("data_{time:yyyyMMdd}.csv"), "two files a day would collide");
        assertThrows(IllegalArgumentException.class, () -> FileDestination.checkFileName("x_{time:qqqqqq-HHmmss}.csv"));
        FileDestination.checkFileName("x_{time:yyyyMMdd-HHmmss}.{ext}");
        assertEquals("a_b_P1.csv", FileDestination.fileName("a b_{plantId}.{ext}", env("b", "M"), "2026-10-09T08:00:00Z", "P1", "csv"));
    }

    @Test
    void ftpWritesTheSameFiles(@TempDir Path root) throws Exception {
        FtpServerFactory sf = new FtpServerFactory();
        ListenerFactory lf = new ListenerFactory();
        lf.setServerAddress("127.0.0.1");
        lf.setPort(0);
        sf.addListener("default", lf.createListener());
        BaseUser u = new BaseUser();
        u.setName("bimba");
        u.setPassword("secret");
        u.setHomeDirectory(root.toString());
        u.setAuthorities(List.<Authority>of(new WritePermission()));
        sf.getUserManager().save(u);
        ftpd = sf.createServer();
        ftpd.start();
        int port = sf.getListener("default").getPort();
        BimbaConfig.Destination c = cfg("ftp", "ftp://127.0.0.1:" + port + "/out");
        c.format = "xml";
        try (FileDestination d = new FileDestination(c, "P1", null)) {
            d.send(bytes(env("b9", "M")));
            assertEquals(List.of("bimba_P1_20261009T080000Z_b9.xml"), names(root.resolve("out")));
            assertTrue(Files.readString(root.resolve("out/bimba_P1_20261009T080000Z_b9.xml")).contains("<reading>"));
            assertTrue(d.test().contains("folder /out is there"), d.test());
        }
        c.password = "wrong";
        try (FileDestination d = new FileDestination(c, "P1", null)) {
            Exception e = assertThrows(Exception.class, () -> d.send(bytes(env("b10", "M"))));
            assertTrue(e.getMessage().contains("rejected the user name or password"), e.getMessage());
        }
    }
}
