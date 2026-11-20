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
    # Speech to text for Vonu Minutes recordings (faster-whisper). Bigger models are more accurate
    # and slower: tiny, base, small, medium, large-v3. "small" suits a laptop CPU.
    whisper_model: str = "small"
    whisper_device: str = "auto"
    whisper_compute: str = "int8"
    # Recordings are larger than documents: about 29 MB per hour from the app.
    max_audio_mb: int = 300


def load() -> Settings:
    env = os.environ.get
    return Settings(
        token=env("VONU_TOKEN", ""),
        llm_provider=env("LLM_PROVIDER", "openai").strip().lower(),
        llm_base_url=env("LLM_BASE_URL", "http://localhost:11434/v1").rstrip("/"),
        llm_api_key=env("LLM_API_KEY", ""),
        llm_model=env("LLM_MODEL", "llama3.1:8b"),
        llm_timeout=float(env("LLM_TIMEOUT", "180")),
        max_context_chars=int(env("MAX_CONTEXT_CHARS", "24000")),
        max_upload_mb=int(env("MAX_UPLOAD_MB", "60")),
        whisper_model=env("WHISPER_MODEL", "small"),
        whisper_device=env("WHISPER_DEVICE", "auto"),
        whisper_compute=env("WHISPER_COMPUTE", "int8"),
        max_audio_mb=int(env("MAX_AUDIO_MB", "300")),
    )
