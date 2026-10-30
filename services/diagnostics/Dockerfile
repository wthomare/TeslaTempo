FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY services/diagnostics/collector.py /app/collector.py
COPY services/relay/relay/diagnostics.py /app/schema.py
RUN chmod -R a+rX /app
USER 10001:10001
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD python -c "import os,time; assert time.time()-os.stat('/state/collector.json').st_mtime < 120"
CMD ["python", "/app/collector.py"]
