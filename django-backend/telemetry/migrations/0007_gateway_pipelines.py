import django.utils.timezone
from django.db import migrations, models

# Delivery pipelines of the BIMBA Gateway: a destination (connection) gets machine data only when a pipeline uses it as
# primary, secondary or mirror. The gateway turns its former role-based destination list into one pipeline on its
# first start with this table. The gateway (Java) writes the table directly, so the defaults are also set in the database.


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0006_gateway_destinations"),
    ]

    operations = [
        migrations.CreateModel(
            name="GatewayPipeline",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("name", models.CharField(max_length=80, unique=True)),
                ("description", models.CharField(blank=True, default="", max_length=300)),
                ("active", models.BooleanField(default=False)),
                ("position", models.IntegerField(default=0)),
                ("config", models.JSONField(default=dict)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("updated_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("updated_by", models.CharField(blank=True, default="", max_length=150)),
            ],
            options={"db_table": "bimba_gw_pipeline", "ordering": ["position", "id"]},
        ),
        migrations.RunSQL(
            """
            ALTER TABLE bimba_gw_pipeline ALTER COLUMN description SET DEFAULT '', ALTER COLUMN active SET DEFAULT false,
                ALTER COLUMN position SET DEFAULT 0, ALTER COLUMN config SET DEFAULT '{}'::jsonb,
                ALTER COLUMN created_at SET DEFAULT now(), ALTER COLUMN updated_at SET DEFAULT now(),
                ALTER COLUMN updated_by SET DEFAULT '';
            CREATE UNIQUE INDEX bimba_gw_pipeline_lower_name ON bimba_gw_pipeline (lower(name));
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
    ]
