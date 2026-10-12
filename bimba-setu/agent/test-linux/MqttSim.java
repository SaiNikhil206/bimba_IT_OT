import com.bimba.setu.collect.SparkplugB;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * TEST ONLY: MQTT simulator for the site agent's MQTT sources, our own (no vendor software). Publishes every second:
 *   sim/line1/temperature, sim/line1/running         plain values (retained)
 *   sim/line1/status                                  JSON {"motor":{"speed":..,"current":..},"mode":"AUTO"}
 *   spBv1.0/SimPlant/{NBIRTH,NDATA}/Line1 + DBIRTH / DDATA Line1/Press1   Sparkplug B with aliases, answers rebirth NCMDs
 * Run: java -cp "<agent lib>/*" MqttSim.java tcp://broker:1883
 */
public class MqttSim {
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "tcp://localhost:1883";
        MqttClient c = new MqttClient(url, "bimba-mqtt-sim", new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setAutomaticReconnect(true);
        long[] bdSeq = {0};
        // NDEATH as the will, like a real edge node
        o.setWill("spBv1.0/SimPlant/NDEATH/Line1", SparkplugB.encode(System.currentTimeMillis(), null,
                List.of(new SparkplugB.Metric("bdSeq", null, null, SparkplugB.INT64, false, bdSeq[0]))), 0, false);
        c.connect(o);
        long[] seq = {0};
        Runnable birth = () -> {
            try {
                long t = System.currentTimeMillis();
                seq[0] = 0;
                pub(c, "spBv1.0/SimPlant/NBIRTH/Line1", SparkplugB.encode(t, seq[0]++, List.of(
                        new SparkplugB.Metric("bdSeq", null, t, SparkplugB.INT64, false, bdSeq[0]),
                        new SparkplugB.Metric("Node Control/Rebirth", null, t, SparkplugB.BOOLEAN, false, false),
                        new SparkplugB.Metric("Line/Speed", 1L, t, SparkplugB.DOUBLE, false, 0.0),
                        new SparkplugB.Metric("Line/Mode", 2L, t, SparkplugB.STRING, false, "AUTO"))));
                pub(c, "spBv1.0/SimPlant/DBIRTH/Line1/Press1", SparkplugB.encode(t, seq[0]++, List.of(
                        new SparkplugB.Metric("Hydraulic/Pressure", 10L, t, SparkplugB.FLOAT, false, 0f),
                        new SparkplugB.Metric("Cycles", 11L, t, SparkplugB.INT32, false, 0),
                        new SparkplugB.Metric("Running", 12L, t, SparkplugB.BOOLEAN, false, true))));
                System.out.println("births published");
            } catch (Exception e) {
                System.out.println("birth: " + e);
            }
        };
        // never publish from Paho's callback thread: the main loop sends the births
        java.util.concurrent.atomic.AtomicBoolean rebirth = new java.util.concurrent.atomic.AtomicBoolean();
        c.subscribe("spBv1.0/SimPlant/NCMD/Line1", (topic, m) -> {
            System.out.println("NCMD received: rebirth");
            rebirth.set(true);
        });
        birth.run();
        long start = System.currentTimeMillis();
        while (true) {
            if (rebirth.getAndSet(false)) {
                birth.run();
            }
            double s = (System.currentTimeMillis() - start) / 1000.0;
            long t = System.currentTimeMillis();
            retained(c, "sim/line1/temperature", String.format("%.2f", 20 + 5 * Math.sin(s / 20)));
            retained(c, "sim/line1/running", ((long) s / 10) % 2 == 0 ? "true" : "false");
            retained(c, "sim/line1/status", String.format("{\"motor\":{\"speed\":%.1f,\"current\":%.2f},\"mode\":\"AUTO\",\"alarms\":[]}",
                    1450 + 30 * Math.sin(s / 7), 12 + Math.cos(s / 5)));
            // report by exception, aliases only
            pub(c, "spBv1.0/SimPlant/NDATA/Line1", SparkplugB.encode(t, seq[0]++ % 256, List.of(
                    new SparkplugB.Metric(null, 1L, t, SparkplugB.DOUBLE, false, 120 + 10 * Math.sin(s / 15)))));
            pub(c, "spBv1.0/SimPlant/DDATA/Line1/Press1", SparkplugB.encode(t, seq[0]++ % 256, List.of(
                    new SparkplugB.Metric(null, 10L, t, SparkplugB.FLOAT, false, (float) (210 + 15 * Math.sin(s / 9))),
                    new SparkplugB.Metric(null, 11L, t, SparkplugB.INT32, false, (int) s))));
            Thread.sleep(1000);
        }
    }

    private static void pub(MqttClient c, String topic, byte[] payload) throws Exception {
        c.publish(topic, payload, 0, false);
    }

    private static void retained(MqttClient c, String topic, String text) throws Exception {
        MqttMessage m = new MqttMessage(text.getBytes(StandardCharsets.UTF_8));
        m.setRetained(true);
        c.publish(topic, m);
    }
}
