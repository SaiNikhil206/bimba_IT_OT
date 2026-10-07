FROM python:3.11-slim
RUN pip install --no-cache-dir aiohttp==3.10.5
COPY mock.py /app/mock.py
CMD ["python", "-u", "/app/mock.py"]
