import os

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand


class Command(BaseCommand):
    help = "Create the POC admin user from DJANGO_ADMIN_USER / DJANGO_ADMIN_PASSWORD if missing"

    def handle(self, *args, **options):
        user, password = os.environ.get("DJANGO_ADMIN_USER"), os.environ.get("DJANGO_ADMIN_PASSWORD")
        if not user or not password:
            return
        User = get_user_model()
        if not User.objects.filter(username=user).exists():
            User.objects.create_superuser(user, email="", password=password)
            self.stdout.write(f"created admin user {user}")
