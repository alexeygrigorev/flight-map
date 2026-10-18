# Data and network services

Bundled on 2026-10-18 using `tools/update_data.py`:

- OurAirports, public-domain airport database: https://ourairports.com/data/ . Airports with IATA codes and type medium/large; airport time zones inferred from the nearest GeoNames city in the same country. Confirm ticket times, especially near time-zone borders.
- GeoNames cities5000: https://download.geonames.org/export/dump/ , CC BY 4.0 https://creativecommons.org/licenses/by/4.0/ . This product includes data from GeoNames (https://www.geonames.org). Fields reduced to ID, name, country, coordinates, time zone, population.
- Natural Earth 1:110m land: https://www.naturalearthdata.com/ , public domain. Source https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_land.geojson . Geometry reduced by the import script.

Weather: Open-Meteo https://open-meteo.com/ , CC BY 4.0. Coordinates for corridor cities are sent only during preparation. Hourly temperature, WMO weather code and day/night, in Unix UTC timestamps. Forecast horizon approximately 16 days; preparation reports unavailable dates. Cache reused for up to three hours, refreshed on later preparation. Saved forecasts remain available offline but are not live observations.

Map: MapLibre Native Android 13.6.1. OpenFreeMap https://openfreemap.org/ provides vector tiles, © OpenStreetMap contributors https://www.openstreetmap.org/copyright and OpenMapTiles https://openmaptiles.org/ . Policy https://openfreemap.org/quick_start/ (checked 2026-10-18) permits at most 3,000 tiles or 50 MB per download, four parallel requests and a unique User-Agent. Each flight pack has one aggregate budget across its detail tiers, with headroom below those limits. Route geometries split at the date line; detail is concentrated in a narrow route band and around airports. Natural Earth is the permanent offline base. Native offline regions retain corridor resources in private app storage. Android removal/clear-storage deletes them.

Flight lookup deliberately requires no commercial key: AY87 HEL–PVG is a bundled schedule fallback, 01:10 Helsinki to 18:25 Shanghai, 12h15m. Route and example times were cross-checked against https://aviability.com/flight-number/flight-ay87-finnair and https://www.flight.info/AY87 on 2026-10-18. **These are example schedule data, not a date-verified booking or live flight status.** Confirm against a ticket; all airports, local departure/arrival dates and times are editable. Unknown flight numbers use manual entry. No flight tracking API, GPS, account or secret is used.
