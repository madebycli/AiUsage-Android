"""Real hardware-accelerated Android screenshots, never host-rendered substitutes."""
from pathlib import Path
import json
import hashlib
import struct
import subprocess
import urllib.request

OUTPUT = Path('pixel9-artifacts')
OUTPUT.mkdir(exist_ok=True)


def adb(*args, check=True):
    return subprocess.run(['adb', *args], check=check, capture_output=True, text=True).stdout


assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Requires an emulator, never a real account/device'
adb('shell', 'wm', 'size', '1080x2424')
adb('shell', 'wm', 'density', '420')
device = {name: adb('shell', 'getprop', name).strip() for name in
          ['ro.build.version.sdk', 'ro.build.version.release', 'ro.product.model', 'ro.kernel.qemu']}
device.update(size=adb('shell', 'wm', 'size').strip(), density=adb('shell', 'wm', 'density').strip(),
              avd_profile='Google Pixel 9', hardware_acceleration_required=True,
              baseline_commit='6b04ff2c91ca0ae97d3a814cac9b4f190c03d749',
              fixture_data=True, capture='UiAutomation.takeScreenshot on a real Pixel 9 AVD')
(OUTPUT / 'device.json').write_text(json.dumps(device, indent=2) + '\n')

expected = set('usage account settings accounts appearance notifications navigation widgets about '
               'notifications-bottom navigation-bottom accounts-bottom settings-bottom forecast-codex '
               'forecast-codex-probabilities sources-codex history-codex forecast-claude sources-claude '
               'statistics sorting welcome login-codex login-claude widget-config widget-layout widget-appearance'.split())
test_apk = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
for phase, apk in [('before', 'baseline/app/build/outputs/apk/debug/app-debug.apk'),
                   ('after', 'app/build/outputs/apk/debug/app-debug.apk')]:
    print('Running Pixel 9 visual audit:', phase, flush=True)
    adb('install', '-r', apk)
    adb('install', '-r', test_apk)
    adb('logcat', '-c')
    result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-e', 'class',
        'com.madebycli.aiusage.Pixel9VisualAuditTest', '-e', 'auditPhase', phase,
        'com.madebycli.aiusage.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True)
    log = result.stdout + result.stderr
    (OUTPUT / f'{phase}-instrumentation.txt').write_text(log)
    (OUTPUT / f'{phase}-logcat.txt').write_text(adb('logcat', '-d'))
    adb('pull', f'/sdcard/Android/data/com.madebycli.aiusage/files/visual-audit/{phase}', str(OUTPUT / phase), check=False)
    print(log, flush=True)
    assert 'OK (2 tests)' in log and 'FAILURES!!!' not in log, f'{phase} instrumentation failed; inspect uploaded logs'
    for theme in ['dark', 'light']:
        for page in expected:
            path = OUTPUT / phase / theme / f'{page}.png'
            assert path.is_file(), f'Missing native screenshot: {path}'
            raw = path.read_bytes()
            assert raw[:8] == b'\x89PNG\r\n\x1a\n'
            assert struct.unpack('>II', raw[16:24]) == (1080, 2424), path
    print(phase, ': all', len(expected) * 2, 'full-resolution native screenshots verified', flush=True)

# The reference APK is unmodified. Only its local theme/onboarding preferences are configured on this disposable emulator.
reference = OUTPUT / 'orion-official-2.7.5.apk'
urllib.request.urlretrieve('https://github.com/RookieEnough/Orion-Store/releases/download/2.7.5/app-release.apk', reference)
assert hashlib.sha256(reference.read_bytes()).hexdigest() == 'adcce30c43d033a183a4b5f3079becc5c8ecea7277819dc95dc5135a18e8d5df'
adb('install', '-r', str(reference))
adb('root')
adb('wait-for-device')
assert adb('shell', 'id', '-u').strip() == '0', 'AOSP test emulator root is needed to configure reference-only preferences'
for theme in ['dark', 'light']:
    adb('shell', 'am', 'force-stop', 'com.orion.store')
    settings = OUTPUT / f'orion-{theme}-settings.json'
    settings.write_text(json.dumps(dict(theme=theme, seenNativeWelcome=True, webMigrated=True, immersive=False,
        materialYouEnabled=False, hasSeenModernUITutorial=True, seenFlags=['guide=yes', 'tour=yes', 'verifier=yes'])))
    root = '/data/user/0/com.orion.store'
    uid = adb('shell', 'stat', '-c', '%u', root).strip()
    assert uid.isdigit()
    adb('shell', 'mkdir', '-p', f'{root}/files')
    adb('push', str(settings), f'{root}/files/settings.json')
    adb('shell', 'chown', '-R', f'{uid}:{uid}', f'{root}/files')
    adb('shell', 'restorecon', '-R', f'{root}/files')
    result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-e', 'class',
        'com.madebycli.aiusage.OrionReferenceTest', '-e', 'referenceTheme', theme,
        'com.madebycli.aiusage.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True)
    log = result.stdout + result.stderr
    (OUTPUT / f'orion-{theme}-instrumentation.txt').write_text(log)
    adb('pull', '/sdcard/Android/data/com.madebycli.aiusage/files/visual-audit/orion', str(OUTPUT / 'orion'), check=False)
    print(log, flush=True)
    assert 'OK (1 test)' in log, 'Original Orion reference capture failed; inspect uploaded logs'
reference.unlink()
