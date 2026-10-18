#!/usr/bin/env python3
"""Create a second pack, restore both offline, and delete it without losing the first.
Requires the default AY87 HEL–PVG pack already prepared on a disposable emulator.
"""
import argparse
import json
import re
import sqlite3
import time
from pathlib import Path
from emulator_v02_smoke import Device

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--shots',required=True)
p.add_argument('--evidence',required=True)
a=p.parse_args()
d=Device(a.serial,a.shots)
out=Path(a.evidence);out.mkdir(parents=True,exist_ok=True)

def wait_for(fn,timeout=180):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(2)
    raise AssertionError('Timed out')

def replace(old,new):
    for _ in range(8):
        root=d.tree()
        for n in root.iter('node'):
            if n.attrib.get('class')=='android.widget.EditText' and n.attrib.get('text')==old:
                l,t,r,b=map(int,re.findall(r'\d+',n.attrib['bounds']))
                d.adb('shell','input','tap',str((l+r)//2),str((t+b)//2))
                d.adb('shell','input','keyevent','123')
                d.adb('shell','input','keyevent',*(['67']*(len(old)+5)))
                d.adb('shell','input','text',new)
                d.adb('shell','input','keyevent','4')
                time.sleep(.5)
                return
        d.adb('shell','input','swipe','550','1850','550','650','400')
    raise AssertionError('Input not found: '+old)

first=json.loads(d.private('files/flight-packs.json'))[0]
original_ids=first['mapRegionIds']
d.online();d.adb('emu','network','speed','full')
d.adb('shell','am','force-stop','com.flightmap.app');d.launch();time.sleep(6)
replace('AY87','TEST2')
replace('PVG','TLL')
from datetime import datetime
# The HEL ticket day is already displayed in the source pack's origin timezone.
from zoneinfo import ZoneInfo
date=datetime.fromisoformat(first['plan']['departure'].replace('Z','+00:00')).astimezone(ZoneInfo('Europe/Helsinki')).date().isoformat()
replace(date+'T18:25',date+'T02:10')
d.find_tap('Prepare for flight')
d.adb('shell','input','keyevent','3')
print('second pack preparation started',flush=True)
def second_ready():
    cp=json.loads(d.private('files/preparation-checkpoint.json'))
    if cp['plan']['number']=='TEST2':
        if cp['status'] in ('FAILED','PAUSED'):raise AssertionError(str(cp.get('error')))
        return cp['status']=='COMPLETED'
    return False
wait_for(second_ready,300)
packs=json.loads(d.private('files/flight-packs.json'));assert len(packs)==2
second=next(x for x in packs if x['plan']['number']=='TEST2')
assert second['mapReady'] and len(second['mapRegionIds'])==3
assert set(original_ids).isdisjoint(second['mapRegionIds'])
d.offline();d.adb('shell','am','force-stop','com.flightmap.app');d.launch();time.sleep(6)
assert 'SAVED FLIGHTS · 2' in d.texts()
d.shot('05-two-packs-offline.png')
assert d.tap('Open')
time.sleep(5)
assert 'TLL' in d.texts() and 'OFFLINE READY' in d.texts()
d.shot('05b-second-pack-offline.png')
d.adb('shell','input','keyevent','4')
time.sleep(2)
assert d.tap('Delete TEST2')
d.shot('06-delete-confirmation.png')
assert d.tap('Delete')
wait_for(lambda:len(json.loads(d.private('files/flight-packs.json')))==1)
time.sleep(4)
path=out/'native-after-delete.db';path.write_bytes(d.adb('exec-out','run-as','com.flightmap.app','cat','files/mbgl-offline.db',binary=True))
c=sqlite3.connect(path);ids=[r[0] for r in c.execute('select id from regions')];c.close()
assert set(original_ids)<=set(ids)
assert set(second['mapRegionIds']).isdisjoint(ids)
assert len(ids)==3
remaining=json.loads(d.private('files/flight-packs.json'))[0]
assert remaining['plan']['number']=='AY87' and remaining['mapRegionIds']==original_ids
summary={'twoPacksRestoredOffline':True,'deletedLatestPackRegions':second['mapRegionIds'],'retainedNativeRegionIds':ids,'remainingFlight':remaining['plan']['number'],'deleteReclaimedMapRegions':True,'airplaneMode':d.adb('shell','settings','get','global','airplane_mode_on').strip()}
(out/'packs-result.json').write_text(json.dumps(summary,indent=2))
print('PASS_MULTIPACK',json.dumps(summary),flush=True)
d.shot('07-after-pack-delete.png')
