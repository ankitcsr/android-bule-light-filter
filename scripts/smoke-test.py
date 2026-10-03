#!/usr/bin/env python3
"""Functional checks on a disposable emulator; resets Amber's app data only."""
import argparse
import io
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--apk', default='app/build/sdk/amber-debug.apk')
args = parser.parse_args()
PACKAGE = 'com.anknonimp.amber'
ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / 'app/build/smoke'
OUTPUT.mkdir(parents=True, exist_ok=True)


def adb(*command, timeout=45, binary=False, check=True):
    result = subprocess.run(['adb', '-s', args.serial, *command],
                            capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'ADB failed ({result.returncode}): {command}\n'
                           + result.stdout.decode(errors='replace')
                           + result.stderr.decode(errors='replace'))
    return result.stdout if binary else result.stdout.decode(errors='replace')


def wait_for(predicate, message, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except (subprocess.SubprocessError, RuntimeError, ET.ParseError):
            pass
        time.sleep(1)
    raise AssertionError(message)


def ui():
    # Use shell-owned internal storage; emulator external-storage/FUSE startup
    # is independent of app readiness and can lag behind the UI.
    output = adb('shell', 'uiautomator', 'dump', '/data/local/tmp/amber-ui.xml')
    assert 'dumped to' in output, f'UI dump did not finish: {output}'
    return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/amber-ui.xml'))


def node_with(resource=None, text=None):
    for node in ui().iter('node'):
        if resource is not None and node.get('resource-id') == resource:
            return node
        if text is not None and node.get('text', '').casefold() == text.casefold():
            return node
    return None


def tap(resource=None, text=None):
    node = node_with(resource=resource, text=text)
    assert node is not None, f'Missing control: {resource or text}'
    bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]
    adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2),
        str((bounds[1] + bounds[3]) // 2))


def running():
    services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
    return 'FilterService' in services and 'isForeground=true' in services


def foreground(package):
    activities = adb('shell', 'dumpsys', 'activity', 'activities')
    return any(package + '/' in line and ('mResumedActivity' in line or 'topResumedActivity' in line)
               for line in activities.splitlines())


def screenshot(name):
    data = adb('exec-out', 'screencap', '-p', binary=True)
    (OUTPUT / name).write_bytes(data)
    return Image.open(io.BytesIO(data)).convert('RGB')


def app():
    result = adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    assert 'Error:' not in result, result
    time.sleep(1)
    # Android preserves the activity's scroll position when returning from another
    # app. Bring the status/toggle back into view before checking those controls.
    sizes = re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)', adb('shell', 'wm', 'size'))
    width, height = map(int, sizes[-1])
    adb('shell', 'input', 'swipe', str(width // 2), str(height // 5),
        str(width // 2), str(height * 4 // 5), '350')


def saved_settings():
    return adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/filter.xml')


print('Waiting for Android to finish booting…', flush=True)
wait_for(lambda: adb('shell', 'getprop', 'sys.boot_completed', timeout=10).strip() == '1',
         'Android did not finish booting', timeout=600)
wait_for(lambda: 'input:' in adb('shell', 'service', 'list', timeout=10),
         'Android input service did not become ready', timeout=120)
adb('shell', 'input', 'keyevent', '224')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'svc', 'power', 'stayon', 'true')
adb('shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
print(adb('install', '--no-incremental', '-r', str(ROOT / args.apk), timeout=120).strip(), flush=True)
assert adb('shell', 'pm', 'clear', PACKAGE).strip() == 'Success'
adb('shell', 'appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'deny')
app()
assert node_with(text='Filter is off') is not None
assert node_with(resource=PACKAGE + ':id/permission_button') is not None
before = screenshot('off.png')
tap(resource=PACKAGE + ':id/toggle')
wait_for(lambda: foreground('com.android.settings'),
         'Overlay settings did not open')
assert not running(), 'Filter must not start before permission is granted'
print('PASS: activation requires overlay permission', flush=True)

permission_switch = node_with(resource='android:id/switch_widget')
if permission_switch is not None:
    # Exercise the permission screen as a user would; shell app-op changes can
    # leave Settings' own UI state stale while the activity is still open.
    if permission_switch.get('checked') != 'true':
        tap(resource='android:id/switch_widget')
else:
    adb('shell', 'appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
wait_for(lambda: ': allow' in adb('shell', 'appops', 'get', PACKAGE, 'SYSTEM_ALERT_WINDOW'),
         'Overlay permission was not granted by Settings')
adb('shell', 'input', 'keyevent', '4')
wait_for(lambda: foreground(PACKAGE), 'Did not return to Amber after permission settings')
# Notifications are optional: declining must not prevent filtering.
deny = node_with(resource='com.android.permissioncontroller:id/permission_deny_button')
if deny is not None:
    tap(resource='com.android.permissioncontroller:id/permission_deny_button')
wait_for(running, 'Filter did not start after returning from overlay settings')
assert node_with(text='Filter is on') is not None
after = screenshot('on.png')
# Sample the unchanging left margin inside the activity, away from status icons.
x, y = 8, before.height // 3
off_rgb, on_rgb = before.getpixel((x, y)), after.getpixel((x, y))
assert on_rgb[2] < off_rgb[2], f'Blue was not reduced: {off_rgb} -> {on_rgb}'
assert on_rgb[0] > off_rgb[0], f'Warm tint missing: {off_rgb} -> {on_rgb}'
print(f'PASS: foreground filter renders a warmer screen ({off_rgb} -> {on_rgb})', flush=True)

# Presets must update a running overlay and survive leaving the activity.
swipe_x = str(before.width // 2)
adb('shell', 'input', 'swipe', swipe_x, str(before.height * 4 // 5),
    swipe_x, str(before.height * 2 // 5), '350')
tap(resource=PACKAGE + ':id/deep')
settings = saved_settings()
assert 'name="warmth" value="90"' in settings
assert 'name="strength" value="80"' in settings
assert running()
print('PASS: preset settings update and persist', flush=True)

launch = adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
assert 'Error:' not in launch, launch
wait_for(lambda: foreground('com.android.settings'), 'Settings did not become foreground')
wait_for(lambda: node_with(text='Connected devices') is not None, 'Settings dashboard did not render')
assert running(), 'Filter stopped when switching apps'
windows = adb('shell', 'dumpsys', 'window', 'windows')
window_blocks = re.split(r'(?m)^\s*Window #', windows)
assert any(PACKAGE in block and re.search(r'\bty=(?:APPLICATION_OVERLAY|2038)\b', block)
           for block in window_blocks), 'Amber has no application-overlay window'
tap(text='Network & internet')
assert node_with(text='Airplane mode') is not None, 'Overlay blocked a touch in another app'
print('PASS: filter remains active across apps and passes touches through', flush=True)

if int(adb('shell', 'getprop', 'ro.build.version.sdk').strip()) >= 33:
    adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
adb('shell', 'cmd', 'statusbar', 'expand-notifications')
time.sleep(1)
# Notification actions may be collapsed initially.
if node_with(text='Turn off') is None:
    expand = node_with(resource='com.android.systemui:id/expand_button')
    if expand is not None:
        tap(resource='com.android.systemui:id/expand_button')
tap(text='Turn off')
wait_for(lambda: not running(), 'Notification stop action did not stop the filter')
adb('shell', 'cmd', 'statusbar', 'collapse')
print('PASS: notification turns off the foreground filter', flush=True)

app()
assert node_with(text='Filter is off') is not None
tap(resource=PACKAGE + ':id/toggle')
wait_for(running, 'Filter did not restart')
adb('shell', 'am', 'force-stop', PACKAGE)
app()
assert node_with(text='Filter is off') is not None
assert not running(), 'Force-stopped filter must not unexpectedly restart'
settings = saved_settings()
assert 'name="warmth" value="90"' in settings
assert 'name="strength" value="80"' in settings
print('PASS: force-stop clears runtime state and retains user settings', flush=True)
print('All 6 functional checks passed.', flush=True)
