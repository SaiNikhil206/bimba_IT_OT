from django.urls import path

from . import console, views

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
]
