#!/usr/bin/env python3
"""
Data update and build script for Flight Map MVP.

This script downloads and processes:
1. OurAirports airports.csv
   - Source: OurAirports (Public Domain / CC0)
   - URL: https://davidmegginson.github.io/ourairports-data/airports.csv
   - Alternate: https://ourairports.com/data/airports.csv
   - Output: app/src/main/assets/data/airports.json
   - Filter: type in ('large_airport', 'medium_airport') and non-empty iata_code.
   - Timezone assignment: Nearest city in cities15000 (country-matched first,
     falling back to global nearest, then UTC fallback).

2. GeoNames cities15000
   - Source: GeoNames (Creative Commons Attribution 4.0 - CC BY 4.0)
   - URL: https://download.geonames.org/export/dump/cities15000.zip
   - Output: app/src/main/assets/data/cities.json
   - Format: [{id, name, country, lat, lon, timezone, population}, ...]

3. Natural Earth 110m Land Polygons
   - Source: Natural Earth (Public Domain)
   - URL: https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson
   - Output: app/src/main/assets/data/land.geojson
   - Fallback offline vector land geometry for map rendering.

Reproducible script using Python standard library only.
"""

import argparse
import csv
import io
import json
import math
import os
import sys
import time
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

# Sources
URL_OURAIRPORTS = "https://davidmegginson.github.io/ourairports-data/airports.csv"
URL_GEONAMES_CITIES15000 = "https://download.geonames.org/export/dump/cities15000.zip"
URL_NATURAL_EARTH_110M_LAND = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson"

USER_AGENT = "FlightMap-DataUpdater/1.0 (+https://github.com/alexey/flight-map)"


def fetch_url(url: str, timeout: int = 60) -> bytes:
    """Fetch binary data from URL using standard library."""
    print(f"Downloading: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if resp.status != 200:
            raise RuntimeError(f"HTTP {resp.status} fetching {url}")
        data = resp.read()
        print(f"Downloaded {len(data):,} bytes from {url}")
        return data


def lat_lon_to_cartesian(lat: float, lon: float):
    """Convert spherical lat/lon in degrees to 3D unit sphere cartesian coordinates."""
    phi = math.radians(lat)
    theta = math.radians(lon)
    return (
        math.cos(phi) * math.cos(theta),
        math.cos(phi) * math.sin(theta),
        math.sin(phi),
    )


class KDNode:
    __slots__ = ("point", "data", "axis", "left", "right")

    def __init__(self, point, data, axis, left, right):
        self.point = point
        self.data = data
        self.axis = axis
        self.left = left
        self.right = right


def build_kdtree(points, depth: int = 0):
    """Build a 3D KD-Tree for fast nearest-neighbor spatial queries."""
    if not points:
        return None
    axis = depth % 3
    points.sort(key=lambda p: p[0][axis])
    mid = len(points) // 2
    return KDNode(
        point=points[mid][0],
        data=points[mid][1],
        axis=axis,
        left=build_kdtree(points[:mid], depth + 1),
        right=build_kdtree(points[mid + 1:], depth + 1),
    )


def nearest_neighbor(node, target, best=None, best_dist=float("inf")):
    """Query nearest neighbor in 3D KD-Tree."""
    if node is None:
        return best, best_dist
    p = node.point
    d = (p[0] - target[0]) ** 2 + (p[1] - target[1]) ** 2 + (p[2] - target[2]) ** 2
    if d < best_dist:
        best_dist = d
        best = node.data

    axis_diff = target[node.axis] - p[node.axis]
    first = node.left if axis_diff < 0 else node.right
    second = node.right if axis_diff < 0 else node.left

    best, best_dist = nearest_neighbor(first, target, best, best_dist)
    if axis_diff ** 2 < best_dist:
        best, best_dist = nearest_neighbor(second, target, best, best_dist)
    return best, best_dist


def parse_cities(zip_bytes: bytes):
    """
    Parse GeoNames cities15000.txt from zip archive.
    Returns list of city dicts and spatial indexes.
    """
    print("Processing GeoNames cities15000...")
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        with zf.open("cities15000.txt") as f:
            cities = []
            for line in f:
                parts = line.decode("utf-8").strip().split("\t")
                if len(parts) < 18:
                    continue
                # 0: geonameid, 1: name, 4: latitude, 5: longitude, 8: country_code, 14: population, 17: timezone
                city_id = parts[0]
                name = parts[1]
                lat = round(float(parts[4]), 5)
                lon = round(float(parts[5]), 5)
                country = parts[8].strip().upper()
                population = int(parts[14]) if parts[14] else 0
                timezone = parts[17].strip()

                cities.append({
                    "id": city_id,
                    "name": name,
                    "country": country,
                    "lat": lat,
                    "lon": lon,
                    "timezone": timezone,
                    "population": population,
                })

    print(f"Loaded {len(cities):,} cities from GeoNames.")

    # Sort cities by population descending (most prominent cities first)
    cities.sort(key=lambda c: c["population"], reverse=True)

    # Build spatial KD-Trees (by country and global)
    print("Building spatial indexes for nearest city lookup...")
    country_points = defaultdict(list)
    all_points = []
    for c in cities:
        if c["timezone"]:
            pt = (lat_lon_to_cartesian(c["lat"], c["lon"]), c)
            country_points[c["country"]].append(pt)
            all_points.append(pt)

    country_trees = {k: build_kdtree(v) for k, v in country_points.items()}
    global_tree = build_kdtree(all_points)
    print("Spatial indexes ready.")

    return cities, country_trees, global_tree


def parse_airports(csv_bytes: bytes, country_trees, global_tree):
    """
    Parse OurAirports airports.csv.
    Filter: type in ('large_airport', 'medium_airport') and non-empty iata_code.
    Assigns timezone via nearest city (country-first, then global, then UTC).
    """
    print("Processing OurAirports airports.csv...")
    reader = csv.DictReader(io.TextIOWrapper(io.BytesIO(csv_bytes), encoding="utf-8", errors="ignore"))

    airports = []
    seen_iatas = set()

    for r in reader:
        airport_type = r.get("type", "").strip()
        if airport_type not in ("large_airport", "medium_airport"):
            continue

        iata = r.get("iata_code", "").strip().upper()
        if not iata or len(iata) != 3:
            continue

        # Prevent duplicate IATAs if any
        if iata in seen_iatas:
            continue
        seen_iatas.add(iata)

        name = r.get("name", "").strip()
        lat = round(float(r["latitude_deg"]), 5)
        lon = round(float(r["longitude_deg"]), 5)
        country = r.get("iso_country", "").strip().upper()

        # Resolve timezone
        # If airport record somehow had timezone column, use it; otherwise find nearest city
        tz = r.get("timezone", "").strip()
        if not tz:
            target = lat_lon_to_cartesian(lat, lon)
            # Try country tree first for timezone consistency within country borders
            nearest_city = None
            if country in country_trees:
                nearest_city, _ = nearest_neighbor(country_trees[country], target)
            if not nearest_city and global_tree:
                nearest_city, _ = nearest_neighbor(global_tree, target)

            if nearest_city and nearest_city.get("timezone"):
                tz = nearest_city["timezone"]
            else:
                tz = "UTC"

        airports.append({
            "iata": iata,
            "name": name,
            "lat": lat,
            "lon": lon,
            "timezone": tz,
        })

    # Sort airports alphabetically by IATA code
    airports.sort(key=lambda a: a["iata"])
    print(f"Processed {len(airports):,} airports (large and medium with IATA).")

    # Verification of key airports
    airport_dict = {a["iata"]: a for a in airports}
    assert "HEL" in airport_dict, "HEL airport missing!"
    assert airport_dict["HEL"]["timezone"] == "Europe/Helsinki", f"HEL timezone unexpected: {airport_dict['HEL']['timezone']}"
    assert "PVG" in airport_dict, "PVG airport missing!"
    assert airport_dict["PVG"]["timezone"] == "Asia/Shanghai", f"PVG timezone unexpected: {airport_dict['PVG']['timezone']}"
    print(f"Verified HEL -> {airport_dict['HEL']['timezone']}")
    print(f"Verified PVG -> {airport_dict['PVG']['timezone']}")

    return airports


def process_land_geojson(geojson_bytes: bytes):
    """Validate and minify Natural Earth land GeoJSON."""
    print("Processing Natural Earth 110m land geojson...")
    data = json.loads(geojson_bytes.decode("utf-8"))
    if data.get("type") != "FeatureCollection":
        raise ValueError(f"Expected FeatureCollection, got {data.get('type')}")
    features = data.get("features", [])
    print(f"Land GeoJSON valid: {len(features)} features.")
    return data


def save_compact_json(obj, target_path: Path):
    """Save object as compact JSON (no extra whitespace)."""
    target_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = target_path.with_suffix(".tmp")
    with open(temp_path, "w", encoding="utf-8") as f:
        json.dump(obj, f, separators=(",", ":"), ensure_ascii=False)
    temp_path.replace(target_path)
    size_bytes = target_path.stat().st_size
    print(f"Saved {target_path} ({size_bytes:,} bytes / {size_bytes / 1024 / 1024:.2f} MB)")
    return size_bytes


def validate_outputs(output_dir: Path):
    """Validate generated datasets."""
    print("\n--- Validating Generated Datasets ---")
    airports_path = output_dir / "airports.json"
    cities_path = output_dir / "cities.json"
    land_path = output_dir / "land.geojson"

    assert airports_path.exists(), f"Missing {airports_path}"
    assert cities_path.exists(), f"Missing {cities_path}"
    assert land_path.exists(), f"Missing {land_path}"

    with open(airports_path, "r", encoding="utf-8") as f:
        airports = json.load(f)
    with open(cities_path, "r", encoding="utf-8") as f:
        cities = json.load(f)
    with open(land_path, "r", encoding="utf-8") as f:
        land = json.load(f)

    # Validate airports
    assert isinstance(airports, list) and len(airports) > 3000, f"Airports count suspicious: {len(airports)}"
    for a in airports:
        assert "iata" in a and len(a["iata"]) == 3, f"Bad IATA in {a}"
        assert "name" in a and a["name"], f"Bad name in {a}"
        assert "lat" in a and isinstance(a["lat"], (int, float)), f"Bad lat in {a}"
        assert "lon" in a and isinstance(a["lon"], (int, float)), f"Bad lon in {a}"
        assert "timezone" in a and a["timezone"], f"Bad timezone in {a}"

    hel = next(a for a in airports if a["iata"] == "HEL")
    pvg = next(a for a in airports if a["iata"] == "PVG")
    assert hel["timezone"] == "Europe/Helsinki", f"HEL tz bad: {hel}"
    assert pvg["timezone"] == "Asia/Shanghai", f"PVG tz bad: {pvg}"

    # Validate cities
    assert isinstance(cities, list) and len(cities) > 20000, f"Cities count suspicious: {len(cities)}"
    for c in cities:
        assert "id" in c and c["id"], f"Bad id in {c}"
        assert "name" in c and c["name"], f"Bad name in {c}"
        assert "country" in c and c["country"], f"Bad country in {c}"
        assert "lat" in c and isinstance(c["lat"], (int, float)), f"Bad lat in {c}"
        assert "lon" in c and isinstance(c["lon"], (int, float)), f"Bad lon in {c}"
        assert "timezone" in c and c["timezone"], f"Bad timezone in {c}"
        assert "population" in c and isinstance(c["population"], int), f"Bad population in {c}"

    # Validate land
    assert land.get("type") == "FeatureCollection", f"Bad land type: {land.get('type')}"
    assert len(land.get("features", [])) > 0, "No land features"

    total_size = (
        airports_path.stat().st_size
        + cities_path.stat().st_size
        + land_path.stat().st_size
    )

    print(f"Validation successful:")
    print(f"  airports.json: {len(airports):,} entries, {airports_path.stat().st_size:,} bytes")
    print(f"  cities.json:   {len(cities):,} entries, {cities_path.stat().st_size:,} bytes")
    print(f"  land.geojson:  {len(land.get('features', []))} features, {land_path.stat().st_size:,} bytes")
    print(f"  Total size:    {total_size:,} bytes ({total_size / 1024 / 1024:.2f} MB)")
    assert total_size < 15 * 1024 * 1024, f"Dataset exceeds 15MB: {total_size} bytes"
    return len(airports), len(cities), len(land.get("features", [])), total_size


def main():
    parser = argparse.ArgumentParser(description="Update and generate Flight Map offline datasets.")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "data",
        help="Directory to save generated datasets (default: app/src/main/assets/data)",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=None,
        help="Optional directory to cache raw downloads",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Only verify existing outputs without downloading",
    )
    args = parser.parse_args()

    if args.verify_only:
        validate_outputs(args.output_dir)
        return

    start_time = time.time()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    cache_dir = args.cache_dir
    if cache_dir:
        cache_dir.mkdir(parents=True, exist_ok=True)

    def get_data(url: str, filename: str) -> bytes:
        if cache_dir:
            cache_file = cache_dir / filename
            if cache_file.exists():
                print(f"Using cached file: {cache_file}")
                return cache_file.read_bytes()
        data = fetch_url(url)
        if cache_dir:
            cache_file = cache_dir / filename
            cache_file.write_bytes(data)
        return data

    # 1. GeoNames cities15000
    cities_zip = get_data(URL_GEONAMES_CITIES15000, "cities15000.zip")
    cities, country_trees, global_tree = parse_cities(cities_zip)
    save_compact_json(cities, args.output_dir / "cities.json")

    # 2. OurAirports airports
    airports_csv = get_data(URL_OURAIRPORTS, "airports.csv")
    airports = parse_airports(airports_csv, country_trees, global_tree)
    save_compact_json(airports, args.output_dir / "airports.json")

    # 3. Natural Earth land geojson
    land_geojson = get_data(URL_NATURAL_EARTH_110M_LAND, "ne_110m_land.geojson")
    land_data = process_land_geojson(land_geojson)
    save_compact_json(land_data, args.output_dir / "land.geojson")

    # 4. Final validation
    validate_outputs(args.output_dir)
    print(f"\nAll datasets generated successfully in {time.time() - start_time:.2f}s.")


if __name__ == "__main__":
    main()
