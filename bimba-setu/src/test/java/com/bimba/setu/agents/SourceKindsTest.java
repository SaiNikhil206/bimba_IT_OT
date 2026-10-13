package com.bimba.setu.agents;

import com.bimba.setu.Json;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The data source field list (source-kinds.json): what the console gets, and how saved settings are checked with it. */
class SourceKindsTest {

    private static JsonObject s(String json) {
        return Json.GSON.fromJson(json, JsonObject.class);
    }

    private static String refused(String kind, String settings) {
        ApiException e = assertThrows(ApiException.class, () -> AgentSources.validateSettings(kind, s(settings)));
        return e.code + ": " + e.getMessage();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyTypeIsDescribed() {
        List<Map<String, Object>> kinds = (List<Map<String, Object>>) SourceKinds.definitions().get("kinds");
        assertEquals(List.of("opcua", "modbus", "mqtt", "s7", "eip", "dnp3", "iec104", "sql", "opcda"),
                kinds.stream().map(k -> k.get("id")).toList());
        for (Map<String, Object> k : kinds) {
            assertFalse(((List<?>) k.get("fields")).isEmpty(), k.get("id") + " has fields");
            assertTrue(k.containsKey("summary") && k.containsKey("tags") && k.containsKey("menu"), k.get("id") + " is complete");
        }
        assertEquals(List.of(102), SourceKinds.scanPorts("s7"));
        assertTrue(SourceKinds.scanPorts("opcda").isEmpty(), "OPC DA is not on the network");
        assertTrue(SourceKinds.names().endsWith(" or OPC DA"), SourceKinds.names());
    }

    @Test
    void tidiesWhatTheFormSends() throws Exception {
        JsonObject m = s("""
                {"host":" 10.0.0.5 ","port":"","unitId":"3","zeroBased":"true"}""");
        AgentSources.validateSettings("modbus", m);
        assertEquals("10.0.0.5", Json.str(m, "host"));
        assertFalse(m.has("port"), "an empty number is left to the agent's default");
        assertEquals(3, m.get("unitId").getAsInt());
        assertTrue(m.get("zeroBased").getAsBoolean());

        JsonObject q = s("""
                {"url":"mqtt://broker:1883","payload":"json","qos":"0"}""");
        AgentSources.validateSettings("mqtt", q);
        assertTrue(q.get("qos").getAsJsonPrimitive().isNumber(), "the option's own value (a number) is stored");

        JsonObject g = s("""
                {"host":"rtu1","commonAddress":2,"giMinutes":"","timeZone":" Europe/Berlin "}""");
        AgentSources.validateSettings("iec104", g);
        assertEquals(15, g.get("giMinutes").getAsInt(), "an empty choice gets its default");
        assertEquals("Europe/Berlin", Json.str(g, "timeZone"));

        JsonObject sql = s("""
                {"engine":"postgresql","host":"db1","instance":"SQLEXPRESS","layout":"rows","query":"SELECT tag, value FROM t WHERE tag IN ({tags})"}""");
        AgentSources.validateSettings("sql", sql);
        assertFalse(sql.has("instance"), "a SQL Server instance is dropped for PostgreSQL");
        assertEquals("off", Json.str(sql, "tls"));
    }

    @Test
    void refusesWithTheFieldsOwnMessage() {
        assertEquals("endpoint: Endpoint: an address like opc.tcp://localhost:62541.", refused("opcua", """
                {"endpoint":"http://x"}"""));
        assertEquals("username: Enter the OPC UA user name.", refused("opcua", """
                {"endpoint":"opc.tcp://x:4840","auth":"Username"}"""));
        assertEquals("port: Port: 1–65535.", refused("modbus", """
                {"host":"plc","port":70000}"""));
        assertEquals("cpu: CPU: S7_300, S7_400, S7_1200, S7_1500 or LOGO.", refused("s7", """
                {"host":"plc","cpu":"S7_200"}"""));
        assertEquals("master: The master address must differ from the outstation's.", refused("dnp3", """
                {"host":"rtu","outstation":5,"master":5}"""));
        assertTrue(refused("mqtt", """
                {"url":"mqtts://b:8883","payload":"value","caCert":"not a certificate"}""").startsWith("caCert:"));
        assertTrue(refused("sql", """
                {"engine":"sqlserver","host":"h","layout":"rows","query":"DELETE FROM t"}""").startsWith("query:"));
        assertTrue(refused("iec104", """
                {"host":"rtu","timeZone":"Mars/Base"}""").startsWith("timeZone:"));
        assertTrue(refused("nope", "{}").startsWith("kind:"));
    }

    @Test
    void savedSourcesStillSave() throws Exception {
        // what the API gives back for a stored source: numbers as 1.0, a greyed-out OPC UA message security "None"
        JsonObject ua = s("""
                {"auth":"Anonymous","mode":"None","policy":"None","endpoint":"opc.tcp://opcua-sim:4840/scada/","timeoutMs":4000.0}""");
        AgentSources.validateSettings("opcua", ua);
        assertEquals("None", Json.str(ua, "mode"));
        JsonObject mb = s("""
                {"host":"plc-sim","port":5020.0,"unitId":1.0,"timeoutMs":1500.0,"zeroBased":false}""");
        AgentSources.validateSettings("modbus", mb);
        assertEquals("1", mb.get("unitId").toString(), "stored as a whole number again");
        JsonObject mq = s("""
                {"qos":1.0,"url":"mqtt://broker:1883","payload":"sparkplug","rebirth":true}""");
        AgentSources.validateSettings("mqtt", mq);
        assertEquals("1", mq.get("qos").toString(), "the option's own value");
        assertTrue(refused("modbus", """
                {"host":"plc","unitId":1.5}""").startsWith("unitId:"));
    }

    @Test
    void checksOnlyTheFieldsShown() throws Exception {
        // LOGO! has no rack / slot: an odd stored slot does not block saving
        AgentSources.validateSettings("s7", s("""
                {"host":"logo1","cpu":"LOGO","slot":99}"""));
        // a directly addressed Logix controller: backplane -1 (refused before the field list)
        AgentSources.validateSettings("eip", s("""
                {"host":"plc","slot":0,"backplane":-1}"""));
    }

    @Test
    void netscanRequests() throws Exception {
        JsonObject r = AgentSources.netscan(s("""
                {"protocols":["modbus","opcda"],"subnets":["192.168.10.0/24"," "],"timeoutMs":50}"""));
        assertEquals("[\"modbus\"]", r.get("protocols").toString(), "types without ports are left out");
        assertEquals("[502]", r.getAsJsonObject("ports").get("modbus").toString());
        assertEquals(100, r.get("timeoutMs").getAsInt());
        assertEquals("modbus", r.getAsJsonObject("source").get("kind").getAsString(), "names a type the Windows agent relays");
        assertTrue(r.get("ownNetworks").getAsBoolean());

        JsonObject all = AgentSources.netscan(s("{}"));
        assertEquals(8, all.getAsJsonArray("protocols").size());
        assertThrows(ApiException.class, () -> AgentSources.netscan(s("""
                {"subnets":["10.0.0.0/16"]}""")));
        assertThrows(ApiException.class, () -> AgentSources.netscan(s("""
                {"subnets":["10.0.0.0/22","10.0.4.0/24"]}""")), "more than 1024 addresses");
        assertThrows(ApiException.class, () -> AgentSources.netscan(s("""
                {"subnets":["300.1.1.1"]}""")));
        assertThrows(ApiException.class, () -> AgentSources.netscan(s("""
                {"protocols":["opcda"]}""")));
        assertEquals("[\"plc7:5020\",\"10.0.0.5:502\"]", AgentSources.netscan(s("""
                {"subnets":["plc7:5020","10.0.0.5:502"]}""")).get("subnets").toString(), "an address with its port");
        assertThrows(ApiException.class, () -> AgentSources.netscan(s("""
                {"subnets":["plc7:70000"]}""")));
    }
}
