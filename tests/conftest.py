"""테스트 환경 — 임시 SQLite + 데모 모드. app 임포트 전에 환경변수를 고정한다."""
import os
from datetime import date, timedelta
from pathlib import Path

TEST_DB = Path(__file__).parent / "test_nullnull.db"
os.environ["DATABASE_URL"] = f"sqlite:///{TEST_DB}"
os.environ["DEMO_MODE"] = "true"
os.environ["ADMIN_TOKEN"] = "test-admin"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


@pytest.fixture(scope="session")
def client():
    if TEST_DB.exists():
        TEST_DB.unlink()
    from app.main import app

    with TestClient(app) as c:   # lifespan: 테이블 생성 + 시드 적재
        yield c
    TEST_DB.unlink(missing_ok=True)


@pytest.fixture
def db(client):
    from app.database import SessionLocal

    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture(scope="session")
def visit_date() -> str:
    """예측 창 내 다가오는 토요일."""
    today = date.today()
    return (today + timedelta(days=(5 - today.weekday()) % 7)).isoformat()


def spot_id_by_name(client, name: str) -> int:
    resp = client.get("/api/spots", params={"keyword": name})
    items = resp.json()["items"]
    assert items, f"시드에 '{name}' 스팟이 없어요"
    return items[0]["spot_id"]


@pytest.fixture(scope="session")
def gyeongbok_id(client) -> int:
    return spot_id_by_name(client, "경복궁")
