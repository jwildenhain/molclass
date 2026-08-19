FROM python:3.12-slim
RUN groupadd --system molclass && useradd --system --gid molclass --home /app molclass
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY --chown=molclass:molclass app ./app
USER molclass
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
