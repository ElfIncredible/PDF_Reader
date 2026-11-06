# Folio PDF — Android PDF reader & editor

A simple, all-round PDF app: read, annotate, sign, merge, scan and more.
This repository is **Phase 1: Foundation and UI**.

## Run it

1. Install **Android Studio** (Ladybug 2024.2 or newer) with Android SDK 35.
2. *File → Open* → select this `Folio` folder. Let Gradle sync (first sync downloads ~500 MB).
3. Plug in a phone (USB debugging on) or start an emulator (API 26+), then press **Run ▶**.
4. If Studio offers to upgrade AGP/Kotlin/dependencies, accepting is safe.

Command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## What Phase 1 delivers

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
| **2 – Reader** | Search, text selection, bookmarks, history & favourites, reading modes (night / sepia / single page / horizontal), outline | Swap `PdfFile` internals to **PDFium** (`io.legere:pdfiumandroid`, Apache-2.0) for text, search and outline. Bookmarks go in a new Room table. Night mode uses a colour-matrix filter on the page image. Tile rendering for sharp deep zoom. |
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

## Next steps (Phase 2 kick-off)
1. Add PDFium and move `PdfFile` onto it (same public API).
2. Add a `Bookmark` entity and the bookmarks/outline sheet in the viewer.
3. Search bar in the viewer with result highlighting.
4. Reading-mode menu (night, sepia, horizontal paging).
