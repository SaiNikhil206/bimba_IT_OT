# Build context: repository root (needs shared/sparkplug_b.proto)
FROM python:3.11-slim
WORKDIR /app
COPY edge/agent/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY shared/sparkplug_b.proto .
RUN python -m grpc_tools.protoc -I. --python_out=. sparkplug_b.proto
COPY edge/agent/edge_agent.py .
VOLUME /data
CMD ["python", "-u", "edge_agent.py"]
