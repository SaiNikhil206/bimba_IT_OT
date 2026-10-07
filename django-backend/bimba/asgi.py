"""ASGI entrypoint: HTTP (Django), WebSocket (Channels) and a lifespan hook that
runs the PostgreSQL LISTEN loop feeding the live UI."""
import asyncio
import os

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "bimba.settings")

from django.core.asgi import get_asgi_application  # noqa: E402

django_http = get_asgi_application()

from django.conf import settings  # noqa: E402
from django.contrib.staticfiles.handlers import ASGIStaticFilesHandler  # noqa: E402

if settings.DEBUG:  # POC: serve admin CSS/JS directly (use a CDN / nginx in production)
    django_http = ASGIStaticFilesHandler(django_http)

from channels.routing import ProtocolTypeRouter, URLRouter  # noqa: E402

from telemetry.live import pg_listener  # noqa: E402
from telemetry.routing import websocket_urlpatterns  # noqa: E402

router = ProtocolTypeRouter({
    "http": django_http,
    "websocket": URLRouter(websocket_urlpatterns),
})


async def application(scope, receive, send):
    if scope["type"] != "lifespan":
        return await router(scope, receive, send)
    task = None
    while True:
        message = await receive()
        if message["type"] == "lifespan.startup":
            task = asyncio.create_task(pg_listener())
            await send({"type": "lifespan.startup.complete"})
        elif message["type"] == "lifespan.shutdown":
            if task:
                task.cancel()
            await send({"type": "lifespan.shutdown.complete"})
            return
