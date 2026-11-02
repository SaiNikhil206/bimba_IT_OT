"""Vercel serverless entrypoint: serves the Django app (django-backend) over WSGI.
WebSockets (/ws/live/) and the PostgreSQL LISTEN loop are not available on Vercel."""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "django-backend"))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "bimba.settings")

from django.core.wsgi import get_wsgi_application  # noqa: E402

app = get_wsgi_application()
