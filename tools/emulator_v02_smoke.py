#!/usr/bin/env python3
"""UI + private-storage checks on an explicitly selected, disposable emulator.
The task operator owns the AVD; never use another person's emulator.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


class Device:
    def __init__(self, serial, shots, adb='/home/alexey/Android/Sdk/platform-tools/adb'):
        if not serial.startswith('emulator-'):
            raise ValueError('Dedicated emulator serial required')
        self.serial, self.adb_path, self.shots = serial, adb, Path(shots)
        self.shots.mkdir(parents=True, exist_ok=True)

    def adb(self, *args, binary=False, check=True):
        r = subprocess.run([self.adb_path, '-s', self.serial, *args], capture_output=True, check=check, timeout=45)
        return r.stdout if binary else r.stdout.decode()

    def tree(self):
        self.adb('shell', 'uiautomator', 'dump', '/sdcard/flight-map-v02-window.xml')
        return ET.fromstring(self.adb('exec-out', 'cat', '/sdcard/flight-map-v02-window.xml'))

    def texts(self):
        return '\n'.join(n.attrib.get('text', '') for n in self.tree().iter('node'))

    def tap(self, label, contains=False):
        root = self.tree()
        parents = {c: p for p in root.iter() for c in p}
        for node in root.iter('node'):
            text = node.attrib.get('text', '') or node.attrib.get('content-desc', '')
            if text == label or (contains and label in text):
                candidate = node
                while candidate is not None and candidate.attrib.get('clickable') != 'true':
                    candidate = parents.get(candidate)
                if candidate is None or candidate.attrib.get('enabled') != 'true':
                    continue
                l, t, r, b = map(int, re.findall(r'\d+', candidate.attrib['bounds']))
                self.adb('shell', 'input', 'tap', str((l+r)//2), str((t+b)//2))
                time.sleep(.8)
                return True
        return False

    def find_tap(self, label, attempts=8, contains=False):
        for _ in range(attempts):
            if self.tap(label, contains):
                return True
            self.adb('shell', 'input', 'swipe', '550', '1900', '550', '650', '400')
            time.sleep(.5)
        raise AssertionError('Button not found: '+label+'\n'+self.texts())

    def shot(self, name):
        (self.shots / name).write_bytes(self.adb('exec-out', 'screencap', '-p', binary=True))

    def private(self, path):
        return self.adb('exec-out', 'run-as', 'com.flightmap.app', 'cat', path)

    def launch(self):
        self.adb('shell', 'am', 'start', '-n', 'com.flightmap.app/.MainActivity')
        time.sleep(3)

    def online(self):
        self.adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
        self.adb('shell', 'svc', 'wifi', 'enable')
        self.adb('shell', 'svc', 'data', 'enable')

    def offline(self):
        self.adb('shell', 'svc', 'wifi', 'disable')
        self.adb('shell', 'svc', 'data', 'disable')
        self.adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
        assert self.adb('shell', 'settings', 'get', 'global', 'airplane_mode_on').strip() == '1'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--shots', required=True)
    parser.add_argument('--offline-open', action='store_true')
    args = parser.parse_args()
    d = Device(args.serial, args.shots)
    if args.offline_open:
        d.offline()
        d.adb('shell', 'am', 'force-stop', 'com.flightmap.app')
        d.launch()
        # The per-pack Open buttons are above the entry form.
        for _ in range(15):
            if 'SAVED FLIGHTS' in d.texts():
                break
            time.sleep(1)
        d.shot('saved-packs-airplane-mode.png')
        for _ in range(15):
            if d.tap('Open'):
                break
            time.sleep(1)
        else:
            raise AssertionError('Saved pack Open button unavailable: '+d.texts())
        time.sleep(6)
        assert 'OFFLINE READY' in d.texts()
        d.shot('offline-flight-current-time.png')
        d.find_tap('Preview journey')
        slider = next(n for n in d.tree().iter('node') if n.attrib.get('class') == 'android.widget.SeekBar')
        l, t, r, b = map(int, re.findall(r'\d+', slider.attrib['bounds']))
        d.adb('shell', 'input', 'tap', str((l+r)//2), str((t+b)//2))
        time.sleep(2)
        assert 'PREVIEW' in d.texts()
        d.shot('offline-flight-preview.png')
        assert d.tap('Plane')
        time.sleep(4)
        d.shot('offline-route-detail.png')
        assert d.tap('Zoom in') or d.tap('+')
        time.sleep(4)
        d.shot('offline-corridor-zoom8.png')
        slider = next(n for n in d.tree().iter('node') if n.attrib.get('class') == 'android.widget.SeekBar')
        l, t, r, b = map(int, re.findall(r'\d+', slider.attrib['bounds']))
        d.adb('shell', 'input', 'swipe', str((l+r)//2), str((t+b)//2), '0', str((t+b)//2), '700')
        time.sleep(2)
        assert '0 km flown' in d.texts(), 'Airport preview did not reach route origin'
        assert d.tap('Plane')
        for _ in range(3):
            assert d.tap('Zoom in') or d.tap('+')
        time.sleep(4)
        d.shot('offline-airport-zoom10.png')
        assert d.tap('Along the route')
        d.shot('offline-cities.png')
        print('PASS: airplane-mode cold restart, saved pack selection, offline overview/detail, preview, city list')
    else:
        d.launch()
        print(d.texts())


if __name__ == '__main__':
    main()
