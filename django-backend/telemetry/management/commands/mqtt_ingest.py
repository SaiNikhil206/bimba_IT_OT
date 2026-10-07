"""Django MQTT ingest: subscribes to the plugin's Django topic and stores batches.

    python manage.py mqtt_ingest

Persistent session + QoS 1 + manual ack: a message is acknowledged only after
the DB transaction commits, so a crash never loses a batch (it is redelivered
and de-duplicated on messageId)."""
import json
import logging
import time

import paho.mqtt.client as mqtt
from django.conf import settings
from django.core.management.base import BaseCommand
from django.db import DatabaseError, close_old_connections

from telemetry.ingest import ingest_batch

log = logging.getLogger("telemetry.mqtt")


class Command(BaseCommand):
    help = "Consume BIMBA canonical batches from MQTT and store them"

    def handle(self, *args, **options):
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="bimba-django-ingest",
                             clean_session=False, manual_ack=True)
        client.reconnect_delay_set(1, 30)

        def on_connect(c, userdata, flags, reason_code, properties):
            log.info("connected to %s:%s (%s), subscribing %s", settings.MQTT_HOST, settings.MQTT_PORT,
                     reason_code, settings.MQTT_TOPIC)
            c.subscribe(settings.MQTT_TOPIC, qos=1)

        def on_message(c, userdata, msg):
            while True:
                try:
                    close_old_connections()
                    result = ingest_batch(json.loads(msg.payload), "bimba-mqtt")
                    log.info("stored %s from %s: %s", result.get("received"), msg.topic, result)
                    break
                except ValueError:
                    log.warning("dropping non-JSON message on %s", msg.topic)
                    break
                except DatabaseError as exc:
                    log.warning("db error, retrying in 2s: %s", exc)
                    time.sleep(2)
            c.ack(msg.mid, msg.qos)

        client.on_connect = on_connect
        client.on_message = on_message
        while True:
            try:
                client.connect(settings.MQTT_HOST, settings.MQTT_PORT, keepalive=30)
                break
            except OSError as exc:
                log.info("waiting for broker: %s", exc)
                time.sleep(3)
        client.loop_forever(retry_first_connection=True)
