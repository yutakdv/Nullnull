# 널널(Nullnull) 백엔드 — FastAPI
FROM python:3.14-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY weights.yaml .

# DB는 볼륨(/data)에 저장 — 컨테이너 재생성에도 수집 데이터·로그 유지
ENV DATABASE_URL=sqlite:////data/nullnull.db
RUN mkdir -p /data

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
