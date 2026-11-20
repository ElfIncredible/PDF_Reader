"""Language-model providers behind one tiny interface. Claude uses the official anthropic SDK."""

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
    """Claude through the official Anthropic SDK (pip install anthropic)."""

    # Current models: thinking is always on (and counts toward max_tokens), effort sets its depth,
    # and the API can re-run a declined request on a fallback model. Older models get neither field.
    _CURRENT = ("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    _THINKING_ROOM = 16000

    def __init__(self, api_key: str, model: str, timeout: float, base_url: str = "https://api.anthropic.com"):
        if not api_key:
            raise LLMUnavailable("LLM_API_KEY is required for the anthropic provider")
        self.model = model
        try:
            import anthropic
        except ImportError:  # reported on use, so the rest of the server still starts
            self._sdk = self.client = None
            return
        self._sdk = anthropic
        self.client = anthropic.Anthropic(api_key=api_key, base_url=base_url.rstrip("/"), timeout=timeout)

    def complete(self, system: str, prompt: str, max_tokens: int = 900) -> str:
        sdk = self._sdk
        if sdk is None:
            raise LLMUnavailable("The anthropic package isn't installed on this server (pip install anthropic)")
        args: dict = {
            "model": self.model,
            "max_tokens": max_tokens + self._THINKING_ROOM,
            "system": system,
            "messages": [{"role": "user", "content": prompt}],
        }
        if self.model in self._CURRENT:
            args["output_config"] = {"effort": "medium"}
            args["betas"] = ["server-side-fallback-2026-07-01"]
            args["fallbacks"] = "default"
        try:
            # Streamed so long minutes never hit an HTTP timeout; only the final message is used.
            with self.client.beta.messages.stream(**args) as stream:
                message = stream.get_final_message()
        except sdk.AuthenticationError as e:
            raise LLMUnavailable("Anthropic rejected the API key (LLM_API_KEY)") from e
        except sdk.PermissionDeniedError as e:
            raise LLMUnavailable(f"This API key can't use {self.model}") from e
        except sdk.NotFoundError as e:
            raise LLMUnavailable(f"Anthropic doesn't know the model '{self.model}' (LLM_MODEL)") from e
        except sdk.RateLimitError as e:
            raise LLMUnavailable("Anthropic is rate limiting this key; try again in a minute") from e
        except sdk.APIStatusError as e:
            raise LLMUnavailable(f"The AI model returned an error ({e.status_code}): {e.message}"[:300]) from e
        except sdk.APIConnectionError as e:
            raise LLMUnavailable(f"Couldn't reach Anthropic: {e}") from e
        if message.stop_reason == "refusal":
            raise LLMUnavailable("The AI model declined to process this text")
        return "".join(b.text for b in message.content if b.type == "text").strip()


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
