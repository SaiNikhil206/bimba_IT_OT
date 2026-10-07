FROM python:3.10-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["sh", "-c", "python manage.py ensure_schema && python manage.py migrate --noinput && python manage.py install_sql && python manage.py ensure_admin && uvicorn bimba.asgi:application --host 0.0.0.0 --port 8000 --lifespan on"]
