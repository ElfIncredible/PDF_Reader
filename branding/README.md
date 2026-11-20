# Vonu PDF brand

The app follows the Vonu identity ("Scute Geometry") from the shared Vonu brand folder,
`D:ds Max Work\Vonu\HD_Video_Converterrand` (`README.md`, `PHILOSOPHY.md`, `vonu-brand-book.pdf`).
The family rules that apply here: the product hue `#C2412D` with the **page** signal, Lagoon and Reef Night
themes, coral only as the mark's signal, and the family fonts. This folder holds only what is specific to Vonu PDF.

![UI direction](vonu-pdf-ui-direction.png)

## In the app

| Token | Light (Lagoon) | Dark (Reef Night) |
| --- | --- | --- |
| Ground | `#F3EEE4` | `#0D1B2A` |
| Text | `#14262B` | `#E4EEF0` |
| Primary (Vonu PDF hue) | `#C2412D` | `#FFB4A5` |
| Secondary (Lagoon ink) | `#0B4F5C` | `#7FD6CF` |

- The mark is the Vonu hexagon with a white **V** and the **page** signal, on the PDF red.
  Coral is never used in the UI.
- The wordmark is lowercase **vonu** in Outfit Bold (`res/font/outfit_bold.ttf`).
- Product UI text is **Archivo** (`res/font/archivo.ttf`, variable): ExtraBold headings, SemiBold labels,
  Regular body, tabular figures, the same scale as Vonu HD Uploader.
- Small labels ("VONU", section labels, "CONTINUE READING") are **Geist Mono** (`res/font/geist_mono.ttf`).
- All three are OFL; the licences ship in `app/src/main/assets/licenses/`.
- Navigation is three tabs: **Files**, **Tools**, **History**. Settings is in the Files brand bar.
- A cold launch shows the system splash (Android 12+) and then a short landing screen
  (`ui/components/VonuBrand.kt`). "Open with Vonu" skips it.

## Regenerate

`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_vonu_mark.xml` and
`vonu-pdf-ui-direction.png` are drawn from the shared generator, never by hand:

```
python -I branding/make_pdf_ui.py "D:/3ds Max Work/Vonu/HD_Video_Converter/brand"
```

Needs Python 3 with Pillow and the canvas-design fonts. The older blue SVG/PNG files here
(`vonu-*.svg`, `tile.py`) belong to the previous identity and are no longer used by the app.
