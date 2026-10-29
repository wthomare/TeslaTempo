FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && pip check \
    && groupadd --gid 10001 relay \
    && useradd --uid 10001 --gid relay --no-create-home relay \
    && mkdir /data && chown relay:relay /data

COPY relay/ ./relay/
RUN chmod -R a+rX /app/relay
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import http.client; c=http.client.HTTPConnection('127.0.0.1',8080,timeout=3); c.request('GET','/healthz'); assert c.getresponse().status == 200" || exit 1

# One worker: OAuth state, rate limits and refresh serialization are process-local.
# Access logs are disabled so OAuth codes and launch tickets cannot leak in URLs.
CMD ["uvicorn", "relay.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log", "--no-proxy-headers"]
