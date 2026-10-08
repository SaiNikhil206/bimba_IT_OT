import uuid

from django.db import migrations, models

# Public ids for the BIMBA Gateway console's records: a random UUID per organization, role, destination, pipeline and
# user (on the user's profile; auth_user stays Django's own table). The console's addresses use them
# (/pipelines/<uid>), so a shared link does not reveal or depend on the internal sequence numbers. The integer ids stay
# the primary keys: foreign keys and the pipelines' destination references (in their config) are unchanged.
#
# The gateway (Java) writes these tables, so the database fills the uid itself. PostgreSQL 10 without extensions has no
# gen_random_uuid(): the default builds a version 4 UUID from md5(random()).

UUID4 = ("(overlay(overlay(md5(random()::text || ':' || clock_timestamp()::text) placing '4' from 13)"
         " placing to_hex(8 + floor(random() * 4)::int) from 17))::uuid")

TABLES = ["bimba_org", "bimba_gw_role", "bimba_gw_destination", "bimba_gw_pipeline", "bimba_gw_user_profile"]
MODELS = ["organization", "gatewayrole", "gatewaydestination", "gatewaypipeline", "gatewayuserprofile"]

SQL = "".join(f"ALTER TABLE {t} ADD COLUMN uid uuid NOT NULL DEFAULT {UUID4};\n"
              f"CREATE UNIQUE INDEX {t}_uid_uq ON {t} (uid);\n" for t in TABLES)
REVERSE = "".join(f"ALTER TABLE {t} DROP COLUMN IF EXISTS uid;\n" for t in TABLES)

# Every user gets a profile row, so every user has a uid (the gateway also creates missing ones when it lists users).
# A separate, later step: an insert leaves foreign key checks pending, and PostgreSQL refuses ALTER TABLE on that table
# in the same transaction.
PROFILES = ("INSERT INTO bimba_gw_user_profile (user_id) SELECT u.id FROM auth_user u"
            " WHERE NOT EXISTS (SELECT 1 FROM bimba_gw_user_profile p WHERE p.user_id = u.id)")


class Migration(migrations.Migration):
    dependencies = [
        ("telemetry", "0012_agent_certificates"),
    ]

    operations = [
        migrations.SeparateDatabaseAndState(
            database_operations=[migrations.RunSQL(SQL, reverse_sql=REVERSE)],
            state_operations=[
                migrations.AddField(model_name=m, name="uid", field=models.UUIDField(default=uuid.uuid4, editable=False, unique=True))
                for m in MODELS
            ],
        ),
        migrations.RunSQL(PROFILES, reverse_sql=migrations.RunSQL.noop),
    ]
