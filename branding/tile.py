# Generates the Vonu marks. Geometry lives in a 108-unit square (Android adaptive icon grid),
# content inside the 66-unit safe circle so every launcher mask keeps it whole.
INDIGO_TOP, INDIGO_BOT, AMBER, FOLD, LINE = "#4338CA", "#1E1B4B", "#FFB627", "#C9C6F2", "#4338CA"

GLOW = f'''<circle cx="66" cy="38" r="17" fill="{AMBER}" opacity="0.22"/>
<circle cx="66" cy="38" r="11" fill="{AMBER}"/>'''
PAGE = f'''<path d="M36 32 a4 4 0 0 1 4-4 h18 l12 12 v36 a4 4 0 0 1 -4 4 h-26 a4 4 0 0 1 -4-4 z" fill="#FFFFFF"/>
<path d="M58 28 v8 a4 4 0 0 0 4 4 h8 z" fill="{FOLD}"/>
<g stroke="{LINE}" stroke-width="3.4" stroke-linecap="round">
<path d="M43 52 h20"/><path d="M43 60 h20"/><path d="M43 68 h12"/></g>'''

def symbol(kind):
    if kind == "read": return GLOW + PAGE
    glow = f'<circle cx="70" cy="34" r="16" fill="{AMBER}" opacity="0.22"/><circle cx="70" cy="34" r="10" fill="{AMBER}"/>'
    if kind == "write":   # pen
        return glow + '<g fill="#FFFFFF"><path d="M38 70 l26-26 l8 8 l-26 26 l-11 3 z"/></g><path d="M38 70 l8 8 l-11 3 z" fill="#C9C6F2"/>'
    if kind == "post":    # upload arrow into a tray
        return glow + '<g stroke="#FFFFFF" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" fill="none"><path d="M54 70 V42"/><path d="M42 53 L54 41 L66 53"/><path d="M36 64 v8 a4 4 0 0 0 4 4 h28 a4 4 0 0 0 4-4 v-8"/></g>'
    if kind == "themes":  # fanned colour swatches
        card = '<rect x="47" y="38" width="14" height="38" rx="5" fill="{c}" transform="rotate({a} 54 74)"/>'
        return glow + card.format(c="#8B84E8", a=-28) + card.format(c="#C9C6F2", a=0) + card.format(c="#FFFFFF", a=28) + '<circle cx="54" cy="70" r="2.6" fill="#4338CA"/>'

def tile(kind="read", size=512, rounded=True):
    r = 24 if rounded else 0
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">
<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{INDIGO_TOP}"/><stop offset="1" stop-color="{INDIGO_BOT}"/></linearGradient></defs>
<rect width="108" height="108" rx="{r}" fill="url(#bg)"/>
{symbol(kind)}
</svg>'''

# Wordmark: geometric strokes (no font needed); the amber dot is the "o", the light.
def wordmark(color="#1E1B4B", size_h=160, with_tile=True):
    letters = f'''<g fill="none" stroke="{color}" stroke-width="26" stroke-linecap="round" stroke-linejoin="round">
<path d="M20 62 L54 130 L88 62"/>
<path d="M222 62 V130 M222 97 a33 33 0 0 1 66 0 V130"/>
<path d="M340 62 V97 a33 33 0 0 0 66 0 V62 M406 62 V130"/></g>
<circle cx="155" cy="96" r="48" fill="{AMBER}" opacity="0.25"/>
<circle cx="155" cy="96" r="34" fill="{AMBER}"/>'''
    if not with_tile:
        return f'<svg xmlns="http://www.w3.org/2000/svg" height="{size_h}" viewBox="0 0 440 150">{letters}</svg>'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" height="{size_h}" viewBox="0 0 640 150">
<g transform="scale(1.389)">{tile("read",108).split(">",1)[1].rsplit("</svg>",1)[0]}</g>
<g transform="translate(190,0)">{letters}</g></svg>'''

if __name__ == "__main__":
    for k in ["read","write","post","themes"]:
        open(f"vonu-{k}.svg","w").write(tile(k))
    open("vonu-wordmark.svg","w").write(wordmark(with_tile=False))
    open("vonu-wordmark-white.svg","w").write(wordmark("#FFFFFF", with_tile=False))
    open("vonu-logo.svg","w").write(wordmark())
