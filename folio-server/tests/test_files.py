"""Uses LibreOffice (if installed) to make real PDFs, then checks tables and conversion."""

import shutil
import zipfile
import io

import pytest

from app import convert, tables

needs_soffice = pytest.mark.skipif(not convert.soffice(), reason="LibreOffice not installed")

HTML = """<html><body><h1>Invoice</h1>
<table border="1" cellspacing="0" cellpadding="4">
<tr><th>Item</th><th>Qty</th><th>Price</th></tr>
<tr><td>Paper A4</td><td>10</td><td>450</td></tr>
<tr><td>Toner</td><td>2</td><td>3,200</td></tr>
</table></body></html>"""


@needs_soffice
def test_office_to_pdf_and_table_extraction():
    pdf = convert.to_pdf(HTML.encode(), "invoice.html")
    assert pdf.startswith(b"%PDF")
    found = tables.extract(pdf)
    assert found, "the invoice table should be found"
    rows = found[0]["rows"]
    flat = " ".join(" ".join(r) for r in rows)
    assert "Paper A4" in flat and "Toner" in flat
    xlsx = tables.to_xlsx(found)
    with zipfile.ZipFile(io.BytesIO(xlsx)) as z:
        assert any(n.startswith("xl/worksheets/") for n in z.namelist())


def test_rejects_unknown_extensions():
    with pytest.raises(convert.ConversionError):
        convert.to_pdf(b"x", "virus.exe")


def test_numbers_become_numbers_in_excel():
    assert tables._number_or_text("3,200") == 3200
    assert tables._number_or_text("12.5") == 12.5
    assert tables._number_or_text("Toner") == "Toner"
