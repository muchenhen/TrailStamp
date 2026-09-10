# Synthetic fixtures

`sample-track.json` is generated test data: two stationary points at **0°, 0°**
on **2000-01-01**, with fictional administrative-area labels. It is not a
recording, a travel itinerary, or a photo owner's location. The Android exporter
test compares its output to this same fixture.

Try the matching engine on Windows, macOS or Linux:

```sh
cargo run --locked --bin gpslog-geotag -- inspect --track fixtures/sample-track.json --at 2000-01-01T00:04:00Z
```

Expected: two usable points, a 240-second interval, and a high-confidence match
at 0°, 0°. This command reads only the chosen track. It does not launch ExifTool,
connect to a server, or write photo metadata.

`gps-matcher-v1.cases.json` contains deterministic boundary cases for the matching
rules. These are independent synthetic tests, not personal recordings.

`site-rollback/` is a minimal website contract whose `locations:backfill` script
deliberately changes `data/china.json` and fails. Its photo filename and timestamp
are fictional. Use it only as a disposable test site; its scripts exercise rollback
rather than build a real website.

Real NEF files are excluded from Git. Windows end-to-end tests require explicitly
selected RAW, track and site inputs, copy RAW into an ignored `reports/` directory,
check its hash before and after, and write metadata only to a separate output copy.
