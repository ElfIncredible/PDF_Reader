"""Settings from environment variables (see .env.example)."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    # Shared secret the app sends as "Authorization: Bearer <token>". Empty = no auth (local testing only).
    token: str
    # "openai" (any OpenAI-compatible API: Ollama, LM Studio, OpenAI, Groq…), "anthropic", or "none".
    llm_provider: str
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_timeout: float
    # How much document text one model call may carry. Larger documents are summarised in parts.
    max_context_chars: int
    max_upload_mb: int


def load() -> Settings:
    env = os.environ.get
    return Settings(
        token=env("FOLIO_TOKEN", ""),
        llm_provider=env("LLM_PROVIDER", "openai").strip().lower(),
        llm_base_url=env("LLM_BASE_URL", "http://localhost:11434/v1").rstrip("/"),
        llm_api_key=env("LLM_API_KEY", ""),
        llm_model=env("LLM_MODEL", "llama3.1:8b"),
        llm_timeout=float(env("LLM_TIMEOUT", "180")),
        max_context_chars=int(env("MAX_CONTEXT_CHARS", "24000")),
        max_upload_mb=int(env("MAX_UPLOAD_MB", "60")),
    )
