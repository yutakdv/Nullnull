from __future__ import annotations

import pytest
from pydantic import ValidationError

from nullnull_ai.explain.ports import NoopLlmExplanationPort
from nullnull_ai.main import llm_port
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


def test_a_provider_without_an_adapter_fails_at_startup_instead_of_falling_back() -> None:
    """AI_PROVIDER=NONE is the P0 default. A model provider arrives with its own adapter, model id,
    timeout and kill switch; until then an unknown value stops the process rather than quietly
    answering with the template as if a model had been asked."""
    assert Settings(NULLNULL_ENV="test").ai_provider == "NONE"
    with pytest.raises(ValidationError, match="AI_PROVIDER"):
        Settings(NULLNULL_ENV="test", AI_PROVIDER="OPENAI")


def test_the_wiring_refuses_a_provider_it_has_no_port_for() -> None:
    """The same guard one layer up, for the day a value is added to the Literal before its adapter."""
    unsupported = Settings(NULLNULL_ENV="test").model_copy(update={"ai_provider": "OPENAI"})
    with pytest.raises(ValueError, match="unsupported AI_PROVIDER"):
        llm_port(unsupported)


def test_the_default_provider_never_attributes_an_answer_to_a_model() -> None:
    port = llm_port(Settings(NULLNULL_ENV="test"))
    assert isinstance(port, NoopLlmExplanationPort)
