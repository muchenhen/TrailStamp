#!/usr/bin/env python3
"""Read the Git index and fail on accidental private inputs or known credentials."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


APPROVED_BINARIES = {
    "gradle/wrapper/gradle-wrapper.jar":
        "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046",
    "tools/geotag/vendor/exiftool-13.59_64.zip":
        "44b512b25af500724ba579d0a53c8fc5851628b692dd5e5d94ae4a15c2cba9ec",
}
PRIVATE_DIRECTORIES = {"private", "tracks", "photos", "reports", "imports", "exports"}
PRIVATE_SUFFIXES = {
    ".nef", ".dng", ".arw", ".cr2", ".cr3", ".raf", ".jpg", ".jpeg",
    ".gpx", ".csv", ".db", ".sqlite", ".sqlite3", ".jsonl", ".log",
    ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".apk", ".aab",
}
PATTERNS = {
    "provider credential": re.compile(
        rb"(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|"
        rb"github_pat_[A-Za-z0-9_]{25,}|AKID[A-Za-z0-9]{13,}|"
        rb"AKIA[A-Z0-9]{16}|LTAI[A-Za-z0-9]{12,})"
    ),
    "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "robot webhook credential": re.compile(
        rb"(?:qyapi\.weixin\.qq\.com/cgi-bin/webhook/send\?key=|"
        rb"api\.telegram\.org/bot|oapi\.dingtalk\.com/robot/send\?access_token=)"
        rb"[A-Za-z0-9_:-]{15,}"
    ),
    "personal home path": re.compile(
        rb"(?:[A-Za-z]:\\Users\\[A-Za-z0-9_.-]+|/Users/[A-Za-z0-9_.-]+/|"
        rb"/home/[A-Za-z0-9_.-]+/)"
    ),
}


def staged_files(repo):
    result = subprocess.run(
        ["git", "-C", str(repo), "ls-files", "--stage", "-z"],
        check=True, capture_output=True,
    )
    for entry in result.stdout.split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, oid, stage = metadata.decode("ascii").split()
        yield raw_path.decode("utf-8"), mode, oid, stage


def check(repo):
    findings = []
    seen = set()
    for name, mode, oid, stage in staged_files(repo):
        seen.add(name)
        path = PurePosixPath(name)
        if stage != "0" or mode not in {"100644", "100755"}:
            findings.append((name, "conflict, symlink or nested Git repository"))
            continue
        private_name = (
            path.parts[0] in PRIVATE_DIRECTORIES
            or path.name in {"local.properties", "keystore.properties", "plan.json", "apply-log.json"}
            or (path.name.startswith(".env") and path.name != ".env.example")
            or (path.suffix.lower() in PRIVATE_SUFFIXES
                and not (path.parts[0] == "fixtures" and path.suffix.lower() in {".csv", ".gpx"}))
        )
        if private_name:
            findings.append((name, "private input, output or credential file"))
        if name.startswith(".github/workflows/"):
            findings.append((name, "this project uses local checks, not GitHub Actions"))
        content = subprocess.run(
            ["git", "-C", str(repo), "cat-file", "blob", oid],
            check=True, capture_output=True,
        ).stdout
        if name in APPROVED_BINARIES:
            if hashlib.sha256(content).hexdigest() != APPROVED_BINARIES[name]:
                findings.append((name, "bundled binary checksum differs from the reviewed release"))
            continue
        try:
            content.decode("utf-8")
        except UnicodeDecodeError:
            findings.append((name, "unreviewed binary or non-UTF-8 file"))
            continue
        if b"\0" in content:
            findings.append((name, "unreviewed binary file"))
            continue
        for rule, pattern in PATTERNS.items():
            for match in pattern.finditer(content):
                line = content[:match.start()].count(b"\n") + 1
                findings.append((name + ":" + str(line), rule))
        if name == "fixtures/sample-track.json":
            try:
                sample = json.loads(content)
                valid_session = (
                    sample["session"]["timeZone"] == "UTC"
                    and sample["session"]["startedAt"] == "2000-01-01T00:00:00Z"
                    and len(sample["points"]) == 2
                )
                valid_points = all(
                    point["latitude"] == 0 and point["longitude"] == 0
                    and point["provider"] == "fixture"
                    and point["timestampUtc"].startswith("2000-01-01T")
                    for point in sample["points"]
                )
                if not valid_session or not valid_points:
                    raise ValueError("non-synthetic shared fixture")
            except (KeyError, TypeError, ValueError):
                findings.append((name, "shared example must remain the documented synthetic fixture"))
    for required in {"LICENSE", "README.md", "fixtures/sample-track.json"} - seen:
        findings.append((required, "required public project file is missing from the index"))
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        findings = check(args.repo)
    except (OSError, subprocess.CalledProcessError) as error:
        print("Public check could not read the Git index: " + str(error), file=sys.stderr)
        return 2
    for location, reason in findings:
        print(location + ": " + reason, file=sys.stderr)
    if findings:
        print("Check failed; no secret values were printed.", file=sys.stderr)
        return 1
    print("Staged public-content checks passed. Review free-form text and attachments manually.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
