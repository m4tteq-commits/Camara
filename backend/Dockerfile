FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENV OMP_THREAD_LIMIT=2
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils tesseract-ocr tesseract-ocr-ron tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
RUN useradd --uid 10001 --create-home camara && mkdir /data /backups && chown camara:camara /data /backups
COPY requirements-docker.lock .
RUN pip install --no-cache-dir --require-hashes --only-binary=:all: -r requirements-docker.lock
COPY --chown=camara:camara app ./app
USER 10001:10001
EXPOSE 8080
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--loop", "uvloop", "--http", "httptools", "--no-access-log"]
