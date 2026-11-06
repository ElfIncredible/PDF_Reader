# Folio PDF — Android PDF reader & editor

A simple, all-round PDF app: read, annotate, sign, merge, scan and more.
Current milestone: **Phase 2: PDF reader** (built on Phase 1: Foundation and UI).

## Run it

1. Install **Android Studio** (Ladybug 2024.2 or newer) with Android SDK 35.
2. *File → Open* → select this `Folio` folder. Let Gradle sync (first sync downloads ~500 MB).
3. Plug in a phone (USB debugging on) or start an emulator (API 26+), then press **Run ▶**.
4. If Studio offers to upgrade AGP/Kotlin/dependencies, accepting is safe.

Command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## What Phase 2 adds

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
| **3 – Editing** | Highlight, underline, ink, shapes, text boxes, images, undo/redo, save | Compose `Canvas` overlay per page with a command-pattern undo stack. Write real PDF annotations with **PdfBox-Android** (`com.tom-roush:pdfbox-android`, Apache-2.0), then save a copy or overwrite through SAF. |
| **4 – Toolkit** | Merge, split, reorder, rotate, extract/delete pages, compress, images→PDF, PDF→images | PdfBox-Android for page operations. Compress by re-encoding embedded images. `android.graphics.pdf.PdfDocument` for images→PDF. Long jobs run under **WorkManager** with notifications. Office→PDF needs a server: a **Python FastAPI + LibreOffice** service. |
| **5 – Sign & scan** | Draw/type/photo signature, fill forms (AcroForm), digital signatures (PAdES), camera scanner, OCR | Signature pad → transparent PNG stamp. PdfBox AcroForm for forms. PdfBox + **BouncyCastle** for certificate signatures. **ML Kit Document Scanner** (edge detection, crop, filters). **ML Kit Text Recognition v2** (on-device) for OCR, written as an invisible text layer so scans become searchable. |
| **6 – Advanced** | Password protect/unlock, true redaction, batch jobs, compare two PDFs, metadata editor, PDF/A | PdfBox AES-256 encryption. Redaction rasterises affected pages so hidden text is really gone. A WorkManager batch queue. Comparison uses a per-page text diff plus an image diff overlay. Metadata via PDDocumentInformation/XMP. Full PDF/A validation is server-side (Python + veraPDF/Ghostscript). |
| **7 – AI & speed** | Summarise, ask questions, table extraction, translation, performance | A **Python FastAPI backend**: text → chunk → embed → LLM for summaries and Q&A with page citations; tables via `pdfplumber`/`camelot` → CSV/Excel. On-device translation with ML Kit. Performance work: bitmap pooling, tile cache, prefetching nearby pages, Baseline Profiles, LeakCanary, large-file tests (1,000+ pages). |

### Where your Python skills fit
The phone app is Kotlin for native speed, the camera and the system file picker. Kotlin
syntax is close to Python's (`val`/`var`, lambdas, data classes, no semicolons).
A small **FastAPI** service (Phases 4, 6 and 7) handles what phones do poorly: Office conversion,
PDF/A, table extraction and AI. The app calls it over HTTPS and keeps working offline without it.

## Next steps (Phase 3 kick-off: editing)
1. An annotation model (highlight, underline, strike, ink, shape, text box, image) with a command-based undo/redo stack.
2. An editing toolbar plus a Compose drawing overlay on each page, reusing Phase 2's character boxes for text-snapped highlights.
3. Save: write real PDF annotations with PdfBox, as "Save" (overwrite via SAF) or "Save as copy".
