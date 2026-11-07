"""Table extraction with pdfplumber, exported as JSON rows or an Excel workbook."""

from __future__ import annotations

import io

import pdfplumber
from openpyxl import Workbook
from openpyxl.styles import Font


def extract(pdf_bytes: bytes, pages: list[int] | None = None, max_pages: int = 200) -> list[dict]:
    """Returns [{"page": 1-based, "index": n, "rows": [[cell, …], …]}, …]."""
    found: list[dict] = []
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
        wanted = pages or list(range(1, min(len(pdf.pages), max_pages) + 1))
        for number in wanted:
            if not 1 <= number <= len(pdf.pages):
                continue
            page = pdf.pages[number - 1]
            for i, table in enumerate(page.extract_tables(), start=1):
                rows = [[" ".join((cell or "").split()) for cell in row] for row in table if row]
                rows = [r for r in rows if any(r)]
                # Ignore "tables" that are really one line of text or a single cell.
                if len(rows) >= 2 and max(len(r) for r in rows) >= 2:
                    found.append({"page": number, "index": i, "rows": rows})
    return found


def to_xlsx(tables: list[dict]) -> bytes:
    wb = Workbook()
    wb.remove(wb.active)
    if not tables:
        wb.create_sheet("No tables").append(["No tables were found in this PDF."])
    for t in tables:
        ws = wb.create_sheet(f"p{t['page']}-t{t['index']}"[:31])
        for r, row in enumerate(t["rows"], start=1):
            for c, value in enumerate(row, start=1):
                ws.cell(row=r, column=c, value=_number_or_text(value))
        for cell in ws[1]:
            cell.font = Font(bold=True)
        for column in ws.columns:
            width = max((len(str(c.value or "")) for c in column), default=8)
            ws.column_dimensions[column[0].column_letter].width = min(60, max(8, width + 2))
    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


def _number_or_text(value: str):
    """Numbers become real numbers in Excel (so sums work); everything else stays text."""
    s = value.replace(",", "").strip()
    try:
        return int(s) if s.lstrip("-").isdigit() else float(s)
    except ValueError:
        return value
