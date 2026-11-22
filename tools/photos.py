# SPDX-License-Identifier: GPL-3.0-only
"""MyTV's online photo set: photos/ in this repository, mirrored to photos/ in the public tinteractive/MyTV repository.

  python tools/photos.py add "File:Some photo.jpg" <id> "<Name>" [--y 0.5] [--x 0.5]
      Fetches a Wikimedia Commons photo with its author and licence, crops it to 16:9 around x/y (0..1, where the
      subject should stay), scales it to 1920x1080 and saves photos/<id>.webp; adds it to photos/photos.json.
  python tools/photos.py check
      Recomputes size and SHA-256 of every photo in photos.json, checks credits, writes a preview sheet with the
      home screen's controls drawn over each photo (dist/photos-check/) to check the subject is in the free area.
  python tools/photos.py sheet
      Writes dist/photos-sheet.html: every photo (in the APK and online) with its size, to choose which ship in the APK.
  python tools/photos.py bundle <id> [<id> …]
      These photos ship in the APK (assets/backgrounds, credits.json); all others move to the online set.
  python tools/photos.py publish
      Uploads changed files to tinteractive/MyTV (photos/…) with gh; photos.json last, so a TV never sees a photo first.

Only photos with an open licence (CC0, CC BY, CC BY-SA, public domain). Photos are never sent anywhere else.
"""
import base64, hashlib, io, json, os, subprocess, sys, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR = os.path.join(ROOT, 'photos')
LIST = os.path.join(DIR, 'photos.json')
REPO = os.environ.get('MTV_UPDATE_REPO', 'tinteractive/MyTV')
UA = 'MyTV-photo-tool/1.0 (https://github.com/tinteractive/MyTV)'
OPEN = ('CC0', 'CC BY', 'CC-BY', 'Public domain', 'PD')
GH = 'gh' if subprocess.run(['where', 'gh'], capture_output=True, shell=True).returncode == 0 else r'C:\Program Files\GitHub CLI\gh.exe'


def load():
    with open(LIST, encoding='utf-8') as f:
        return json.load(f)


def save(d):
    with open(LIST, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write('\n')


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=60).read()


def strip_html(s):
    import re
    return re.sub(r'<[^>]+>', '', s or '').strip()


def add(title, pid, name, x=0.5, y=0.5):
    from PIL import Image
    if not title.startswith('File:'):
        title = 'File:' + title
    q = urllib.parse.urlencode({'action': 'query', 'titles': title, 'prop': 'imageinfo', 'format': 'json',
                                'iiprop': 'url|size|extmetadata', 'iiurlwidth': 3840})
    page = next(iter(json.loads(get('https://commons.wikimedia.org/w/api.php?' + q))['query']['pages'].values()))
    ii = page['imageinfo'][0]
    meta = ii['extmetadata']
    lic = meta.get('LicenseShortName', {}).get('value', '')
    if not lic.startswith(OPEN):
        sys.exit(f'not an open licence: {lic!r}')
    author = strip_html(meta.get('Artist', {}).get('value'))
    w, h = ii['width'], ii['height']
    if w < 1920 or h < 1080 or w <= h:
        sys.exit(f'too small or not landscape: {w}x{h}')
    img = Image.open(io.BytesIO(get(ii.get('thumburl') or ii['url']))).convert('RGB')
    W, H = img.size
    cw, ch = (W, round(W * 9 / 16)) if W / H < 16 / 9 else (round(H * 16 / 9), H)
    left = min(max(round(x * W - cw / 2), 0), W - cw)
    top = min(max(round(y * H - ch / 2), 0), H - ch)
    img = img.crop((left, top, left + cw, top + ch)).resize((1920, 1080), Image.LANCZOS)
    img.save(os.path.join(DIR, pid + '.webp'), 'WEBP', quality=80, method=6)
    d = load()
    d['photos'] = [p for p in d['photos'] if p['id'] != pid]
    d['photos'].append({
        'id': pid, 'name': name, 'file': pid + '.webp', 'title': title[5:], 'author': author, 'license': lic,
        'licenseUrl': meta.get('LicenseUrl', {}).get('value', ''),
        'source': 'https://commons.wikimedia.org/wiki/' + urllib.parse.quote(title.replace(' ', '_')),
        'changes': 'cropped to 16:9, resized to 1920x1080, recompressed (WebP)',
    })
    save(d)
    print(f'added {pid}: {author} · {lic}')
    check()


def check():
    from PIL import Image, ImageDraw
    d = load()
    out = os.path.join(ROOT, 'dist', 'photos-check')
    os.makedirs(out, exist_ok=True)
    bad = []
    for p in d['photos']:
        f = os.path.join(DIR, p['file'])
        data = open(f, 'rb').read()
        p['bytes'] = len(data)
        p['sha256'] = hashlib.sha256(data).hexdigest()
        if not (p.get('author') and p.get('license') and p.get('source', '').startswith('https://')):
            bad.append(p['id'] + ': credit missing')
        if not p['license'].startswith(OPEN):
            bad.append(p['id'] + ': licence ' + p['license'])
        if len(data) > 1_500_000:
            bad.append(p['id'] + ': larger than 1.5 MB')
        # Preview: the home screen's fixed parts (clock + weather top left, round buttons top right, dock and the
        # Now playing card at the bottom) dimmed, so the subject can be checked to sit in the free area.
        im = Image.open(io.BytesIO(data)).convert('RGB')
        if im.size != (1920, 1080):
            bad.append(p['id'] + f': {im.size}')
        ov = Image.new('RGBA', im.size, (0, 0, 0, 0))
        g = ImageDraw.Draw(ov)
        for box in [(60, 40, 760, 330), (1480, 40, 1860, 150), (60, 780, 1860, 1050)]:
            g.rectangle(box, fill=(0, 0, 0, 150), outline=(255, 80, 80, 255), width=4)
        Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB').resize((960, 540)).save(os.path.join(out, p['id'] + '.jpg'), quality=85)
    d['format'] = 1
    import datetime
    d['updated'] = datetime.date.today().isoformat()
    d['photos'].sort(key=lambda p: p['id'])
    save(d)
    total = sum(p['bytes'] for p in d['photos'])
    print(f"{len(d['photos'])} photos, {total / 1e6:.1f} MB; previews in {out}")
    if bad:
        sys.exit('problems:\n  ' + '\n  '.join(bad))


ASSETS = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'backgrounds')
CREDITS = os.path.join(ASSETS, 'credits.json')


def bundled():
    with open(CREDITS, encoding='utf-8') as f:
        return json.load(f)


def bundle(ids):
    apk, web = bundled(), load()
    every = {p['id']: (p, 'apk') for p in apk} | {p['id']: (p, 'web') for p in web['photos']}
    unknown = [i for i in ids if i not in every]
    if unknown:
        sys.exit('unknown: ' + ', '.join(unknown))
    new_apk, new_web = [], []
    for pid, (p, where) in every.items():
        to = 'apk' if pid in ids else 'web'
        if to != where:
            src = os.path.join(ASSETS if where == 'apk' else DIR, p['file'])
            dst = os.path.join(ASSETS if to == 'apk' else DIR, p['file'])
            subprocess.run(['git', 'mv', src, dst], check=True, cwd=ROOT)
        q = {k: v for k, v in p.items() if k not in ('bytes', 'sha256')}
        (new_apk if to == 'apk' else new_web).append(q)
    # The APK's first photo is the one a new install starts with: keep the order given.
    new_apk.sort(key=lambda p: ids.index(p['id']))
    with open(CREDITS, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(new_apk, f, ensure_ascii=False, indent=2)
        f.write('\n')
    web['photos'] = new_web
    save(web)
    check()
    print('in the APK:', ', '.join(p['id'] for p in new_apk), f"({sum(os.path.getsize(os.path.join(ASSETS, p['file'])) for p in new_apk) / 1e6:.1f} MB)")


def sheet():
    import html
    from PIL import Image
    rows = [(p, True, ASSETS) for p in bundled()] + [(p, False, DIR) for p in load()['photos']]
    cards = []
    for p, inapk, folder in rows:
        f = os.path.join(folder, p['file'])
        im = Image.open(f).convert('RGB').resize((480, 270))
        b = io.BytesIO(); im.save(b, 'JPEG', quality=80)
        src = 'data:image/jpeg;base64,' + base64.b64encode(b.getvalue()).decode()
        e = html.escape
        cards.append(f'''<label class="card"><img src="{src}" alt="{e(p['name'])}">
<div class="meta"><input type="checkbox" value="{e(p['id'])}"{' checked' if inapk else ''}>
<b>{e(p['id'])}</b> <span>{os.path.getsize(f) // 1024} KB</span><br><small>{e(p['author'])} · {e(p['license'])}</small></div></label>''')
    page = f'''<!doctype html><meta charset="utf-8"><title>MyTV photos: choose the built-in ones</title>
<style>body{{font:14px system-ui;background:#111;color:#eee;margin:24px}}.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:16px}}
.card{{background:#222;border-radius:10px;overflow:hidden;cursor:pointer;border:3px solid transparent}}.card:has(input:checked){{border-color:#4caf50}}
img{{width:100%;display:block}}.meta{{padding:8px 10px}}small{{color:#aaa}}span{{color:#8bc34a}}
#out{{position:sticky;top:0;background:#111;padding:10px 0 14px;z-index:1}}code{{background:#333;padding:6px 10px;border-radius:6px;display:inline-block;user-select:all}}</style>
<div id="out">Checked = built into the APK (the first one checked is what a new install starts with). Everything else is online.<br><br>
<code id="cmd"></code> <span id="size"></span></div><div class="grid">{''.join(cards)}</div>
<script>const kb={{}};document.querySelectorAll('.card').forEach(c=>kb[c.querySelector('input').value]=parseInt(c.querySelector('span').textContent));
function upd(){{const ids=[...document.querySelectorAll('input:checked')].map(i=>i.value);
document.getElementById('cmd').textContent='python tools/photos.py bundle '+ids.join(' ');
document.getElementById('size').textContent=ids.length+' photos, '+(ids.reduce((a,i)=>a+kb[i],0)/1024).toFixed(1)+' MB in the APK';}}
document.addEventListener('change',upd);upd();</script>'''
    out = os.path.join(ROOT, 'dist', 'photos-sheet.html')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, 'w', encoding='utf-8').write(page)
    print(out)


def publish():
    check()
    d = load()
    remote = {}
    r = subprocess.run([GH, 'api', f'repos/{REPO}/contents/photos'], capture_output=True, text=True)
    if r.returncode == 0:
        remote = {e['name']: e['sha'] for e in json.loads(r.stdout)}

    def put(name, data, msg):
        blob = hashlib.sha1(b'blob %d\0' % len(data) + data).hexdigest()   # git's own hash: unchanged files are skipped
        if remote.get(name) == blob:
            return False
        body = {'message': msg, 'content': base64.b64encode(data).decode()}
        if name in remote:
            body['sha'] = remote[name]
        subprocess.run([GH, 'api', '-X', 'PUT', f'repos/{REPO}/contents/photos/{name}', '--input', '-'],
                       input=json.dumps(body), text=True, check=True, capture_output=True)
        print('uploaded', name)
        return True

    for p in d['photos']:
        put(p['file'], open(os.path.join(DIR, p['file']), 'rb').read(), f"Photo {p['id']}: {p['author']}, {p['license']}")
    put('photos.json', open(LIST, 'rb').read(), 'Photo list')
    listed = {p['file'] for p in d['photos']} | {'photos.json'}
    for name, sha in remote.items():
        if name not in listed:
            subprocess.run([GH, 'api', '-X', 'DELETE', f'repos/{REPO}/contents/photos/{name}', '-f', f'message=Remove {name}', '-f', f'sha={sha}'],
                           check=True, capture_output=True)
            print('removed', name)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    if a[0] == 'add':
        opts = dict(zip(a[4::2], a[5::2]))
        add(a[1], a[2], a[3], float(opts.get('--x', 0.5)), float(opts.get('--y', 0.5)))
    elif a[0] == 'bundle':
        bundle(a[1:])
    elif a[0] == 'sheet':
        sheet()
    elif a[0] == 'check':
        check()
    elif a[0] == 'publish':
        publish()
    else:
        sys.exit(__doc__)
