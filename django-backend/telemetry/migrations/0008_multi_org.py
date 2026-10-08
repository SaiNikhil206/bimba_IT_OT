import django.db.models.deletion
import django.utils.timezone
from django.db import migrations, models

# Multi-organization gateway console, step 1 of 2: everything the gateway console manages gets an owning organization.
#   - a user can be a member of several organizations (bimba_org_member) and has gateway roles PER organization
#     (bimba_gw_user_role.org_id); the console session remembers the organization the user is working in
#   - destinations, pipelines, custom roles and machines belong to one organization; built-in roles (org_id NULL)
#     are shared by every organization; audit entries record the organization they happened in
# Existing rows get the organization "default" (created only when there is something to own), which also becomes
# the organization of machines nobody assigned yet. 0009 then makes the ownership mandatory (separate transaction:
# PostgreSQL refuses ALTER TABLE while the deferred foreign key checks of this step's updates are pending).


def assign_default_org(apps, schema_editor):
    Org = apps.get_model("telemetry", "Organization")
    OrgMember = apps.get_model("telemetry", "OrgMember")
    Destination = apps.get_model("telemetry", "GatewayDestination")
    Pipeline = apps.get_model("telemetry", "GatewayPipeline")
    UserRole = apps.get_model("telemetry", "GatewayUserRole")
    Setting = apps.get_model("telemetry", "GatewaySetting")

    unowned = (Destination.objects.filter(org__isnull=True).exists() or Pipeline.objects.filter(org__isnull=True).exists()
               or UserRole.objects.filter(org__isnull=True).exists())
    if unowned:
        default, _ = Org.objects.get_or_create(slug="default", defaults={"name": "Default organization"})
    else:
        default = Org.objects.order_by("id").first()
    if default is None:
        return                                    # fresh install: the first organization becomes the default later
    Destination.objects.filter(org__isnull=True).update(org=default)
    Pipeline.objects.filter(org__isnull=True).update(org=default)
    for ur in UserRole.objects.filter(org__isnull=True).select_related("role"):
        ur.org = default
        ur.save(update_fields=["org"])
        role = "admin" if ur.role.name == "Administrator" else "member"
        m, created = OrgMember.objects.get_or_create(org=default, user_id=ur.user_id, defaults={"role": role})
        if not created and role == "admin" and m.role != "admin":
            m.role = "admin"
            m.save(update_fields=["role"])
    Setting.objects.get_or_create(key="machines.default_org", defaults={"value": {"orgId": default.id}})


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0007_gateway_pipelines"),
    ]

    operations = [
        # roles: built-in = shared (org NULL), custom = one organization's; names unique per organization
        migrations.AddField(
            model_name="gatewayrole", name="org",
            field=models.ForeignKey(null=True, blank=True, on_delete=django.db.models.deletion.CASCADE, related_name="+",
                                    to="telemetry.organization"),
        ),
        migrations.AlterField(model_name="gatewayrole", name="name", field=models.CharField(max_length=80)),
        # a user's roles are per organization
        migrations.RemoveConstraint(model_name="gatewayuserrole", name="bimba_gw_user_role_uq"),
        migrations.AddField(
            model_name="gatewayuserrole", name="org",
            field=models.ForeignKey(null=True, on_delete=django.db.models.deletion.CASCADE, related_name="+",
                                    to="telemetry.organization"),
        ),
        # the organization a console session works in, and the one the user used last
        migrations.AddField(
            model_name="gatewaysession", name="org",
            field=models.ForeignKey(null=True, blank=True, on_delete=django.db.models.deletion.SET_NULL, related_name="+",
                                    to="telemetry.organization"),
        ),
        migrations.AddField(
            model_name="gatewayuserprofile", name="last_org",
            field=models.ForeignKey(null=True, blank=True, on_delete=django.db.models.deletion.SET_NULL, related_name="+",
                                    to="telemetry.organization"),
        ),
        # audit entries outlive their organization: plain id, no foreign key
        migrations.AddField(
            model_name="gatewayaudit", name="org_id",
            field=models.BigIntegerField(null=True, blank=True, db_index=True),
        ),
        # destinations + pipelines: names unique per organization (0009)
        migrations.AddField(
            model_name="gatewaydestination", name="org",
            field=models.ForeignKey(null=True, on_delete=django.db.models.deletion.CASCADE, related_name="+",
                                    to="telemetry.organization"),
        ),
        migrations.AlterField(model_name="gatewaydestination", name="name", field=models.CharField(max_length=64)),
        migrations.AddField(
            model_name="gatewaypipeline", name="org",
            field=models.ForeignKey(null=True, on_delete=django.db.models.deletion.CASCADE, related_name="+",
                                    to="telemetry.organization"),
        ),
        migrations.AlterField(model_name="gatewaypipeline", name="name", field=models.CharField(max_length=80)),
        # which organization a machine of the gateway's tag tree belongs to (unlisted machines: the default organization)
        migrations.CreateModel(
            name="GatewayMachine",
            fields=[
                ("name", models.CharField(max_length=120, primary_key=True, serialize=False)),
                ("org", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="+", to="telemetry.organization")),
                ("assigned_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("assigned_by", models.CharField(blank=True, default="", max_length=150)),
            ],
            options={"db_table": "bimba_gw_machine", "ordering": ["name"]},
        ),
        migrations.RunSQL(
            """
            ALTER TABLE bimba_gw_machine ALTER COLUMN assigned_at SET DEFAULT now(), ALTER COLUMN assigned_by SET DEFAULT '';
            DROP INDEX IF EXISTS bimba_gw_destination_lower_name;
            DROP INDEX IF EXISTS bimba_gw_pipeline_lower_name;
            CREATE UNIQUE INDEX bimba_gw_role_org_lower_name ON bimba_gw_role (COALESCE(org_id, 0), lower(name));
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
        migrations.RunPython(assign_default_org, migrations.RunPython.noop),
    ]
