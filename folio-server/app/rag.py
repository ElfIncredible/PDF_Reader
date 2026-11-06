"""Summaries and question answering over a document's page text, with page citations.

Retrieval is a small BM25 index built per request: no vector database to run, and it works
well on single documents. Answers must cite pages like [p. 3], which the app turns into links.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass

from .llm import LLM

_WORD = re.compile(r"\w+", re.UNICODE)
_STOP = set(
    "a an and are as at be by for from has have in is it its of on or that the this to was were will with "
    "you your we our they their i he she what which who when where why how do does did not no can".split()
)
_CITE = re.compile(r"\[p\.?\s*(\d+)(?:\s*[-–,]\s*(\d+))*\]", re.IGNORECASE)


@dataclass(frozen=True)
class Chunk:
    page: int  # 1-based
    text: str


def tokenize(text: str) -> list[str]:
    return [w for w in (m.group(0).lower() for m in _WORD.finditer(text)) if w not in _STOP and len(w) > 1]


def chunk_pages(pages: list[str], size: int = 1200, overlap: int = 200) -> list[Chunk]:
    """Splits each page into overlapping windows, breaking at whitespace."""
    chunks: list[Chunk] = []
    for number, raw in enumerate(pages, start=1):
        text = " ".join(raw.split())
        if not text:
            continue
        start = 0
        while start < len(text):
            end = min(len(text), start + size)
            if end < len(text):
                space = text.rfind(" ", start + size // 2, end)
                if space > start:
                    end = space
            chunks.append(Chunk(number, text[start:end].strip()))
            if end >= len(text):
                break
            start = max(end - overlap, start + 1)
    return chunks


class BM25:
    def __init__(self, chunks: list[Chunk], k1: float = 1.5, b: float = 0.75):
        self.chunks = chunks
        self.k1, self.b = k1, b
        self.docs = [Counter(tokenize(c.text)) for c in chunks]
        self.lengths = [sum(d.values()) for d in self.docs]
        self.avg = (sum(self.lengths) / len(self.lengths)) if self.lengths else 0.0
        df: Counter[str] = Counter()
        for d in self.docs:
            df.update(d.keys())
        n = len(chunks)
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}

    def search(self, query: str, k: int = 6) -> list[tuple[Chunk, float]]:
        terms = tokenize(query)
        scored = []
        for i, d in enumerate(self.docs):
            score = 0.0
            for t in terms:
                f = d.get(t, 0)
                if not f:
                    continue
                denom = f + self.k1 * (1 - self.b + self.b * self.lengths[i] / (self.avg or 1))
                score += self.idf.get(t, 0.0) * f * (self.k1 + 1) / denom
            if score > 0:
                scored.append((self.chunks[i], score))
        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:k]


def cited_pages(text: str) -> list[int]:
    """Pages cited as [p. 3] or [p. 3, 5] in the model's answer, in order of first mention."""
    seen: list[int] = []
    for m in _CITE.finditer(text):
        for n in re.findall(r"\d+", m.group(0)):
            p = int(n)
            if p not in seen:
                seen.append(p)
    return seen


# ---- Summaries ------------------------------------------------------------------

_LENGTH = {
    "short": "3 to 4 sentences and up to 4 key points",
    "medium": "one or two paragraphs and up to 7 key points",
    "long": "three to five paragraphs and up to 12 key points",
}

SUMMARY_SYSTEM = (
    "You summarise documents for busy people. Be accurate and neutral, and use only what the document says. "
    "Write in the document's own language. Cite the page of every key point like [p. 4]."
)


def _pack(pages: list[str], budget: int) -> list[str]:
    """Groups pages (each labelled with its number) into blocks under `budget` characters."""
    blocks, current = [], ""
    for i, page in enumerate(pages, start=1):
        text = " ".join(page.split())
        if not text:
            continue
        piece = f"\n[Page {i}]\n{text[:budget]}"
        if current and len(current) + len(piece) > budget:
            blocks.append(current)
            current = ""
        current += piece
    if current:
        blocks.append(current)
    return blocks


def parse_summary(raw: str) -> tuple[str, list[str]]:
    """Splits 'SUMMARY: … KEY POINTS: - …' into (summary, points). Lenient with small models."""
    text = raw.strip()
    m = re.search(r"key\s*points\s*:?", text, re.IGNORECASE)
    if m:
        summary_part, points_part = text[: m.start()], text[m.end():]
    else:
        summary_part, points_part = text, ""
    summary = re.sub(r"^\s*summary\s*:?\s*", "", summary_part, flags=re.IGNORECASE).strip()
    points = [
        re.sub(r"^\s*(?:[-*•]|\d+[.)])\s*", "", line).strip()
        for line in points_part.splitlines()
        if line.strip() and re.match(r"^\s*(?:[-*•]|\d+[.)])", line)
    ]
    return summary, points


def summarize(llm: LLM, pages: list[str], length: str, title: str | None, max_chars: int) -> dict:
    if not any(p.strip() for p in pages):
        raise ValueError("This document has no text. If it's a scan, make it searchable (OCR) in Folio first.")
    shape = _LENGTH.get(length, _LENGTH["medium"])
    blocks = _pack(pages, max_chars)
    heading = f'Document: "{title}"\n' if title else ""

    if len(blocks) > 1:
        # Map: brief notes per part, keeping page numbers. Reduce: one summary from the notes.
        notes = []
        for i, block in enumerate(blocks, start=1):
            notes.append(llm.complete(
                SUMMARY_SYSTEM,
                f"{heading}This is part {i} of {len(blocks)} of a long document.\n{block}\n\n"
                "Write concise notes of the important facts, each ending with its page like [p. 12].",
                max_tokens=700,
            ))
        source = "\n\n".join(f"[Notes, part {i}]\n{n}" for i, n in enumerate(notes, start=1))
        intro = "These are notes from each part of a long document. Combine them."
    else:
        source = blocks[0]
        intro = "Here is the document."

    raw = llm.complete(
        SUMMARY_SYSTEM,
        f"{heading}{intro}\n{source}\n\nWrite {shape}. Use exactly this format:\n"
        "SUMMARY:\n<the summary>\nKEY POINTS:\n- <point> [p. N]\n- <point> [p. N]",
        max_tokens=1400 if length == "long" else 900,
    )
    summary, points = parse_summary(raw)
    return {"summary": summary or raw, "points": points, "parts": len(blocks)}


# ---- Questions ------------------------------------------------------------------

ASK_SYSTEM = (
    "You answer questions about one document using only the excerpts provided. "
    "Cite the page for each fact like [p. 3]. If the excerpts don't contain the answer, say "
    "\"I couldn't find that in this document.\" Don't guess. Answer in the language of the question."
)


def ask(llm: LLM, pages: list[str], question: str, history: list[dict] | None = None, k: int = 6) -> dict:
    question = question.strip()
    if not question:
        raise ValueError("Ask a question")
    chunks = chunk_pages(pages)
    if not chunks:
        raise ValueError("This document has no text. If it's a scan, make it searchable (OCR) in Folio first.")
    # Include the previous question so follow-ups like "and the deadline?" still retrieve well.
    last_q = (history or [])[-1].get("q", "") if history else ""
    hits = BM25(chunks).search(f"{question} {last_q}", k=k)
    if not hits:
        # Nothing matched the words; fall back to the start of the document.
        hits = [(c, 0.0) for c in chunks[:3]]
    excerpts = "\n\n".join(f"[p. {c.page}] {c.text}" for c, _ in hits)
    convo = "".join(
        f"Earlier question: {h.get('q', '')}\nEarlier answer: {h.get('a', '')}\n" for h in (history or [])[-3:]
    )
    answer = llm.complete(
        ASK_SYSTEM,
        f"Excerpts from the document:\n{excerpts}\n\n{convo}Question: {question}",
        max_tokens=700,
    )
    retrieved = {c.page: c.text for c, _ in hits}
    citations = [{"page": p, "snippet": retrieved[p][:240]} for p in cited_pages(answer) if p in retrieved]
    return {"answer": answer, "citations": citations}
