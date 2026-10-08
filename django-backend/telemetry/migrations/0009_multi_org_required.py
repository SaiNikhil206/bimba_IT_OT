import django.db.models.deletion
from django.db import migrations, models

# Multi-organization gateway console, step 2 of 2 (see 0008): ownership is mandatory, names unique per organization.


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0008_multi_org"),
    ]

    operations = [
        migrations.AlterField(
            model_name="gatewayuserrole", name="org",
            field=models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="+", to="telemetry.organization"),
        ),
        migrations.AddConstraint(
            model_name="gatewayuserrole",
            constraint=models.UniqueConstraint(fields=("user", "org", "role"), name="bimba_gw_user_org_role_uq"),
        ),
        migrations.AlterField(
            model_name="gatewaydestination", name="org",
            field=models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="+", to="telemetry.organization"),
        ),
        migrations.AlterField(
            model_name="gatewaypipeline", name="org",
            field=models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="+", to="telemetry.organization"),
        ),
        migrations.RunSQL(
            """
            CREATE UNIQUE INDEX bimba_gw_destination_org_lower_name ON bimba_gw_destination (org_id, lower(name));
            CREATE UNIQUE INDEX bimba_gw_pipeline_org_lower_name ON bimba_gw_pipeline (org_id, lower(name));
            CREATE INDEX bimba_gw_user_role_user_org ON bimba_gw_user_role (user_id, org_id);
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
    ]
