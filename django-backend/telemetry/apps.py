from django.apps import AppConfig
from django.conf import settings
from django.db.backends.signals import connection_created


def _set_search_path(sender, connection, **kwargs):
    # Keep every BIMBA object (Django's tables included) in DB_SCHEMA, so the other tables
    # of a shared database are never read or modified. Set per connection rather than as a
    # startup "options" parameter, because the pooler in front of the shared DB rejects that.
    with connection.cursor() as cur:
        cur.execute(f'SET search_path TO "{settings.DB_SCHEMA}"')


class TelemetryConfig(AppConfig):
    name = "telemetry"
    default_auto_field = "django.db.models.BigAutoField"

    def ready(self):
        connection_created.connect(_set_search_path, dispatch_uid="bimba_search_path")
