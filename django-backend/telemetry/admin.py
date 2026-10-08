from django import forms
from django.contrib import admin, messages

from .agents import create_token
from .models import (Agent, AgentEvent, EnrollToken, GatewayAudit, GatewayRole, IngestBatch, Machine, Measurement,
                     Organization, OrgMember, Tag)


@admin.register(Machine)
class MachineAdmin(admin.ModelAdmin):
    list_display = ("plant_id", "machine_id", "first_seen", "last_seen")


@admin.register(Tag)
class TagAdmin(admin.ModelAdmin):
    list_display = ("machine", "name", "unit", "last_value_num", "last_value_text", "last_quality", "last_time",
                    "last_via")
    list_filter = ("machine", "last_via")


@admin.register(Measurement)
class MeasurementAdmin(admin.ModelAdmin):
    list_display = ("time", "tag", "value_num", "value_text", "quality", "received_via", "seq")
    list_filter = ("received_via", "quality")
    list_select_related = ("tag", "tag__machine")
    show_full_result_count = False


@admin.register(IngestBatch)
class IngestBatchAdmin(admin.ModelAdmin):
    list_display = ("received_at", "via", "edge_id", "message_count", "inserted_count", "duplicate_count",
                    "first_seq", "last_seq")
    list_filter = ("via",)


# ---------------------------------------------------------------- site agents

class OrgMemberInline(admin.TabularInline):
    model = OrgMember
    extra = 1
    autocomplete_fields = ("user",)


@admin.register(Organization)
class OrganizationAdmin(admin.ModelAdmin):
    list_display = ("name", "slug", "created_at")
    search_fields = ("name", "slug")
    prepopulated_fields = {"slug": ("name",)}
    inlines = [OrgMemberInline]


class EnrollTokenForm(forms.ModelForm):
    days = forms.IntegerField(required=False, min_value=1, max_value=365, help_text="Valid for N days (empty = no expiry)")

    class Meta:
        model = EnrollToken
        fields = ("org", "label", "max_uses")


@admin.register(EnrollToken)
class EnrollTokenAdmin(admin.ModelAdmin):
    """New tokens are generated here; the token is shown once in the success message."""
    list_display = ("prefix", "label", "org", "state", "uses", "max_uses", "expires_at", "revoked_at", "created_at")
    list_filter = ("org",)
    actions = ["revoke"]

    def get_form(self, request, obj=None, **kwargs):
        if obj is None:
            kwargs["form"] = EnrollTokenForm
        return super().get_form(request, obj, **kwargs)

    def get_readonly_fields(self, request, obj=None):
        if obj is None:
            return ()
        return ("org", "prefix", "token_hash", "uses", "created_by", "created_at", "last_used_at")

    def save_model(self, request, obj, form, change):
        if change:
            return super().save_model(request, obj, form, change)
        row, token = create_token(obj.org, obj.label, obj.max_uses, form.cleaned_data.get("days"), request.user)
        obj.pk, obj.prefix = row.pk, row.prefix
        messages.warning(request, f"Enrollment token for {row.org} (copy it now, it is not shown again): {token}")

    @admin.action(description="Revoke selected tokens")
    def revoke(self, request, queryset):
        from django.utils import timezone
        n = queryset.filter(revoked_at__isnull=True).update(revoked_at=timezone.now())
        messages.info(request, f"{n} token(s) revoked")


@admin.register(Agent)
class AgentAdmin(admin.ModelAdmin):
    list_display = ("display_name", "hostname", "org", "site", "os_name", "primary_ip", "state", "last_seen", "enrolled_by")
    list_filter = ("org", "state")
    search_fields = ("display_name", "hostname", "primary_ip")
    readonly_fields = ("id", "fingerprint", "secret_hash", "details", "scada", "enrolled_at", "enrolled_via", "enrolled_by",
                       "last_seen", "remote_ip", "agent_version")


@admin.register(AgentEvent)
class AgentEventAdmin(admin.ModelAdmin):
    list_display = ("at", "kind", "org", "agent", "actor", "remote_ip")
    list_filter = ("kind", "org")


# ---------------------------------------------------------------- BIMBA Gateway console security (read-mostly here;
# users and roles are managed in the gateway console, which enforces the password policy and writes the audit log)

@admin.register(GatewayRole)
class GatewayRoleAdmin(admin.ModelAdmin):
    list_display = ("name", "org", "builtin", "description")   # org empty = built-in, shared by every organization
    list_filter = ("builtin", "org")


@admin.register(GatewayAudit)
class GatewayAuditAdmin(admin.ModelAdmin):
    list_display = ("at", "org_id", "actor", "action", "target", "success", "ip")
    list_filter = ("action", "success")
    search_fields = ("actor", "target")
