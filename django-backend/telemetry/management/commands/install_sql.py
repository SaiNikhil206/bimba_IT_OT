from pathlib import Path

from django.core.management.base import BaseCommand
from django.db import connection

SQL_DIR = Path(__file__).resolve().parents[2] / "sql"


class Command(BaseCommand):
    help = "(Re)installs the shared SQL functions (CREATE OR REPLACE) used by Django and Boomi"

    def handle(self, *args, **options):
        for f in sorted(SQL_DIR.glob("*.sql")):
            with connection.cursor() as cur:
                cur.execute(f.read_text(encoding="utf-8"))
            self.stdout.write(f"installed {f.name}")
