package com.bimba.gateway.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Profile photos are recognised by their content; SVG / HTML disguised as an image are refused. */
class AvatarAndEmailTest {

    @Test
    void recognisesPngJpegWebpByContent() {
        assertEquals("image/png", SecurityService.imageType(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0}));
        assertEquals("image/jpeg", SecurityService.imageType(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}));
        assertEquals("image/webp", SecurityService.imageType("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void refusesEverythingElse() {
        assertNull(SecurityService.imageType("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes()));
        assertNull(SecurityService.imageType("<html><body>hi</body></html>".getBytes()));
        assertNull(SecurityService.imageType("GIF89a......".getBytes()));
        assertNull(SecurityService.imageType(new byte[0]));
    }

    @Test
    void passwordMustNotContainTheEmailName() {
        assertEquals("ramanjaneyulu.chinthamani", SecurityService.localPart("ramanjaneyulu.chinthamani@easystepin.com"));
        assertEquals("", SecurityService.localPart(null));
    }
}
