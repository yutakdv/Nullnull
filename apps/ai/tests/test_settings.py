from __future__ import annotations

import pytest
from pydantic import ValidationError

from nullnull_ai.explain.ports import NoopLlmExplanationPort
from nullnull_ai.main import llm_port
from nullnull_ai.provider.openai import OpenAiExplanationPort
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


def test_the_default_is_still_no_provider() -> None:
    """P0 and the contest profile run with no model at all; that has to be what an unset value means."""
    assert Settings(NULLNULL_ENV="test").ai_provider == "NONE"


def test_a_name_the_literal_does_not_know_fails_at_startup() -> None:
    """Until BA-084② this clause was carried by AI_PROVIDER=OPENAI, which the Literal now accepts.

    The clause itself did not go away with the adapter - it is "a provider this build cannot serve
    stops the process" - so it moves to a name that is still unknown. Keeping the old case here
    would have left an assertion that passes for a new reason: OPENAI does raise today, but for a
    missing credential, which is the test below.
    """
    with pytest.raises(ValidationError, match="AI_PROVIDER"):
        Settings(NULLNULL_ENV="test", AI_PROVIDER="ANTHROPIC")


@pytest.mark.parametrize(
    ("missing", "given"),
    [
        ("AI_API_KEY", {"AI_MODEL_ID": "gpt-test"}),
        ("AI_MODEL_ID", {"AI_API_KEY": "sk-test"}),
    ],
)
def test_a_named_provider_without_its_credentials_fails_at_startup(missing: str, given: dict[str, str]) -> None:
    """The failure has to happen where somebody is watching.

    A missing key discovered at request time would end in the deterministic template - the same
    sentence a working model produces (REC-LLM-01) - so nothing on screen would distinguish "the
    model is off" from "the model is misconfigured".
    """
    with pytest.raises(ValidationError, match=missing):
        Settings(NULLNULL_ENV="test", AI_PROVIDER="OPENAI", **given)


@pytest.mark.parametrize("blank", ["", "   "])
def test_a_blank_credential_counts_as_missing(blank: str) -> None:
    """An unset variable in compose or a task definition arrives as the empty string, and
    `AI_API_KEY=` is exactly what apps/ai/.env.example declares."""
    with pytest.raises(ValidationError, match="AI_API_KEY"):
        Settings(NULLNULL_ENV="test", AI_PROVIDER="OPENAI", AI_API_KEY=blank, AI_MODEL_ID="gpt-test")


def test_the_wiring_refuses_a_provider_it_has_no_port_for() -> None:
    """The same guard one layer up, for the day a value is added to the Literal before its adapter.

    `model_copy` is what makes this reachable: the Literal refuses the value, so the only way to ask
    the wiring what it does with an unknown name is to set it past validation.
    """
    unsupported = Settings(NULLNULL_ENV="test").model_copy(update={"ai_provider": "ANTHROPIC"})
    with pytest.raises(ValueError, match="unsupported AI_PROVIDER"):
        llm_port(unsupported)


def test_the_wiring_refuses_openai_without_credentials_even_past_validation() -> None:
    """Two checks, on purpose. If the settings validator is ever narrowed, this one keeps a request
    from going out with the string "None" as the credential - a 401 instead of a startup that stopped."""
    stripped = Settings(NULLNULL_ENV="test").model_copy(update={"ai_provider": "OPENAI"})
    with pytest.raises(ValueError, match="AI_API_KEY and AI_MODEL_ID are required"):
        llm_port(stripped)


def test_the_default_provider_never_attributes_an_answer_to_a_model() -> None:
    port = llm_port(Settings(NULLNULL_ENV="test"))
    assert isinstance(port, NoopLlmExplanationPort)


def test_a_configured_provider_is_wired_to_its_adapter() -> None:
    configured = Settings(NULLNULL_ENV="test", AI_PROVIDER="OPENAI", AI_API_KEY="sk-test", AI_MODEL_ID="gpt-test")
    assert isinstance(llm_port(configured), OpenAiExplanationPort)
