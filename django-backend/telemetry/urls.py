from django.urls import path

from . import agents, console, views

urlpatterns = [
    path("ingest/", views.ingest),
    path("machines/", views.machines),
    path("tags/<int:tag_id>/history/", views.tag_history),
    path("deliveries/", views.deliveries),
    path("summary/", views.summary),
    path("plugin-status/", views.plugin_status),
    path("health/", views.health),
    path("messages/lookup/", views.message_lookup),
    # OT/IT Bridge Console
    path("console/config/", console.config),
    path("console/sources/<str:kind>/", console.save_source),
    path("console/kafka/", console.save_kafka),
    path("console/eventstreams/", console.save_eventstreams),
    path("console/test/<str:kind>/", console.test),
    path("console/kafka-tail/", console.kafka_tail),
    # Site agents (organization sign-in; each organization sees only its own machines)
    path("auth/me/", agents.me),
    path("auth/login/", agents.login),
    path("auth/logout/", agents.logout),
    path("agents/", agents.agents),
    path("agents/tokens/", agents.tokens),
    path("agents/tokens/<int:token_id>/revoke/", agents.token_revoke),
    path("agents/<str:agent_id>/", agents.agent_detail),
    path("agents/<str:agent_id>/revoke/", agents.agent_revoke),
    path("agents/<str:agent_id>/remove/", agents.agent_remove),
]
