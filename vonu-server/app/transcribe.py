"""Speech to text for Vonu Minutes recordings, with faster-whisper on this machine.

Audio never leaves the server: it is written to a temporary file, transcribed, and deleted.
The model loads on the first request (and downloads once into the Hugging Face cache).
"""

from __future__ import annotations

import importlib.util
import os
import re
import tempfile
import threading


class TranscriberUnavailable(RuntimeError):
    """Transcription isn't installed or the model can't be loaded (HTTP 503)."""


def installed() -> bool:
    return importlib.util.find_spec("faster_whisper") is not None


_SUFFIX = re.compile(r"\.[A-Za-z0-9]{1,5}")


def safe_suffix(filename: str | None) -> str:
    """Keep a short, plain extension (the decoder uses it as a hint); never a path."""
    ext = os.path.splitext(filename or "")[1]
    return ext if _SUFFIX.fullmatch(ext) else ".m4a"


class Transcriber:
    def __init__(self, model: str, device: str, compute_type: str):
        self._args = (model, device, compute_type)
        self._model = None
        self._lock = threading.Lock()

    def _load(self):
        with self._lock:
            if self._model is None:
                if not installed():
                    raise TranscriberUnavailable("Transcription isn't installed on this server (pip install faster-whisper).")
                from faster_whisper import WhisperModel

                name, device, compute_type = self._args
                try:
                    self._model = WhisperModel(name, device=device, compute_type=compute_type)
                except Exception as e:  # download or hardware problem
                    raise TranscriberUnavailable(f"The speech model '{name}' couldn't be loaded: {e}") from e
            return self._model

    def transcribe(self, audio: bytes, suffix: str = ".m4a", language: str | None = None) -> dict:
        model = self._load()
        fd, path = tempfile.mkstemp(suffix=suffix)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(audio)
            try:
                # Segments are produced lazily; the real work happens while iterating.
                raw, info = model.transcribe(path, language=language or None, vad_filter=True)
                segments = [
                    {"start": round(s.start, 2), "end": round(s.end, 2), "text": s.text.strip()}
                    for s in raw
                    if s.text.strip()
                ]
            except Exception as e:
                raise ValueError(f"Couldn't transcribe that audio ({e.__class__.__name__}).") from e
        finally:
            os.remove(path)
        return {
            "language": info.language,
            "duration": round(info.duration, 1),
            "text": " ".join(s["text"] for s in segments),
            "segments": segments,
        }
