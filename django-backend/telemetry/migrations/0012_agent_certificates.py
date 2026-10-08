from django.db import migrations, models

# BIMBA Site Agent client certificates (mutual TLS). The gateway's own certificate authority signs one certificate per
# machine; the agent's private key never leaves the machine. Once an agent has a certificate, its secret is no longer
# accepted. cert_prev_sha256 keeps the previous certificate valid until the renewed one is first used (so a lost
# answer during renewal cannot lock the machine out). The gateway (Java) writes these columns, so the defaults are
# also set in the database.


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0011_password_reset_avatar"),
    ]

    operations = [
        migrations.AddField(model_name="agent", name="cert_sha256",
                            field=models.CharField(blank=True, default="", max_length=64)),
        migrations.AddField(model_name="agent", name="cert_prev_sha256",
                            field=models.CharField(blank=True, default="", max_length=64)),
        migrations.AddField(model_name="agent", name="cert_issued_at", field=models.DateTimeField(blank=True, null=True)),
        migrations.AddField(model_name="agent", name="cert_not_after", field=models.DateTimeField(blank=True, null=True)),
        migrations.RunSQL(
            """
            ALTER TABLE bimba_agent ALTER COLUMN cert_sha256 SET DEFAULT '', ALTER COLUMN cert_prev_sha256 SET DEFAULT '';
            """,
            reverse_sql=migrations.RunSQL.noop,
        ),
    ]
