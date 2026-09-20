"""Runtime settings. Missing or unsafe values fail at startup; nothing falls back silently."""

from __future__ import annotations

from typing import Literal

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore", frozen=True)

    nullnull_env: Literal["local", "test", "staging", "production"] = Field(default="local", alias="NULLNULL_ENV")
    bind_host: str = Field(default="127.0.0.1", alias="NULLNULL_AI_BIND_HOST")
    port: int = Field(default=8090, alias="NULLNULL_AI_PORT")
    ai_provider: Literal["NONE", "OPENAI"] = Field(default="NONE", alias="AI_PROVIDER")
    ai_api_key: str | None = Field(default=None, alias="AI_API_KEY")
    ai_model_id: str | None = Field(default=None, alias="AI_MODEL_ID")
    catalog_version: str | None = Field(default=None, alias="NULLNULL_CATALOG_VERSION")

    @field_validator("port")
    @classmethod
    def _port_range(cls, value: int) -> int:
        if not 1024 <= value <= 65535:
            raise ValueError("NULLNULL_AI_PORT must be within 1024..65535")
        return value

    @field_validator("bind_host")
    @classmethod
    def _bind_host(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("NULLNULL_AI_BIND_HOST must not be blank")
        return value

    @model_validator(mode="after")
    def _catalog_version_required_when_deployed(self) -> Settings:
        if self.catalog_version is not None and not self.catalog_version.strip():
            raise ValueError("NULLNULL_CATALOG_VERSION must not be blank")
        if self.nullnull_env in ("staging", "production") and self.catalog_version is None:
            raise ValueError("NULLNULL_CATALOG_VERSION is required when NULLNULL_ENV is staging or production")
        return self

    @model_validator(mode="after")
    def _provider_credentials_required(self) -> Settings:
        """A named provider without its credentials stops the process instead of answering as NONE.

        Falling back to the template here would be indistinguishable, on screen, from a working
        model - the sentence is the same either way (REC-LLM-01). So the difference has to be made
        at startup, where somebody is watching, rather than silently at request time.

        Blank is treated as missing: an unset variable in compose or a task definition arrives as
        the empty string, and `AI_API_KEY=` is exactly what apps/ai/.env.example declares.
        """
        if self.ai_provider == "NONE":
            return self
        for name, value in (("AI_API_KEY", self.ai_api_key), ("AI_MODEL_ID", self.ai_model_id)):
            if value is None or not value.strip():
                raise ValueError(f"{name} is required when AI_PROVIDER is {self.ai_provider}")
        return self

    @property
    def effective_catalog_version(self) -> str:
        """local/test use a clearly labelled placeholder; deployed environments must set the real value."""
        return self.catalog_version or f"catalog-unversioned-{self.nullnull_env}"
