"""Vonu Minutes: meeting sync between phones.

The server is a mailbox with a version number, not a second copy of the app's rules. Each meeting
is one JSON document (the meeting and all its rows), stored as the phone sent it. Every write gets
the next number of one global counter (its revision), so a phone asks "what changed since N?" and
never depends on phone clocks.

A phone writes a meeting only on top of the revision it last saw (compare and set). If someone
else wrote first, the write is refused with the current copy; the phone merges and tries again.
Audit events are append-only and travel separately. Attachment files never change once stored
under their id, so they are stored and fetched by id.

Storage: SQLite plus a folder of files under SYNC_DIR. Several server processes may share it:
writes take SQLite's write lock (BEGIN IMMEDIATE) for the whole compare-and-set.
"""

from __future__ import annotations

import json
import re
import sqlite3
from pathlib import Path

_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

MEETING = "meeting"
AUDIT = "audit"


def check_id(value: str) -> str:
    """Ids are UUIDs generated on the phones. Anything else (such as "../x") is refused."""
    if not isinstance(value, str) or not _UUID.match(value):
        raise ValueError(f"Not a valid id: {str(value)[:40]!r}")
    return value


class Conflict(Exception):
    """Someone else wrote the meeting first. [rev] and [doc] are the current copy (None if absent)."""

    def __init__(self, rev: int | None, doc: dict | None):
        super().__init__("The meeting changed on the server")
        self.rev = rev
        self.doc = doc


class SyncStore:
    def __init__(self, root: str | Path):
        self.root = Path(root)
        self.files = self.root / "files"
        self.files.mkdir(parents=True, exist_ok=True)
        self.db_path = self.root / "sync.db"
        c = self._open()
        try:
            c.execute("PRAGMA journal_mode=WAL")
            c.execute(
                "CREATE TABLE IF NOT EXISTS records (kind TEXT NOT NULL, id TEXT NOT NULL, "
                "rev INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY (kind, id))"
            )
            c.execute("CREATE INDEX IF NOT EXISTS records_rev ON records(rev)")
        finally:
            c.close()

    def _open(self) -> sqlite3.Connection:
        # isolation_level=None: transactions are opened explicitly with BEGIN IMMEDIATE.
        return sqlite3.connect(self.db_path, timeout=30, isolation_level=None)

    @staticmethod
    def _next_rev(c: sqlite3.Connection) -> int:
        return c.execute("SELECT COALESCE(MAX(rev), 0) + 1 FROM records").fetchone()[0]

    # ---- meetings ----

    def put_meeting(self, meeting_id: str, base: int | None, doc: dict) -> dict:
        """Stores [doc] if the server still holds revision [base]. Returns the new revision and the
        attachment files the server does not have yet, which the phone uploads next."""
        check_id(meeting_id)
        meeting = doc.get("meeting") if isinstance(doc, dict) else None
        if not isinstance(meeting, dict) or meeting.get("id") != meeting_id:
            raise ValueError("The document's meeting id does not match the address")
        attachments = doc.get("attachments") or {}
        if not isinstance(attachments, dict):
            raise ValueError("attachments must be an object keyed by id")
        for a in attachments:
            check_id(a)
        body = json.dumps(doc, ensure_ascii=False, separators=(",", ":"))

        c = self._open()
        try:
            c.execute("BEGIN IMMEDIATE")
            row = c.execute("SELECT rev, body FROM records WHERE kind = ? AND id = ?", (MEETING, meeting_id)).fetchone()
            current = row[0] if row else None
            if current != base:
                c.execute("ROLLBACK")
                raise Conflict(current, json.loads(row[1]) if row else None)
            rev = self._next_rev(c)
            c.execute(
                "INSERT INTO records (kind, id, rev, body) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(kind, id) DO UPDATE SET rev = excluded.rev, body = excluded.body",
                (MEETING, meeting_id, rev, body),
            )
            c.execute("COMMIT")
        finally:
            if c.in_transaction:
                c.execute("ROLLBACK")
            c.close()
        missing = [a for a in attachments if not self.file_path(a).exists()]
        return {"rev": rev, "missing_files": missing}

    # ---- audit ----

    def add_audit(self, events: list[dict]) -> int:
        """Adds audit events the server does not have yet (by id). Returns how many were new."""
        for e in events:
            if not isinstance(e, dict):
                raise ValueError("Each audit event must be an object")
            check_id(e.get("id"))
        added = 0
        c = self._open()
        try:
            c.execute("BEGIN IMMEDIATE")
            for e in events:
                exists = c.execute("SELECT 1 FROM records WHERE kind = ? AND id = ?", (AUDIT, e["id"])).fetchone()
                if exists:
                    continue
                c.execute(
                    "INSERT INTO records (kind, id, rev, body) VALUES (?, ?, ?, ?)",
                    (AUDIT, e["id"], self._next_rev(c), json.dumps(e, ensure_ascii=False)),
                )
                added += 1
            c.execute("COMMIT")
        finally:
            if c.in_transaction:
                c.execute("ROLLBACK")
            c.close()
        return added

    # ---- the change feed ----

    def changes(self, since: int, limit: int = 200) -> dict:
        """Meetings and audit events written after revision [since], oldest first. [next] is the
        revision to ask from next time; [more] says another page is waiting."""
        c = self._open()
        try:
            rows = c.execute(
                "SELECT kind, id, rev, body FROM records WHERE rev > ? ORDER BY rev LIMIT ?",
                (since, limit + 1),
            ).fetchall()
        finally:
            c.close()
        more = len(rows) > limit
        rows = rows[:limit]
        return {
            "changes": [{"kind": k, "id": i, "rev": r, "doc": json.loads(b)} for k, i, r, b in rows],
            "next": rows[-1][2] if rows else since,
            "more": more,
        }

    # ---- attachment files ----

    def file_path(self, file_id: str) -> Path:
        return self.files / check_id(file_id)
