use std::{fs, path::Path};

use anyhow::{Context, Result, bail};
use chrono::{DateTime, Utc};

use crate::models::{Confidence, PlannedLocation, TrackBracket, TrackFile, TrackPoint};

#[derive(Debug, Clone)]
pub struct MatchResult {
    pub confidence: Confidence,
    pub location: Option<PlannedLocation>,
    pub bracket: Option<TrackBracket>,
    pub warnings: Vec<String>,
}

pub fn load_track(path: &Path) -> Result<TrackFile> {
    let bytes = fs::read(path).with_context(|| format!("cannot read track {}", path.display()))?;
    let mut track: TrackFile = serde_json::from_slice(&bytes).context("invalid GPSLog JSON")?;
    if track.schema != "gpslog.track/v1" {
        bail!("unsupported track schema: {}", track.schema);
    }
    track
        .points
        .sort_by_key(|point| (point.timestamp_utc, point.elapsed_realtime_nanos));
    Ok(track)
}

pub fn validate_track(track: &TrackFile) -> Vec<String> {
    let mut warnings = Vec::new();
    if track.points.is_empty() {
        warnings.push("track has no points".into());
    }
    let mut previous_elapsed = None;
    let mut previous_time = None;
    for (index, point) in track.points.iter().enumerate() {
        if !valid_coordinates(point.latitude, point.longitude) {
            warnings.push(format!("point {index} has invalid coordinates"));
        }
        if let Some(value) = previous_elapsed
            && point.elapsed_realtime_nanos < value
        {
            warnings.push(format!("point {index} has decreasing elapsed realtime"));
        }
        if let Some(value) = previous_time
            && point.timestamp_utc < value
        {
            warnings.push(format!("point {index} has decreasing UTC time"));
        }
        previous_elapsed = Some(point.elapsed_realtime_nanos);
        previous_time = Some(point.timestamp_utc);
    }
    warnings
}

pub fn usable_points(track: &TrackFile) -> Vec<&TrackPoint> {
    let mut points: Vec<_> = track
        .points
        .iter()
        .filter(|point| {
            point.usable
                && !point.is_mock
                && point.timestamp_utc >= track.session.started_at
                && valid_coordinates(point.latitude, point.longitude)
                && point.accuracy_meters.is_some_and(|accuracy| {
                    accuracy.is_finite() && (0.0..=500.0).contains(&accuracy)
                })
        })
        .collect();
    points.sort_by(|a, b| {
        a.timestamp_utc
            .cmp(&b.timestamp_utc)
            .then_with(|| {
                a.accuracy_meters
                    .unwrap_or(500.0)
                    .total_cmp(&b.accuracy_meters.unwrap_or(500.0))
            })
            .then_with(|| a.elapsed_realtime_nanos.cmp(&b.elapsed_realtime_nanos))
    });
    points.dedup_by(|a, b| a.timestamp_utc == b.timestamp_utc);
    points
}

pub fn match_timestamp(track: &TrackFile, timestamp: DateTime<Utc>) -> MatchResult {
    let points = usable_points(track);
    if points.is_empty() {
        return unmatched("track has no usable points");
    }

    match points.binary_search_by_key(&timestamp, |point| point.timestamp_utc) {
        Ok(index) => match_single(points[index], timestamp, "exact track timestamp"),
        Err(0) => extrapolate(points[0], timestamp, "photo precedes first usable point"),
        Err(index) if index == points.len() => extrapolate(
            points[points.len() - 1],
            timestamp,
            "photo follows last usable point",
        ),
        Err(index) => interpolate(points[index - 1], points[index], timestamp),
    }
}

fn match_single(point: &TrackPoint, timestamp: DateTime<Utc>, note: &str) -> MatchResult {
    let accuracy = point.accuracy_meters.unwrap_or(500.0);
    let confidence = if accuracy <= 200.0 {
        Confidence::High
    } else {
        Confidence::Low
    };
    MatchResult {
        confidence,
        location: Some(location_from_point(point, timestamp)),
        bracket: Some(TrackBracket {
            before_timestamp_utc: Some(point.timestamp_utc),
            after_timestamp_utc: Some(point.timestamp_utc),
            before_accuracy_meters: point.accuracy_meters,
            after_accuracy_meters: point.accuracy_meters,
            span_seconds: Some(0.0),
        }),
        warnings: vec![note.into()],
    }
}

fn extrapolate(point: &TrackPoint, timestamp: DateTime<Utc>, note: &str) -> MatchResult {
    let delta = (timestamp - point.timestamp_utc)
        .num_milliseconds()
        .unsigned_abs() as f64
        / 1000.0;
    if delta > 180.0 {
        return unmatched(format!("{note}; extrapolation is {delta:.1}s (>180s)"));
    }
    MatchResult {
        confidence: Confidence::Low,
        location: Some(location_from_point(point, timestamp)),
        bracket: Some(TrackBracket {
            before_timestamp_utc: (point.timestamp_utc <= timestamp).then_some(point.timestamp_utc),
            after_timestamp_utc: (point.timestamp_utc >= timestamp).then_some(point.timestamp_utc),
            before_accuracy_meters: (point.timestamp_utc <= timestamp)
                .then_some(point.accuracy_meters)
                .flatten(),
            after_accuracy_meters: (point.timestamp_utc >= timestamp)
                .then_some(point.accuracy_meters)
                .flatten(),
            span_seconds: None,
        }),
        warnings: vec![format!("{note}; extrapolated {delta:.1}s")],
    }
}

fn interpolate(before: &TrackPoint, after: &TrackPoint, timestamp: DateTime<Utc>) -> MatchResult {
    let span = (after.timestamp_utc - before.timestamp_utc).num_milliseconds() as f64 / 1000.0;
    let bracket = TrackBracket {
        before_timestamp_utc: Some(before.timestamp_utc),
        after_timestamp_utc: Some(after.timestamp_utc),
        before_accuracy_meters: before.accuracy_meters,
        after_accuracy_meters: after.accuracy_meters,
        span_seconds: Some(span),
    };
    if span <= 0.0 {
        return MatchResult {
            bracket: Some(bracket),
            ..unmatched("non-positive track interval")
        };
    }
    if span > 600.0 {
        return MatchResult {
            bracket: Some(bracket),
            ..unmatched(format!("surrounding track points span {span:.1}s (>600s)"))
        };
    }

    let ratio = (timestamp - before.timestamp_utc).num_milliseconds() as f64 / (span * 1000.0);
    let longitude_delta = shortest_longitude_delta(before.longitude, after.longitude);
    let longitude = normalize_longitude(before.longitude + longitude_delta * ratio);
    let altitude = match (before.altitude_meters, after.altitude_meters) {
        (Some(a), Some(b)) => Some(a + (b - a) * ratio),
        _ => None,
    };
    let before_accuracy = before.accuracy_meters.unwrap_or(500.0);
    let after_accuracy = after.accuracy_meters.unwrap_or(500.0);
    let mut confidence = if span <= 360.0 && before_accuracy <= 200.0 && after_accuracy <= 200.0 {
        Confidence::High
    } else {
        Confidence::Low
    };
    let mut warnings = Vec::new();
    if confidence == Confidence::Low {
        warnings.push(format!(
            "low confidence interval: span={span:.1}s, accuracy={before_accuracy:.1}/{after_accuracy:.1}m"
        ));
    }
    let speed_kmh = haversine_meters(
        before.latitude,
        before.longitude,
        after.latitude,
        after.longitude,
    ) / span
        * 3.6;
    if speed_kmh > 250.0 {
        warnings.push(format!(
            "implied speed {speed_kmh:.1} km/h exceeds 250 km/h"
        ));
        confidence = Confidence::Low;
    }
    let closer_admin = if ratio <= 0.5 {
        before.admin.clone()
    } else {
        after.admin.clone()
    };
    MatchResult {
        confidence,
        location: Some(PlannedLocation {
            latitude: before.latitude + (after.latitude - before.latitude) * ratio,
            longitude,
            altitude_meters: altitude,
            horizontal_error_meters: Some(before_accuracy.max(after_accuracy)),
            gps_timestamp_utc: timestamp,
            admin: closer_admin,
        }),
        bracket: Some(bracket),
        warnings,
    }
}

fn location_from_point(point: &TrackPoint, timestamp: DateTime<Utc>) -> PlannedLocation {
    PlannedLocation {
        latitude: point.latitude,
        longitude: point.longitude,
        altitude_meters: point.altitude_meters,
        horizontal_error_meters: point.accuracy_meters,
        gps_timestamp_utc: timestamp,
        admin: point.admin.clone(),
    }
}

fn unmatched(message: impl Into<String>) -> MatchResult {
    MatchResult {
        confidence: Confidence::Unmatched,
        location: None,
        bracket: None,
        warnings: vec![message.into()],
    }
}

fn valid_coordinates(latitude: f64, longitude: f64) -> bool {
    latitude.is_finite()
        && longitude.is_finite()
        && (-90.0..=90.0).contains(&latitude)
        && (-180.0..=180.0).contains(&longitude)
}

fn shortest_longitude_delta(a: f64, b: f64) -> f64 {
    let mut delta = b - a;
    if delta > 180.0 {
        delta -= 360.0;
    } else if delta < -180.0 {
        delta += 360.0;
    }
    delta
}

fn normalize_longitude(value: f64) -> f64 {
    ((value + 180.0).rem_euclid(360.0)) - 180.0
}

fn haversine_meters(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let d_lat = (lat2 - lat1).to_radians();
    let d_lon = (lon2 - lon1).to_radians();
    let a = (d_lat / 2.0).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lon / 2.0).sin().powi(2);
    6_371_000.0 * 2.0 * a.sqrt().atan2((1.0 - a).sqrt())
}

#[cfg(test)]
mod tests {
    use std::{collections::HashMap, path::PathBuf};

    use chrono::{TimeZone, Utc};
    use serde::Deserialize;

    use super::*;
    use crate::models::{TrackFile, TrackSession};

    fn track(span_seconds: i64, accuracy: f64) -> TrackFile {
        let start = Utc.with_ymd_and_hms(2026, 8, 8, 0, 0, 0).unwrap();
        TrackFile {
            schema: "gpslog.track/v1".into(),
            session: TrackSession {
                id: "test".into(),
                started_at: start,
                ended_at: Some(start + chrono::Duration::hours(1)),
                time_zone: "Asia/Shanghai".into(),
                profile: "endurance".into(),
                target_interval_seconds: 240,
                status: Some("completed".into()),
                gap_count: 0,
            },
            points: vec![
                TrackPoint {
                    timestamp_utc: start,
                    elapsed_realtime_nanos: 1,
                    latitude: 31.0,
                    longitude: 121.0,
                    altitude_meters: Some(0.0),
                    accuracy_meters: Some(accuracy),
                    vertical_accuracy_meters: None,
                    speed_mps: None,
                    bearing_degrees: None,
                    provider: None,
                    is_mock: false,
                    usable: true,
                    admin: None,
                },
                TrackPoint {
                    timestamp_utc: start + chrono::Duration::seconds(span_seconds),
                    elapsed_realtime_nanos: 2,
                    latitude: 31.01,
                    longitude: 121.01,
                    altitude_meters: Some(10.0),
                    accuracy_meters: Some(accuracy),
                    vertical_accuracy_meters: None,
                    speed_mps: None,
                    bearing_degrees: None,
                    provider: None,
                    is_mock: false,
                    usable: true,
                    admin: None,
                },
            ],
        }
    }

    #[test]
    fn matches_six_and_ten_minute_boundaries() {
        let six = track(360, 100.0);
        assert_eq!(
            match_timestamp(
                &six,
                six.session.started_at + chrono::Duration::seconds(180)
            )
            .confidence,
            Confidence::High
        );
        let ten = track(600, 100.0);
        assert_eq!(
            match_timestamp(
                &ten,
                ten.session.started_at + chrono::Duration::seconds(300)
            )
            .confidence,
            Confidence::Low
        );
        let over = track(601, 100.0);
        assert_eq!(
            match_timestamp(
                &over,
                over.session.started_at + chrono::Duration::seconds(300)
            )
            .confidence,
            Confidence::Unmatched
        );
    }

    #[test]
    fn low_accuracy_and_extrapolation_are_low_confidence() {
        let input = track(300, 300.0);
        assert_eq!(
            match_timestamp(
                &input,
                input.session.started_at + chrono::Duration::seconds(120)
            )
            .confidence,
            Confidence::Low
        );
        assert_eq!(
            match_timestamp(
                &input,
                input.session.started_at - chrono::Duration::seconds(180)
            )
            .confidence,
            Confidence::Low
        );
        assert_eq!(
            match_timestamp(
                &input,
                input.session.started_at - chrono::Duration::seconds(181)
            )
            .confidence,
            Confidence::Unmatched
        );
        let end = input.points[1].timestamp_utc;
        assert_eq!(
            match_timestamp(&input, end + chrono::Duration::seconds(180)).confidence,
            Confidence::Low
        );
        assert_eq!(
            match_timestamp(&input, end + chrono::Duration::seconds(181)).confidence,
            Confidence::Unmatched
        );
    }

    #[test]
    fn interpolates_across_utc_midnight() {
        let mut input = track(240, 20.0);
        let before = Utc.with_ymd_and_hms(2026, 8, 7, 23, 58, 0).unwrap();
        input.session.started_at = before;
        input.points[0].timestamp_utc = before;
        input.points[1].timestamp_utc = before + chrono::Duration::minutes(4);
        let result = match_timestamp(&input, before + chrono::Duration::minutes(2));
        assert_eq!(result.confidence, Confidence::High);
        let location = result.location.unwrap();
        assert!((location.latitude - 31.005).abs() < 0.000_001);
        assert!((location.altitude_meters.unwrap() - 5.0).abs() < 0.000_001);
    }

    #[test]
    fn abnormal_implied_speed_downgrades_confidence() {
        let mut input = track(300, 20.0);
        input.points[1].latitude = 32.0;
        input.points[1].longitude = 122.0;
        let result = match_timestamp(
            &input,
            input.session.started_at + chrono::Duration::seconds(120),
        );
        assert_eq!(result.confidence, Confidence::Low);
        assert!(
            result
                .warnings
                .iter()
                .any(|value| value.contains("250 km/h"))
        );
    }

    #[test]
    fn duplicate_timestamp_uses_the_most_accurate_point() {
        let mut input = track(300, 40.0);
        let mut better = input.points[0].clone();
        better.elapsed_realtime_nanos += 1;
        better.latitude = 30.0;
        better.longitude = 120.0;
        better.accuracy_meters = Some(5.0);
        input.points.push(better);
        let result = match_timestamp(&input, input.points[0].timestamp_utc);
        let location = result.location.unwrap();
        assert_eq!(location.latitude, 30.0);
        assert_eq!(location.longitude, 120.0);
        assert_eq!(location.horizontal_error_meters, Some(5.0));
    }

    #[test]
    fn rejects_a_forged_usable_point_before_the_session_started() {
        let mut input = track(300, 20.0);
        input.points[0].timestamp_utc = input.session.started_at - chrono::Duration::seconds(1);
        let points = usable_points(&input);
        assert_eq!(points.len(), 1);
        assert_eq!(points[0].timestamp_utc, input.points[1].timestamp_utc);
    }

    #[derive(Debug, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct GoldenFixture {
        algorithm_version: String,
        tracks: HashMap<String, TrackFile>,
        cases: Vec<GoldenCase>,
    }

    #[derive(Debug, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct GoldenCase {
        name: String,
        track: String,
        photo_timestamp_utc: chrono::DateTime<Utc>,
        expected: GoldenExpected,
    }

    #[derive(Debug, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct GoldenExpected {
        confidence: String,
        latitude: Option<f64>,
        longitude: Option<f64>,
        altitude_meters: Option<f64>,
        horizontal_error_meters: Option<f64>,
        before_timestamp_utc: Option<chrono::DateTime<Utc>>,
        after_timestamp_utc: Option<chrono::DateTime<Utc>>,
        span_seconds: Option<f64>,
        warning_contains: Option<String>,
    }

    #[test]
    fn shared_golden_matcher_cases_are_exact() {
        let fixture_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/gps-matcher-v1.cases.json");
        let fixture: GoldenFixture =
            serde_json::from_slice(&std::fs::read(fixture_path).unwrap()).unwrap();
        assert_eq!(fixture.algorithm_version, "gpslog-rust-compatible/v1");
        for case in fixture.cases {
            let track = fixture.tracks.get(&case.track).unwrap();
            let result = match_timestamp(track, case.photo_timestamp_utc);
            let expected_confidence = match case.expected.confidence.as_str() {
                "high" => Confidence::High,
                "low" => Confidence::Low,
                "unmatched" => Confidence::Unmatched,
                value => panic!("unknown confidence {value}"),
            };
            assert_eq!(result.confidence, expected_confidence, "{}", case.name);
            match (result.location, case.expected.latitude) {
                (Some(location), Some(latitude)) => {
                    assert!((location.latitude - latitude).abs() < 1e-9, "{}", case.name);
                    assert!(
                        (location.longitude - case.expected.longitude.unwrap()).abs() < 1e-9,
                        "{}",
                        case.name
                    );
                    assert_optional_float(
                        location.altitude_meters,
                        case.expected.altitude_meters,
                        &case.name,
                    );
                    assert_optional_float(
                        location.horizontal_error_meters,
                        case.expected.horizontal_error_meters,
                        &case.name,
                    );
                }
                (None, None) => {}
                _ => panic!("location mismatch for {}", case.name),
            }
            match result.bracket {
                Some(bracket) => {
                    assert_eq!(
                        bracket.before_timestamp_utc, case.expected.before_timestamp_utc,
                        "{}",
                        case.name
                    );
                    assert_eq!(
                        bracket.after_timestamp_utc, case.expected.after_timestamp_utc,
                        "{}",
                        case.name
                    );
                    assert_optional_float(
                        bracket.span_seconds,
                        case.expected.span_seconds,
                        &case.name,
                    );
                }
                None => {
                    assert!(
                        case.expected.before_timestamp_utc.is_none(),
                        "{}",
                        case.name
                    );
                    assert!(case.expected.after_timestamp_utc.is_none(), "{}", case.name);
                    assert!(case.expected.span_seconds.is_none(), "{}", case.name);
                }
            }
            if let Some(needle) = case.expected.warning_contains {
                assert!(
                    result
                        .warnings
                        .iter()
                        .any(|warning| warning.contains(&needle)),
                    "{}: missing warning {needle:?}",
                    case.name
                );
            }
        }
    }

    fn assert_optional_float(actual: Option<f64>, expected: Option<f64>, case_name: &str) {
        match (actual, expected) {
            (Some(actual), Some(expected)) => {
                assert!((actual - expected).abs() < 1e-6, "{case_name}")
            }
            (None, None) => {}
            _ => panic!("optional float mismatch for {case_name}"),
        }
    }
}
