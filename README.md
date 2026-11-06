# Folio PDF — Android PDF reader & editor

A simple, all-round PDF app: read, annotate, sign, merge, scan and more.
Current milestone: **Phase 5: Signing and scanning** (built on Phases 1–4).

## Run it

1. Install **Android Studio** (Ladybug 2024.2 or newer) with Android SDK 35.
2. *File → Open* → select this `Folio` folder. Let Gradle sync (first sync downloads ~500 MB).
3. Plug in a phone (USB debugging on) or start an emulator (API 26+), then press **Run ▶**.
4. If Studio offers to upgrade AGP/Kotlin/dependencies, accepting is safe.

Command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## What Phase 5 adds

Home now starts with **Sign · Scan · Merge · All tools**.

| Feature | How |
|---|---|
| **Handwritten signatures** | **Sign** on Home (or ✏️ → **Sign** in the editor). Create a signature once: **Draw** it with your finger, **Type** your name in a script style, or take a **Photo** of it on paper (Folio removes the paper and keeps the ink). Saved signatures stay on the phone. Tap one to place it, drag and resize it, then 💾 Save. It's written as a standard stamp annotation. |
| **Fill forms** | Tools → **Fill a form**, or tap *Fill it in* on the banner Folio shows when a PDF has a form. Every field appears as a simple input (text, check box, choice, drop-down), grouped by page. **Save**, **Save as a copy**, or **Save a final copy** (flattened: answers become part of the page and show in every app). |
| **Camera scanner** | **Scan**: Google's ML Kit scanner finds edges, crops, straightens and cleans each page (you can also import photos). Reorder pages, then save as a PDF. |
| **OCR (text recognition)** | Scans are made **searchable** by default: an invisible text layer is added, so Phase 2's search, selection and copy work on scans. For existing scanned PDFs: Tools → **Make PDF searchable**, or tap *Make searchable* on the banner. On-device, nothing uploaded; Latin-script languages (English, Swahili, French, Spanish…). |
| **Digital signatures** | ⋮ → **Sign with digital ID**. **Create** a personal ID (self-signed RSA-2048 certificate) or **import** a `.p12`/`.pfx` from your employer, a government or a certificate authority. Signs as PAdES (`adbe.pkcs7.detached`, SHA-256) using an incremental update, into a new copy. The password is never stored. |

**Signing tip:** sign by hand first (✏️ → Sign → 💾), then sign digitally last. Any change after a
digital signature shows it as modified.

**Limits:** Android's renderer doesn't draw form fields, so in Folio's reader your form answers
appear once you save a *final copy*. Other apps show them either way. XFA forms (an Adobe-only
format) can't be filled. The scanner needs Google Play services, which most Android phones,
Xiaomi included, have.

New dependencies: ML Kit Document Scanner 16.0.0, ML Kit Text Recognition 16.0.1 (bundled model,
works offline), BouncyCastle 1.78.1.
Code: `sign/`, `ocr/`, `pdf/Forms.kt`, `pdf/OcrPdf.kt`, `ui/sign/`, `ui/forms/`.

### Phase 5 test checklist
- [ ] Home → **Sign** → pick a PDF → the editor opens with *Your signatures* → **New signature** → Draw → Save → it's placed → drag/resize → 💾 Save → reopen: signature is there
- [ ] Type and Photo signatures (a photo of ink on white paper comes out with a transparent background)
- [ ] Open a PDF form (e.g. a government application) → banner *Fill it in* → type answers, tick boxes → **Save a final copy** → open: answers visible
- [ ] **Scan** 3 pages → Save (searchable on) → open → 🔍 search for a word on the page → it's found and highlighted
- [ ] Open an old scanned PDF → banner *Make searchable* → after saving, search and long-press selection work
- [ ] ⋮ → **Sign with digital ID** → Create one → sign → open the signed copy in Adobe Acrobat Reader: it shows a signature panel (self-signed IDs are shown as "validity unknown"; that's expected)
- [ ] Wrong digital ID password → a clear "Wrong password" message
- [ ] Import a `.p12` file (if you have one) → its name and issuer show

## What Phase 4 added

Open tools from **Home** (Merge · Compress · Pages · All tools) or from the reader's **⋮ menu**
(Organize pages · Compress · Split · PDF to images, acting on the open file).

| Tool | What it does |
|---|---|
| **Merge PDFs** | Pick two or more PDFs, put them in order with ↑↓, save as one file. |
| **Split PDF** | *Every page*, *every N pages*, or *custom ranges* (`1-3, 4-10, 11-`). You see how many files it will make before you start; they're saved into a folder you choose. |
| **Organize pages** | A page grid: tap pages to select them, then **rotate**, move **earlier/later**, **move to** a position, **extract** into a new PDF, or **delete**. Undo works. Save over the original or as a copy. Bookmarks, links, forms and metadata are kept. |
| **Compress PDF** | *Light / Balanced / Strong*. Only pictures are recompressed (text stays sharp), and an image is only replaced if that actually makes it smaller. Shows "4.2 MB → 1.1 MB (74% smaller)". |
| **Images to PDF** | Pick photos or screenshots (upright automatically), order them, choose A4 / Letter / fit-to-image and margins. |
| **PDF to images** | Every page as JPG or PNG at screen / standard / print quality, saved into a folder. |
| **Text to PDF** | A `.txt` file becomes a neatly wrapped A4 PDF (an embedded system font, so most languages work). |

Every tool writes a **new file** and leaves your original alone; Organize asks first if you
choose to overwrite. When a job finishes you get **Open** and **Share**. Files with only
printing/copying restrictions are unlocked automatically; files that need a password to open say
so (unlocking arrives in Phase 6).

Code: `pdf/PdfTools.kt` (engine), `pdf/PageRanges.kt` (range parser, tested), `ui/tools/`.

> Word, Excel and PowerPoint → PDF can't be done well on a phone. It comes with the Python
> Folio server in Phase 7 (LibreOffice in a container).

### Phase 4 test checklist
- [ ] Home → **Merge** → add 3 PDFs → reorder → Merge → Open: pages are in the order you set
- [ ] **Split** → Custom `1-2, 3-` → choose a folder → two PDFs appear there
- [ ] **Pages** (Organize) → select pages 2 and 3 → Rotate right → Earlier → Save as a copy → Open: correct order and rotation
- [ ] Organize → select a page → **Extract** → the new PDF has only that page
- [ ] Organize → try to delete every page → it refuses and explains why
- [ ] **Compress** a photo-heavy PDF (e.g. a scan) → see the size drop; text still crisp when zoomed
- [ ] **Images to PDF** with a portrait and a landscape photo → both upright, each on a page of the right orientation
- [ ] **PDF to images** at Standard → a JPG per page in the chosen folder
- [ ] **Text to PDF** with a long .txt file → wraps onto several pages
- [ ] Reader ⋮ → Organize pages with unsaved annotation edits → asks you to save first
- [ ] Cancel a running compress → stops; nothing half-written is left as the result

## What Phase 3 added

Tap the **✏️ pencil** in the reader to start editing. You edit one page at a time; the arrows move between pages.
**One finger** uses the tool; **two fingers** zoom and move the page.

| Tool | How |
|---|---|
| **Highlight / Underline / Strike** | Drag across text (it snaps to the words) or tap a single word. On scanned pages, drag a box. |
| **Pen** | Draw freehand. Pick a colour and thickness. |
| **Text** | Tap where the text should go, then type. Edit or resize it later with Select. |
| **Image** | Pick a photo or signature image; it's placed in the middle of the page. |
| **Box / Circle / Line / Arrow** | Drag from corner to corner (or start to end). |
| **Select** | Tap anything you added: drag to move, drag the round handle to resize, change colour, edit text, delete. |
| **Eraser** | Tap or rub over anything you added to remove it. |
| **Undo / Redo** | Top bar. A whole drag counts as one step (up to 100 steps). |
| **Save** | 💾 → **Save** writes into the original file. **Save as a copy…** makes a new PDF and opens it. If Folio isn't allowed to change the original (e.g. a file opened from an email), it asks where to save a copy. Leaving with unsaved changes asks first. |

**Standard PDF annotations.** Edits are saved as real PDF annotations (Highlight, Underline,
StrikeOut, Ink, Square, Circle, Line, FreeText, Stamp) with appearance streams, so Adobe Acrobat,
Chrome, Edge, Preview and others show them, and can edit them too. Folio reads these annotation
types back when you open a file (including ones made in other apps), so you can keep editing them.
Rotated pages are handled.

**Why Folio draws annotations itself:** Android's built-in page renderer ignores annotations, so
Folio draws its own layer on top. Annotation types Folio doesn't edit (links, sticky-note icons,
form fields, other apps' stamps) are kept in the file untouched, but aren't shown by Folio yet.
Form fields arrive in Phase 5.

Code: `edit/` (model, undo/redo), `pdf/AnnotationIO.kt` (PDF read/write), `ui/editor/` (editor UI).
The model and undo/redo logic are covered by a small test (all passing).

### Phase 3 test checklist
- [ ] ✏️ → Highlight: drag across a sentence → yellow highlight snapped to the text; tap a word → just that word
- [ ] Pen: draw; change colour and thickness; Undo removes the last stroke, Redo brings it back
- [ ] Text: tap the page, type two lines → appears; Select → Edit changes it; the handle resizes it
- [ ] Image: add a photo → appears centred; move and resize it with Select
- [ ] Box / Circle / Arrow draw where you drag; Eraser removes them
- [ ] Two-finger pinch zooms the page while editing; one finger keeps drawing
- [ ] Save → "Saved"; close and reopen the PDF → all edits are still there and can still be edited
- [ ] Open the saved file in another app (Drive / Chrome / Acrobat) → highlights, ink, shapes, text and images show
- [ ] Open a PDF that was annotated elsewhere → its highlights/ink/shapes appear in Folio and can be moved or deleted
- [ ] Open a PDF from an email or WhatsApp, edit, Save → Folio asks where to save a copy, then opens the copy
- [ ] Edit, then press Back without saving → "Save your changes?" dialog
- [ ] A rotated (landscape) page: add text → upright both in Folio and in other apps

## What Phase 2 added

| Feature | How to use it | Code |
|---|---|---|
| **Search** | 🔍 in the reader. Matches are highlighted, the counter (e.g. `3/27`) opens a list of results with snippets, and ⌃⌄ step through them. Works while the text is still being read. | `pdf/TextSearch.kt`, `ui/viewer/ViewerBars.kt` |
| **Text selection** | Long-press a word, keep holding and drag to extend. Then Copy, Search, Share or Select whole page. | `pdf/PageText.kt`, `ui/viewer/PageView.kt` |
| **Bookmarks** | Bookmark icon at the top saves the current page (with a text snippet). | `data/BookmarksRepository.kt` (Room v2 + migration) |
| **Contents / Bookmarks / Pages** | ☰ opens a sheet: the PDF's table of contents (your current chapter highlighted), your bookmarks, and a page-thumbnail grid. | `ui/viewer/ViewerSheets.kt` |
| **Zoom & navigation** | Pinch, double-tap, ± buttons, page slider, go-to-page, tap to go full-screen (system bars hide too). | `ui/viewer/Readers.kt` |
| **Reading modes** | ⋮ → Reading mode: *Continuous scroll* or *Page by page* (swipe; pinch/double-tap zoom per page), colours *Normal / Sepia / Night*, keep screen on. Saved for next time. | `ui/viewer/Readers.kt`, `PageView.kt` |
| **History & favourites** | Home shows a *Continue reading* card and a **History** link: everything you've opened, grouped Today / Yesterday / This week…, searchable. Favourite from the reader's ⋮ menu or the ☆ on any card. | `ui/history/`, `ui/home/` |

**How text works:** pages are still drawn by Android's fast built-in renderer. In the background,
**PdfBox-Android** reads each page's text and the exact position of every character, page by
page, so search highlights and selection line up with what you see. Scanned PDFs have no text
layer; the app says so, and OCR arrives in Phase 5.

> Plan change from Phase 1: we use PdfBox-Android (Apache-2.0) for text instead of PDFium, because
> Phases 3–6 (annotations, merge/split, forms, passwords) need PdfBox anyway. One library, not two.

### Phase 2 test checklist
- [ ] Search a word → highlights appear, `1/N` counter, arrows move between matches across pages
- [ ] Tap the counter → results list → tapping a result jumps there
- [ ] Long-press a word → it's selected; drag to extend → Copy → paste in another app
- [ ] Bookmark a page → it appears under ☰ → Bookmarks; tap to jump; delete works
- [ ] A PDF with a table of contents shows it under ☰ → Contents; chapters jump correctly
- [ ] Reading mode → Page by page → swipe pages, pinch-zoom one page, swipe resets zoom
- [ ] Night and Sepia recolour pages and thumbnails
- [ ] Tap the page → full-screen with a small page pill; tap again → controls return
- [ ] Home shows *Continue reading* after you've read past page 1; History groups by day
- [ ] A scanned PDF: search/selection explain there's no text (no crash)
- [ ] Upgrading from Phase 1 keeps your recents (database migration)

## What Phase 1 delivered

| Feature | Where |
|---|---|
| Android Studio project (Kotlin, Jetpack Compose, Material 3, version catalog) | `build.gradle.kts`, `gradle/libs.versions.toml` |
| App navigation (Home → Browse / Viewer / Settings, "Open with Folio" from other apps) | `navigation/AppNavHost.kt`, `MainActivity.kt` |
| Home screen: quick actions, recents with thumbnails, reading progress, favourites | `ui/home/` |
| Document browser: pick a folder once, list all PDFs in it (recursive), search, sort | `ui/browser/`, `data/DocumentRepository.kt` |
| PDF opening & rendering: continuous scroll, pinch / double-tap / button zoom, page slider, go-to-page, share, resume where you left off | `ui/viewer/`, `pdf/PdfFile.kt` |
| Recent documents (Room database) | `data/db/AppDatabase.kt`, `data/RecentsRepository.kt` |
| Dark, light & system themes plus wallpaper colours (DataStore) | `ui/theme/Theme.kt`, `ui/settings/` |

**Privacy by design:** no storage permissions. Folio uses the system file picker (Storage Access
Framework), so it only sees files and folders the user chooses. This also avoids Google Play's
restrictions on "All files access".

### Manual test checklist
- [ ] Open a PDF from Home → it renders, and the page bar shows `1 / N`
- [ ] Pinch, double-tap and the zoom buttons all work; panning sideways works while zoomed
- [ ] Leave on page 7, go back → Home shows "Page 7 of N" and reopening resumes there
- [ ] Browse → choose Downloads → PDFs listed; search and sort work
- [ ] Open a PDF from Gmail / Files with "Open with → Folio"
- [ ] Settings → Dark → the whole app switches immediately; it survives a restart
- [ ] A password-protected or corrupt PDF shows a friendly error, not a crash
- [ ] Rotate the phone in the viewer → no crash, same page

## Architecture

```
ui/ (Compose screens + ViewModels)  →  data/ (repositories)  →  Room · DataStore · SAF
                                     →  pdf/PdfFile (the PDF engine)
```

- **MVVM + unidirectional data flow:** every screen observes one `StateFlow<UiState>`.
- **`PdfFile` is the engine seam.** Today it wraps Android's built-in `PdfRenderer`. From Phase 2,
  features plug in behind it, so the screens barely change.
- **Manual DI** (`AppContainer`) keeps things readable. Move to Hilt if the graph grows.

## Roadmap and technical plan

> **Licence warning:** avoid MuPDF / PyMuPDF (AGPL) unless you will open-source the app or buy a
> commercial licence. The stack below is Apache/BSD/MIT or Google-provided.

| Phase | Features | How |
|---|---|---|
| **2 – Reader** ✅ | Search, text selection, bookmarks, contents, page grid, reading modes, history & favourites | Done: see *What Phase 2 adds* above. |
| **3 – Editing** ✅ | Highlight, underline, ink, shapes, text boxes, images, undo/redo, save | Compose `Canvas` overlay per page with a command-pattern undo stack. Write real PDF annotations with **PdfBox-Android** (`com.tom-roush:pdfbox-android`, Apache-2.0), then save a copy or overwrite through SAF. |
| **4 – Toolkit** ✅ | Merge, split, reorder, rotate, extract/delete pages, compress, images→PDF, PDF→images | PdfBox-Android for page operations. Compress by re-encoding embedded images. `android.graphics.pdf.PdfDocument` for images→PDF. Long jobs run under **WorkManager** with notifications. Office→PDF needs a server: a **Python FastAPI + LibreOffice** service. |
| **5 – Sign & scan** ✅ | Draw/type/photo signature, fill forms (AcroForm), digital signatures (PAdES), camera scanner, OCR | Signature pad → transparent PNG stamp. PdfBox AcroForm for forms. PdfBox + **BouncyCastle** for certificate signatures. **ML Kit Document Scanner** (edge detection, crop, filters). **ML Kit Text Recognition v2** (on-device) for OCR, written as an invisible text layer so scans become searchable. |
| **6 – Advanced** | Password protect/unlock, true redaction, batch jobs, compare two PDFs, metadata editor, PDF/A | PdfBox AES-256 encryption. Redaction rasterises affected pages so hidden text is really gone. A WorkManager batch queue. Comparison uses a per-page text diff plus an image diff overlay. Metadata via PDDocumentInformation/XMP. Full PDF/A validation is server-side (Python + veraPDF/Ghostscript). |
| **7 – AI & speed** | Summarise, ask questions, table extraction, translation, performance | A **Python FastAPI backend**: text → chunk → embed → LLM for summaries and Q&A with page citations; tables via `pdfplumber`/`camelot` → CSV/Excel. On-device translation with ML Kit. Performance work: bitmap pooling, tile cache, prefetching nearby pages, Baseline Profiles, LeakCanary, large-file tests (1,000+ pages). |

### Where your Python skills fit
The phone app is Kotlin for native speed, the camera and the system file picker. Kotlin
syntax is close to Python's (`val`/`var`, lambdas, data classes, no semicolons).
A small **FastAPI** service (Phases 4, 6 and 7) handles what phones do poorly: Office conversion,
PDF/A, table extraction and AI. The app calls it over HTTPS and keeps working offline without it.

## Next steps (Phase 6 kick-off: advanced)
1. **Passwords:** open password-protected PDFs (ask for the password), add or remove a password with AES-256, and set printing/copying permissions.
2. **Redaction:** mark areas or search results and *truly* remove them (rasterise those pages so hidden text is really gone).
3. **Batch processing:** run compress / OCR / images→PDF over many files in the background (WorkManager + notifications).
4. **Compare two PDFs:** a page-by-page text diff with highlighted changes.
5. **Metadata & PDF/A:** edit title/author/subject/keywords, and check PDF/A basics (full validation via the Phase 7 server).
6. **Signature check:** verify digital signatures in a PDF (intact? who signed? trusted?).
