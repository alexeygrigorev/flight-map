#!/usr/bin/env python3
"""Convert an explicitly selected readsb flight segment, retaining only real observations."""
import argparse
import datetime as dt
import gzip
import hashlib
import json
from pathlib import Path


def instant(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


def convert(data, callsign, start, end, number, origin, destination, url, complete=False):
    rows = []
    active = None
    for row in data['trace']:
        details = row[8] if len(row) > 8 else None
        if details and details.get('flight'):
            active = details['flight'].strip()
        timestamp = data['timestamp'] + row[0]
        if start <= timestamp <= end and active == callsign:
            if len(row) > 9 and row[9] != 'adsb_icao':
                raise ValueError('Selected observation is not ADS-B ICAO; use an appropriate source label')
            if rows and timestamp <= rows[-1][0]:
                raise ValueError('Observation timestamps must strictly increase')
            altitude = row[3] * 0.3048 if isinstance(row[3], (float, int)) else None
            rows.append((timestamp, row[1], row[2], altitude))
    if len(rows) < 2:
        raise ValueError('No usable callsign segment; do not fabricate missing observations')
    base = rows[0][0]
    return {
        'flightNumber': number, 'originIata': origin, 'destinationIata': destination,
        'observedDeparture': dt.datetime.fromtimestamp(base, dt.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z'),
        'points': [dict(offsetSeconds=round(t-base, 3), point=dict(lat=lat, lon=lon), altitudeMeters=alt)
                   for t, lat, lon, alt in rows],
        'source': 'adsb.lol · ADS-B observations', 'sourceUrl': url, 'license': 'ODbL-1.0',
        'coverageComplete': complete,
        'coverageNote': 'Importer explicitly marked complete airport-to-airport coverage' if complete else
                        'Partial receiver coverage; first/last observations are not takeoff/landing or gate events'
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('trace', type=Path)
    p.add_argument('--callsign', required=True)
    p.add_argument('--start', required=True, help='Inclusive UTC ISO time; select a single leg')
    p.add_argument('--end', required=True)
    p.add_argument('--number', required=True)
    p.add_argument('--origin', required=True)
    p.add_argument('--destination', required=True)
    p.add_argument('--source-url', required=True)
    p.add_argument('--complete', action='store_true', help='Only after verifying coverage of both airports and all gaps')
    p.add_argument('--plan', type=Path, help='Optional JSON FlightPlan to wrap as importable .flightpack.json')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    raw = args.trace.read_bytes()
    data = json.loads(gzip.decompress(raw) if raw[:2] == b'\x1f\x8b' else raw)
    track = convert(data, args.callsign, instant(args.start), instant(args.end), args.number,
                    args.origin, args.destination, args.source_url, args.complete)
    result = track
    if args.plan:
        plan = json.loads(args.plan.read_text())
        plan['historicalTrack'] = track
        result = {'plan': plan, 'cities': [], 'weather': {}, 'mapReady': False,
                  'preparedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(f'{len(track["points"])} observations; input SHA256 {hashlib.sha256(raw).hexdigest()}')


if __name__ == '__main__':
    main()
