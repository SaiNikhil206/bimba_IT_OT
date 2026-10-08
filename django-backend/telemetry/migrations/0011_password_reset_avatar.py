import django.db.models.deletion
import django.utils.timezone
from django.db import migrations, models

# BIMBA Gateway console: "Forgot password?" links and profile photos.
# - bimba_gw_password_reset: one row per e-mailed reset link; id = SHA-256 of the token in the link (the token itself is
#   never stored), valid for a short time and only once.
# - bimba_gw_user_profile.avatar*: the user's photo (PNG / JPEG / WebP, at most 512 KB; the console sends 256 x 256).
# The gateway (Java) writes these tables directly, so the defaults are also set in the database.


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0010_unique_login_email"),
    ]

    operations = [
        migrations.CreateModel(
            name="GatewayPasswordReset",
            fields=[
                ("id", models.CharField(max_length=64, primary_key=True, serialize=False)),
                ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="+", to="auth.user")),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("expires_at", models.DateTimeField()),
                ("used_at", models.DateTimeField(blank=True, null=True)),
                ("ip", models.CharField(blank=True, default="", max_length=64)),
            ],
            options={"db_table": "bimba_gw_password_reset"},
        ),
        migrations.AddField(model_name="gatewayuserprofile", name="avatar", field=models.BinaryField(blank=True, null=True)),
        migrations.AddField(model_name="gatewayuserprofile", name="avatar_type",
                            field=models.CharField(blank=True, default="", max_length=32)),
        migrations.AddField(model_name="gatewayuserprofile", name="avatar_updated_at",
                            field=models.DateTimeField(blank=True, null=True)),
        migrations.RunSQL(
            """
            ALTER TABLE bimba_gw_password_reset ALTER COLUMN created_at SET DEFAULT now(), ALTER COLUMN ip SET DEFAULT '';
            CREATE INDEX bimba_gw_password_reset_user_open ON bimba_gw_password_reset (user_id) WHERE used_at IS NULL;
            ALTER TABLE bimba_gw_user_profile ALTER COLUMN avatar_type SET DEFAULT '';
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
    ]
