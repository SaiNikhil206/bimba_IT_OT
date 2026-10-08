import django.db.models.deletion
import django.utils.timezone
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ("auth", "0012_alter_user_first_name_max_length"),
        ("telemetry", "0003_rename_via_bimba"),
    ]

    operations = [
        migrations.CreateModel(
            name="Organization",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("slug", models.SlugField(max_length=64, unique=True)),
                ("name", models.CharField(max_length=200)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
            ],
            options={"db_table": "bimba_org", "ordering": ["name"]},
        ),
        migrations.CreateModel(
            name="OrgMember",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("role", models.CharField(choices=[("admin", "Admin"), ("member", "Member")], default="member", max_length=16)),
                ("org", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="members", to="telemetry.organization")),
                ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="bimba_memberships", to="auth.user")),
            ],
            options={"db_table": "bimba_org_member"},
        ),
        migrations.AddConstraint(
            model_name="orgmember",
            constraint=models.UniqueConstraint(fields=("org", "user"), name="bimba_org_member_uq"),
        ),
        migrations.CreateModel(
            name="EnrollToken",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("label", models.CharField(blank=True, max_length=200)),
                ("token_hash", models.CharField(max_length=64, unique=True)),
                ("prefix", models.CharField(max_length=16)),
                ("max_uses", models.IntegerField(blank=True, null=True)),
                ("uses", models.IntegerField(default=0)),
                ("expires_at", models.DateTimeField(blank=True, null=True)),
                ("revoked_at", models.DateTimeField(blank=True, null=True)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("last_used_at", models.DateTimeField(blank=True, null=True)),
                ("created_by", models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name="+", to="auth.user")),
                ("org", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="enroll_tokens", to="telemetry.organization")),
            ],
            options={"db_table": "bimba_enroll_token", "ordering": ["-created_at"]},
        ),
        migrations.CreateModel(
            name="Agent",
            fields=[
                ("id", models.UUIDField(primary_key=True, serialize=False)),
                ("fingerprint", models.CharField(max_length=64)),
                ("display_name", models.CharField(max_length=200)),
                ("hostname", models.CharField(max_length=255)),
                ("site", models.CharField(blank=True, default="", max_length=200)),
                ("os_name", models.CharField(blank=True, default="", max_length=200)),
                ("agent_version", models.CharField(blank=True, default="", max_length=32)),
                ("primary_ip", models.CharField(blank=True, default="", max_length=64)),
                ("remote_ip", models.CharField(blank=True, default="", max_length=64)),
                ("scada", models.JSONField(default=list)),
                ("details", models.JSONField(default=dict)),
                ("secret_hash", models.CharField(max_length=64)),
                ("enrolled_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("enrolled_via", models.CharField(max_length=16)),
                ("enrolled_by", models.CharField(max_length=200)),
                ("heartbeat_seconds", models.IntegerField(default=60)),
                ("last_seen", models.DateTimeField(null=True)),
                ("state", models.CharField(default="active", max_length=16)),
                ("revoked_at", models.DateTimeField(blank=True, null=True)),
                ("org", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="agents", to="telemetry.organization")),
            ],
            options={"db_table": "bimba_agent", "ordering": ["display_name"]},
        ),
        migrations.AddConstraint(
            model_name="agent",
            constraint=models.UniqueConstraint(fields=("org", "fingerprint"), name="bimba_agent_org_fp_uq"),
        ),
        migrations.CreateModel(
            name="AgentEvent",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("kind", models.CharField(max_length=32)),
                ("actor", models.CharField(blank=True, default="", max_length=200)),
                ("remote_ip", models.CharField(blank=True, default="", max_length=64)),
                ("detail", models.JSONField(default=dict)),
                ("at", models.DateTimeField(db_index=True, default=django.utils.timezone.now)),
                ("agent", models.ForeignKey(null=True, on_delete=django.db.models.deletion.CASCADE, related_name="events", to="telemetry.agent")),
                ("org", models.ForeignKey(null=True, on_delete=django.db.models.deletion.CASCADE, related_name="+", to="telemetry.organization")),
            ],
            options={"db_table": "bimba_agent_event", "ordering": ["-at"]},
        ),
        # The Ignition module inserts rows with plain SQL, so the defaults must also exist in the DB
        migrations.RunSQL(
            """
            ALTER TABLE bimba_agent ALTER COLUMN site SET DEFAULT '',
                ALTER COLUMN os_name SET DEFAULT '', ALTER COLUMN agent_version SET DEFAULT '',
                ALTER COLUMN primary_ip SET DEFAULT '', ALTER COLUMN remote_ip SET DEFAULT '',
                ALTER COLUMN scada SET DEFAULT '[]'::jsonb, ALTER COLUMN details SET DEFAULT '{}'::jsonb,
                ALTER COLUMN enrolled_at SET DEFAULT now(), ALTER COLUMN heartbeat_seconds SET DEFAULT 60,
                ALTER COLUMN state SET DEFAULT 'active';
            ALTER TABLE bimba_agent_event ALTER COLUMN actor SET DEFAULT '',
                ALTER COLUMN remote_ip SET DEFAULT '', ALTER COLUMN detail SET DEFAULT '{}'::jsonb,
                ALTER COLUMN at SET DEFAULT now();
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
    ]
