FROM python:3.12-slim
RUN groupadd --system molclass && useradd --system --gid molclass --home /app molclass
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY --chown=molclass:molclass app ./app
# Pre-create the upload mount point: Docker initializes a fresh named volume by
# copying whatever already exists in the image at that path (including
# permissions), so this is what makes /var/lib/molclass/uploads writable instead
# of defaulting to root:root. Mode 1777 (not just chown molclass) because
# sdf-worker also writes here from a separate Alpine-based image whose molclass
# user gets a different uid/gid -- see the equivalent line in the root Dockerfile.
RUN mkdir -p /var/lib/molclass/uploads && chmod 1777 /var/lib/molclass/uploads
USER molclass
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
