# Third-party software

The MIT license at the repository root covers GPSLog's original code. It does
not replace the licenses of bundled software or dependencies.

| Component | Distribution / license information |
| --- | --- |
| ExifTool 13.59 for Windows and its Perl runtime | Bundled without modification in `tools/geotag/vendor/exiftool-13.59_64.zip`. See [the vendor notice](tools/geotag/vendor/THIRD_PARTY_NOTICES.md). The archive retains ExifTool's license and the Strawberry Perl license bundle. |
| Gradle wrapper 8.10.2 | Apache License 2.0; the wrapper scripts retain their copyright notices. See [Gradle's license](https://github.com/gradle/gradle/blob/v8.10.2/LICENSE). |
| Rust crates | Resolved versions and checksums are in `Cargo.lock`. Each crate is distributed under its own license. |
| Android libraries | Dependencies are declared in `app/build.gradle.kts`. AndroidX, Kotlin, Google Play services and their transitive dependencies retain their own licenses and terms. |

The Android location provider requires Google Play services. Its presence in
the dependency list does not make Google Play services part of GPSLog's MIT
licensed source. This repository does not distribute Android SDK or JDK bundles.

Before distributing a compiled application, retain the notices and licenses
required by the dependencies included in that application. Do not remove the
license files inside the ExifTool archive or its extracted runtime directory.
