# Performance & memory: what Folio does, and how to check it

## Built in
| Area | What's done | Where |
|---|---|---|
| Page rendering | Pages render off the main thread, one at a time (PdfRenderer can't do two). Each bitmap is capped at 12 MP (~48 MB) and scaled up on screen if needed | `pdf/PdfFile.kt` |
| Zoom | While you pinch, the existing image is stretched; a sharp image is rendered only once the pinch settles (220 ms) | `ui/viewer/Readers.kt` |
| Caches | Page bitmaps: LRU sized to 1/8 of the app's heap. Thumbnails and annotation images: small LRUs | `PdfFile`, `Thumbnails`, `AnnotationImages` |
| Memory pressure | On `onTrimMemory(RUNNING_LOW)` and above, every cache is emptied; pages re-render on demand | `FolioApp.onTrimMemory` |
| Prefetch | 300 ms after you stop on a page, the next two and the previous page are rendered in the background | `VerticalReader` |
| Big files | PdfBox reads through temp files (`MemoryUsageSetting.setupTempFileOnly`), so a 500 MB PDF doesn't need 500 MB of RAM. Text is read page by page; search works while it's still reading | `PdfTextExtractor`, `PdfTools` |
| Search | Text stays in compact arrays (one `FloatArray` of boxes per page), and search re-runs at most once a second while pages are still being read | `PageText`, `ViewerViewModel` |
| Lists | Lazy lists everywhere, with stable keys, so only visible items exist | all screens |
| Start-up | **Baseline Profile** (`app/src/main/baseline-prof.txt`, installed by `androidx.profileinstaller`): the start-up, Home and reader code is compiled ahead of time | |
| Release builds | R8 shrinking + resource shrinking | `app/build.gradle.kts` |
| Debug builds | **LeakCanary** (leaks show as a notification) and **StrictMode** (logs files left open and network on the main thread) | `FolioApp.enableStrictMode` |

## How to check on your phone
1. **Memory:** Android Studio → *Profiler* → Memory. Open a 300-page PDF, fling through it, zoom to 5×. The Java/Native heap should level off rather than keep climbing. Press *Force GC*; it should drop back.
2. **Leaks:** use a debug build normally for a while (open, edit, close documents). LeakCanary shows a notification if anything leaks.
3. **Jank:** *Settings → Developer options → Profile HWUI rendering → On screen as bars*, then scroll. Bars mostly under the green line means smooth.
4. **Start-up:** `adb shell am start -W -n com.folio.pdf/.MainActivity` and read *TotalTime* (cold start). Compare a release build with and without the baseline profile.

## Big-file test list
- [ ] 1,000-page text PDF: opens in < 2 s; page slider jumps instantly; search finds results while still "Reading text…"
- [ ] 300-page scanned PDF (images): scrolling stays smooth; memory levels off
- [ ] 50 MB+ PDF: merge, compress and OCR finish without "too large for memory"
- [ ] Rotate the phone repeatedly in the reader: no crash, same page, no growing memory

## Shipping
Build an **App Bundle** (*Build → Generate Signed App Bundle*) for the Play Store. Google then delivers only the
native code for each phone's processor (ML Kit and PDF engines include several), which roughly halves download size.
