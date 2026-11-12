"""Publish unchanged native emulator PNGs and an English comparison index."""
from pathlib import Path
import json
import hashlib
import shutil
import sys
from PIL import Image, ImageDraw, ImageFont

SOURCE = Path(sys.argv[1])
SOURCE_COMMIT = sys.argv[2]
RUN_ID = sys.argv[3]
OUTPUT = Path(__file__).resolve().parents[1] / 'docs/pixel9-audit'
PAGES = {
    'usage': 'Usage overview', 'account': 'Account details', 'settings': 'Settings hub',
    'settings-bottom': 'Settings hub · lower categories', 'accounts': 'Accounts',
    'accounts-bottom': 'Accounts · provider actions', 'appearance': 'Appearance',
    'notifications': 'Notifications and background refresh', 'notifications-bottom': 'Notifications · lower rules',
    'navigation': 'Navigation preferences', 'navigation-bottom': 'Navigation · default statistics',
    'widgets': 'Installed widgets', 'about': 'About and source attribution',
    'forecast-codex': 'Codex forecast', 'forecast-codex-probabilities': 'Codex forecast · probability details',
    'sources-codex': 'Codex sources', 'history-codex': 'Codex reset history',
    'forecast-claude': 'Claude forecast', 'sources-claude': 'Claude sources',
    'statistics': 'Statistics', 'sorting': 'Account ordering', 'welcome': 'Welcome',
    'login-codex': 'Codex sign-in', 'login-claude': 'Claude sign-in',
    'widget-config': 'Widget configuration', 'widget-layout': 'Widget layout controls',
    'widget-appearance': 'Widget appearance controls',
}
metadata = json.loads((SOURCE / 'device.json').read_text())
assert metadata['ro.kernel.qemu'] == '1' and metadata['hardware_acceleration_required']
OUTPUT.mkdir(parents=True, exist_ok=True)
for phase in ['before', 'after']:
    for theme in ['dark', 'light']:
        dest = OUTPUT / 'images' / phase / theme
        dest.mkdir(parents=True, exist_ok=True)
        for name in PAGES:
            path = SOURCE / phase / theme / f'{name}.png'
            with Image.open(path) as im:
                assert im.size == (1080, 2424), path
                im.verify()
            shutil.copyfile(path, dest / path.name)
for theme in ['dark', 'light']:
    dest = OUTPUT / 'images/orion' / theme
    dest.mkdir(parents=True, exist_ok=True)
    for name in ['home', 'settings', 'appearance', 'navigation']:
        path = SOURCE / 'orion' / theme / f'{name}.png'
        if not path.exists():
            path = SOURCE / 'orion/orion' / theme / f'{name}.png'
        with Image.open(path) as im:
            assert im.size == (1080, 2424)
            im.verify()
        shutil.copyfile(path, dest / path.name)
shutil.copyfile(SOURCE / 'device.json', OUTPUT / 'device.json')
provenance = dict(source_commit=SOURCE_COMMIT,
    workflow_run=f'https://github.com/madebycli/AiUsage-Android/actions/runs/{RUN_ID}',
    reference_apk_sha256='adcce30c43d033a183a4b5f3079becc5c8ecea7277819dc95dc5135a18e8d5df',
    png_sha256={str(p.relative_to(OUTPUT)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted((OUTPUT / 'images').rglob('*.png'))})
(OUTPUT / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')

try:
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 13)
except OSError:
    font = ImageFont.load_default()
for theme in ['dark', 'light']:
    w, h = 236, 564
    sheet = Image.new('RGB', (w * 3, h * 9), '#edf0f5')
    draw = ImageDraw.Draw(sheet)
    for n, (name, label) in enumerate(PAGES.items()):
        with Image.open(OUTPUT / f'images/after/{theme}/{name}.png') as original:
            im = original.convert('RGB')
            im.thumbnail((w - 16, h - 42), Image.Resampling.LANCZOS)
            x, y = n % 3 * w, n // 3 * h
            sheet.paste(im, (x + (w - im.width) // 2, y + 34))
            draw.text((x + 8, y + 10), label[:31], font=font, fill='#1d2029')
    sheet.save(OUTPUT / f'images/{theme}-overview.jpg', quality=91)

intro = '''# Pixel 9: native Android visual audit

The comparison runs the unchanged **0.9.4** source (`6b04ff2`) and corrected **0.9.5** on the same hardware-accelerated **Google Pixel 9 AVD**, **1080 × 2424 px, 420 dpi**, Android 15 / API 35, full Google APIs system image. The complete device metadata is in [device.json](device.json).

These are full-resolution `UiAutomation.takeScreenshot` captures of the production Compose screens running in Android. PNGs are copied unchanged. Only the two overview sheets scale the photos and add English captions outside them. Account names, usage measurements and reset forecasts are fixtures; these photos do not claim live authentication or current predictions. UI labels remain German to exercise long wording.

[Detailed review and differences](SELF_REVIEW.md) · [Dark overview](images/dark-overview.jpg) · [Light overview](images/light-overview.jpg) · [Native capture workflow](../../.github/workflows/pixel9-audit.yml)

Dark mode was reviewed first. Click a photo for its original 1080 × 2424 resolution.

'''
blocks = [intro]
blocks.append(f'Native capture: [successful workflow run]({provenance["workflow_run"]}) · [audited source](https://github.com/madebycli/AiUsage-Android/tree/{SOURCE_COMMIT}) · [PNG hashes and provenance](provenance.json). All four native instrumentation invocations passed (two app tests per build; one original Orion test per theme).\n\n')
blocks.append('## Corrected build: dark and light side by side\n\n')
for page in ['settings', 'usage', 'forecast-codex', 'forecast-claude']:
    blocks.append(f'### {PAGES[page]} · both themes\n\n| Dark | Light |\n|---|---|\n'
        f'| ![{PAGES[page]} in dark mode](images/after/dark/{page}.png) | ![{PAGES[page]} in light mode](images/after/light/{page}.png) |\n\n')
blocks.append('## Before and after: dark mode\n\n')
for page in ['settings', 'appearance', 'notifications', 'navigation', 'accounts', 'widgets', 'about', 'usage', 'forecast-codex', 'forecast-codex-probabilities', 'forecast-claude']:
    blocks.append(f'### {PAGES[page]}\n\n| 0.9.4 · before | 0.9.5 · corrected |\n|---|---|\n'
        f'| ![Before: {PAGES[page]}](images/before/dark/{page}.png) | ![Corrected: {PAGES[page]}](images/after/dark/{page}.png) |\n\n')
blocks.append('## The original Orion app on the same device\n\nThe official **Orion Store 2.7.5** APK is unchanged and its published SHA256 is checked before installation. Only local theme and onboarding preferences are configured on this disposable emulator. Its store features and bottom navigation are not imported into AI Usage.\n\n')
for page in ['settings', 'appearance', 'navigation']:
    blocks.append(f'| Original Orion · dark · {page} | AI Usage · dark · {page} |\n|---|---|\n'
        f'| ![Original Orion](images/orion/dark/{page}.png) | ![AI Usage](images/after/dark/{page}.png) |\n\n')
blocks.append('## Every page in both themes\n\nAll 108 before/after app photos are indexed below, including the lower portions of long screens. The original Orion light references are [Settings](images/orion/light/settings.png), [Appearance](images/orion/light/appearance.png) and [Interface](images/orion/light/navigation.png).\n\n| Page | Before dark | Corrected dark | Before light | Corrected light |\n|---|---|---|---|---|\n')
for name, label in PAGES.items():
    links = ' | '.join(f'[Photo](images/{phase}/{theme}/{name}.png)' for phase, theme in
        [('before', 'dark'), ('after', 'dark'), ('before', 'light'), ('after', 'light')])
    blocks.append(f'| {label} | {links} |\n')
blocks.append('''
## Scope and limits

The bottom-navigation implementation remains unchanged. The local software emulator attempt booted Android but could not produce a useful app frame; it is not counted as a successful visual pass. This gallery comes from the separate KVM-backed Android run, not Robolectric. No physical Pixel 9, Android 17 validation, live sign-in or launcher widget-placement validation is implied.
''')
(OUTPUT / 'README.md').write_text(''.join(blocks))
print('Published 108 native app photos, 8 original Orion photos, two overview sheets and an English index.')
