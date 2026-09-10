# Security and privacy

GPSLog handles precise location and photo metadata. Keep real tracks, photos,
exported JSON/GPX/CSV, device diagnostics and generated reports outside Git.
Use the ignored `private/`, `tracks/`, `photos/` and `reports/` directories.

The Android app records locally by default. Administrative-area lookup and
automatic upload are separate opt-in settings. Upload requires an explicitly
configured HTTPS server and a user-supplied credential; no hosted backend or
shared API key is provided by this repository. See [privacy details](docs/PRIVACY.md).

Before sharing an issue or pull request:

- Reproduce the problem with a synthetic fixture whenever possible.
- Remove credentials, account identifiers, coordinates, photo timestamps and
  personal paths from logs and screenshots.
- Never attach a real database, a signing key or a browser/session token.

For a vulnerability, use GitHub's private vulnerability reporting if enabled.
Otherwise open an issue asking for a private contact channel, without posting
the exploit details or sensitive data. Report accidentally committed credentials
privately and revoke them at the service provider before cleaning Git history.

`scripts/check-public.py` checks staged paths, known credential formats and fixture
consistency. It is a local release aid; it cannot prove that arbitrary text or
images contain no personal information. Review your staged diff before committing.
