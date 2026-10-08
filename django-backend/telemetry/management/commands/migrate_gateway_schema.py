from django.conf import settings
from django.core.management import call_command
from django.core.management.base import BaseCommand
from django.db import connection

from bimba.pgbackend.base import pinned


class Command(BaseCommand):
    help = """Applies the migrations (+ install_sql) to the BIMBA Gateway's own schema (GATEWAY_DB_SCHEMA, e.g. bimba_ignition).

The gateway (Java) reads and writes its tables in that schema, but Django migrations still define them. Every statement
is pinned to that schema by the database backend (bimba/pgbackend), so nothing can land in DB_SCHEMA or public."""

    def handle(self, *args, **options):
        schema = getattr(settings, "GATEWAY_DB_SCHEMA", "") or ""
        if not schema or schema == settings.DB_SCHEMA:
            self.stdout.write("gateway schema = Django schema: nothing extra to migrate")
            return
        connection.close()
        with pinned(schema):
            with connection.cursor() as cur:
                cur.execute(f'CREATE SCHEMA IF NOT EXISTS "{schema}"')
            call_command("migrate", interactive=False, verbosity=1)
            call_command("install_sql")
        connection.close()
        self.stdout.write(f"gateway schema {schema} up to date")
