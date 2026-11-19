"""Vonu PDF: app vectors + UI direction comp, drawn from the shared Vonu brand generator.

Usage:  python -I branding/make_pdf_ui.py "<path to Vonu Branding>"
Writes app/src/main/res/drawable/{ic_launcher_foreground,ic_launcher_monochrome,ic_vonu_mark}.xml
and branding/vonu-pdf-ui-direction.png. The mark is never hand drawn: geometry comes from make_brand.py.
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
spec = importlib.util.spec_from_file_location("make_brand", os.path.join(sys.argv[1], "source", "make_brand.py"))
B = importlib.util.module_from_spec(spec)
spec.loader.exec_module(B)

SS, LAG = B.SS, B.LAG
PDF = next(p for p in B.PRODUCTS if p["key"] == "pdf")
RED = PDF["color"]

# App palette: Lagoon ground with the PDF hue as primary (mirrors ui/theme/Theme.kt).
UI = dict(bg="#F3EEE4", card="#FBF8F2", on="#14262B", muted="#5A6A6D", line="#E0D7C6",
          primary=RED, onp="#FFFFFF", pill="#F4D9D2", nav="#EAE3D5", tint="#F7E3DD")


def page_mark(k, off, cell):
    """make_brand.mark_paths skips filled glyph polygons, so the page signal is added here."""
    paths = B.mark_paths(k, off, cell, "#FFFFFF", "#FFFFFF", "page")
    for poly in B.glyph_parts("page")[2]:
        pts = " L".join(f"{off + x * k:.2f},{off + y * k:.2f}" for x, y in poly)
        paths.append(f'android:fillColor="#FFFFFF" android:pathData="M{pts} Z"')
    return paths


def write_vectors():
    res = os.path.join(REPO, "app", "src", "main", "res", "drawable")
    k = 0.062
    off = 54 - 500 * k
    files = {
        "ic_launcher_foreground.xml": B.vector(108, 108, page_mark(k, off, B.shade(RED, 0.78)),
                                               "Vonu PDF launcher foreground (Vonu mark, page signal)."),
        "ic_launcher_monochrome.xml": B.vector(108, 108, page_mark(k, off, None),
                                               "Vonu PDF themed-icon layer (Android 13+)."),
        "ic_vonu_mark.xml": B.vector(24, 1000, page_mark(1, 0, RED),
                                     "Vonu PDF mark for the in-app brand bar and landing screen."),
    }
    for name, xml in files.items():
        with open(os.path.join(res, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(xml)


def ws(size, bold=False):
    return B.font("WorkSans-Bold.ttf" if bold else "WorkSans-Regular.ttf", size * SS)


class Phone:
    W, H = 400, 840

    def __init__(self, d, ox, oy):
        self.d, self.ox, self.oy = d, ox, oy
        d.rounded_rectangle([self.X(-12), self.Y(-12), self.X(self.W + 12), self.Y(self.H + 12)], radius=58 * SS, fill=B.rgb("#16161A"))
        d.rounded_rectangle([self.X(0), self.Y(0), self.X(self.W), self.Y(self.H)], radius=46 * SS, fill=B.rgb(UI["bg"]))
        self.t(30, 26, "9:41", ws(15, True), UI["on"])

    def X(self, v):
        return self.ox + v * SS

    def Y(self, v):
        return self.oy + v * SS

    def t(self, x, y, s, f, fill, anchor="la", tracking=0):
        B.text(self.d, (self.X(x), self.Y(y)), s, f, fill, anchor, tracking * SS)

    def box(self, x0, y0, x1, y1, r, fill=None, outline=None, width=0):
        self.d.rounded_rectangle([self.X(x0), self.Y(y0), self.X(x1), self.Y(y1)], radius=r * SS,
                                 fill=B.rgb(fill) if fill else None, outline=B.rgb(outline) if outline else None,
                                 width=width * SS)

    def mark(self, x, y, size):
        B.draw_mark(self.d, self.X(x), self.Y(y), size * SS, RED, "#FFFFFF", "#FFFFFF", "page")

    def label(self, x, y, s):
        self.t(x, y, s, B.mono(10), UI["muted"], tracking=2)

    def nav(self, active):
        self.d.rectangle([self.X(0), self.Y(724), self.X(self.W), self.Y(self.H - 46)], fill=B.rgb(UI["nav"]))
        self.box(0, self.H - 92, self.W, self.H, 46, UI["nav"])
        for i, name in enumerate(("Files", "Tools", "History")):
            cx, on = 67 + i * 133, i == active
            if on:
                self.box(cx - 32, 742, cx + 32, 774, 16, UI["pill"])
            self.box(cx - 9, 749, cx + 9, 767, 4, outline=UI["on"] if on else UI["muted"], width=2)
            self.t(cx, 792, name, ws(12, on), UI["on"] if on else UI["muted"], "mm")

    def gear(self):
        self.d.ellipse([self.X(352), self.Y(72), self.X(376), self.Y(96)], outline=B.rgb(UI["on"]), width=2 * SS)
        self.d.ellipse([self.X(360), self.Y(80), self.X(368), self.Y(88)], fill=B.rgb(UI["on"]))

    def thumb(self, x, y, w, h):
        self.box(x, y, x + w, y + h, 4, "#FFFFFF", UI["line"], 1)
        for i in range(3):
            self.d.line([(self.X(x + 7), self.Y(y + 10 + i * 9)), (self.X(x + w - 7 - (i == 2) * 10), self.Y(y + 10 + i * 9))],
                        fill=B.rgb(UI["line"]), width=3 * SS)


def landing(d, ox, oy):
    p = Phone(d, ox, oy)
    p.mark(140, 270, 120)
    p.t(200, 452, "vonu", B.font("Outfit-Bold.ttf", 48 * SS), LAG["ink"], "mm")
    p.t(200, 500, "PDF", B.mono(13), UI["muted"], "mm", tracking=6)
    p.box(150, 760, 250, 764, 2, UI["line"])
    p.box(150, 760, 196, 764, 2, UI["primary"])


def home(d, ox, oy):
    p = Phone(d, ox, oy)
    p.mark(20, 64, 40)
    p.label(70, 66, "VONU")
    p.t(70, 80, "PDF", ws(22, True), UI["on"])
    p.gear()
    p.t(20, 136, "Your PDFs", ws(28, True), UI["on"])
    p.t(20, 180, "Open a file to read, sign or share it.", ws(15), UI["muted"])
    p.box(20, 220, 380, 272, 26, UI["primary"])
    p.t(200, 246, "+  Open a PDF", ws(16, True), UI["onp"], "mm")
    p.box(20, 284, 380, 336, 26, outline=UI["line"], width=2)
    p.t(200, 310, "Browse folders", ws(16, True), UI["on"], "mm")
    p.box(20, 360, 380, 446, 18, UI["card"])
    p.thumb(36, 375, 42, 56)
    p.label(94, 378, "CONTINUE READING")
    p.t(94, 394, "Lease agreement.pdf", ws(16, True), UI["on"])
    p.t(94, 416, "Page 4 of 12", ws(13), UI["muted"])
    p.box(232, 422, 362, 426, 2, UI["line"])
    p.box(232, 422, 275, 426, 2, UI["primary"])
    p.t(20, 474, "Recent", ws(18, True), UI["on"])
    p.box(262, 470, 306, 498, 14, UI["pill"])
    p.t(284, 484, "All", ws(13, True), UI["on"], "mm")
    p.box(312, 470, 380, 498, 14, outline=UI["line"], width=1)
    p.t(346, 484, "Starred", ws(13), UI["muted"], "mm")
    for i, (n, m) in enumerate((("Invoice 0412.pdf", "Today · 2 pages"), ("Field notes.pdf", "Yesterday · 18 pages"),
                                ("Tax return 2025.pdf", "3 Nov · 6 pages"))):
        y = 516 + i * 66
        p.thumb(20, y, 38, 50)
        p.t(72, y + 6, n, ws(15, True), UI["on"])
        p.t(72, y + 28, m, ws(13), UI["muted"])
    p.nav(0)


def tools(d, ox, oy):
    p = Phone(d, ox, oy)
    p.t(20, 66, "Tools", ws(28, True), UI["on"])
    groups = [("SIGN & FILL", [("Sign a PDF", "Add your signature or initials"), ("Fill a form", "Type into form fields")]),
              ("PAGES", [("Merge PDFs", "Combine several into one"), ("Organize pages", "Reorder, rotate, delete")]),
              ("CONVERT", [("Scan documents", "Camera to clean PDF"), ("Images to PDF", "Photos into one file")]),
              ("PROTECT", [("Add a password", "Lock a PDF"), ("Remove a password", "Unlock a PDF")])]
    y = 122
    for g, rows in groups:
        p.label(20, y, g)
        y += 22
        for title, sub in rows:
            p.box(20, y, 60, y + 40, 12, UI["tint"])
            p.box(32, y + 12, 48, y + 28, 3, outline=UI["primary"], width=2)
            p.t(74, y + 1, title, ws(15, True), UI["on"])
            p.t(74, y + 22, sub, ws(13), UI["muted"])
            y += 50
        y += 8
    p.nav(1)


def comp():
    img, d = B.canvas(B.W, B.H, LAG["ground"])
    for i, screen in enumerate((landing, home, tools)):
        screen(d, (84 + i * 448) * SS, 150 * SS)
    notes = [("01", "Landing", "Mark, wordmark and one quiet progress line while the app wakes."),
             ("02", "Brand bar", "The PDF mark and name lead Home."),
             ("03", "One clear start", "Open a PDF is the only filled button."),
             ("04", "Three tabs", "Files, Tools, History. Settings sits in the bar."),
             ("05", "Tools by job", "Every tool lives in a named group.")]
    body = ws(17)
    y = 180
    for n, title, s in notes:
        B.text(d, (1440 * SS, y * SS), n, B.mono(14), LAG["accent"])
        B.text(d, (1480 * SS, (y - 6) * SS), title, B.font("Outfit-Bold.ttf", 24 * SS), LAG["ink"])
        lines, cur = [], ""
        for word in s.split():
            if cur and body.getlength(cur + " " + word) > 250 * SS:
                lines.append(cur)
                cur = word
            else:
                cur = (cur + " " + word).strip()
        lines.append(cur)
        for j, ln in enumerate(lines):
            B.text(d, (1480 * SS, (y + 32 + j * 23) * SS), ln, body, LAG["ink"])
        y += 158
    m, r = 72 * SS, (B.W - 72) * SS
    B.text(d, (m, m), "VONU PDF — UI DIRECTION", B.mono(15), LAG["text"], tracking=3 * SS)
    B.text(d, (r, m), "LAGOON · PDF RED", B.mono(15), LAG["text"], "ra", tracking=3 * SS)
    B.text(d, (m, (B.H - 72) * SS), "LANDING · FILES · TOOLS", B.mono(15), LAG["text"], "ls", tracking=3 * SS)
    B.text(d, (r, (B.H - 72) * SS), "SCUTE GEOMETRY", B.mono(15), LAG["text"], "rs", tracking=3 * SS)
    d.line([(m, m + 34 * SS), (r, m + 34 * SS)], fill=B.rgb(LAG["text"], 60), width=SS)
    out = img.resize((B.W, B.H), B.Image.LANCZOS).convert("RGB")
    out.save(os.path.join(HERE, "vonu-pdf-ui-direction.png"))


if __name__ == "__main__":
    write_vectors()
    comp()
    print("ok")
