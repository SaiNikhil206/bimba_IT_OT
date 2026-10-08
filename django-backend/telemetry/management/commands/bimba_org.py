import getpass
import secrets
import string

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from telemetry.agents import create_token
from telemetry.models import (Agent, GatewayPipeline, GatewayRole, GatewayUserProfile, GatewayUserRole, Organization,
                              OrgMember)

# demo-multi-org: two organizations and one account that works in both (different gateway role in each)
DEMO_ORGS = [("northwind-water", "Northwind Water"), ("contoso-energy", "Contoso Energy")]
DEMO_USER = "sam"
DEMO_ROLES = {"northwind-water": "Administrator", "contoso-energy": "Operator"}


def _password():
    """A random password that meets the gateway policy (12+ characters, upper, lower, digit, symbol)."""
    alphabet = string.ascii_letters + string.digits
    core = "".join(secrets.choice(alphabet) for _ in range(14))
    return core[:5] + secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ") + secrets.choice("abcdefghijkmnopqrstuvwxyz") \
        + secrets.choice("23456789") + secrets.choice("!#%+=?@") + core[5:]


def grant(org, user, role_name):
    """Gives the user a gateway role in the organization (and membership, as the gateway console does)."""
    role = GatewayRole.objects.filter(name=role_name, org__isnull=True).first() or GatewayRole.objects.filter(name=role_name, org=org).first()
    if role is None:
        raise CommandError(f"unknown gateway role {role_name} (built-in: Administrator, Operator, Viewer, Auditor)")
    GatewayUserRole.objects.get_or_create(user=user, org=org, role=role)
    perms = set(role.permissions) | ({"agents.manage"} if role.builtin and role.name == "Administrator" else set())
    member, created = OrgMember.objects.get_or_create(org=org, user=user, defaults={"role": "admin" if "agents.manage" in perms else "member"})
    if not created and "agents.manage" in perms and member.role != "admin":
        member.role = "admin"
        member.save(update_fields=["role"])
    return role


class Command(BaseCommand):
    help = """Organizations, organization users and enrollment tokens (BIMBA Site Agent + BIMBA Gateway console).

  bimba_org create <slug> "<name>"
  bimba_org add-user <slug> <username> [--role admin|member] [--gateway-role NAME ...] [--password PW]
                                                                           (prompts for a new user when no --password)
  bimba_org token <slug> [--label L] [--uses N] [--days N]                 (prints the token once)
  bimba_org demo-multi-org [--password PW]   two organizations + the user "sam" who works in both
  bimba_org list"""

    def add_arguments(self, parser):
        parser.add_argument("action", choices=["create", "add-user", "token", "list", "demo-multi-org"])
        parser.add_argument("slug", nargs="?")
        parser.add_argument("value", nargs="?", help="organization name (create) or username (add-user)")
        parser.add_argument("--role", choices=["admin", "member"], default="member")
        parser.add_argument("--gateway-role", action="append", default=[], dest="gateway_roles",
                            help="gateway console role in this organization (repeatable), e.g. Administrator")
        parser.add_argument("--password")
        parser.add_argument("--label", default="")
        parser.add_argument("--uses", type=int)
        parser.add_argument("--days", type=int)

    def handle(self, *args, action, slug, value, **o):
        if action == "list":
            for org in Organization.objects.all():
                members = []
                for m in org.members.select_related("user"):
                    roles = GatewayUserRole.objects.filter(org=org, user=m.user).select_related("role")
                    gw = ",".join(r.role.name for r in roles)
                    members.append(f"{m.user.username} ({m.role}{'; ' + gw if gw else ''})")
                agents = Agent.objects.filter(org=org).count()
                pipelines = GatewayPipeline.objects.filter(org=org).count()
                self.stdout.write(f"{org.slug:20} {org.name:30} agents={agents:<3} pipelines={pipelines:<3} users: {', '.join(members) or '-'}")
            return
        if action == "demo-multi-org":
            self.demo(o["password"])
            return
        if not slug:
            raise CommandError("organization slug required")
        if action == "create":
            org, created = Organization.objects.get_or_create(slug=slug, defaults={"name": value or slug})
            self.stdout.write(f"organization {org.slug} ({org.name}) {'created' if created else 'already exists'}")
            return
        org = Organization.objects.filter(slug=slug).first()
        if org is None:
            raise CommandError(f"unknown organization {slug}")
        if action == "add-user":
            if not value:
                raise CommandError("username required")
            User = get_user_model()
            user = User.objects.filter(username=value).first()
            password = o["password"]
            if user is None:
                password = password or getpass.getpass(f"Password for new user {value}: ")
                user = User.objects.create_user(value, password=password)
            elif password:
                user.set_password(password)
                user.save(update_fields=["password"])
            m, _ = OrgMember.objects.update_or_create(org=org, user=user, defaults={"role": o["role"]})
            for name in o["gateway_roles"]:
                grant(org, user, name)
            m.refresh_from_db()
            gw = ", ".join(r.role.name for r in GatewayUserRole.objects.filter(org=org, user=user).select_related("role"))
            self.stdout.write(f"{user.username} is {m.role} of {org.slug}" + (f", gateway roles: {gw}" if gw else ""))
        elif action == "token":
            row, token = create_token(org, o["label"], o["uses"], o["days"])
            self.stdout.write(f"token for {org.slug} (id {row.id}, uses {row.max_uses or 'unlimited'}, "
                              f"expires {row.expires_at or 'never'}):")
            self.stdout.write(token)

    @transaction.atomic
    def demo(self, password):
        User = get_user_model()
        orgs = []
        for slug, name in DEMO_ORGS:
            org, created = Organization.objects.get_or_create(slug=slug, defaults={"name": name})
            orgs.append(org)
            self.stdout.write(f"organization {org.slug} ({org.name}) {'created' if created else 'already exists'}")
        user = User.objects.filter(username=DEMO_USER).first()
        new_password = None
        if user is None:
            new_password = password or _password()
            user = User.objects.create_user(DEMO_USER, password=new_password, first_name="Sam", last_name="Rivera",
                                            email="sam@example.com")
        elif password:
            new_password = password
            user.set_password(password)
            user.save(update_fields=["password"])
        GatewayUserProfile.objects.get_or_create(user=user)
        for org in orgs:
            role = grant(org, user, DEMO_ROLES[org.slug])
            self.stdout.write(f"{user.username}: {role.name} in {org.name}")
        if new_password:
            self.stdout.write(f"password of {user.username} (shown once): {new_password}")
        else:
            self.stdout.write(f"{user.username} already existed; password unchanged (use --password to set one)")
