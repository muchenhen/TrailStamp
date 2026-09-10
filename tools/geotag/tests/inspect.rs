use std::{fs, path::PathBuf, process::Command};

use serde_json::Value;
use tempfile::tempdir;

fn fixture() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/sample-track.json")
}

fn command() -> Command {
    Command::new(env!("CARGO_BIN_EXE_gpslog-geotag"))
}

#[test]
fn inspects_and_matches_shared_fixture_without_exiftool_or_a_site() {
    let path = fixture();
    let original = fs::read(&path).unwrap();
    let output = command()
        .args(["inspect", "--track"])
        .arg(&path)
        .args(["--at", "2000-01-01T00:04:00Z"])
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let report: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(report["schema"], "gpslog.inspect/v1");
    assert_eq!(report["usablePoints"], 2);
    assert_eq!(report["maxUsableGapSeconds"], 240.0);
    assert_eq!(report["match"]["confidence"], "high");
    assert_eq!(report["match"]["location"]["latitude"], 0.0);
    assert_eq!(report["match"]["location"]["longitude"], 0.0);
    assert_eq!(fs::read(path).unwrap(), original);
}

#[test]
fn rejects_a_timestamp_without_an_offset() {
    let output = command()
        .args(["inspect", "--track"])
        .arg(fixture())
        .args(["--at", "2000-01-01T00:04:00"])
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("RFC 3339"));
}

#[test]
fn returns_failure_for_a_track_with_no_usable_points() {
    let directory = tempdir().unwrap();
    let path = directory.path().join("unusable.json");
    let mut track: Value = serde_json::from_slice(&fs::read(fixture()).unwrap()).unwrap();
    for point in track["points"].as_array_mut().unwrap() {
        point["isMock"] = Value::Bool(true);
    }
    fs::write(&path, serde_json::to_vec(&track).unwrap()).unwrap();
    let output = command()
        .args(["inspect", "--track"])
        .arg(path)
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("no usable points"));
}
