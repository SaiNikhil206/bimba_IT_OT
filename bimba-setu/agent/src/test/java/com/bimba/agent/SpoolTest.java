package com.bimba.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The disk buffer: a file is one upload, so it stays under Setu's 256 KB request limit, and a too big one is split. */
class SpoolTest {

    private static String reading(int i) {
        return "{\"messageId\":\"" + i + "\",\"machineId\":\"DNP3-SUB1\",\"tagName\":\"t\",\"pad\":\"" + "x".repeat(480) + "\"}";
    }

    private static List<String> lines(Path f) throws Exception {
        return Files.readAllLines(f, StandardCharsets.UTF_8).stream().filter(l -> l.length() > 1).toList();
    }

    @Test
    void filesStayUnderTheUploadLimit(@TempDir Path dir) throws Exception {
        Spool s = new Spool(dir, 1L << 30);
        for (int i = 0; i < 1000; i++) {                       // ~530 KB: was one file over 256 KB, refused with 413
            s.add(reading(i));
        }
        s.flush();
        int total = 0;
        for (Path f = s.oldest(); f != null; f = s.oldest()) {
            assertTrue(Files.size(f) <= Spool.MAX_FILE_CHARS, f + " is " + Files.size(f) + " bytes");
            total += lines(f).size();
            s.done(f);
        }
        assertEquals(1000, total);
    }

    @Test
    void aTooBigFileIsSplitInOrderNotDiscarded(@TempDir Path dir) throws Exception {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            all.add(reading(i));
        }
        // a file as an older agent wrote it (1000 messages, ~530 KB)
        Path old = dir.resolve("0017917870405940000-018959.jsonl");
        Files.writeString(old, String.join("\n", all) + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("0017917870426000000-018960.jsonl"), reading(1000) + "\n", StandardCharsets.UTF_8);
        Spool s = new Spool(dir, 1L << 30);

        List<String> uploaded = new ArrayList<>();
        for (Path f = s.oldest(); f != null; f = s.oldest()) {
            List<String> ls = lines(f);
            if (String.join(",", ls).length() > 250 * 1024 && s.split(f, ls)) {
                continue;                                      // what the upload loop does (also on 413)
            }
            uploaded.addAll(ls);
            s.done(f);
        }
        all.add(reading(1000));
        assertEquals(all, uploaded, "every reading, in the original order");
    }

    @Test
    void aSingleReadingCannotBeSplit(@TempDir Path dir) throws Exception {
        Spool s = new Spool(dir, 1L << 30);
        s.add(reading(1));
        s.flush();
        Path f = s.oldest();
        assertFalse(s.split(f, lines(f)));
        s.reject(f);
        assertNull(s.oldest());
        assertTrue(Files.exists(dir.resolve("rejected").resolve(f.getFileName())));
    }
}
