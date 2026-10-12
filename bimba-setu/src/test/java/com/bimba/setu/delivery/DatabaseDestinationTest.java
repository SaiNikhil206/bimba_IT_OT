package com.bimba.setu.delivery;

import com.bimba.setu.config.BimbaConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseDestinationTest {

    private static BimbaConfig.Destination cfg() {
        BimbaConfig.Destination c = new BimbaConfig.Destination();
        c.name = "bimba-database";
        c.transport = "database";
        return c;
    }

    @Test
    void transportDatabaseIsTheBuiltInDestination() {
        assertInstanceOf(DatabaseDestination.class, Destination.create(cfg(), "PLANT01", null));
    }

    @Test
    void withoutADatabaseItFailsLikeADownDestination() {
        Destination d = Destination.create(cfg(), "PLANT01", null);
        byte[] batch = "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        // an IOException (not RejectedException): the route fails over / keeps the batch on disk
        Exception e = assertThrows(java.io.IOException.class, () -> d.send(batch));
        assertTrue(e.getMessage().contains("BIMBA_DB_"));
        assertThrows(java.io.IOException.class, d::test);
        assertEquals("BIMBA database", d.endpoint());
    }
}
