#!/usr/bin/env python3
"""Destructive recovery test for an explicitly chosen disposable Flight Map emulator.
Deletes only test app pack/map state; retains its weather HTTP cache.
"""
import sys,time,json,sqlite3,subprocess,argparse
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from emulator_v02_smoke import Device
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--shots',required=True)
parser.add_argument('--evidence',required=True)
a=parser.parse_args()
out=Path(a.evidence);out.mkdir(parents=True,exist_ok=True)
d=Device(a.serial,a.shots)
d.adb('emu','network','speed','2048','2048')

def checkpoint():
    try:return json.loads(d.private('files/preparation-checkpoint.json'))
    except Exception:return {}

def db(name='native-live.db'):
    p=out/name
    p.write_bytes(d.adb('exec-out','run-as','com.flightmap.app','cat','files/mbgl-offline.db',binary=True))
    return sqlite3.connect(p)

def report(stage,**kwargs):
    print(stage,json.dumps(kwargs),flush=True)

def wait_for(fn,timeout=180):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(2)
    raise AssertionError('Timed out waiting for recovery state')

# Reset only this dedicated test app's pack/map state; retain the legitimate weather HTTP cache.
d.adb('shell','am','force-stop','com.flightmap.app')
d.adb('shell','run-as','com.flightmap.app','rm','-f','files/flight-pack.json','files/flight-packs.json','files/preparation-checkpoint.json','files/mbgl-offline.db','files/mbgl-offline.db-wal','files/mbgl-offline.db-shm')
d.online();time.sleep(3)
d.launch();time.sleep(5)
d.shot('01-final-entry.png')
d.find_tap('Prepare for flight')
d.adb('shell','input','keyevent','3')
report('preparation_started_on_home')
wait_for(lambda: checkpoint().get('weatherReady'))
report('weather_complete',cities=len(checkpoint().get('cities',[])),forecasts=len(checkpoint().get('weather',{})))

def enough_tiles():
    try:
        c=db();rows=c.execute('select id,cast(description as text) from regions').fetchall();n=c.execute('select count(*) from tiles').fetchone()[0];c.close()
        return (rows,n) if n>=12 else None
    except Exception:return None
rows,n=wait_for(enough_tiles)
report('native_progress_before_kill',regions=rows,tiles=n)
d.adb('shell','cmd','statusbar','expand-notifications');time.sleep(4);d.texts();d.shot('02-final-background-notification.png');d.adb('shell','cmd','statusbar','collapse')
oldpid=d.adb('shell','pidof','com.flightmap.app').strip()
d.adb('shell','run-as','com.flightmap.app','kill','-9',oldpid)
report('process_killed',pid=oldpid)
newpid=wait_for(lambda:(lambda p:p if p and p!=oldpid else None)(d.adb('shell','pidof','com.flightmap.app',check=False).strip()),timeout=100)
report('sticky_service_restarted',pid=newpid)
wait_for(lambda:'isForeground=true' in d.adb('shell','dumpsys','activity','services','com.flightmap.app'))
# Reopen while service is active: startup cleanup must preserve the in-progress region.
d.launch();time.sleep(3)
d.find_tap('Pause download')
wait_for(lambda:checkpoint().get('status')=='PAUSED')
paused=checkpoint();before_ids=paused['mapRegionIds'];assert before_ids
c=db('native-paused.db');native_ids=[r[0] for r in c.execute('select id from regions')];c.close()
assert set(before_ids)<=set(native_ids)
d.shot('03-paused-before-reboot.png')
report('paused',ids=before_ids,weather=len(paused['weather']))
(out/'paused-checkpoint-summary.json').write_text(json.dumps({k:v for k,v in paused.items() if k not in ('weather','cities')},indent=2))
# Full dedicated emulator reboot. Android15 resumes dataSync only after user action.
d.adb('reboot')
report('emulator_reboot_requested')
subprocess.run([d.adb_path,'-s',d.serial,'wait-for-device'],check=True,timeout=90)
wait_for(lambda:d.adb('shell','getprop','sys.boot_completed',check=False).strip()=='1',timeout=120)
time.sleep(5);d.adb('shell','input','keyevent','82');d.online();d.launch();time.sleep(5)
assert checkpoint()['status']=='PAUSED'
assert checkpoint()['mapRegionIds']==before_ids
# Remove throttle for completion after resuming the same native region IDs.
d.adb('emu','network','speed','full')
d.find_tap('Resume preparation')
report('resume_after_reboot',ids=before_ids)
wait_for(lambda:checkpoint().get('status')=='COMPLETED',timeout=300)
packs=json.loads(d.private('files/flight-packs.json'));assert len(packs)==1
p=packs[0];assert p['mapReady'];assert set(before_ids)<=set(p['mapRegionIds']);assert len(p['mapRegionIds'])==3
assert len(p['weather'])==600;assert len(p['sampledWeatherIds'])==600
assert p['cities'] and len(p['cities'])>600
c=db('native-recovered.db');zoom=c.execute('select z,count(*),sum(length(data)) from tiles group by z').fetchall();regions=c.execute('select id,cast(description as text) from regions').fetchall();c.close()
assert any(z>=8 and n>0 for z,n,b in zoom);assert any(z==10 and n>0 for z,n,b in zoom)
assert sum(n for z,n,b in zoom)<2900;assert sum(b for z,n,b in zoom)<49_000_000
summary={'homePreparation':True,'stickyRestart':True,'oldPid':oldpid,'newPid':newpid,'pauseRebootResume':True,'pausedRegionIds':before_ids,'finalRegionIds':p['mapRegionIds'],'cityCount':len(p['cities']),'forecastLocations':len(p['weather']),'summary':p['mapDetailSummary'],'tilesByZoom':zoom,'regions':regions}
(out/'recovery-result.json').write_text(json.dumps(summary,indent=2))
report('PASS_RECOVERY',**summary)
d.shot('04-recovered-ready.png')
