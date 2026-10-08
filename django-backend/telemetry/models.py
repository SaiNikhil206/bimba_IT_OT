"""BIMBA data model (PostgreSQL). Shared by both end systems:
BIMBA Ingest (Django) writes through the API/MQTT ingest, Boomi writes via SELECT bimba_ingest(...).
Rows are created by the bimba_ingest() SQL function, so the timestamp defaults are also set in the DB (DEFAULT now(), see migration 0001)."""
import uuid

from django.db import models
from django.utils import timezone


class Machine(models.Model):
    plant_id = models.CharField(max_length=64)
    machine_id = models.CharField(max_length=128)
    first_seen = models.DateTimeField(default=timezone.now)
    last_seen = models.DateTimeField(null=True)

    class Meta:
        db_table = "bimba_machine"
        constraints = [models.UniqueConstraint(fields=["plant_id", "machine_id"], name="bimba_machine_uq")]
        ordering = ["plant_id", "machine_id"]

    def __str__(self):
        return f"{self.plant_id}/{self.machine_id}"


class Tag(models.Model):
    machine = models.ForeignKey(Machine, on_delete=models.CASCADE, related_name="tags")
    name = models.CharField(max_length=255)
    unit = models.CharField(max_length=32, null=True, blank=True)
    data_type = models.CharField(max_length=16, null=True, blank=True)
    source_path = models.CharField(max_length=512, null=True, blank=True)
    first_seen = models.DateTimeField(default=timezone.now)
    # latest value, maintained by bimba_ingest() for fast live views
    last_value_num = models.FloatField(null=True)
    last_value_text = models.TextField(null=True)
    last_quality = models.CharField(max_length=16, null=True)
    last_time = models.DateTimeField(null=True)
    last_via = models.CharField(max_length=32, null=True)

    class Meta:
        db_table = "bimba_tag"
        constraints = [models.UniqueConstraint(fields=["machine", "name"], name="bimba_tag_uq")]
        ordering = ["machine", "name"]

    def __str__(self):
        return f"{self.machine}/{self.name}"


class Measurement(models.Model):
    VIA_CHOICES = [(v, v) for v in ("boomi-es", "boomi-rest", "boomi-mqtt", "bimba-rest", "bimba-mqtt")]

    tag = models.ForeignKey(Tag, on_delete=models.CASCADE, related_name="measurements")
    time = models.DateTimeField()
    value_num = models.FloatField(null=True)
    value_text = models.TextField(null=True)
    quality = models.CharField(max_length=16)
    message_id = models.UUIDField(unique=True)          # idempotent ingest
    seq = models.BigIntegerField(null=True)             # plugin sequence number (loss audit)
    received_via = models.CharField(max_length=32)
    received_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "bimba_measurement"
        indexes = [models.Index(fields=["tag", "-time"], name="bimba_meas_tag_time")]


class IngestBatch(models.Model):
    """One row per batch received by an end system (Boomi or Django)."""
    batch_id = models.UUIDField(null=True, db_index=True)
    plant_id = models.CharField(max_length=64, null=True)
    edge_id = models.CharField(max_length=128, null=True)
    via = models.CharField(max_length=32)
    received_at = models.DateTimeField(default=timezone.now, db_index=True)
    message_count = models.IntegerField()
    inserted_count = models.IntegerField()
    duplicate_count = models.IntegerField()
    first_seq = models.BigIntegerField(null=True)
    last_seq = models.BigIntegerField(null=True)
    event_time_min = models.DateTimeField(null=True)
    event_time_max = models.DateTimeField(null=True)

    class Meta:
        db_table = "bimba_ingest_batch"
        ordering = ["-received_at"]


# ---------------------------------------------------------------- site agents
# Machines that run the BIMBA Site Agent (Windows installer). The agent enrolls through the BIMBA
# Gateway (/system/bimba-agent-enroll) with an organization's enrollment token or an
# organization user's password; the gateway writes these tables directly.
# Each organization sees only its own agents in BIMBA Operations (/operations/agents).


class Organization(models.Model):
    uid = models.UUIDField(default=uuid.uuid4, editable=False, unique=True)   # public id in the gateway console's addresses
    slug = models.SlugField(max_length=64, unique=True)
    name = models.CharField(max_length=200)
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "bimba_org"
        ordering = ["name"]

    def __str__(self):
        return self.name


class OrgMember(models.Model):
    """An organization user (a Django user). admin: manage enrollment tokens and revoke agents."""
    ROLES = [("admin", "Admin"), ("member", "Member")]

    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="members")
    user = models.ForeignKey("auth.User", on_delete=models.CASCADE, related_name="bimba_memberships")
    role = models.CharField(max_length=16, choices=ROLES, default="member")

    class Meta:
        db_table = "bimba_org_member"
        constraints = [models.UniqueConstraint(fields=["org", "user"], name="bimba_org_member_uq")]

    def __str__(self):
        return f"{self.user} @ {self.org} ({self.role})"


class EnrollToken(models.Model):
    """Enrollment token for the installer. Only the SHA-256 of the token is stored; it is shown once."""
    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="enroll_tokens")
    label = models.CharField(max_length=200, blank=True)
    token_hash = models.CharField(max_length=64, unique=True)
    prefix = models.CharField(max_length=16)               # first characters, to recognise a token in lists
    max_uses = models.IntegerField(null=True, blank=True)  # null = unlimited
    uses = models.IntegerField(default=0)
    expires_at = models.DateTimeField(null=True, blank=True)
    revoked_at = models.DateTimeField(null=True, blank=True)
    created_by = models.ForeignKey("auth.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+")
    created_at = models.DateTimeField(default=timezone.now)
    last_used_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "bimba_enroll_token"
        ordering = ["-created_at"]

    def __str__(self):
        return f"{self.prefix}… {self.label}".strip()

    @property
    def state(self):
        if self.revoked_at:
            return "revoked"
        if self.expires_at and self.expires_at <= timezone.now():
            return "expired"
        if self.max_uses is not None and self.uses >= self.max_uses:
            return "used up"
        return "active"


class Agent(models.Model):
    id = models.UUIDField(primary_key=True)
    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="agents")
    fingerprint = models.CharField(max_length=64)          # SHA-256 of the Windows MachineGuid
    display_name = models.CharField(max_length=200)
    hostname = models.CharField(max_length=255)
    site = models.CharField(max_length=200, blank=True, default="")
    os_name = models.CharField(max_length=200, blank=True, default="")
    agent_version = models.CharField(max_length=32, blank=True, default="")
    primary_ip = models.CharField(max_length=64, blank=True, default="")
    remote_ip = models.CharField(max_length=64, blank=True, default="")  # address the gateway saw
    scada = models.JSONField(default=list)                 # detected SCADA / OT software
    details = models.JSONField(default=dict)               # full machine inventory sent by the agent
    secret_hash = models.CharField(max_length=64)          # SHA-256 of the agent's own secret
    cert_sha256 = models.CharField(max_length=64, blank=True, default="")       # client certificate (mutual TLS)
    cert_prev_sha256 = models.CharField(max_length=64, blank=True, default="")  # accepted until the renewed one is used
    cert_issued_at = models.DateTimeField(null=True, blank=True)
    cert_not_after = models.DateTimeField(null=True, blank=True)
    enrolled_at = models.DateTimeField(default=timezone.now)
    enrolled_via = models.CharField(max_length=16)         # token | password
    enrolled_by = models.CharField(max_length=200)         # token label/prefix or username
    heartbeat_seconds = models.IntegerField(default=60)
    last_seen = models.DateTimeField(null=True)
    state = models.CharField(max_length=16, default="active")  # active | revoked | uninstalled
    revoked_at = models.DateTimeField(null=True, blank=True)
    collector = models.JSONField(default=dict)             # data collection status reported by the agent

    class Meta:
        db_table = "bimba_agent"
        ordering = ["display_name"]
        constraints = [models.UniqueConstraint(fields=["org", "fingerprint"], name="bimba_agent_org_fp_uq")]

    def __str__(self):
        return f"{self.display_name} ({self.org.slug})"


class AgentEvent(models.Model):
    """Audit trail: enrolled, re-enrolled, revoked, uninstalled, token created/revoked."""
    org = models.ForeignKey(Organization, null=True, on_delete=models.CASCADE, related_name="+")
    agent = models.ForeignKey(Agent, null=True, on_delete=models.CASCADE, related_name="events")
    kind = models.CharField(max_length=32)
    actor = models.CharField(max_length=200, blank=True, default="")
    remote_ip = models.CharField(max_length=64, blank=True, default="")
    detail = models.JSONField(default=dict)
    at = models.DateTimeField(default=timezone.now, db_index=True)

    class Meta:
        db_table = "bimba_agent_event"
        ordering = ["-at"]


class AgentSource(models.Model):
    """A SCADA data source the site agent reads on its machine (edge collector) and pushes to the gateway.
    name = the machine name its readings report as (unique on the gateway; owned by the agent's organization)."""
    id = models.UUIDField(primary_key=True)
    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="+")
    agent = models.ForeignKey(Agent, on_delete=models.CASCADE, related_name="sources")
    name = models.CharField(max_length=120)
    kind = models.CharField(max_length=16)                 # opcua | modbus | opcda
    settings = models.JSONField(default=dict)              # connection; secrets sealed by the gateway ("enc:v1:")
    tags = models.JSONField(default=list)                  # [{name, address, unit, dataType, ...}]
    poll_ms = models.IntegerField(default=1000)
    enabled = models.BooleanField(default=True)
    created_at = models.DateTimeField(default=timezone.now)
    updated_at = models.DateTimeField(default=timezone.now)
    created_by = models.CharField(max_length=200, blank=True, default="")

    class Meta:
        db_table = "bimba_agent_source"
        ordering = ["name"]
        constraints = [models.UniqueConstraint(fields=["name"], name="bimba_agent_source_name_uq")]


class AgentCommand(models.Model):
    """Console -> agent request answered within seconds (test a connection, browse tags); the agent long-polls."""
    id = models.UUIDField(primary_key=True)
    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="+")
    agent = models.ForeignKey(Agent, on_delete=models.CASCADE, related_name="commands")
    kind = models.CharField(max_length=16)                 # test | browse | discover
    request = models.JSONField(default=dict)
    state = models.CharField(max_length=16, default="pending")  # pending | sent | done | failed | expired
    result = models.JSONField(null=True, blank=True)
    error = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(default=timezone.now)
    sent_at = models.DateTimeField(null=True, blank=True)
    done_at = models.DateTimeField(null=True, blank=True)
    created_by = models.CharField(max_length=200, blank=True, default="")

    class Meta:
        db_table = "bimba_agent_command"
        indexes = [models.Index(fields=["agent", "state"], name="bimba_agent_cmd_state_ix")]


# ---------------------------------------------------------------- BIMBA Gateway console security
# Shared Django users sign in to the BIMBA Gateway console; the gateway (Java) enforces these roles,
# lockout, forced password changes and sessions, and writes the audit log (see bimba-gateway/.../security).


class GatewayRole(models.Model):
    """org NULL = built-in role shared by every organization; otherwise a custom role of that organization.
    Names are unique per organization (index bimba_gw_role_org_lower_name)."""
    org = models.ForeignKey(Organization, null=True, blank=True, on_delete=models.CASCADE, related_name="+")
    uid = models.UUIDField(default=uuid.uuid4, editable=False, unique=True)   # public id in the gateway console's addresses
    name = models.CharField(max_length=80)
    description = models.CharField(max_length=300, blank=True, default="")
    permissions = models.JSONField(default=list)            # e.g. ["gateway.view", "agents.view"]
    builtin = models.BooleanField(default=False)
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "bimba_gw_role"
        ordering = ["name"]

    def __str__(self):
        return self.name


class GatewayUserRole(models.Model):
    """A user's gateway role in one organization (a user can have different roles in each organization)."""
    user = models.ForeignKey("auth.User", on_delete=models.CASCADE, related_name="gateway_roles")
    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="+")
    role = models.ForeignKey(GatewayRole, on_delete=models.CASCADE, related_name="members")

    class Meta:
        db_table = "bimba_gw_user_role"
        constraints = [models.UniqueConstraint(fields=["user", "org", "role"], name="bimba_gw_user_org_role_uq")]


class GatewayUserProfile(models.Model):
    user = models.OneToOneField("auth.User", on_delete=models.CASCADE, primary_key=True, related_name="gateway_profile")
    uid = models.UUIDField(default=uuid.uuid4, editable=False, unique=True)   # the user's public id in the gateway console's addresses
    must_change_password = models.BooleanField(default=False)
    password_changed_at = models.DateTimeField(null=True, blank=True)
    failed_logins = models.IntegerField(default=0)
    locked_until = models.DateTimeField(null=True, blank=True)
    last_login_ip = models.CharField(max_length=64, blank=True, default="")
    last_org = models.ForeignKey(Organization, null=True, blank=True, on_delete=models.SET_NULL, related_name="+")
    avatar = models.BinaryField(null=True, blank=True)                     # profile photo (PNG / JPEG / WebP, <= 512 KB)
    avatar_type = models.CharField(max_length=32, blank=True, default="")
    avatar_updated_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "bimba_gw_user_profile"


class GatewaySession(models.Model):
    id = models.CharField(max_length=64, primary_key=True)  # SHA-256 of the cookie value
    user = models.ForeignKey("auth.User", on_delete=models.CASCADE, related_name="gateway_sessions")
    org = models.ForeignKey(Organization, null=True, blank=True, on_delete=models.SET_NULL, related_name="+")  # works in
    created_at = models.DateTimeField(default=timezone.now)
    last_seen_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField()
    ip = models.CharField(max_length=64, blank=True, default="")
    user_agent = models.CharField(max_length=300, blank=True, default="")
    revoked_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "bimba_gw_session"


class GatewayAudit(models.Model):
    org_id = models.BigIntegerField(null=True, blank=True, db_index=True)   # no foreign key: entries outlive the org
    at = models.DateTimeField(default=timezone.now, db_index=True)
    actor = models.CharField(max_length=150, blank=True, default="")
    action = models.CharField(max_length=40)
    target = models.CharField(max_length=200, blank=True, default="")
    success = models.BooleanField(default=True)
    ip = models.CharField(max_length=64, blank=True, default="")
    detail = models.JSONField(default=dict)

    class Meta:
        db_table = "bimba_gw_audit"
        ordering = ["-at"]


class GatewayDestination(models.Model):
    """A delivery destination of the BIMBA Gateway, managed in the gateway console (Delivery). config = the
    destination as the gateway reads it (role, enabled, transport, url, ...); secrets in it are encrypted by the
    gateway ("enc:v1:...") with a key only the gateway has. Names are unique per organization."""
    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="+")
    uid = models.UUIDField(default=uuid.uuid4, editable=False, unique=True)   # public id in the gateway console's addresses
    name = models.CharField(max_length=64)
    service = models.CharField(max_length=40, blank=True, default="")    # catalog entry, e.g. "confluent-cloud"
    position = models.IntegerField(default=0)                             # delivery order
    config = models.JSONField(default=dict)
    created_at = models.DateTimeField(default=timezone.now)
    updated_at = models.DateTimeField(default=timezone.now)
    updated_by = models.CharField(max_length=150, blank=True, default="")

    class Meta:
        db_table = "bimba_gw_destination"
        ordering = ["position", "id"]

    def __str__(self):
        return self.name


class GatewaySetting(models.Model):
    """Small key/value state of the BIMBA Gateway (e.g. that config.json destinations were imported)."""
    key = models.CharField(max_length=80, primary_key=True)
    value = models.JSONField(default=dict)
    updated_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "bimba_gw_setting"


class GatewayPipeline(models.Model):
    """A delivery pipeline of the BIMBA Gateway: which machines' data goes to which destinations. config =
    {"machines": {"mode": "all"} | {"mode": "selected", "names": [...]}, "primary": <destination id>,
    "secondary": [ids, in the order they are tried], "mirrors": [ids]}. Destinations get data only through a pipeline.
    A pipeline only carries machines of its own organization and only uses that organization's destinations."""
    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="+")
    uid = models.UUIDField(default=uuid.uuid4, editable=False, unique=True)   # public id in the gateway console's addresses
    name = models.CharField(max_length=80)
    description = models.CharField(max_length=300, blank=True, default="")
    active = models.BooleanField(default=False)
    position = models.IntegerField(default=0)
    config = models.JSONField(default=dict)
    created_at = models.DateTimeField(default=timezone.now)
    updated_at = models.DateTimeField(default=timezone.now)
    updated_by = models.CharField(max_length=150, blank=True, default="")

    class Meta:
        db_table = "bimba_gw_pipeline"
        ordering = ["position", "id"]

    def __str__(self):
        return self.name


class GatewayMachine(models.Model):
    """Owner of a machine of the gateway's tag tree. Machines not listed belong to the default organization
    (GatewaySetting "machines.default_org"). Platform admins assign machines in the gateway console."""
    name = models.CharField(max_length=120, primary_key=True)
    org = models.ForeignKey(Organization, on_delete=models.CASCADE, related_name="+")
    assigned_at = models.DateTimeField(default=timezone.now)
    assigned_by = models.CharField(max_length=150, blank=True, default="")

    class Meta:
        db_table = "bimba_gw_machine"
        ordering = ["name"]


class GatewayPasswordReset(models.Model):
    """A "Forgot password?" link of the gateway console: id = SHA-256 of the token in the e-mailed link (the token is
    never stored); valid until expires_at and only once (used_at)."""
    id = models.CharField(max_length=64, primary_key=True)
    user = models.ForeignKey("auth.User", on_delete=models.CASCADE, related_name="+")
    created_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField()
    used_at = models.DateTimeField(null=True, blank=True)
    ip = models.CharField(max_length=64, blank=True, default="")

    class Meta:
        db_table = "bimba_gw_password_reset"
