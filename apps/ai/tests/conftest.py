from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from nullnull_ai.main import create_app
from nullnull_ai.settings import Settings


@pytest.fixture(scope="session")
def client() -> Iterator[TestClient]:
    settings = Settings(NULLNULL_ENV="test", NULLNULL_CATALOG_VERSION="catalog-test-1")
    with TestClient(create_app(settings), raise_server_exceptions=False) as test_client:
        yield test_client
