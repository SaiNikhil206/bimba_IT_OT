from django.db import migrations

# The BIMBA Gateway signs people in with their e-mail address, so an address can belong to one account only
# (case-insensitive). Accounts without an e-mail address (empty) are not affected.


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0009_multi_org_required"),
    ]

    operations = [
        migrations.RunSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS auth_user_email_ci_uq ON auth_user (lower(email)) WHERE email <> ''",
            reverse_sql="DROP INDEX IF EXISTS auth_user_email_ci_uq",
        ),
    ]
