"""Office documents → PDF with LibreOffice (headless)."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path

ALLOWED = {
    "doc", "docx", "odt", "rtf", "txt", "wpd",
    "xls", "xlsx", "ods", "csv",
    "ppt", "pptx", "odp",
    "html", "htm", "epub",
}


class ConversionError(RuntimeError):
    pass


def soffice() -> str | None:
    return shutil.which("soffice") or shutil.which("libreoffice")


def to_pdf(data: bytes, filename: str, timeout: int = 180) -> bytes:
    binary = soffice()
    if not binary:
        raise ConversionError("LibreOffice isn't installed on this server")
    ext = Path(filename).suffix.lower().lstrip(".")
    if ext not in ALLOWED:
        raise ConversionError(f"Can't convert .{ext} files. Supported: {', '.join(sorted(ALLOWED))}")
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / f"input.{ext}"
        src.write_bytes(data)
        # A private profile per call lets several conversions run at once.
        profile = Path(tmp) / f"profile-{uuid.uuid4().hex}"
        cmd = [
            binary, f"-env:UserInstallation=file://{profile}", "--headless", "--norestore",
            "--convert-to", "pdf", "--outdir", tmp, str(src),
        ]
        try:
            subprocess.run(cmd, check=True, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as e:
            raise ConversionError("The conversion took too long") from e
        except subprocess.CalledProcessError as e:
            raise ConversionError(f"LibreOffice couldn't convert this file: {e.stderr.decode(errors='replace')[:200]}") from e
        out = Path(tmp) / "input.pdf"
        if not out.exists():
            raise ConversionError("LibreOffice didn't produce a PDF (is the file damaged or password-protected?)")
        return out.read_bytes()
