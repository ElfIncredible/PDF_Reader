"""AI first drafts of Vonu Minutes from a meeting's transcript and rough notes.

The app sends the agenda, attendees, transcripts and notes; the reply is structured (discussion
per agenda item, decisions, actions) so the app can offer each part for the secretary to accept.
Everything returned is checked here, so a weaker model can't send back unusable values.
Nothing is stored.
"""

from __future__ import annotations

import re

from pydantic import BaseModel, Field

from .llm import LLM, complete_json

OUTCOMES = ["Carried", "Rejected", "Deferred", "Noted"]


class AgendaIn(BaseModel):
    title: str = Field(..., max_length=500)
    notes: str = Field("", max_length=20_000)


class DraftIn(BaseModel):
    title: str = Field("", max_length=500)
    date: str = Field("", max_length=10, description="Meeting date, yyyy-MM-dd")
    attendees: list[str] = Field(default_factory=list, max_length=500)
    agenda: list[AgendaIn] = Field(default_factory=list, max_length=200)
    transcript: str = Field("", max_length=2_000_000)
    notes: str = Field("", max_length=200_000)


SYSTEM = (
    "You are an experienced company secretary writing the first draft of formal meeting minutes from a "
    "transcript and rough notes. Write in formal, neutral, past-tense minute style (\"The committee noted...\", "
    "\"It was agreed that...\"). Record what was discussed and decided, not every word said. Use only what the "
    "source says: never invent names, figures, dates or decisions. If something is unclear, leave it out. "
    "Write in the language of the source."
)

INSTRUCTIONS = """Draft the minutes as JSON:
- opening: who opened the meeting and when, apologies and quorum, if mentioned; else "".
- items: for each numbered agenda item that was discussed, its number in "agenda" and a concise summary of the discussion in "discussion" (one or two short paragraphs).
- decisions: each formal decision or resolution. "agenda" is the agenda number it belongs to, or 0. "outcome" is one of Carried, Rejected, Deferred, Noted.
- actions: each task someone agreed to do. "owner" is the person's name as said (prefer the attendee list spelling) or "". "due" is yyyy-MM-dd only when a date is stated or clearly implied (resolve "next Friday" from the meeting date), else "". "agenda" as for decisions.
- other_business: anything discussed outside the agenda; else "".
- closing: when and how the meeting closed and the next meeting date, if mentioned; else ""."""

_INT = {"type": "integer"}
_STR = {"type": "string"}


def _obj(props: dict) -> dict:
    return {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}


SCHEMA = _obj({
    "opening": _STR,
    "items": {"type": "array", "items": _obj({"agenda": _INT, "discussion": _STR})},
    "decisions": {"type": "array", "items": _obj({"agenda": _INT, "text": _STR, "outcome": {"type": "string", "enum": OUTCOMES}})},
    "actions": {"type": "array", "items": _obj({"agenda": _INT, "description": _STR, "owner": _STR, "due": _STR})},
    "other_business": _STR,
    "closing": _STR,
})

_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


def _source(body: DraftIn) -> str:
    agenda = "\n".join(
        f"{i}. {a.title}" + (f"\n   Secretary's notes: {a.notes.strip()}" if a.notes.strip() else "")
        for i, a in enumerate(body.agenda, start=1)
    ) or "(no agenda)"
    parts = [
        f"Meeting: {body.title or '(untitled)'}",
        f"Date: {body.date or '(unknown)'}",
        "Attendees: " + (", ".join(a for a in body.attendees if a.strip()) or "(not recorded)"),
        f"Agenda:\n{agenda}",
    ]
    if body.notes.strip():
        parts.append(f"Rough notes from the secretary:\n{body.notes.strip()}")
    if body.transcript.strip():
        parts.append(f"Transcript (automatic speech recognition; names and numbers may be misheard):\n{body.transcript.strip()}")
    return "\n\n".join(parts)


def _text(value) -> str:
    return value.strip() if isinstance(value, str) else ""


def _agenda(value, count: int) -> int:
    """A valid 1-based agenda number, or 0 for none."""
    return value if isinstance(value, int) and not isinstance(value, bool) and 1 <= value <= count else 0


def clean(raw: dict, agenda_count: int) -> dict:
    """Keeps only well-formed parts of the model's reply."""
    def rows(key):
        value = raw.get(key)
        return [r for r in value if isinstance(r, dict)] if isinstance(value, list) else []

    items, seen = [], set()
    for r in rows("items"):
        n, text = _agenda(r.get("agenda"), agenda_count), _text(r.get("discussion"))
        if n and text and n not in seen:
            seen.add(n)
            items.append({"agenda": n, "discussion": text})
    decisions = [
        {
            "agenda": _agenda(r.get("agenda"), agenda_count),
            "text": _text(r.get("text")),
            "outcome": r.get("outcome") if r.get("outcome") in OUTCOMES else "Carried",
        }
        for r in rows("decisions") if _text(r.get("text"))
    ]
    actions = [
        {
            "agenda": _agenda(r.get("agenda"), agenda_count),
            "description": _text(r.get("description")),
            "owner": _text(r.get("owner")),
            "due": _text(r.get("due")) if _DATE.fullmatch(_text(r.get("due"))) else "",
        }
        for r in rows("actions") if _text(r.get("description"))
    ]
    return {
        "opening": _text(raw.get("opening")),
        "items": sorted(items, key=lambda i: i["agenda"]),
        "decisions": decisions,
        "actions": actions,
        "other_business": _text(raw.get("other_business")),
        "closing": _text(raw.get("closing")),
    }


def draft(llm: LLM, body: DraftIn, max_chars: int) -> dict:
    if not body.transcript.strip() and not body.notes.strip():
        raise ValueError("There is nothing to draft from. Transcribe a recording or add rough notes first.")
    source = _source(body)
    if len(source) > max_chars:
        # A draft has to see the whole meeting at once; cutting it would silently lose decisions.
        raise ValueError(
            f"This meeting is too long for one draft on this server ({len(source):,} characters, limit "
            f"{max_chars:,}). Raise MAX_CONTEXT_CHARS on the server if its AI model can read more."
        )
    raw = complete_json(llm, SYSTEM, f"{source}\n\n{INSTRUCTIONS}", SCHEMA, max_tokens=8000)
    return clean(raw, len(body.agenda))
