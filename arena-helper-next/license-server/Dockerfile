FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd -u 10001 app && mkdir /data && chown app:app /data
COPY server.py .
USER app
ENV LICENSE_DB=/data/licenses.sqlite
EXPOSE 8000
CMD ["gunicorn", "--bind", "0.0.0.0:8000", "--workers", "2", "--threads", "4", "--timeout", "30", "server:production()"]
