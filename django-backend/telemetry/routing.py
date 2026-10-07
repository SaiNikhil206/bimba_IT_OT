from django.urls import path

from .live import LiveConsumer

websocket_urlpatterns = [path("ws/live/", LiveConsumer.as_asgi())]
