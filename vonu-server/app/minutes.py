"""AI summaries of Vonu Minutes meetings.

The app sends the written minutes (Markdown) and, when there are recordings, their transcripts.
The written minutes are the record; transcripts only add context. Nothing is stored here.
"""

from __future__ import annotations

from .llm import LLM
from .rag import parse_summary

SYSTEM = (
    "You summarise meeting minutes for people who missed the meeting. Be accurate and neutral and use only "
    "what the text says; never invent names, dates or decisions. The written minutes are the official record: "
    "where a transcript disagrees with them, follow the minutes. Write in the minutes' own language."
)

FORMAT = (
    "Write a short overview (one paragraph), then the key points: every decision, and every action with "
    "its owner and due date when given. Use exactly this format:\n"
    "SUMMARY:\n<the overview>\nKEY POINTS:\n- <point>\n- <point>"
)


def _parts(text: str, budget: int) -> list[str]:
    """Splits on blank lines into parts under `budget` characters, cutting very long paragraphs."""
    parts, current = [], ""
    for para in text.split("\n\n"):
        while len(para) > budget:
            if current:
                parts.append(current)
                current = ""
            parts.append(para[:budget])
            para = para[budget:]
        if current and len(current) + len(para) + 2 > budget:
            parts.append(current)
            current = ""
        current = f"{current}\n\n{para}" if current else para
    if current.strip():
        parts.append(current)
    return parts


def summarize(llm: LLM, text: str, title: str | None, max_chars: int) -> dict:
    if not text.strip():
        raise ValueError("These minutes are empty. Add the discussion first.")
    heading = f'Meeting: "{title}"\n' if title else ""
    parts = _parts(text, max_chars)
    if len(parts) > 1:
        # Map: notes per part. Reduce: one summary from the notes.
        notes = [
            llm.complete(
                SYSTEM,
                f"{heading}This is part {i} of {len(parts)} of a meeting record.\n\n{part}\n\n"
                "Write concise notes of the decisions, actions (with owners and dates) and main points.",
                max_tokens=800,
            )
            for i, part in enumerate(parts, start=1)
        ]
        source = "These are notes from each part of a long meeting record. Combine them.\n\n" + "\n\n".join(
            f"[Notes, part {i}]\n{n}" for i, n in enumerate(notes, start=1)
        )
    else:
        source = parts[0]
    raw = llm.complete(SYSTEM, f"{heading}{source}\n\n{FORMAT}", max_tokens=1200)
    summary, points = parse_summary(raw)
    return {"summary": summary or raw, "points": points, "parts": len(parts)}
