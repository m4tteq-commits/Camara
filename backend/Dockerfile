FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENV OMP_THREAD_LIMIT=2
WORKDIR /app
COPY requirements-lock.txt .
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils tesseract-ocr tesseract-ocr-ron tesseract-ocr-eng && rm -rf /var/lib/apt/lists/* && pip install --no-cache-dir -r requirements-lock.txt && useradd --uid 10001 --create-home camara && mkdir /data /backups && chown camara:camara /data /backups
COPY --chown=camara:camara app ./app
USER 10001:10001
EXPOSE 8080
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log"]
