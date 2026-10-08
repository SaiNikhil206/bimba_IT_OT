import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent


def env(name, default=None):
    return os.environ.get(name, default)


SECRET_KEY = env("DJANGO_SECRET_KEY", "dev-only-change-me")
DEBUG = env("DJANGO_DEBUG", "1") == "1"
ALLOWED_HOSTS = env("DJANGO_ALLOWED_HOSTS", "*").split(",")
CSRF_TRUSTED_ORIGINS = [o for o in env("DJANGO_CSRF_TRUSTED_ORIGINS", "http://localhost:3000").split(",") if o]

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "channels",
    "telemetry",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
]

ROOT_URLCONF = "bimba.urls"
ASGI_APPLICATION = "bimba.asgi.application"

TEMPLATES = [{
    "BACKEND": "django.template.backends.django.DjangoTemplates",
    "DIRS": [],
    "APP_DIRS": True,
    "OPTIONS": {"context_processors": [
        "django.template.context_processors.request",
        "django.contrib.auth.context_processors.auth",
        "django.contrib.messages.context_processors.messages",
    ]},
}]

DATABASES = {
    "default": {
        # postgresql + every statement pinned to DB_SCHEMA (safe behind the transaction-mode pooler, see bimba/pgbackend)
        "ENGINE": "bimba.pgbackend",
        "HOST": env("DB_HOST", "localhost"),
        "PORT": env("DB_PORT", "5432"),
        "NAME": env("DB_NAME", "bimba"),
        "USER": env("DB_USER", "bimba"),
        "PASSWORD": env("DB_PASSWORD", "bimba"),
        "CONN_MAX_AGE": 60,
        "DISABLE_SERVER_SIDE_CURSORS": True,
    }
}
# All BIMBA objects (including Django's own tables) live in this schema only, so a shared
# database's other tables are never read or modified. See bimba/pgbackend and ensure_schema.
DB_SCHEMA = env("DB_SCHEMA", "public")
# The BIMBA Gateway keeps its own tables in this schema (same migrations); migrate_gateway_schema keeps it current.
GATEWAY_DB_SCHEMA = env("GATEWAY_DB_SCHEMA", "")

# Single-process POC: in-memory layer. Use channels_redis for multiple workers.
CHANNEL_LAYERS = {"default": {"BACKEND": "channels.layers.InMemoryChannelLayer"}}

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
LANGUAGE_CODE = "en-us"
TIME_ZONE = "UTC"
USE_TZ = True
STATIC_URL = "/django-static/"
STATIC_ROOT = BASE_DIR / "staticfiles"

# ---- BIMBA
INGEST_API_KEY = env("INGEST_API_KEY", "dev-ingest-key")
PLUGIN_STATUS_URL = env("PLUGIN_STATUS_URL", "http://gateway:8090/system/bimba-status")
MQTT_HOST = env("MQTT_HOST", "mqtt")
MQTT_PORT = int(env("MQTT_PORT", "1883"))
MQTT_TOPIC = env("MQTT_TOPIC", "bimba/v1/+/django")
# Plugin config folder (./ignition/bimba) edited by the OT/IT Bridge Console
BIMBA_CONFIG_DIR = env("BIMBA_CONFIG_DIR", "/bimba-config")
# Live updates: auto = LISTEN/NOTIFY when the DB delivers notifications, else polling
LIVE_MODE = env("LIVE_MODE", "auto")
LIVE_POLL_SECONDS = float(env("LIVE_POLL_SECONDS", "1"))

LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "handlers": {"console": {"class": "logging.StreamHandler"}},
    "root": {"handlers": ["console"], "level": "INFO"},
}
