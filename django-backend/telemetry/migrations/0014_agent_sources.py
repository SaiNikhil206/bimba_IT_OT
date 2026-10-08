import django.db.models.deletion
import django.utils.timezone
from django.db import migrations, models

# BIMBA Site Agent as edge collector: the agent reads SCADA data sources on its machine (OPC UA, Modbus TCP, OPC DA)
# and pushes the readings to the gateway. The sources are configured in the gateway console (bimba_agent_source);
# console requests that need an answer from the machine (test a connection, browse tags) go through
# bimba_agent_command, which the agent long-polls. The gateway (Java) inserts with plain SQL, so the defaults are
# also set in the database.


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0013_public_uids"),
    ]

    operations = [
        migrations.AddField(model_name="agent", name="collector", field=models.JSONField(default=dict)),
        migrations.CreateModel(
            name="AgentSource",
            fields=[
                ("id", models.UUIDField(primary_key=True, serialize=False)),
                ("name", models.CharField(max_length=120)),
                ("kind", models.CharField(max_length=16)),
                ("settings", models.JSONField(default=dict)),
                ("tags", models.JSONField(default=list)),
                ("poll_ms", models.IntegerField(default=1000)),
                ("enabled", models.BooleanField(default=True)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("updated_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("created_by", models.CharField(blank=True, default="", max_length=200)),
                ("agent", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="sources",
                                            to="telemetry.agent")),
                ("org", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="+",
                                          to="telemetry.organization")),
            ],
            options={"db_table": "bimba_agent_source", "ordering": ["name"]},
        ),
        migrations.AddConstraint(
            model_name="agentsource",
            constraint=models.UniqueConstraint(fields=("name",), name="bimba_agent_source_name_uq"),
        ),
        migrations.CreateModel(
            name="AgentCommand",
            fields=[
                ("id", models.UUIDField(primary_key=True, serialize=False)),
                ("kind", models.CharField(max_length=16)),
                ("request", models.JSONField(default=dict)),
                ("state", models.CharField(default="pending", max_length=16)),
                ("result", models.JSONField(blank=True, null=True)),
                ("error", models.TextField(blank=True, default="")),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("sent_at", models.DateTimeField(blank=True, null=True)),
                ("done_at", models.DateTimeField(blank=True, null=True)),
                ("created_by", models.CharField(blank=True, default="", max_length=200)),
                ("agent", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="commands",
                                            to="telemetry.agent")),
                ("org", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="+",
                                          to="telemetry.organization")),
            ],
            options={"db_table": "bimba_agent_command"},
        ),
        migrations.AddIndex(
            model_name="agentcommand",
            index=models.Index(fields=["agent", "state"], name="bimba_agent_cmd_state_ix"),
        ),
        migrations.RunSQL(
            """
            ALTER TABLE bimba_agent ALTER COLUMN collector SET DEFAULT '{}'::jsonb;
            ALTER TABLE bimba_agent_source ALTER COLUMN settings SET DEFAULT '{}'::jsonb,
                ALTER COLUMN tags SET DEFAULT '[]'::jsonb, ALTER COLUMN poll_ms SET DEFAULT 1000,
                ALTER COLUMN enabled SET DEFAULT true, ALTER COLUMN created_at SET DEFAULT now(),
                ALTER COLUMN updated_at SET DEFAULT now(), ALTER COLUMN created_by SET DEFAULT '';
            ALTER TABLE bimba_agent_command ALTER COLUMN request SET DEFAULT '{}'::jsonb,
                ALTER COLUMN state SET DEFAULT 'pending', ALTER COLUMN error SET DEFAULT '',
                ALTER COLUMN created_at SET DEFAULT now(), ALTER COLUMN created_by SET DEFAULT '';
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
    ]
