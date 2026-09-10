use std::path::PathBuf;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackFile {
    pub schema: String,
    pub session: TrackSession,
    pub points: Vec<TrackPoint>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackSession {
    pub id: String,
    pub started_at: DateTime<Utc>,
    pub ended_at: Option<DateTime<Utc>>,
    pub time_zone: String,
    pub profile: String,
    pub target_interval_seconds: u32,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub gap_count: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackPoint {
    pub timestamp_utc: DateTime<Utc>,
    pub elapsed_realtime_nanos: i64,
    pub latitude: f64,
    pub longitude: f64,
    pub altitude_meters: Option<f64>,
    pub accuracy_meters: Option<f64>,
    pub vertical_accuracy_meters: Option<f64>,
    pub speed_mps: Option<f64>,
    pub bearing_degrees: Option<f64>,
    #[serde(default)]
    pub provider: Option<String>,
    pub is_mock: bool,
    pub usable: bool,
    pub admin: Option<Admin>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Admin {
    #[serde(default)]
    pub country: String,
    #[serde(default)]
    pub province: String,
    #[serde(default)]
    pub city: String,
    #[serde(default)]
    pub district: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub source: Option<String>,
    #[serde(default)]
    pub resolved_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "PascalCase")]
pub struct ExifMetadata {
    #[serde(rename = "SourceFile")]
    pub source_file: PathBuf,
    #[serde(default, rename = "SubSecDateTimeOriginal")]
    pub sub_sec_date_time_original: Option<String>,
    #[serde(default, rename = "DateTimeOriginal")]
    pub date_time_original: Option<String>,
    #[serde(default, rename = "OffsetTimeOriginal")]
    pub offset_time_original: Option<String>,
    #[serde(default, rename = "CreateDate")]
    pub create_date: Option<String>,
    #[serde(default, rename = "Model")]
    pub camera_model: Option<String>,
    #[serde(default, rename = "GPSLatitude")]
    pub gps_latitude: Option<f64>,
    #[serde(default, rename = "GPSLongitude")]
    pub gps_longitude: Option<f64>,
    #[serde(default, rename = "GPSAltitude")]
    pub gps_altitude: Option<f64>,
    #[serde(default, rename = "GPSAltitudeRef")]
    pub gps_altitude_ref: Option<i32>,
    #[serde(default, rename = "Country")]
    pub country: Option<String>,
    #[serde(default, rename = "State")]
    pub state: Option<String>,
    #[serde(default, rename = "City")]
    pub city: Option<String>,
    #[serde(default, rename = "Location")]
    pub location: Option<String>,
    #[serde(default, rename = "GPSHPositioningError")]
    pub gps_horizontal_error: Option<f64>,
    #[serde(default, rename = "GPSDateTime")]
    pub gps_date_time: Option<String>,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Confidence {
    High,
    Low,
    Unmatched,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedLocation {
    pub latitude: f64,
    pub longitude: f64,
    pub altitude_meters: Option<f64>,
    pub horizontal_error_meters: Option<f64>,
    pub gps_timestamp_utc: DateTime<Utc>,
    pub admin: Option<Admin>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackBracket {
    pub before_timestamp_utc: Option<DateTime<Utc>>,
    pub after_timestamp_utc: Option<DateTime<Utc>>,
    pub before_accuracy_meters: Option<f64>,
    pub after_accuracy_meters: Option<f64>,
    pub span_seconds: Option<f64>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoPlan {
    pub raw_path: PathBuf,
    pub raw_sha256: String,
    pub output_file_name: String,
    pub camera_time_source: Option<String>,
    pub camera_local_time: Option<String>,
    pub corrected_timestamp_utc: Option<DateTime<Utc>>,
    pub camera_model: Option<String>,
    pub existing_raw_gps: bool,
    pub confidence: Confidence,
    pub bracket: Option<TrackBracket>,
    pub location: Option<PlannedLocation>,
    pub site_json_path: Option<PathBuf>,
    pub site_json_sha256: Option<String>,
    pub site_mapping: String,
    pub existing_site_location: bool,
    pub eligible: bool,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewParameters {
    pub track: PathBuf,
    pub raw_dir: PathBuf,
    pub site_repo: PathBuf,
    pub album: PathBuf,
    pub timezone: String,
    pub clock_offset: String,
    pub include_low_confidence: bool,
    pub overwrite_existing: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecutionPlan {
    pub schema: String,
    pub created_at: DateTime<Utc>,
    pub tool_version: String,
    pub exiftool_version: String,
    pub parameters: PreviewParameters,
    pub track_sha256: String,
    pub photos: Vec<PhotoPlan>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewSummary {
    pub total_raw_files: usize,
    pub high_confidence: usize,
    pub low_confidence: usize,
    pub unmatched: usize,
    pub conflicts: usize,
    pub existing_locations: usize,
    pub eligible: usize,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplyLog {
    pub schema: String,
    pub started_at: DateTime<Utc>,
    pub finished_at: DateTime<Utc>,
    pub plan_path: PathBuf,
    pub raw_out: PathBuf,
    pub raw_outputs: Vec<PathBuf>,
    pub site_files_updated: Vec<PathBuf>,
    pub site_transaction_succeeded: Option<bool>,
    pub warnings: Vec<String>,
}
