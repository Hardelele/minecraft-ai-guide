# AI Guide share service: the standard library only, no pip packages.
FROM python:3.12-slim
RUN useradd --system --uid 10001 --home /app share && mkdir -p /data && chown share /data
WORKDIR /app
COPY sigv4.py server.py ./
USER share
ENV SHARE_BIND=0.0.0.0 SHARE_PORT=8080 SHARE_DB=/data/share.sqlite SHARE_TRUST_PROXY=1 PYTHONUNBUFFERED=1
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=3).status == 200 else 1)"
CMD ["python", "server.py"]
