from __future__ import annotations

import pytest
from pydantic import ValidationError

from nullnull_ai.settings import Settings


def test_deployed_environments_require_a_catalog_version() -> None:
    with pytest.raises(ValidationError, match="NULLNULL_CATALOG_VERSION"):
        Settings(NULLNULL_ENV="staging")
    deployed = Settings(NULLNULL_ENV="staging", NULLNULL_CATALOG_VERSION="catalog-2026-09-07")
    assert deployed.effective_catalog_version == "catalog-2026-09-07"


def test_local_placeholder_is_labelled_not_silent() -> None:
    assert Settings(NULLNULL_ENV="local").effective_catalog_version == "catalog-unversioned-local"
    with pytest.raises(ValidationError):
        Settings(NULLNULL_ENV="local", NULLNULL_CATALOG_VERSION="  ")
