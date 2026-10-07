from django.conf import settings
from django.core.management.base import BaseCommand
from django.db import connection


class Command(BaseCommand):
    help = "Creates the BIMBA schema (DB_SCHEMA) if missing. Runs before migrate; touches nothing else"

    def handle(self, *args, **options):
        schema = settings.DB_SCHEMA
        with connection.cursor() as cur:
            cur.execute(f'CREATE SCHEMA IF NOT EXISTS "{schema}"')
        self.stdout.write(f"schema {schema} ready")
