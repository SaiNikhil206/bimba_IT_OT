package com.bimba.gateway;

import com.bimba.gateway.config.BimbaConfig;
import com.bimba.gateway.security.Passwords;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Accounts are shared with Django, so both sides must read each other's password hashes. */
class PasswordsTest {
    /** made by Django 4.0: make_password('Bimba-Test-2026!', salt='bimbatestsalt', hasher='pbkdf2_sha256') */
    private static final String DJANGO_HASH = "pbkdf2_sha256$320000$bimbatestsalt$3s69RuyVLKB+pUeldA4nTeeK9rr4buXl3/oDpWww5aw=";

    @Test
    void readsDjangoHashes() {
        assertTrue(Passwords.matches("Bimba-Test-2026!", DJANGO_HASH));
        assertFalse(Passwords.matches("bimba-test-2026!", DJANGO_HASH));
        assertFalse(Passwords.matches("anything", "!unusable"));
        assertFalse(Passwords.matches("anything", null));
    }

    @Test
    void writesDjangoFormat() {
        String h = Passwords.hash("Another-Secret-42");
        assertTrue(h.matches("pbkdf2_sha256\\$320000\\$[A-Za-z0-9]{22}\\$[A-Za-z0-9+/=]{44}"), h);
        assertTrue(Passwords.matches("Another-Secret-42", h));
    }

    @Test
    void policy() {
        BimbaConfig.Security s = new BimbaConfig.Security();
        assertEquals(2, Passwords.check("short", "olga", s).size());              // length + character classes
        assertTrue(Passwords.check("Good-Password-123", "olga", s).isEmpty());
        assertEquals(1, Passwords.check("Olga-Password-123", "olga", s).size());  // contains the user name
        for (int i = 0; i < 20; i++) {
            assertTrue(Passwords.check(Passwords.temporary(), "someone", s).isEmpty(), "temporary passwords meet the policy");
        }
    }
}
