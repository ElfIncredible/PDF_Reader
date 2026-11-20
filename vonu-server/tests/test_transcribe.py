"""/transcribe tests. The Whisper model is faked, so these run without faster-whisper installed."""

import os
from types import SimpleNamespace

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient  # noqa: E402

from app import main, transcribe  # noqa: E402


class FakeModel:
    def __init__(self):
        self.calls = []

    def transcribe(self, path, language=None, vad_filter=False):
        with open(path, "rb") as f:
            self.calls.append((path, f.read(), language))
        segs = [
            SimpleNamespace(start=0.0, end=2.345, text=" Welcome, everyone. "),
            SimpleNamespace(start=2.5, end=3.0, text="   "),  # silence is dropped
            SimpleNamespace(start=3.1, end=6.0, text=" Item one is the budget."),
        ]
        return iter(segs), SimpleNamespace(language=language or "en", duration=6.04)


def client(model, token=""):
    main.settings = main.settings.__class__(**{**main.settings.__dict__, "token": token})
    t = transcribe.Transcriber("small", "cpu", "int8")
    t._model = model
    main.transcriber = t
    return TestClient(main.app)


def upload(c, name="Recording.m4a", data=b"audio-bytes", **kw):
    return c.post("/transcribe", files={"file": (name, data, "audio/mp4")}, **kw)


def test_returns_text_and_timed_segments():
    model = FakeModel()
    r = upload(client(model))
    assert r.status_code == 200
    body = r.json()
    assert body["text"] == "Welcome, everyone. Item one is the budget."
    assert body["segments"] == [
        {"start": 0.0, "end": 2.35, "text": "Welcome, everyone."},
        {"start": 3.1, "end": 6.0, "text": "Item one is the budget."},
    ]
    assert body["language"] == "en" and body["duration"] == 6.0
    path, data, _ = model.calls[0]
    assert data == b"audio-bytes" and path.endswith(".m4a")


def test_audio_is_not_kept():
    model = FakeModel()
    upload(client(model))
    assert not os.path.exists(model.calls[0][0])


def test_token_required_when_set():
    c = client(FakeModel(), token="secret")
    assert upload(c).status_code == 401
    assert upload(c, headers={"Authorization": "Bearer secret"}).status_code == 200


def test_language_is_passed_and_validated():
    model = FakeModel()
    c = client(model)
    assert c.post("/transcribe?language=sw", files={"file": ("a.m4a", b"x", "audio/mp4")}).status_code == 200
    assert model.calls[0][2] == "sw"
    assert c.post("/transcribe?language=../x", files={"file": ("a.m4a", b"x", "audio/mp4")}).status_code == 422


def test_empty_upload_is_400():
    assert upload(client(FakeModel()), data=b"").status_code == 400


def test_odd_file_names_never_become_paths():
    assert transcribe.safe_suffix("../../etc/passwd") == ".m4a"
    assert transcribe.safe_suffix("talk.webm") == ".webm"
    assert transcribe.safe_suffix(None) == ".m4a"


def test_unreadable_audio_is_a_clear_422():
    class Broken:
        def transcribe(self, *a, **k):
            raise RuntimeError("bad container")

    r = upload(client(Broken()))
    assert r.status_code == 422 and "Couldn't transcribe" in r.json()["detail"]


def test_missing_engine_is_503(monkeypatch):
    monkeypatch.setattr(transcribe, "installed", lambda: False)
    r = upload(client(None))
    assert r.status_code == 503 and "faster-whisper" in r.json()["detail"]
