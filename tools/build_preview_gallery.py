"""Assemble the English gallery from real UiSmokeTest/WidgetRendererTest output.

Run the full Android host test suite first, then `python tools/build_preview_gallery.py`.
Requires Pillow only for document image/animation formatting, not for the Android app.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/build/screenshots"
OUTPUT = ROOT / "docs/orion-preview"
IMAGES = OUTPUT / "images"
IMAGES.mkdir(parents=True, exist_ok=True)
SCREENS = {
    "settings": "orion-settings-full", "appearance": "orion-page-appearance",
    "accounts": "orion-page-accounts", "notifications": "orion-page-notifications",
    "navigation": "orion-page-navigation", "widgets": "orion-page-widgets",
    "about": "orion-page-about", "settings-large": "iris-settings-large",
    "welcome": "accounts-signed-out", "login-codex": "connect-codex",
    "login-claude": "connect-claude", "usage": "home-three-accounts",
    "account-details": "claude-partial-usage", "statistics": "stats-month-dark",
    "resets": "resets-dark", "sources": "resets-sources-dark", "history": "history-dark",
    "sorting": "sort-mode", "widget-config": "orion-widget-config",
    "widget-layout": "orion-widget-layout", "widget-appearance": "orion-widget-appearance",
    "widget-vertical": "widget/vertical-detailed-2x4", "widget-rings": "widget/rings-preset-4x2",
    "widget-side": "widget/window-side-by-side", "widget-stacked": "widget/window-stacked",
}
for name in SCREENS.values():
    if not (SOURCE / f"{name}.png").is_file():
        raise FileNotFoundError(f"Run the full host tests first: {name}.png is missing")
for target, name in SCREENS.items():
    shutil.copyfile(SOURCE / f"{name}.png", IMAGES / f"{target}.png")
for name, prefix, width in (("settings-entry", "settings", 440), ("submenu", "subpage", 440), ("submenu-close", "return", 440),
                            ("settings-close", "settings-close", 440),
                            ("search", "search", 440), ("percentage", "counter", 288)):
    files = sorted(SOURCE.glob(f"motion-{prefix}-[0-9][0-9].png"))
    if not files:
        raise FileNotFoundError(f"Missing recorded {prefix} frames")
    frames = []
    for path in files:
        with Image.open(path) as raw:
            rgb = raw.convert("RGB")
            rgb = rgb.resize((width, round(rgb.height * width / rgb.width)), Image.Resampling.LANCZOS)
            frames.append(rgb.quantize(colors=128, dither=Image.Dither.NONE))
    durations = [40] * len(frames)
    durations[-1] = 700
    frames[0].save(IMAGES / f"{name}.gif", save_all=True, append_images=frames[1:],
                   duration=durations, loop=0, disposal=2, optimize=False)

# A document contact sheet. UI pixels are only scaled; captions sit outside the screenshots.
items = [(name.replace("-", " ").title(), name) for name in SCREENS if name != "settings-large"]
w, h = 236, 548
sheet = Image.new("RGB", (w * 4, h * ((len(items) + 3) // 4)), (242, 244, 250))
draw = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
except OSError:
    font = ImageFont.load_default()
for index, (title, name) in enumerate(items):
    with Image.open(IMAGES / f"{name}.png") as raw:
        image = raw.convert("RGB")
        image.thumbnail((w - 20, h - 44), Image.Resampling.LANCZOS)
        sheet.paste(image, ((index % 4) * w + (w - image.width) // 2, (index // 4) * h + 34))
    draw.text(((index % 4) * w + 10, (index // 4) * h + 10), title, fill=(29, 32, 41), font=font)
sheet.save(IMAGES / "overview.jpg", quality=90)

reports = list((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
suites = [ET.parse(path).getroot() for path in reports]
# Preserve a previous full-suite record when only the screenshot subset was rerun.
if len(suites) > 1:
    result = {field: sum(int(s.attrib[field]) for s in suites)
              for field in ("tests", "failures", "errors", "skipped")}
    result["suites"] = [{key: s.attrib[key] for key in ("name", "tests", "failures", "errors", "skipped")}
                        for s in suites]
    (OUTPUT / "tests.json").write_text(json.dumps(result, indent=2) + "\n")
print(f"Assembled {len(SCREENS)} screenshots, six GIFs and an overview sheet.")
