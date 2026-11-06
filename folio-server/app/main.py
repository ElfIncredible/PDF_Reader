"""Folio server HTTP API. Run: uvicorn app.main:app --host 0.0.0.0 --port 8000"""

from __future__ import annotations

from typing import Literal

from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, UploadFile
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

from . import __version__, config, convert, pdfa, rag, tables
from .llm import LLMUnavailable, from_settings

settings = config.load()
llm = from_settings(settings)

app = FastAPI(title="Folio server", version=__version__)


def require_token(authorization: str | None = Header(default=None)) -> None:
    if settings.token and authorization != f"Bearer {settings.token}":
        raise HTTPException(status_code=401, detail="Wrong or missing access token")


@app.exception_handler(LLMUnavailable)
async def llm_unavailable(_, exc: LLMUnavailable):
    return JSONResponse(status_code=503, content={"detail": str(exc)})


@app.exception_handler(ValueError)
async def bad_value(_, exc: ValueError):
    return JSONResponse(status_code=422, content={"detail": str(exc)})


async def read_upload(file: UploadFile) -> bytes:
    data = await file.read()
    if len(data) > settings.max_upload_mb * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"Files larger than {settings.max_upload_mb} MB aren't accepted")
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
        },
        "auth": bool(settings.token),
    }


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
    return Response(pdf, media_type="application/pdf", headers={"X-Folio-Validation": validation})
