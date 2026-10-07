from django.contrib import admin

from .models import IngestBatch, Machine, Measurement, Tag


@admin.register(Machine)
class MachineAdmin(admin.ModelAdmin):
    list_display = ("plant_id", "machine_id", "first_seen", "last_seen")


@admin.register(Tag)
class TagAdmin(admin.ModelAdmin):
    list_display = ("machine", "name", "unit", "last_value_num", "last_value_text", "last_quality", "last_time",
                    "last_via")
    list_filter = ("machine", "last_via")


@admin.register(Measurement)
class MeasurementAdmin(admin.ModelAdmin):
    list_display = ("time", "tag", "value_num", "value_text", "quality", "received_via", "seq")
    list_filter = ("received_via", "quality")
    list_select_related = ("tag", "tag__machine")
    show_full_result_count = False


@admin.register(IngestBatch)
class IngestBatchAdmin(admin.ModelAdmin):
    list_display = ("received_at", "via", "edge_id", "message_count", "inserted_count", "duplicate_count",
                    "first_seq", "last_seq")
    list_filter = ("via",)
