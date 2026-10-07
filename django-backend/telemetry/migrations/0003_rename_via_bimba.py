from django.db import migrations

# The failover end system is now called "BIMBA Ingest": rename the delivery-path labels
# written by earlier versions (django-rest / django-mqtt) so charts keep one series per path.
TABLES = [("bimba_measurement", "received_via"), ("bimba_ingest_batch", "via"), ("bimba_tag", "last_via")]


def _rename(old_prefix, new_prefix):
    sql = []
    for table, column in TABLES:
        for kind in ("rest", "mqtt"):
            sql.append(f"UPDATE {table} SET {column} = '{new_prefix}-{kind}' WHERE {column} = '{old_prefix}-{kind}';")
    return "\n".join(sql)


class Migration(migrations.Migration):
    dependencies = [("telemetry", "0002_bimba_ingest_function")]

    operations = [
        migrations.RunSQL(_rename("django", "bimba"), reverse_sql=_rename("bimba", "django")),
    ]
