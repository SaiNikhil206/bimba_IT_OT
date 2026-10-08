import django.utils.timezone
from django.db import migrations, models

# Delivery destinations of the BIMBA Gateway move from config.json into the database, where the gateway console
# (Delivery) manages them. The gateway imports the config.json destinations once on its first start with this table.
# The gateway (Java) writes these tables directly, so the defaults are also set in the database.


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0005_gateway_security"),
    ]

    operations = [
        migrations.CreateModel(
            name="GatewayDestination",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("name", models.CharField(max_length=64, unique=True)),
                ("service", models.CharField(blank=True, default="", max_length=40)),
                ("position", models.IntegerField(default=0)),
                ("config", models.JSONField(default=dict)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("updated_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("updated_by", models.CharField(blank=True, default="", max_length=150)),
            ],
            options={"db_table": "bimba_gw_destination", "ordering": ["position", "id"]},
        ),
        migrations.CreateModel(
            name="GatewaySetting",
            fields=[
                ("key", models.CharField(max_length=80, primary_key=True, serialize=False)),
                ("value", models.JSONField(default=dict)),
                ("updated_at", models.DateTimeField(default=django.utils.timezone.now)),
            ],
            options={"db_table": "bimba_gw_setting"},
        ),
        migrations.RunSQL(
            """
            ALTER TABLE bimba_gw_destination ALTER COLUMN service SET DEFAULT '', ALTER COLUMN position SET DEFAULT 0,
                ALTER COLUMN config SET DEFAULT '{}'::jsonb, ALTER COLUMN created_at SET DEFAULT now(),
                ALTER COLUMN updated_at SET DEFAULT now(), ALTER COLUMN updated_by SET DEFAULT '';
            CREATE UNIQUE INDEX bimba_gw_destination_lower_name ON bimba_gw_destination (lower(name));
            ALTER TABLE bimba_gw_setting ALTER COLUMN value SET DEFAULT '{}'::jsonb, ALTER COLUMN updated_at SET DEFAULT now();
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
    ]
