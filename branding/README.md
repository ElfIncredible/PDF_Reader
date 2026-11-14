# Vonu brand

## Colours

| Name | Hex | Use |
| --- | --- | --- |
| Vonu Blue | `#1D4ED8` | Tiles, buttons, links, the app's main colour |
| Bright Blue | `#2563EB` | Tiles on dark backgrounds |
| Navy | `#0B1F44` | Wordmark and headings |
| Amber | `#FFB627` | The Vonu dot. Use it only for the dot, never for large areas |
| Sky | `#BFDBFE` | Small details inside icons (folded corners, swatches) |
| Paper | `#F6F8FC` | Light backgrounds |

## The logo

The Vonu symbol is a white **V holding an amber dot** on a blue tile. The wordmark is drawn as shapes, not typed, so it looks the same everywhere.

| File | Use |
| --- | --- |
| `vonu-logo.svg` / `vonu-logo.png` | The main logo on light backgrounds |
| `vonu-logo-white.svg` | On dark or photo backgrounds |
| `vonu-symbol.svg` / `vonu-symbol-1024.png` | The tile alone: the Vonu PDF app icon, profile pictures, favicons |
| `vonu-pdf-playstore-512.png` | The symbol as a full-bleed 512 px square for the Google Play listing |
| `vonu-brand-sheet.png` | Overview of the logo and product icons |

Rules: leave clear space around the logo of at least half the tile's width; don't recolour, stretch, rotate or add shadows; below 24 px, use the symbol alone.

## Product icons

The Vonu PDF Android app uses the Vonu symbol (the V) as its icon. The adaptive icon lives in
`app/src/main/res/drawable/ic_launcher_*.xml`, the in-app logo in `drawable/vonu_logo.xml`, and the
Android 7.x PNGs in `mipmap-*/` are made from `vonu-symbol-1024.png`.

The other product icons are the same blue tile with the amber dot at the top right. Only the white symbol changes.

| Product | File |
| --- | --- |
| Vonu PDF (page icon, no longer the app icon) | `vonu-pdf.svg` |
| Vonu Write | `vonu-write.svg` |
| Vonu Post | `vonu-post.svg` |
| Vonu Themes | `vonu-themes.svg` |

