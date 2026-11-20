"""HTTP-level tests. Need fastapi + httpx (pip install -r requirements-dev.txt)."""

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient  # noqa: E402

from app import main  # noqa: E402


class Echo:
    def complete(self, system, prompt, max_tokens=900):
        return "SUMMARY:\nOK.\nKEY POINTS:\n- Rent [p. 1]"


def client(token=""):
    main.settings = main.settings.__class__(**{**main.settings.__dict__, "token": token})
    main.llm = Echo()
    return TestClient(main.app)


def test_health():
    r = client().get("/health")
    assert r.status_code == 200 and r.json()["ok"] is True


def test_token_required_when_set():
    c = client(token="secret")
    assert c.post("/summarize", json={"pages": ["Rent is due."]}).status_code == 401
    ok = c.post("/summarize", json={"pages": ["Rent is due."]}, headers={"Authorization": "Bearer secret"})
    assert ok.status_code == 200 and ok.json()["points"] == ["Rent [p. 1]"]


def test_empty_document_is_a_clear_422():
    r = client().post("/ask", json={"pages": [""], "question": "What?"})
    assert r.status_code == 422 and "no text" in r.json()["detail"]


def test_minutes_summary_endpoint():
    c = client(token="secret")
    body = {"text": "# Minutes: Board\n\nBudget approved.", "title": "Board"}
    assert c.post("/minutes/summary", json=body).status_code == 401
    r = c.post("/minutes/summary", json=body, headers={"Authorization": "Bearer secret"})
    assert r.status_code == 200 and r.json()["summary"] == "OK."
    assert client().get("/health").json()["features"]["minutes_summary"] in (True, False)


def test_minutes_draft_endpoint():
    c = client(token="secret")
    body = {"title": "Board", "agenda": [{"title": "Budget"}], "notes": "Budget approved."}
    assert c.post("/minutes/draft", json=body).status_code == 401
    r = c.post("/minutes/draft", json=body, headers={"Authorization": "Bearer secret"})
    # The Echo model answers in prose, so the draft is reported as unreadable (503), not a crash.
    assert r.status_code == 503 and "expected form" in r.json()["detail"]
    empty = c.post("/minutes/draft", json={"title": "Board"}, headers={"Authorization": "Bearer secret"})
    assert empty.status_code == 422
