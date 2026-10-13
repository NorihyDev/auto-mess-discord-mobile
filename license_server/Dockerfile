FROM python:3.14-slim
WORKDIR /service
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py admin.html ./
EXPOSE 8765
CMD ["python", "server.py", "--host", "0.0.0.0", "--db", "/data/licenses.sqlite3"]
