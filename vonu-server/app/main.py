"""Vonu server HTTP API. Run: uvicorn app.main:app --host 0.0.0.0 --port 8000"""

from __future__ import annotations

from typing import Literal

import os
import uuid

from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field

from fastapi.concurrency import run_in_threadpool

from . import __version__, config, convert, drafts, minutes, pdfa, rag, sync, tables, transcribe
from .llm import LLMUnavailable, from_settings
from .transcribe import TranscriberUnavailable

settings = config.load()
llm = from_settings(settings)
transcriber = transcribe.Transcriber(settings.whisper_model, settings.whisper_device, settings.whisper_compute)

app = FastAPI(title="Vonu server", version=__version__)


def require_token(authorization: str | None = Header(default=None)) -> None:
    if settings.token and authorization != f"Bearer {settings.token}":
        raise HTTPException(status_code=401, detail="Wrong or missing access token")


@app.exception_handler(LLMUnavailable)
async def llm_unavailable(_, exc: LLMUnavailable):
    return JSONResponse(status_code=503, content={"detail": str(exc)})


@app.exception_handler(TranscriberUnavailable)
async def transcriber_unavailable(_, exc: TranscriberUnavailable):
    return JSONResponse(status_code=503, content={"detail": str(exc)})


@app.exception_handler(ValueError)
async def bad_value(_, exc: ValueError):
    return JSONResponse(status_code=422, content={"detail": str(exc)})


async def read_upload(file: UploadFile, limit_mb: int | None = None) -> bytes:
    limit = limit_mb or settings.max_upload_mb
    data = await file.read()
    if len(data) > limit * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"Files larger than {limit} MB aren't accepted")
    if not data:
        raise HTTPException(status_code=400, detail="The file is empty")
    return data


@app.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "version": __version__,
        "features": {
            "ai": settings.llm_provider != "none",
            "model": settings.llm_model if settings.llm_provider != "none" else None,
            "convert": convert.soffice() is not None,
            "pdfa": pdfa.ghostscript() is not None,
            "validate": pdfa.verapdf() is not None,
            "tables": True,
            "transcribe": transcribe.installed(),
            "minutes_summary": settings.llm_provider != "none",
            "minutes_draft": settings.llm_provider != "none",
            "sync": True,
        },
        "auth": bool(settings.token),
    }


@app.post("/transcribe", dependencies=[Depends(require_token)])
async def transcribe_audio(
    file: UploadFile = File(...),
    language: str | None = Query(None, pattern=r"^[a-z]{2,3}$", description="e.g. en; blank = detect"),
) -> dict:
    """Vonu Minutes: a meeting recording → text with timed segments. Nothing is kept."""
    data = await read_upload(file, settings.max_audio_mb)
    # Transcription is CPU-bound and can take minutes; keep the event loop free.
    return await run_in_threadpool(transcriber.transcribe, data, transcribe.safe_suffix(file.filename), language)


# ---- Vonu Minutes: meeting sync between phones (see sync.py) ----

_store: sync.SyncStore | None = None


def store() -> sync.SyncStore:
    global _store
    if _store is None:
        _store = sync.SyncStore(settings.sync_dir)
    return _store


@app.get("/sync/changes", dependencies=[Depends(require_token)])
def sync_changes(since: int = Query(0, ge=0), limit: int = Query(200, ge=1, le=500)) -> dict:
    return store().changes(since, limit)


class MeetingDocIn(BaseModel):
    base: int | None = Field(None, description="The revision this copy was made from; null for a new meeting")
    doc: dict


@app.put("/sync/meetings/{meeting_id}", dependencies=[Depends(require_token)])
def sync_put_meeting(meeting_id: str, body: MeetingDocIn):
    try:
        return store().put_meeting(meeting_id, body.base, body.doc)
    except sync.Conflict as c:
        return JSONResponse(status_code=409, content={"detail": str(c), "rev": c.rev, "doc": c.doc})


class AuditIn(BaseModel):
    events: list[dict] = Field(..., max_length=1000)


@app.post("/sync/audit", dependencies=[Depends(require_token)])
def sync_audit(body: AuditIn) -> dict:
    return {"added": store().add_audit(body.events)}


@app.put("/sync/files/{file_id}", dependencies=[Depends(require_token)])
async def sync_put_file(file_id: str, request: Request) -> dict:
    """An attachment's bytes, streamed to disk (recordings can be hundreds of MB)."""
    target = store().file_path(file_id)
    limit = max(settings.max_audio_mb, settings.max_upload_mb) * 1024 * 1024
    part = target.with_name(f".{file_id}.{uuid.uuid4().hex}.part")
    size = 0
    try:
        with open(part, "wb") as out:
            async for chunk in request.stream():
                size += len(chunk)
                if size > limit:
                    raise HTTPException(status_code=413, detail=f"Files larger than {limit // 1024 // 1024} MB aren't accepted")
                out.write(chunk)
        if size == 0:
            raise HTTPException(status_code=400, detail="The file is empty")
        os.replace(part, target)
    finally:
        part.unlink(missing_ok=True)
    return {"size": size}


@app.get("/sync/files/{file_id}", dependencies=[Depends(require_token)])
def sync_get_file(file_id: str):
    path = store().file_path(file_id)
    if not path.exists():
        raise HTTPException(status_code=404, detail="Not uploaded yet")
    return FileResponse(path, media_type="application/octet-stream")


class PagesIn(BaseModel):
    pages: list[str] = Field(..., description="Text of each page, in order")
    title: str | None = None


class SummarizeIn(PagesIn):
    length: Literal["short", "medium", "long"] = "medium"


class Turn(BaseModel):
    q: str
    a: str


class AskIn(PagesIn):
    question: str
    history: list[Turn] = []


@app.post("/summarize", dependencies=[Depends(require_token)])
def summarize(body: SummarizeIn) -> dict:
    return rag.summarize(llm, body.pages, body.length, body.title, settings.max_context_chars)


class MinutesIn(BaseModel):
    text: str = Field(..., max_length=2_000_000, description="The minutes as Markdown, then any transcripts")
    title: str | None = None


@app.post("/minutes/summary", dependencies=[Depends(require_token)])
def summarize_minutes(body: MinutesIn) -> dict:
    """Vonu Minutes: an AI summary of one meeting. Nothing is kept."""
    return minutes.summarize(llm, body.text, body.title, settings.max_context_chars)


@app.post("/minutes/draft", dependencies=[Depends(require_token)])
def draft_minutes(body: drafts.DraftIn) -> dict:
    """Vonu Minutes: a first draft of the minutes from a transcript and notes. Nothing is kept."""
    return drafts.draft(llm, body, settings.max_context_chars)


@app.post("/ask", dependencies=[Depends(require_token)])
def ask(body: AskIn) -> dict:
    return rag.ask(llm, body.pages, body.question, [t.model_dump() for t in body.history])


@app.post("/tables", dependencies=[Depends(require_token)])
async def extract_tables(
    file: UploadFile = File(...),
    format: Literal["json", "xlsx"] = Query("json"),
    pages: str | None = Query(None, description="Comma-separated 1-based pages, e.g. 2,3"),
):
    data = await read_upload(file)
    wanted = [int(p) for p in pages.split(",") if p.strip().isdigit()] if pages else None
    try:
        found = tables.extract(data, wanted)
    except Exception as e:  # pdfplumber raises many kinds of errors on damaged files
        raise HTTPException(status_code=422, detail=f"Couldn't read this PDF: {e}") from e
    if format == "xlsx":
        return Response(
            tables.to_xlsx(found),
            media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            headers={"Content-Disposition": 'attachment; filename="tables.xlsx"'},
        )
    return {"tables": found}


@app.post("/convert", dependencies=[Depends(require_token)])
async def convert_office(file: UploadFile = File(...)):
    data = await read_upload(file)
    try:
        pdf = convert.to_pdf(data, file.filename or "document.docx")
    except convert.ConversionError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    return Response(pdf, media_type="application/pdf")


@app.post("/pdfa", dependencies=[Depends(require_token)])
async def to_pdfa(file: UploadFile = File(...), part: int = Query(2, ge=1, le=3)):
    data = await read_upload(file)
    try:
        pdf, validation = pdfa.convert(data, part)
    except pdfa.PdfaError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    return Response(pdf, media_type="application/pdf", headers={"X-Vonu-Validation": validation})
