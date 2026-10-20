import gzip
import json
import unittest
from pathlib import Path
from import_readsb import convert, instant

ROOT = Path(__file__).resolve().parents[2]


class RealObservationImportTest(unittest.TestCase):
    def test_bundled_capture_is_reproducible_without_added_samples(self):
        data = json.loads(gzip.decompress((ROOT / 'tools/history/mu689-20261006-readsb.json.gz').read_bytes()))
        bundle = json.loads((ROOT / 'app/src/main/assets/data/history/MU689-PVG-DYG.json').read_text())
        result = convert(data, 'CSH7225', instant('2026-10-06T03:53:38Z'), instant('2026-10-06T04:20:21Z'),
                         'MU689', 'PVG', 'DYG', bundle['sourceUrl'])
        self.assertEqual(bundle, result)
        self.assertEqual(103, len(result['points']))
        raw_positions = {(r[1], r[2]) for r in data['trace']}
        self.assertTrue(all((r['point']['lat'], r['point']['lon']) in raw_positions for r in result['points']))
        self.assertFalse(result['coverageComplete'])
        self.assertNotIn('observedTakeoff', result)
        self.assertNotIn('observedLanding', result)

    def test_wrong_callsign_has_no_fabricated_fallback(self):
        data = json.loads(gzip.decompress((ROOT / 'tools/history/mu689-20261006-readsb.json.gz').read_bytes()))
        with self.assertRaises(ValueError):
            convert(data, 'WRONG', 0, 2e9, 'MU689', 'PVG', 'DYG', 'https://example.org')

    def test_multiple_day_legs_cannot_be_merged_under_stale_callsign(self):
        # Explicitly synthetic regression fixture: day-long trace, second leg has no callsign.
        data = {'timestamp': 0, 'trace': [[0, 0, 0, 100, None, None, None, None, {'flight': 'TEST'}, 'adsb_icao'],
                                        [10, 0, 1, 100, None, None, None, None, None, 'adsb_icao'],
                                        [10000, 0, 2, 100, None, None, None, None, None, 'adsb_icao']]}
        result = convert(data, 'TEST', 0, 20000, 'TEST', 'AAA', 'BBB', 'https://example.org')
        self.assertEqual(2, len(result['points']))


if __name__ == '__main__':
    unittest.main()
