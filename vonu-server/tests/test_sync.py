"""Vonu Minutes meeting sync: compare-and-set, the change feed, audit events and files."""

import uuid

import pytest

from app import sync

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient  # noqa: E402

from app import main  # noqa: E402


def new_id():
    return str(uuid.uuid4())


def doc(meeting_id, title="Board", attachments=None):
    return {"meeting": {"id": meeting_id, "title": title}, "attachments": attachments or {}}


@pytest.fixture
def client(tmp_path):
    main.settings = main.settings.__class__(**{**main.settings.__dict__, "token": "secret", "max_audio_mb": 1, "max_upload_mb": 1})
    main._store = sync.SyncStore(tmp_path)
    c = TestClient(main.app)
    c.headers["Authorization"] = "Bearer secret"
    return c


def test_a_meeting_is_written_on_top_of_the_revision_it_was_made_from(tmp_path):
    s = sync.SyncStore(tmp_path)
    m = new_id()
    first = s.put_meeting(m, None, doc(m))["rev"]
    second = s.put_meeting(m, first, doc(m, "Board, edited"))["rev"]
    assert second > first
    # A phone still holding the first revision is refused and gets the current copy.
    with pytest.raises(sync.Conflict) as c:
        s.put_meeting(m, first, doc(m, "Stale"))
    assert c.value.rev == second and c.value.doc["meeting"]["title"] == "Board, edited"
    # A new meeting that already exists is a conflict too.
    with pytest.raises(sync.Conflict):
        s.put_meeting(m, None, doc(m))


def test_the_feed_pages_through_meetings_and_audit_in_order(tmp_path):
    s = sync.SyncStore(tmp_path)
    ids = [new_id() for _ in range(5)]
    for i in ids:
        s.put_meeting(i, None, doc(i))
    events = [{"id": new_id(), "action": "Created"}]
    assert s.add_audit(events) == 1
    assert s.add_audit(events) == 0  # already there

    page = s.changes(0, limit=4)
    assert page["more"] is True and [c["id"] for c in page["changes"]] == ids[:4]
    rest = s.changes(page["next"], limit=4)
    assert rest["more"] is False
    assert [c["kind"] for c in rest["changes"]] == ["meeting", "audit"]
    assert s.changes(rest["next"])["changes"] == []

    # A rewritten meeting moves to the end of the feed, once.
    s.put_meeting(ids[0], page["changes"][0]["rev"], doc(ids[0], "Again"))
    later = s.changes(rest["next"])["changes"]
    assert [(c["id"], c["doc"]["meeting"]["title"]) for c in later] == [(ids[0], "Again")]


def test_ids_must_be_uuids(tmp_path):
    s = sync.SyncStore(tmp_path)
    with pytest.raises(ValueError):
        s.file_path("../sync.db")
    with pytest.raises(ValueError):
        s.put_meeting("not-an-id", None, {"meeting": {"id": "not-an-id"}})
    m = new_id()
    with pytest.raises(ValueError):
        s.put_meeting(m, None, {"meeting": {"id": new_id()}})  # body for another meeting
    with pytest.raises(ValueError):
        s.add_audit([{"id": "1; DROP TABLE records"}])


def test_sync_endpoints_need_the_token(client):
    m = new_id()
    assert client.get("/sync/changes", headers={"Authorization": ""}).status_code == 401
    assert client.put(f"/sync/meetings/{m}", json={"base": None, "doc": doc(m)}, headers={"Authorization": ""}).status_code == 401
    assert client.get("/health").json()["features"]["sync"] is True


def test_put_reports_missing_files_then_files_round_trip(client):
    m, a = new_id(), new_id()
    r = client.put(f"/sync/meetings/{m}", json={"base": None, "doc": doc(m, attachments={a: {"id": a}})})
    assert r.status_code == 200 and r.json()["missing_files"] == [a]
    rev = r.json()["rev"]

    assert client.get(f"/sync/files/{a}").status_code == 404
    assert client.put(f"/sync/files/{a}", content=b"audio bytes").json() == {"size": 11}
    assert client.get(f"/sync/files/{a}").content == b"audio bytes"

    again = client.put(f"/sync/meetings/{m}", json={"base": rev, "doc": doc(m, attachments={a: {"id": a}})})
    assert again.json()["missing_files"] == []

    stale = client.put(f"/sync/meetings/{m}", json={"base": rev, "doc": doc(m)})
    assert stale.status_code == 409 and stale.json()["rev"] == again.json()["rev"]

    feed = client.get("/sync/changes", params={"since": 0}).json()
    assert [c["id"] for c in feed["changes"]] == [m]


def test_files_have_a_size_limit_and_bad_ids_are_refused(client):
    a = new_id()
    big = client.put(f"/sync/files/{a}", content=b"x" * (1024 * 1024 + 1))
    assert big.status_code == 413 and client.get(f"/sync/files/{a}").status_code == 404
    assert client.put(f"/sync/files/{a}", content=b"").status_code == 400
    assert client.put("/sync/files/..%2Fsync.db", content=b"x").status_code in (404, 422)
    assert client.get("/sync/files/not-a-uuid").status_code == 422
    leftovers = [p.name for p in main._store.files.iterdir()]
    assert leftovers == []
