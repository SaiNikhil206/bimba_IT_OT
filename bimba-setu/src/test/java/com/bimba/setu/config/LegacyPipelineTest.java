package com.bimba.setu.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPipelineTest {
    private static JsonObject d(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void rolesBecomeOnePipeline() {
        JsonObject p = DestinationStore.legacyPipeline(List.of(
                d("{\"id\":1,\"name\":\"es\",\"role\":\"primary\",\"enabled\":true}"),
                d("{\"id\":2,\"name\":\"atom\",\"role\":\"primary\",\"enabled\":false}"),
                d("{\"id\":3,\"name\":\"ingest\",\"role\":\"failover\"}"),
                d("{\"id\":4,\"name\":\"kafka\",\"role\":\"mirror\",\"enabled\":true}"),
                d("{\"id\":5,\"name\":\"hook\",\"role\":\"mirror\",\"enabled\":false}")));
        assertEquals("Plant data", p.get("name").getAsString());
        assertTrue(p.get("active").getAsBoolean());
        assertEquals("all", p.getAsJsonObject("machines").get("mode").getAsString());
        assertEquals(1, p.get("primary").getAsLong());
        assertEquals("[3]", p.get("secondary").toString());
        assertEquals("[4]", p.get("mirrors").toString());
        assertTrue(p.get("legacyBuffer").getAsBoolean());
    }

    @Test
    void failoverBecomesPrimaryWhenNoPrimaryIsEnabled() {
        JsonObject p = DestinationStore.legacyPipeline(List.of(
                d("{\"name\":\"es\",\"role\":\"primary\",\"enabled\":false}"),
                d("{\"name\":\"ingest\",\"role\":\"failover\",\"enabled\":true}")));
        assertEquals("ingest", p.get("primary").getAsString());
        assertEquals(0, p.getAsJsonArray("secondary").size());
    }

    @Test
    void mirrorsOnlyGiveADraftAndNothingGivesNoPipeline() {
        JsonObject p = DestinationStore.legacyPipeline(List.of(d("{\"id\":4,\"name\":\"kafka\",\"role\":\"mirror\"}")));
        assertFalse(p.get("active").getAsBoolean());
        assertFalse(p.has("primary"));
        assertNull(DestinationStore.legacyPipeline(List.of(d("{\"id\":1,\"name\":\"es\",\"enabled\":false}"))));
    }
}
