mod apply;
mod exiftool;
mod hashing;
mod models;
mod report;
mod site;
mod timeutil;
mod track;

use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{Context, Result, anyhow, bail};
use chrono::Utc;
use chrono_tz::Tz;
use clap::{Args, Parser, Subcommand};
use serde_json::json;
use walkdir::WalkDir;

use crate::{
    hashing::sha256_file,
    models::{Confidence, ExecutionPlan, PhotoPlan, PreviewParameters},
    timeutil::{parse_camera_timestamp, parse_clock_offset},
};

#[derive(Debug, Parser)]
#[command(
    name = "gpslog-geotag",
    version,
    about = "Safely match GPSLog tracks to Nikon NEF copies and site JSON"
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Debug, Subcommand)]
enum Commands {
    /// Validate a track and optionally match a timestamp, without RAW files or a server.
    Inspect(InspectArgs),
    Doctor(DoctorArgs),
    Preview(PreviewArgs),
    Apply(ApplyArgs),
}

#[derive(Debug, Args)]
struct InspectArgs {
    #[arg(long)]
    track: PathBuf,
    /// RFC 3339 timestamp with an explicit UTC offset, for example 2000-01-01T00:04:00Z.
    #[arg(long)]
    at: Option<String>,
}

#[derive(Debug, Args)]
struct DoctorArgs {
    #[arg(long)]
    track: PathBuf,
    #[arg(long)]
    raw_dir: PathBuf,
    #[arg(long)]
    site_repo: PathBuf,
}

#[derive(Debug, Args)]
struct PreviewArgs {
    #[arg(long)]
    track: PathBuf,
    #[arg(long)]
    raw_dir: PathBuf,
    #[arg(long)]
    site_repo: PathBuf,
    #[arg(long)]
    album: PathBuf,
    #[arg(long, default_value = "Asia/Shanghai")]
    timezone: String,
    #[arg(long, default_value = "+00:00:00", allow_hyphen_values = true)]
    clock_offset: String,
    #[arg(long)]
    report_dir: PathBuf,
    #[arg(long, default_value_t = false)]
    include_low_confidence: bool,
    #[arg(long, default_value_t = false)]
    overwrite_existing: bool,
}

#[derive(Debug, Args)]
struct ApplyArgs {
    #[arg(long)]
    plan: PathBuf,
    #[arg(long)]
    raw_out: PathBuf,
    #[arg(long, default_value_t = false)]
    update_site: bool,
    #[arg(long, default_value_t = false)]
    include_low_confidence: bool,
    #[arg(long, default_value_t = false)]
    overwrite_existing: bool,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("error: {error:#}");
        std::process::exit(1);
    }
}

fn run() -> Result<()> {
    match Cli::parse().command {
        Commands::Inspect(arguments) => inspect(arguments),
        Commands::Doctor(arguments) => doctor(arguments),
        Commands::Preview(arguments) => preview(arguments),
        Commands::Apply(arguments) => {
            let log = apply::run(
                &arguments.plan,
                &arguments.raw_out,
                arguments.update_site,
                arguments.include_low_confidence,
                arguments.overwrite_existing,
            )?;
            println!("{}", serde_json::to_string_pretty(&log)?);
            Ok(())
        }
    }
}

fn inspect(arguments: InspectArgs) -> Result<()> {
    let track_path = canonical_file(&arguments.track, "track")?;
    let track = track::load_track(&track_path)?;
    let usable = track::usable_points(&track);
    let max_gap = usable
        .windows(2)
        .map(|pair| (pair[1].timestamp_utc - pair[0].timestamp_utc).num_milliseconds())
        .max()
        .map(|millis| millis as f64 / 1_000.0);
    let matched = arguments
        .at
        .as_deref()
        .map(|value| {
            let timestamp = chrono::DateTime::parse_from_rfc3339(value)
                .context("--at must be an RFC 3339 timestamp with a UTC offset")?
                .with_timezone(&Utc);
            let result = track::match_timestamp(&track, timestamp);
            Ok::<_, anyhow::Error>(json!({
                "timestampUtc": timestamp,
                "confidence": result.confidence,
                "location": result.location,
                "warnings": result.warnings,
            }))
        })
        .transpose()?;
    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "schema": "gpslog.inspect/v1",
            "points": track.points.len(),
            "usablePoints": usable.len(),
            "sessionGapCount": track.session.gap_count,
            "maxUsableGapSeconds": max_gap,
            "warnings": track::validate_track(&track),
            "match": matched,
        }))?
    );
    if usable.is_empty() {
        bail!("track has no usable points");
    }
    Ok(())
}

fn doctor(arguments: DoctorArgs) -> Result<()> {
    let track_path = canonical_file(&arguments.track, "track")?;
    let raw_dir = canonical_directory(&arguments.raw_dir, "RAW directory")?;
    let site_repo = canonical_directory(&arguments.site_repo, "site repository")?;
    let track = track::load_track(&track_path)?;
    let track_warnings = track::validate_track(&track);
    let usable_points = track::usable_points(&track);
    let usable = usable_points.len();
    let usable_intervals = usable_points
        .windows(2)
        .map(|pair| {
            (pair[1].timestamp_utc - pair[0].timestamp_utc).num_milliseconds() as f64 / 1_000.0
        })
        .collect::<Vec<_>>();
    let max_usable_gap_seconds = usable_intervals.iter().copied().reduce(f64::max);
    let usable_gaps_over_330_seconds = usable_intervals
        .iter()
        .filter(|value| **value > 330.0)
        .count();
    let raw_files = scan_nef(&raw_dir)?;
    let executable = exiftool::ensure_exiftool()?;
    let version = exiftool::version(&executable)?;
    let metadata = if raw_files.is_empty() {
        Vec::new()
    } else {
        exiftool::read_metadata(&executable, &raw_files)?
    };
    let timezone: Tz = track.session.time_zone.parse().with_context(|| {
        format!(
            "invalid track session timeZone: {}",
            track.session.time_zone
        )
    })?;
    let no_capture_time = metadata
        .iter()
        .filter(|value| {
            parse_camera_timestamp(value, timezone, chrono::Duration::zero())
                .ok()
                .flatten()
                .is_none()
        })
        .count();
    let site_status = Command::new("git")
        .args([
            "-C",
            site_repo.to_string_lossy().as_ref(),
            "status",
            "--short",
            "--branch",
        ])
        .output()?;
    let package_json = site_repo.join("package.json");
    let package_scripts_ok = fs::read_to_string(&package_json)
        .ok()
        .and_then(|value| serde_json::from_str::<serde_json::Value>(&value).ok())
        .is_some_and(|value| {
            [
                "locations:backfill",
                "test",
                "typecheck",
                "build",
                "maps:validate",
            ]
            .iter()
            .all(|script| value.pointer(&format!("/scripts/{script}")).is_some())
        });
    let output = json!({
        "schema": "gpslog.doctor/v1",
        "track": {
            "path": track_path,
            "points": track.points.len(),
            "usablePoints": usable,
            "maxUsableGapSeconds": max_usable_gap_seconds,
            "usableGapsOver330Seconds": usable_gaps_over_330_seconds,
            "sessionGapCount": track.session.gap_count,
            "warnings": track_warnings,
        },
        "raw": {
            "directory": raw_dir,
            "nefFiles": raw_files.len(),
            "missingCaptureTime": no_capture_time,
        },
        "exiftool": { "version": version, "archiveSha256": exiftool::EXIFTOOL_ARCHIVE_SHA256 },
        "site": {
            "repository": site_repo,
            "packageScriptsOk": package_scripts_ok,
            "gitStatus": String::from_utf8_lossy(&site_status.stdout).trim(),
        },
    });
    println!("{}", serde_json::to_string_pretty(&output)?);
    if usable == 0 {
        bail!("track has no usable points");
    }
    if !package_scripts_ok {
        bail!("site repository is missing required package scripts");
    }
    Ok(())
}

fn preview(arguments: PreviewArgs) -> Result<()> {
    let timezone: Tz = arguments
        .timezone
        .parse()
        .context("invalid IANA timezone")?;
    let clock_offset = parse_clock_offset(&arguments.clock_offset)?;
    let track_path = canonical_file(&arguments.track, "track")?;
    let raw_dir = canonical_directory(&arguments.raw_dir, "RAW directory")?;
    let site_repo = canonical_directory(&arguments.site_repo, "site repository")?;
    let report_dir = absolute_path(&arguments.report_dir)?;
    let album = site::resolve_album(&site_repo, &arguments.album)?;
    let track = track::load_track(&track_path)?;
    let raw_files = scan_nef(&raw_dir)?;
    if raw_files.is_empty() {
        bail!("RAW directory contains no .NEF files");
    }
    let site_photos = site::load_album(&album)?;
    let executable = exiftool::ensure_exiftool()?;
    let exiftool_version = exiftool::version(&executable)?;
    let metadata = exiftool::read_metadata(&executable, &raw_files)?;
    let mut metadata_by_path = HashMap::new();
    for value in metadata {
        let key = canonical_file(&value.source_file, "ExifTool source")?
            .to_string_lossy()
            .to_ascii_lowercase();
        metadata_by_path.insert(key, value);
    }

    let mut photos = Vec::with_capacity(raw_files.len());
    for raw_path in raw_files {
        let key = raw_path.to_string_lossy().to_ascii_lowercase();
        let metadata = metadata_by_path
            .remove(&key)
            .ok_or_else(|| anyhow!("ExifTool omitted {}", raw_path.display()))?;
        let raw_sha256 = sha256_file(&raw_path)?;
        let mut warnings = Vec::new();
        let camera_time = match parse_camera_timestamp(&metadata, timezone, clock_offset) {
            Ok(value) => value,
            Err(error) => {
                warnings.push(format!("camera time error: {error:#}"));
                None
            }
        };
        if let Some(value) = &camera_time {
            warnings.extend(value.warnings.clone());
        } else {
            warnings.push(
                "RAW has no valid capture timestamp; file modification time was not used".into(),
            );
        }
        let match_result = camera_time
            .as_ref()
            .map(|value| track::match_timestamp(&track, value.utc))
            .unwrap_or_else(|| track::MatchResult {
                confidence: Confidence::Unmatched,
                location: None,
                bracket: None,
                warnings: Vec::new(),
            });
        warnings.extend(match_result.warnings.clone());
        let raw_stem = raw_path
            .file_stem()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned();
        let site_match = site::match_site_photo(
            &raw_stem,
            camera_time.as_ref().map(|value| value.local_naive),
            &site_photos,
        );
        if site_match.conflict {
            warnings.push(site_match.method.clone());
        }
        let existing_raw_gps = metadata.gps_latitude.is_some() || metadata.gps_longitude.is_some();
        let existing_site_location = site_match.existing_gps.is_some();
        let mut conflict = site_match.conflict;
        if existing_raw_gps && !arguments.overwrite_existing {
            conflict = true;
            warnings.push(
                "RAW already contains GPS; use --overwrite-existing only after review".into(),
            );
        }
        if let (Some((latitude, longitude)), Some(planned)) =
            (site_match.existing_gps, match_result.location.as_ref())
        {
            let same = (latitude - planned.latitude).abs() <= 0.000_001
                && (longitude - planned.longitude).abs() <= 0.000_001;
            if !same && !arguments.overwrite_existing {
                conflict = true;
                warnings.push("site JSON already contains different GPS coordinates".into());
            }
        }
        let eligible_confidence = match_result.confidence == Confidence::High
            || (match_result.confidence == Confidence::Low && arguments.include_low_confidence);
        let site_json_sha256 = site_match.path.as_deref().map(sha256_file).transpose()?;
        photos.push(PhotoPlan {
            raw_path: raw_path.clone(),
            raw_sha256,
            output_file_name: raw_path
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned(),
            camera_time_source: camera_time.as_ref().map(|value| value.source.clone()),
            camera_local_time: camera_time.as_ref().map(|value| value.original.clone()),
            corrected_timestamp_utc: camera_time.as_ref().map(|value| value.utc),
            camera_model: metadata.camera_model,
            existing_raw_gps,
            confidence: match_result.confidence,
            bracket: match_result.bracket,
            location: match_result.location,
            site_json_path: site_match.path,
            site_json_sha256,
            site_mapping: site_match.method,
            existing_site_location,
            eligible: eligible_confidence && !conflict,
            warnings,
        });
    }
    mark_duplicate_targets(&mut photos);
    photos.sort_by(|a, b| a.raw_path.cmp(&b.raw_path));

    let plan = ExecutionPlan {
        schema: "gpslog.geotag-plan/v1".into(),
        created_at: Utc::now(),
        tool_version: env!("CARGO_PKG_VERSION").into(),
        exiftool_version,
        parameters: PreviewParameters {
            track: track_path.clone(),
            raw_dir,
            site_repo,
            album,
            timezone: arguments.timezone,
            clock_offset: arguments.clock_offset,
            include_low_confidence: arguments.include_low_confidence,
            overwrite_existing: arguments.overwrite_existing,
        },
        track_sha256: sha256_file(&track_path)?,
        photos,
    };
    let summary = report::write_report(&report_dir, &plan)?;
    println!("{}", serde_json::to_string_pretty(&summary)?);
    Ok(())
}

fn mark_duplicate_targets(photos: &mut [PhotoPlan]) {
    let mut output_counts: HashMap<String, usize> = HashMap::new();
    let mut site_counts: HashMap<PathBuf, usize> = HashMap::new();
    for photo in photos.iter() {
        *output_counts
            .entry(photo.output_file_name.to_ascii_lowercase())
            .or_default() += 1;
        if let Some(path) = &photo.site_json_path {
            *site_counts.entry(path.clone()).or_default() += 1;
        }
    }
    for photo in photos.iter_mut() {
        if output_counts
            .get(&photo.output_file_name.to_ascii_lowercase())
            .copied()
            .unwrap_or(0)
            > 1
        {
            photo.eligible = false;
            photo.site_mapping = "conflict: duplicate RAW output filename".into();
            photo
                .warnings
                .push("multiple input RAW files have the same output filename".into());
        }
        if photo
            .site_json_path
            .as_ref()
            .and_then(|path| site_counts.get(path))
            .copied()
            .unwrap_or(0)
            > 1
        {
            photo.eligible = false;
            photo.site_mapping = "conflict: multiple RAW files map to one site JSON".into();
            photo
                .warnings
                .push("multiple RAW files map to the same site JSON".into());
        }
    }
}

fn scan_nef(directory: &Path) -> Result<Vec<PathBuf>> {
    let mut files = WalkDir::new(directory)
        .follow_links(false)
        .into_iter()
        .collect::<std::result::Result<Vec<_>, _>>()?
        .into_iter()
        .filter(|entry| {
            entry.file_type().is_file()
                && entry
                    .path()
                    .extension()
                    .is_some_and(|value| value.eq_ignore_ascii_case("nef"))
        })
        .map(|entry| dunce::canonicalize(entry.path()))
        .collect::<std::io::Result<Vec<_>>>()?;
    files.sort_by_key(|path| path.to_string_lossy().to_ascii_lowercase());
    Ok(files)
}

fn canonical_file(path: &Path, label: &str) -> Result<PathBuf> {
    let resolved = dunce::canonicalize(path)
        .with_context(|| format!("{label} does not exist: {}", path.display()))?;
    if !resolved.is_file() {
        bail!("{label} is not a file: {}", resolved.display());
    }
    Ok(resolved)
}

fn canonical_directory(path: &Path, label: &str) -> Result<PathBuf> {
    let resolved = dunce::canonicalize(path)
        .with_context(|| format!("{label} does not exist: {}", path.display()))?;
    if !resolved.is_dir() {
        bail!("{label} is not a directory: {}", resolved.display());
    }
    Ok(resolved)
}

fn absolute_path(path: &Path) -> Result<PathBuf> {
    Ok(if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    })
}
