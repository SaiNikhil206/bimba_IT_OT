from django.apps import AppConfig


class TelemetryConfig(AppConfig):
    # The schema (DB_SCHEMA) is pinned per statement by the database backend (bimba/pgbackend), not with a
    # session-level SET search_path: the pooler in front of the shared DB does not keep session settings.
    name = "telemetry"
    default_auto_field = "django.db.models.BigAutoField"
