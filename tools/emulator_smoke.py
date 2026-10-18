#!/usr/bin/env python3
"""Exercise a dedicated emulator, download AY87 and verify restart in airplane mode.
Never defaults to an existing emulator: pass --serial explicitly.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--adb', default='/home/alexey/Android/Sdk/platform-tools/adb')
p.add_argument('--shots', type=Path, required=True)
p.add_argument('--skip-prepare', action='store_true')
a = p.parse_args()
a.shots.mkdir(parents=True, exist_ok=True)

def adb(*args, binary=False):
    r = subprocess.run([a.adb, '-s', a.serial, *args], check=True, capture_output=True, timeout=45)
    return r.stdout if binary else r.stdout.decode()

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/flight-map-window.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/flight-map-window.xml'))

def texts():
    return '\n'.join(n.attrib.get('text', '') for n in tree().iter('node'))

def tap(label, contains=False):
    root = tree()
    parents = {c: p for p in root.iter() for c in p}
    for n in root.iter('node'):
        text = n.attrib.get('text', '')
        if text == label or (contains and label in text):
            candidate = n
            while candidate is not None and candidate.attrib.get('clickable') != 'true':
                candidate = parents.get(candidate)
            if candidate is None or candidate.attrib.get('enabled') != 'true':
                continue
            left, top, right, bottom = map(int, re.findall(r'\d+', candidate.attrib['bounds']))
            if right <= left or bottom <= top:
                continue
            adb('shell', 'input', 'tap', str((left+right)//2), str((top+bottom)//2))
            time.sleep(1)
            return True
    return False

def shot(name):
    (a.shots / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

def scroll():
    adb('shell', 'input', 'swipe', '550', '1900', '550', '650', '450')
    time.sleep(1)

adb('shell', 'am', 'start', '-n', 'com.flightmap.app/.MainActivity')
time.sleep(4)
if not a.skip_prepare:
    shot('01-prepare-entry.png')
    for _ in range(5):
        if tap('Prepare for flight'):
            break
        scroll()
    else:
        raise RuntimeError('Prepare button not found')
    deadline = time.monotonic() + 420
    last = ''
    while time.monotonic() < deadline:
        last = texts()
        print(last[-1700:], flush=True)
        if 'Open offline flight' in last or 'Open saved flight' in last:
            break
        time.sleep(6)
    else:
        raise RuntimeError('Preparation did not finish: '+last)
    shot('02-prepared-status.png')
pack = json.loads(adb('exec-out', 'run-as', 'com.flightmap.app', 'cat', 'files/flight-pack.json'))
assert pack['plan']['origin']['iata'] == 'HEL'
assert pack['plan']['destination']['iata'] == 'PVG'
assert pack['mapReady'], 'Map corridor did not download'
assert pack['cities'], 'No corridor cities'
expected = pack.get('sampledWeatherIds') or [c['id'] for c in pack['cities']]
assert all(pack['weather'].get(city_id) for city_id in expected), 'Missing selected city forecasts'
assert pack['mapRegionIds'], 'No offline region saved'
summary = {'flight': pack['plan'], 'cityCount': len(pack['cities']), 'weatherCityCount': len(pack['weather']),
           'weatherHourCount': sum(len(v) for v in pack['weather'].values()), 'mapRegionIds': pack['mapRegionIds'],
           'preparedAt': pack['preparedAt']}
(a.shots / 'offline-smoke-result.json').write_text(json.dumps(summary, indent=2))
# Turn off all network in this task's dedicated emulator and cold-restart the app.
adb('shell', 'svc', 'wifi', 'disable')
adb('shell', 'svc', 'data', 'disable')
adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
adb('shell', 'am', 'force-stop', 'com.flightmap.app')
adb('shell', 'am', 'start', '-n', 'com.flightmap.app/.MainActivity')
time.sleep(4)
for _ in range(5):
    if tap('Open') or tap('Open offline flight'):
        break
    scroll()
else:
    raise RuntimeError('Offline-ready pack not restored after airplane-mode restart')
time.sleep(8)
shot('03-offline-flight-current-time.png')
assert 'OFFLINE READY' in texts()
assert tap('Preview journey')
# Slider position at approximately midpoint in lower card; determine its bounds from accessibility.
root = tree()
slider = next((n for n in root.iter('node') if n.attrib.get('class') == 'android.widget.SeekBar'), None)
if slider is not None:
    l,t,r,b = map(int, re.findall(r'\d+', slider.attrib['bounds']))
    adb('shell','input','tap',str((l+r)//2),str((t+b)//2))
time.sleep(3)
shot('04-offline-flight-preview.png')
assert 'PREVIEW' in texts()
assert tap('Along the route')
time.sleep(2)
shot('05-offline-cities.png')
summary['airplaneMode'] = adb('shell', 'settings', 'get', 'global', 'airplane_mode_on').strip()
assert summary['airplaneMode'] == '1'
summary['coldRestartOfflineReady'] = True
summary['previewAccessible'] = True
(a.shots / 'offline-smoke-result.json').write_text(json.dumps(summary, indent=2))
print('PASS: offline map, forecasts, city times, preview and airplane-mode cold restart', flush=True)
