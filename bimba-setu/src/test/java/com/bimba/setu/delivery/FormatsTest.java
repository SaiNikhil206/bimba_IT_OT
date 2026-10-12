package com.bimba.setu.delivery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatsTest {
    private static final String BATCH = """
            {"schemaVersion":"1.0","batchId":"b1","plantId":"PLANT01","edgeId":"gw1","count":3,"messages":[
             {"messageId":"m1","plantId":"PLANT01","machineId":"Press 1","timestamp":"2026-10-07T10:00:00.123Z","tagName":"Temp,C",
              "tagValue":21.5,"dataType":"number","unit":"C","quality":"GOOD"},
             {"messageId":"m2","plantId":"PLANT01","machineId":"M2","timestamp":"2026-10-07T10:00:01Z","tagName":"Run",
              "tagValue":true,"dataType":"boolean","unit":"","quality":"GOOD"},
             {"messageId":"m3","plantId":"PLANT01","machineId":"M2","timestamp":"2026-10-07T10:00:02Z","tagName":"Mode",
              "tagValue":"say \\"hi\\"","dataType":"string","unit":"","quality":"GOOD"}]}""";

    private static String render(String format, String url) {
        return new String(Formats.render(format, BATCH.getBytes(StandardCharsets.UTF_8), url).bytes(), StandardCharsets.UTF_8);
    }

    @Test
    void influxLineProtocol() {
        String[] lines = render("influx-line", "http://influx:8086/api/v2/write?org=o&bucket=b&precision=ms").split("\n");
        assertEquals(3, lines.length);
        assertEquals("bimba,plant=PLANT01,machine=Press\\ 1,tag=Temp\\,C,quality=GOOD,unit=C value=21.5 1791367200123", lines[0]);
        assertEquals("bimba,plant=PLANT01,machine=M2,tag=Run,quality=GOOD value_bool=true 1791367201000", lines[1]);
        assertEquals("bimba,plant=PLANT01,machine=M2,tag=Mode,quality=GOOD value_str=\"say \\\"hi\\\"\" 1791367202000", lines[2]);
        assertTrue(render("influx-line", "http://influx:8086/api/v2/write?bucket=b").startsWith(
                "bimba,plant=PLANT01,machine=Press\\ 1,tag=Temp\\,C,quality=GOOD,unit=C value=21.5 1791367200123000000"));
    }

    @Test
    void elasticBulkUsesMessageIdAsDocumentId() {
        String[] lines = render("elastic-bulk", "http://es:9200/bimba/_bulk").split("\n");
        assertEquals(6, lines.length);
        assertEquals("{\"index\":{\"_id\":\"m1\"}}", lines[0]);
        assertTrue(lines[1].contains("\"@timestamp\":\"2026-10-07T10:00:00.123Z\""));
    }

    @Test
    void splunkEvents() {
        String first = render("splunk-hec", "https://splunk:8088/services/collector/event").split("\n")[0];
        assertTrue(first.startsWith("{\"time\":1791367200.123,\"host\":\"gw1\",\"source\":\"bimba-gateway\",\"sourcetype\":\"_json\",\"event\":{"));
    }

    @Test
    void envelopeIsUnchangedAndNdjsonHasOneLinePerMessage() {
        assertEquals(BATCH, render("envelope", ""));
        assertEquals(3, render("ndjson", "").split("\n").length);
    }
}
