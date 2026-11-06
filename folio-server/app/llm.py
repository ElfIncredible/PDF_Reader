"""Language-model providers behind one tiny interface. Uses only the standard library."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Protocol

from .config import Settings


class LLMUnavailable(RuntimeError):
    """Raised when no model is configured or the provider can't be reached."""


class LLM(Protocol):
    def complete(self, system: str, prompt: str, max_tokens: int = 900) -> str: ...


def _post_json(url: str, payload: dict, headers: dict, timeout: float) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", **headers}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        raise LLMUnavailable(f"The AI model returned an error ({e.code}): {detail}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise LLMUnavailable(f"Couldn't reach the AI model at {url}: {e}") from e


class OpenAICompatible:
    """OpenAI's /chat/completions format, also spoken by Ollama, LM Studio, vLLM, Groq and others."""

    def __init__(self, base_url: str, api_key: str, model: str, timeout: float):
        self.url = f"{base_url}/chat/completions"
        self.headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self.model = model
        self.timeout = timeout

    def complete(self, system: str, prompt: str, max_tokens: int = 900) -> str:
        body = {
            "model": self.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}],
            "max_tokens": max_tokens,
            "temperature": 0.2,
        }
        out = _post_json(self.url, body, self.headers, self.timeout)
        try:
            return out["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError, TypeError) as e:
            raise LLMUnavailable("Unexpected reply from the AI model") from e


class Anthropic:
    """Anthropic's Messages API."""

    def __init__(self, api_key: str, model: str, timeout: float, base_url: str = "https://api.anthropic.com"):
        if not api_key:
            raise LLMUnavailable("LLM_API_KEY is required for the anthropic provider")
        self.url = f"{base_url.rstrip('/')}/v1/messages"
        self.headers = {"x-api-key": api_key, "anthropic-version": "2023-06-01"}
        self.model = model
        self.timeout = timeout

    def complete(self, system: str, prompt: str, max_tokens: int = 900) -> str:
        body = {
            "model": self.model,
            "max_tokens": max_tokens,
            "system": system,
            "messages": [{"role": "user", "content": prompt}],
        }
        out = _post_json(self.url, body, self.headers, self.timeout)
        try:
            return "".join(part.get("text", "") for part in out["content"]).strip()
        except (KeyError, TypeError) as e:
            raise LLMUnavailable("Unexpected reply from the AI model") from e


class Disabled:
    def complete(self, system: str, prompt: str, max_tokens: int = 900) -> str:
        raise LLMUnavailable("AI features are turned off on this server (LLM_PROVIDER=none)")


def from_settings(s: Settings) -> LLM:
    if s.llm_provider == "none":
        return Disabled()
    if s.llm_provider == "anthropic":
        base = s.llm_base_url if "anthropic" in s.llm_base_url else "https://api.anthropic.com"
        return Anthropic(s.llm_api_key, s.llm_model, s.llm_timeout, base)
    return OpenAICompatible(s.llm_base_url, s.llm_api_key, s.llm_model, s.llm_timeout)
