"""BIMBA data model (PostgreSQL). Shared by both end systems:
BIMBA Ingest (Django) writes through the API/MQTT ingest, Boomi writes via SELECT bimba_ingest(...).
Rows are created by the bimba_ingest() SQL function, so the timestamp defaults are also set in the DB (DEFAULT now(), see migration 0001)."""
from django.db import models
from django.utils import timezone


class Machine(models.Model):
    plant_id = models.CharField(max_length=64)
    machine_id = models.CharField(max_length=128)
    first_seen = models.DateTimeField(default=timezone.now)
    last_seen = models.DateTimeField(null=True)

    class Meta:
        db_table = "bimba_machine"
        constraints = [models.UniqueConstraint(fields=["plant_id", "machine_id"], name="bimba_machine_uq")]
        ordering = ["plant_id", "machine_id"]

    def __str__(self):
        return f"{self.plant_id}/{self.machine_id}"


class Tag(models.Model):
    machine = models.ForeignKey(Machine, on_delete=models.CASCADE, related_name="tags")
    name = models.CharField(max_length=255)
    unit = models.CharField(max_length=32, null=True, blank=True)
    data_type = models.CharField(max_length=16, null=True, blank=True)
    source_path = models.CharField(max_length=512, null=True, blank=True)
    first_seen = models.DateTimeField(default=timezone.now)
    # latest value, maintained by bimba_ingest() for fast live views
    last_value_num = models.FloatField(null=True)
    last_value_text = models.TextField(null=True)
    last_quality = models.CharField(max_length=16, null=True)
    last_time = models.DateTimeField(null=True)
    last_via = models.CharField(max_length=32, null=True)

    class Meta:
        db_table = "bimba_tag"
        constraints = [models.UniqueConstraint(fields=["machine", "name"], name="bimba_tag_uq")]
        ordering = ["machine", "name"]

    def __str__(self):
        return f"{self.machine}/{self.name}"


class Measurement(models.Model):
    VIA_CHOICES = [(v, v) for v in ("boomi-es", "boomi-rest", "boomi-mqtt", "bimba-rest", "bimba-mqtt")]

    tag = models.ForeignKey(Tag, on_delete=models.CASCADE, related_name="measurements")
    time = models.DateTimeField()
    value_num = models.FloatField(null=True)
    value_text = models.TextField(null=True)
    quality = models.CharField(max_length=16)
    message_id = models.UUIDField(unique=True)          # idempotent ingest
    seq = models.BigIntegerField(null=True)             # plugin sequence number (loss audit)
    received_via = models.CharField(max_length=32)
    received_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "bimba_measurement"
        indexes = [models.Index(fields=["tag", "-time"], name="bimba_meas_tag_time")]


class IngestBatch(models.Model):
    """One row per batch received by an end system (Boomi or Django)."""
    batch_id = models.UUIDField(null=True, db_index=True)
    plant_id = models.CharField(max_length=64, null=True)
    edge_id = models.CharField(max_length=128, null=True)
    via = models.CharField(max_length=32)
    received_at = models.DateTimeField(default=timezone.now, db_index=True)
    message_count = models.IntegerField()
    inserted_count = models.IntegerField()
    duplicate_count = models.IntegerField()
    first_seq = models.BigIntegerField(null=True)
    last_seq = models.BigIntegerField(null=True)
    event_time_min = models.DateTimeField(null=True)
    event_time_max = models.DateTimeField(null=True)

    class Meta:
        db_table = "bimba_ingest_batch"
        ordering = ["-received_at"]
