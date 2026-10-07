from pathlib import Path

from django.db import migrations

SQL = (Path(__file__).resolve().parent.parent / "sql" / "bimba_ingest.sql").read_text(encoding="utf-8")


class Migration(migrations.Migration):
    dependencies = [("telemetry", "0001_initial")]

    operations = [
        migrations.RunSQL(SQL, reverse_sql="DROP FUNCTION IF EXISTS bimba_ingest(jsonb, text);"),
    ]
