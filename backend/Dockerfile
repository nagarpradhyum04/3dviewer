# Scan3D AI reconstruction server (CPU). For GPU dense reconstruction build pycolmap with CUDA
# on an NVIDIA base image instead (see README "GPU").
FROM python:3.13-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends libegl1 libgl1 libgomp1 libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY tools/make_marker.py ./tools/make_marker.py

ENV SCAN3D_DATA_DIR=/data \
    PYTHONUNBUFFERED=1
VOLUME ["/data"]
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/v1/health')"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
