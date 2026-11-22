# SPDX-License-Identifier: GPL-3.0-only
"""Lists setting explanations (texts ending in _sub) that may not fit on one line in a settings row, in any language.

    python tools/check_subtitles.py [limit-in-px]      (needs Pillow and fonts for each script; Windows has them)
"""
import importlib.util, os, sys
sys.stdout.reconfigure(encoding='utf-8')
from PIL import ImageFont

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('strings', os.path.join(here, 'strings.py'))
strings = importlib.util.module_from_spec(spec); spec.loader.exec_module(strings)

# A font per script, close to what the TV uses (Roboto / Noto); the first one that exists is used.
FONTS = {
    'default': ['C:/Windows/Fonts/arial.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', '/Library/Fonts/Arial.ttf'],
    'hi': ['C:/Windows/Fonts/Nirmala.ttc', 'C:/Windows/Fonts/NirmalaUI.ttf', '/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf'],
    'th': ['C:/Windows/Fonts/LeelawUI.ttf', 'C:/Windows/Fonts/tahoma.ttf', '/usr/share/fonts/truetype/noto/NotoSansThai-Regular.ttf'],
    'ko': ['C:/Windows/Fonts/malgun.ttf', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'],
    'zh-TW': ['C:/Windows/Fonts/msjh.ttc', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'],
}
_cache = {}
def font(lang):
    key = lang if lang in FONTS else 'default'
    if key not in _cache:
        path = next((f for f in FONTS[key] + FONTS['default'] if os.path.exists(f)), None)
        if path is None: sys.exit('no font found for ' + key)
        _cache[key] = ImageFont.truetype(path, 26)   # 13 sp at density 2
    return _cache[key]

LIMIT = float(sys.argv[1]) if len(sys.argv) > 1 else 700      # px available for a subtitle next to a value
# wel_sub and clean_caution_sub are free text that wraps, not a subtitle in a settings row.
# Also the texts shown as a row's subtitle that do not end in _sub.
KEYS = [k for k in strings.S if (k.endswith('_sub') or k in ('np_apps_access', 'cleanup_slow_advice', 'clean_slows'))
        and k not in ('wel_sub', 'clean_caution_sub')]
# Explanations at the top of a settings page: at most two lines across the page (about 1000 px at 14 sp, with some margin).
TWO_LINES = {'cleanup_hint': 1000}

def lines(text, f, width):
    """Lines the text takes when wrapped at spaces (scripts without spaces: per character)."""
    words = text.split(' ') if ' ' in text else list(text)
    sep = ' ' if ' ' in text else ''
    n, cur = 1, ''
    for w in words:
        trial = (cur + sep + w) if cur else w
        if f.getlength(trial) <= width: cur = trial
        else: n, cur = n + 1, w
    return n

for lang, texts, _ in strings.languages():
    for key in KEYS:
        w = font(lang).getlength(texts[key])
        if w > LIMIT: print(f'{int(w):4d}  {lang:5s} {key}: {texts[key]}')
    small = ImageFont.truetype(font(lang).path, 28)   # 14 sp at density 2
    for key, width in TWO_LINES.items():
        n = lines(texts[key], small, width)
        if n > 2: print(f'{n} lines {lang:5s} {key}: {texts[key]}')
