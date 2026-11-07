"""PDF → PDF/A with Ghostscript, optionally validated with veraPDF."""

from __future__ import annotations

import glob
import shutil
import subprocess
import tempfile
from pathlib import Path


class PdfaError(RuntimeError):
    pass


def ghostscript() -> str | None:
    return shutil.which("gs")


def verapdf() -> str | None:
    return shutil.which("verapdf")


def _srgb_profile() -> str | None:
    patterns = [
        "/usr/share/ghostscript/*/iccprofiles/srgb.icc",
        "/usr/share/ghostscript/iccprofiles/srgb.icc",
        "/usr/share/color/icc/ghostscript/srgb.icc",
        "/usr/local/share/ghostscript/*/iccprofiles/srgb.icc",
    ]
    for p in patterns:
        hits = sorted(glob.glob(p))
        if hits:
            return hits[-1]
    return None


_DEF = """%!
/ICCProfile ({icc}) def
[/_objdef {{icc_PDFA}} /type /stream /OBJ pdfmark
[{{icc_PDFA}} << /N 3 >> /PUT pdfmark
[{{icc_PDFA}} ICCProfile (r) file /PUT pdfmark
[/_objdef {{OutputIntent_PDFA}} /type /dict /OBJ pdfmark
[{{OutputIntent_PDFA}} <<
  /Type /OutputIntent /S /GTS_PDFA1 /DestOutputProfile {{icc_PDFA}}
  /OutputConditionIdentifier (sRGB) /Info (sRGB IEC61966-2.1)
>> /PUT pdfmark
[{{Catalog}} << /OutputIntents [ {{OutputIntent_PDFA}} ] >> /PUT pdfmark
"""


def convert(data: bytes, part: int = 2, timeout: int = 300) -> tuple[bytes, str]:
    """Returns (pdf bytes, validation) where validation is 'passed', 'failed: …' or 'not-run'."""
    gs = ghostscript()
    if not gs:
        raise PdfaError("Ghostscript isn't installed on this server")
    icc = _srgb_profile()
    if not icc:
        raise PdfaError("No sRGB colour profile found for Ghostscript")
    if part not in (1, 2, 3):
        raise PdfaError("PDF/A part must be 1, 2 or 3")
    with tempfile.TemporaryDirectory() as tmp:
        src, out, definition = Path(tmp) / "in.pdf", Path(tmp) / "out.pdf", Path(tmp) / "PDFA_def.ps"
        src.write_bytes(data)
        definition.write_text(_DEF.format(icc=icc))
        cmd = [
            gs, f"-dPDFA={part}", "-dBATCH", "-dNOPAUSE", "-dNOOUTERSAVE", "-dQUIET",
            "-sColorConversionStrategy=RGB", "-sProcessColorModel=DeviceRGB",
            "-dPDFACompatibilityPolicy=1", "-sDEVICE=pdfwrite",
            f"--permit-file-read={icc}", f"-sOutputFile={out}", str(definition), str(src),
        ]
        try:
            subprocess.run(cmd, check=True, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as e:
            raise PdfaError("The conversion took too long") from e
        except subprocess.CalledProcessError as e:
            raise PdfaError(f"Ghostscript couldn't convert this file: {e.stderr.decode(errors='replace')[:200]}") from e
        result = out.read_bytes()
        return result, _validate(out, part)


def _validate(path: Path, part: int) -> str:
    tool = verapdf()
    if not tool:
        return "not-run"
    try:
        run = subprocess.run([tool, "--flavour", f"{part}b", "--format", "text", str(path)],
                             capture_output=True, timeout=180, text=True)
    except subprocess.TimeoutExpired:
        return "not-run"
    text = run.stdout.strip()
    if text.startswith("PASS"):
        return "passed"
    return "failed: " + (text.splitlines()[0][:160] if text else "see server logs")
