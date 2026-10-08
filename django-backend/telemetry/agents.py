"""Site agents API for BIMBA Operations (/operations/agents).

Organization users sign in (Django session) and see only their own organization's agents. Agents
themselves never call this API: they enroll and heartbeat through the BIMBA Gateway
(/system/bimba-agent-enroll + /system/bimba-agent-heartbeat), which writes the same tables.

Visibility rule: every query is filtered by an organization the signed-in user is a member of.
Superusers (platform operators) can open every organization.
"""
import hashlib
import json
import secrets
import uuid
from datetime import timedelta

from django.contrib.auth import authenticate, login as auth_login, logout as auth_logout
from django.core.cache import cache
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import ensure_csrf_cookie
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from .models import Agent, AgentEvent, EnrollToken, Organization, OrgMember

TOKEN_PREFIX = "bimba_et_"
LOGIN_MAX_FAILURES = 10
LOGIN_WINDOW_SECONDS = 15 * 60


# ---------------------------------------------------------------- tokens (shared with the bimba_org command)

def hash_secret(value):
    """SHA-256 hex. The BIMBA Gateway hashes enrollment tokens and agent secrets the same way."""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def create_token(org, label="", max_uses=None, days=None, user=None):
    """Returns (EnrollToken, plaintext token). The plaintext is never stored."""
    token = TOKEN_PREFIX + secrets.token_urlsafe(32)
    row = EnrollToken.objects.create(
        org=org, label=label[:200], token_hash=hash_secret(token), prefix=token[:16], max_uses=max_uses,
        expires_at=timezone.now() + timedelta(days=days) if days else None, created_by=user)
    AgentEvent.objects.create(org=org, kind="token-created", actor=user.username if user else "system",
                              detail={"tokenId": row.id, "label": row.label, "prefix": row.prefix})
    return row, token


# ---------------------------------------------------------------- helpers

def _client_ip(request):
    return request.META.get("REMOTE_ADDR", "")


def _body(request):
    try:
        return json.loads(request.body or b"{}")
    except ValueError:
        return None


def _orgs_of(user):
    """[(Organization, role)] the user may open."""
    if not user.is_authenticated:
        return []
    if user.is_superuser:
        return [(o, "admin") for o in Organization.objects.all()]
    return [(m.org, m.role) for m in OrgMember.objects.filter(user=user).select_related("org").order_by("org__name")]


def _me(request):
    u = request.user
    if not u.is_authenticated:
        return {"user": None, "orgs": []}
    return {
        "user": {"username": u.username, "name": u.get_full_name() or u.username, "platformAdmin": u.is_superuser},
        "orgs": [{"slug": o.slug, "name": o.name, "role": r} for o, r in _orgs_of(u)],
    }


def _org_access(request, slug, admin=False):
    """(Organization, role) or a JsonResponse error. Unknown and foreign organizations look the same (404)."""
    if not request.user.is_authenticated:
        return JsonResponse({"error": "sign in required"}, status=401)
    for org, role in _orgs_of(request.user):
        if org.slug == slug:
            if admin and role != "admin":
                return JsonResponse({"error": "organization admin role required"}, status=403)
            return org, role
    return JsonResponse({"error": "organization not found"}, status=404)


def _status(a, now):
    if a.state != "active":
        return a.state
    if not a.last_seen:
        return "pending"
    return "online" if a.last_seen >= now - timedelta(seconds=3 * max(a.heartbeat_seconds, 10)) else "offline"


def _agent_json(a, now, details=False):
    out = {
        "id": str(a.id), "name": a.display_name, "hostname": a.hostname, "site": a.site, "os": a.os_name,
        "agentVersion": a.agent_version, "ip": a.primary_ip, "remoteIp": a.remote_ip, "scada": a.scada,
        "status": _status(a, now), "lastSeen": a.last_seen.isoformat() if a.last_seen else None,
        "enrolledAt": a.enrolled_at.isoformat(), "enrolledVia": a.enrolled_via, "enrolledBy": a.enrolled_by,
        "heartbeatSeconds": a.heartbeat_seconds,
    }
    if details:
        out["details"] = a.details
    return out


def _token_json(t):
    return {
        "id": t.id, "label": t.label, "prefix": t.prefix, "state": t.state, "uses": t.uses, "maxUses": t.max_uses,
        "expiresAt": t.expires_at.isoformat() if t.expires_at else None, "createdAt": t.created_at.isoformat(),
        "createdBy": t.created_by.username if t.created_by else None,
        "lastUsedAt": t.last_used_at.isoformat() if t.last_used_at else None,
    }


# ---------------------------------------------------------------- sign-in

@ensure_csrf_cookie
@require_GET
def me(request):
    """Current user + organizations. Also sets the csrftoken cookie the UI sends back as X-CSRFToken."""
    return JsonResponse(_me(request))


@require_POST
def login(request):
    body = _body(request) or {}
    username = str(body.get("username", "")).strip()
    key = f"bimba-login:{_client_ip(request)}:{username.lower()}"
    if cache.get(key, 0) >= LOGIN_MAX_FAILURES:
        return JsonResponse({"error": "Too many failed sign-ins. Try again in 15 minutes."}, status=429)
    user = authenticate(request, username=username, password=str(body.get("password", "")))
    if user is None or not user.is_active:
        cache.set(key, cache.get(key, 0) + 1, LOGIN_WINDOW_SECONDS)
        return JsonResponse({"error": "Wrong username or password."}, status=401)
    cache.delete(key)
    auth_login(request, user)
    data = _me(request)
    if not data["orgs"]:
        auth_logout(request)
        return JsonResponse({"error": "This user is not a member of any organization."}, status=403)
    return JsonResponse(data)


@require_POST
def logout(request):
    auth_logout(request)
    return JsonResponse({"user": None, "orgs": []})


# ---------------------------------------------------------------- agents

@require_GET
def agents(request):
    access = _org_access(request, request.GET.get("org", ""))
    if isinstance(access, JsonResponse):
        return access
    org, role = access
    now = timezone.now()
    rows = [_agent_json(a, now) for a in Agent.objects.filter(org=org)]
    return JsonResponse({"org": {"slug": org.slug, "name": org.name, "role": role}, "serverTime": now.isoformat(),
                         "agents": rows})


def _agent_of(request, agent_id, admin=False):
    """(Agent, role) within one of the user's organizations, or a JsonResponse error."""
    if not request.user.is_authenticated:
        return JsonResponse({"error": "sign in required"}, status=401)
    try:
        a = Agent.objects.select_related("org").get(pk=uuid.UUID(str(agent_id)))
    except (ValueError, Agent.DoesNotExist):
        return JsonResponse({"error": "agent not found"}, status=404)
    access = _org_access(request, a.org.slug, admin=admin)
    if isinstance(access, JsonResponse):
        return JsonResponse({"error": "agent not found"}, status=404) if access.status_code == 404 else access
    return a, access[1]


@require_GET
def agent_detail(request, agent_id):
    found = _agent_of(request, agent_id)
    if isinstance(found, JsonResponse):
        return found
    a, _ = found
    out = _agent_json(a, timezone.now(), details=True)
    out["events"] = [{"kind": e.kind, "actor": e.actor, "remoteIp": e.remote_ip, "at": e.at.isoformat(),
                      "detail": e.detail} for e in a.events.all()[:20]]
    return JsonResponse(out)


@require_POST
def agent_revoke(request, agent_id):
    """Blocks the agent: its next heartbeat is refused (410) and it stops. Re-installing enrolls it again."""
    found = _agent_of(request, agent_id, admin=True)
    if isinstance(found, JsonResponse):
        return found
    a, _ = found
    a.state, a.revoked_at = "revoked", timezone.now()
    a.save(update_fields=["state", "revoked_at"])
    AgentEvent.objects.create(org=a.org, agent=a, kind="revoked", actor=request.user.username,
                              remote_ip=_client_ip(request))
    return JsonResponse(_agent_json(a, timezone.now()))


@require_http_methods(["DELETE"])
def agent_remove(request, agent_id):
    """Removes a revoked or uninstalled agent from the list."""
    found = _agent_of(request, agent_id, admin=True)
    if isinstance(found, JsonResponse):
        return found
    a, _ = found
    if a.state == "active":
        return JsonResponse({"error": "Revoke the agent before removing it."}, status=409)
    AgentEvent.objects.create(org=a.org, kind="removed", actor=request.user.username, remote_ip=_client_ip(request),
                              detail={"agentId": str(a.id), "name": a.display_name, "hostname": a.hostname})
    a.delete()
    return JsonResponse({"removed": str(agent_id)})


# ---------------------------------------------------------------- enrollment tokens (org admins)

@require_http_methods(["GET", "POST"])
def tokens(request):
    if request.method == "GET":
        access = _org_access(request, request.GET.get("org", ""), admin=True)
        if isinstance(access, JsonResponse):
            return access
        rows = EnrollToken.objects.filter(org=access[0]).select_related("created_by")[:200]
        return JsonResponse({"tokens": [_token_json(t) for t in rows]})
    body = _body(request)
    if body is None:
        return JsonResponse({"error": "body is not valid JSON"}, status=400)
    access = _org_access(request, str(body.get("org", "")), admin=True)
    if isinstance(access, JsonResponse):
        return access
    try:
        max_uses = int(body["maxUses"]) if body.get("maxUses") not in (None, "") else None
        days = int(body["days"]) if body.get("days") not in (None, "") else None
    except (TypeError, ValueError):
        return JsonResponse({"error": "maxUses and days must be whole numbers"}, status=400)
    if (max_uses is not None and not 1 <= max_uses <= 10000) or (days is not None and not 1 <= days <= 365):
        return JsonResponse({"error": "maxUses must be 1-10000 and days 1-365 (or empty for no limit)"}, status=400)
    row, token = create_token(access[0], str(body.get("label", "")).strip(), max_uses, days, request.user)
    return JsonResponse({**_token_json(row), "token": token}, status=201)


@require_POST
def token_revoke(request, token_id):
    if not request.user.is_authenticated:
        return JsonResponse({"error": "sign in required"}, status=401)
    t = EnrollToken.objects.select_related("org").filter(pk=token_id).first()
    access = _org_access(request, t.org.slug if t else "", admin=True)
    if t is None or isinstance(access, JsonResponse):
        return access if t is not None and access.status_code == 403 else JsonResponse({"error": "token not found"}, status=404)
    if not t.revoked_at:
        t.revoked_at = timezone.now()
        t.save(update_fields=["revoked_at"])
        AgentEvent.objects.create(org=t.org, kind="token-revoked", actor=request.user.username,
                                  remote_ip=_client_ip(request), detail={"tokenId": t.id, "prefix": t.prefix})
    return JsonResponse(_token_json(t))
