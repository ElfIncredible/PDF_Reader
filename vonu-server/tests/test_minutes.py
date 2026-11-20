"""AI summaries of meeting minutes, and the Claude provider's error handling (no network)."""

from types import SimpleNamespace

import pytest

from app import llm as llm_mod
from app import minutes


class Recorder:
    def __init__(self):
        self.prompts = []

    def complete(self, system, prompt, max_tokens=900):
        self.prompts.append(prompt)
        return "SUMMARY:\nBudget approved.\nKEY POINTS:\n- Decision R1: budget approved\n- Peter: quotes by Friday"


def test_short_minutes_need_one_call():
    rec = Recorder()
    out = minutes.summarize(rec, "# Minutes\n\nBudget approved.", "Board", 24000)
    assert out == {"summary": "Budget approved.", "points": ["Decision R1: budget approved", "Peter: quotes by Friday"], "parts": 1}
    assert len(rec.prompts) == 1 and 'Meeting: "Board"' in rec.prompts[0]


def test_long_minutes_are_summarised_in_parts_without_losing_text():
    rec = Recorder()
    text = "\n\n".join(f"Paragraph {i} " + "x" * 300 for i in range(40))
    out = minutes.summarize(rec, text, None, 2000)
    assert out["parts"] > 1 and len(rec.prompts) == out["parts"] + 1
    assert all(f"Paragraph {i} " in "".join(rec.prompts) for i in range(40))


def test_parts_cut_a_huge_paragraph():
    parts = minutes._parts("y" * 5000, 2000)
    assert [len(p) for p in parts] == [2000, 2000, 1000]


def test_empty_minutes_are_rejected():
    with pytest.raises(ValueError):
        minutes.summarize(Recorder(), "  ", None, 24000)


class FakeStream:
    def __init__(self, message=None, error=None):
        self.message, self.error = message, error

    def __enter__(self):
        if self.error:
            raise self.error
        return self

    def __exit__(self, *a):
        return False

    def get_final_message(self):
        return self.message


def claude(stream, calls):
    anthropic = pytest.importorskip("anthropic")
    c = llm_mod.Anthropic("key", "claude-opus-5-5", 30)

    def fake(**kw):
        calls.append(kw)
        return stream

    c.client = SimpleNamespace(beta=SimpleNamespace(messages=SimpleNamespace(stream=fake)))
    return c, anthropic


def test_claude_returns_text_and_sends_current_model_options():
    msg = SimpleNamespace(stop_reason="end_turn", content=[
        SimpleNamespace(type="thinking", thinking=""), SimpleNamespace(type="text", text=" Done. "),
    ])
    calls = []
    c, _ = claude(FakeStream(msg), calls)
    assert c.complete("sys", "hi", 900) == "Done."
    kw = calls[0]
    assert kw["fallbacks"] == "default" and kw["output_config"] == {"effort": "medium"}
    assert kw["max_tokens"] > 900 and "thinking" not in kw


def test_claude_refusal_is_a_clear_error():
    calls = []
    c, _ = claude(FakeStream(SimpleNamespace(stop_reason="refusal", content=[])), calls)
    with pytest.raises(llm_mod.LLMUnavailable, match="declined"):
        c.complete("sys", "hi")


def test_claude_connection_error_is_reported():
    anthropic = pytest.importorskip("anthropic")
    import httpx2

    err = anthropic.APIConnectionError(request=httpx2.Request("POST", "https://api.anthropic.com/v1/messages"))
    c, _ = claude(FakeStream(error=err), [])
    with pytest.raises(llm_mod.LLMUnavailable, match="reach Anthropic"):
        c.complete("sys", "hi")
