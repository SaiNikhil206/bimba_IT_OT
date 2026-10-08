import django.db.models.deletion
import django.utils.timezone
from django.db import migrations, models

# Users, roles and sessions of the BIMBA Gateway console. Accounts are the shared Django users (auth_user);
# the gateway adds roles with permissions, account lockout / forced password change, sessions and an audit log.
# The gateway (Java) reads and writes these tables directly, so the defaults are also set in the database.

PERMS_ALL = ["gateway.view", "agents.view", "agents.manage", "users.manage", "roles.manage", "audit.view"]
BUILTIN_ROLES = [
    ("Administrator", "Full access, including users, roles and the audit log.", PERMS_ALL),
    ("Operator", "Watches the gateway and manages site agents.", ["gateway.view", "agents.view", "agents.manage"]),
    ("Viewer", "Read-only: gateway status and site agents.", ["gateway.view", "agents.view"]),
    ("Auditor", "Gateway status and the audit log.", ["gateway.view", "audit.view"]),
]


def seed_roles(apps, schema_editor):
    Role = apps.get_model("telemetry", "GatewayRole")
    for name, description, perms in BUILTIN_ROLES:
        Role.objects.get_or_create(name=name, defaults={"description": description, "permissions": perms, "builtin": True})


class Migration(migrations.Migration):
    dependencies = [
        ("auth", "0012_alter_user_first_name_max_length"),
        ("telemetry", "0004_site_agents"),
    ]

    operations = [
        migrations.CreateModel(
            name="GatewayRole",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("name", models.CharField(max_length=80, unique=True)),
                ("description", models.CharField(blank=True, default="", max_length=300)),
                ("permissions", models.JSONField(default=list)),
                ("builtin", models.BooleanField(default=False)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
            ],
            options={"db_table": "bimba_gw_role", "ordering": ["name"]},
        ),
        migrations.CreateModel(
            name="GatewayUserRole",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("role", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="members", to="telemetry.gatewayrole")),
                ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="gateway_roles", to="auth.user")),
            ],
            options={"db_table": "bimba_gw_user_role"},
        ),
        migrations.AddConstraint(
            model_name="gatewayuserrole",
            constraint=models.UniqueConstraint(fields=("user", "role"), name="bimba_gw_user_role_uq"),
        ),
        migrations.CreateModel(
            name="GatewayUserProfile",
            fields=[
                ("user", models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, primary_key=True, related_name="gateway_profile", serialize=False, to="auth.user")),
                ("must_change_password", models.BooleanField(default=False)),
                ("password_changed_at", models.DateTimeField(null=True, blank=True)),
                ("failed_logins", models.IntegerField(default=0)),
                ("locked_until", models.DateTimeField(null=True, blank=True)),
                ("last_login_ip", models.CharField(blank=True, default="", max_length=64)),
            ],
            options={"db_table": "bimba_gw_user_profile"},
        ),
        migrations.CreateModel(
            name="GatewaySession",
            fields=[
                ("id", models.CharField(max_length=64, primary_key=True, serialize=False)),   # SHA-256 of the cookie value
                ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="gateway_sessions", to="auth.user")),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("last_seen_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("expires_at", models.DateTimeField()),
                ("ip", models.CharField(blank=True, default="", max_length=64)),
                ("user_agent", models.CharField(blank=True, default="", max_length=300)),
                ("revoked_at", models.DateTimeField(null=True, blank=True)),
            ],
            options={"db_table": "bimba_gw_session"},
        ),
        migrations.CreateModel(
            name="GatewayAudit",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("at", models.DateTimeField(db_index=True, default=django.utils.timezone.now)),
                ("actor", models.CharField(blank=True, default="", max_length=150)),
                ("action", models.CharField(max_length=40)),
                ("target", models.CharField(blank=True, default="", max_length=200)),
                ("success", models.BooleanField(default=True)),
                ("ip", models.CharField(blank=True, default="", max_length=64)),
                ("detail", models.JSONField(default=dict)),
            ],
            options={"db_table": "bimba_gw_audit", "ordering": ["-at"]},
        ),
        migrations.RunSQL(
            """
            ALTER TABLE bimba_gw_role ALTER COLUMN description SET DEFAULT '', ALTER COLUMN permissions SET DEFAULT '[]'::jsonb,
                ALTER COLUMN builtin SET DEFAULT false, ALTER COLUMN created_at SET DEFAULT now();
            ALTER TABLE bimba_gw_user_profile ALTER COLUMN must_change_password SET DEFAULT false,
                ALTER COLUMN failed_logins SET DEFAULT 0, ALTER COLUMN last_login_ip SET DEFAULT '';
            ALTER TABLE bimba_gw_session ALTER COLUMN created_at SET DEFAULT now(), ALTER COLUMN last_seen_at SET DEFAULT now(),
                ALTER COLUMN ip SET DEFAULT '', ALTER COLUMN user_agent SET DEFAULT '';
            CREATE INDEX bimba_gw_session_user ON bimba_gw_session (user_id);
            ALTER TABLE bimba_gw_audit ALTER COLUMN at SET DEFAULT now(), ALTER COLUMN actor SET DEFAULT '',
                ALTER COLUMN target SET DEFAULT '', ALTER COLUMN success SET DEFAULT true, ALTER COLUMN ip SET DEFAULT '',
                ALTER COLUMN detail SET DEFAULT '{}'::jsonb;
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
        migrations.RunPython(seed_roles, migrations.RunPython.noop),
    ]
