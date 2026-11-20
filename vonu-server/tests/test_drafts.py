"""AI drafts of minutes: validation of the model's reply, limits, and the Claude structured path."""

import json
from types import SimpleNamespace

import pytest

from app import drafts
from app import llm as llm_mod

GOOD = {
    "opening": "The chair opened the meeting at 09:30.",
    "items": [{"agenda": 2, "discussion": "Quotes were reviewed."}, {"agenda": 1, "discussion": "Minutes confirmed."}],
    "decisions": [{"agenda": 2, "text": "Supplier B was approved.", "outcome": "Carried"}],
    "actions": [{"agenda": 2, "description": "Order the racking", "owner": "Peter", "due": "2026-11-27"}],
    "other_business": "",
    "closing": "Closed at 10:15.",
}


class Chatty:
    """A small local model: JSON wrapped in prose, asked for via the prompt."""

    def __init__(self, reply):
        self.reply, self.prompts = reply, []

    def complete(self, system, prompt, max_tokens=900):
        self.prompts.append(prompt)
        return "Here is the draft:" + chr(10) + json.dumps(self.reply) + chr(10) + "Hope this helps."


def body(**kw):
    base = {"title": "Warehouse", "date": "2026-11-20", "attendees": ["Peter Otieno"],
            "agenda": [{"title": "Previous minutes"}, {"title": "Racking"}], "transcript": "[00:00] Hello"}
    return drafts.DraftIn(**{**base, **kw})


def test_draft_from_a_chatty_model_is_parsed_and_sorted():
    model = Chatty(GOOD)
    out = drafts.draft(model, body(), 100_000)
    assert [i["agenda"] for i in out["items"]] == [1, 2]
    assert out["actions"][0] == {"agenda": 2, "description": "Order the racking", "owner": "Peter", "due": "2026-11-27"}
    prompt = model.prompts[0]
    assert "JSON Schema" in prompt and "1. Previous minutes" in prompt and "Peter Otieno" in prompt


def test_bad_values_are_dropped_or_corrected():
    raw = {
        "opening": 5,
        "items": [{"agenda": 9, "discussion": "Out of range"}, {"agenda": True, "discussion": "Bool"},
                  {"agenda": 1, "discussion": "First"}, {"agenda": 1, "discussion": "Duplicate"}, "junk"],
        "decisions": [{"agenda": 0, "text": "  ", "outcome": "Carried"}, {"agenda": 7, "text": "Kept", "outcome": "Maybe"}],
        "actions": [{"agenda": 1, "description": "Call", "owner": None, "due": "next Friday"}],
    }
    out = drafts.clean(raw, agenda_count=2)
    assert out["opening"] == "" and out["closing"] == "" and out["other_business"] == ""
    assert out["items"] == [{"agenda": 1, "discussion": "First"}]
    assert out["decisions"] == [{"agenda": 0, "text": "Kept", "outcome": "Carried"}]
    assert out["actions"] == [{"agenda": 1, "description": "Call", "owner": "", "due": ""}]


def test_nothing_to_draft_and_too_long_are_clear_errors():
    with pytest.raises(ValueError, match="nothing to draft"):
        drafts.draft(Chatty(GOOD), body(transcript="", notes=" "), 100_000)
    with pytest.raises(ValueError, match="too long"):
        drafts.draft(Chatty(GOOD), body(transcript="x" * 5000), 1000)


def test_unreadable_reply_is_reported():
    class Prose:
        def complete(self, system, prompt, max_tokens=900):
            return "Sorry, I cannot help."
    with pytest.raises(llm_mod.LLMUnavailable, match="expected form"):
        drafts.draft(Prose(), body(), 100_000)


class FakeStream:
    def __init__(self, message):
        self.message = message

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def get_final_message(self):
        return self.message


def claude(stop_reason="end_turn"):
    pytest.importorskip("anthropic")
    c = llm_mod.Anthropic("key", "claude-opus-5-5", 30)
    calls = []
    msg = SimpleNamespace(stop_reason=stop_reason, content=[SimpleNamespace(type="text", text=json.dumps(GOOD))])

    def fake(**kw):
        calls.append(kw)
        return FakeStream(msg)

    c.client = SimpleNamespace(beta=SimpleNamespace(messages=SimpleNamespace(stream=fake)))
    return c, calls


def test_claude_uses_structured_outputs_for_drafts():
    c, calls = claude()
    out = drafts.draft(c, body(), 100_000)
    assert out["decisions"][0]["text"] == "Supplier B was approved."
    fmt = calls[0]["output_config"]["format"]
    assert fmt["type"] == "json_schema" and fmt["schema"] is drafts.SCHEMA
    assert "JSON Schema" not in calls[0]["messages"][0]["content"]


def test_claude_cut_off_draft_is_an_error():
    c, _ = claude(stop_reason="max_tokens")
    with pytest.raises(llm_mod.LLMUnavailable, match="cut off"):
        drafts.draft(c, body(), 100_000)


def test_schema_follows_structured_output_rules():
    def check(node):
        if node.get("type") == "object":
            assert node["additionalProperties"] is False and set(node["required"]) == set(node["properties"])
            for child in node["properties"].values():
                check(child)
        if node.get("type") == "array":
            check(node["items"])
    check(drafts.SCHEMA)
