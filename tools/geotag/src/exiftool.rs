use std::{
    fs,
    io::{self, Cursor, Write},
    path::{Path, PathBuf},
    process::{Command, Output},
};

use anyhow::{Context, Result, anyhow, bail};
use chrono::{Datelike, TimeZone, Timelike, Utc};
use sha2::{Digest, Sha256};
use tempfile::NamedTempFile;
use walkdir::WalkDir;
use zip::ZipArchive;

use crate::{
    models::{ExifMetadata, PlannedLocation},
    timeutil::parse_exif_local,
};

pub const EXIFTOOL_VERSION: &str = "13.59";
pub const EXIFTOOL_ARCHIVE_SHA256: &str =
    "44b512b25af500724ba579d0a53c8fc5851628b692dd5e5d94ae4a15c2cba9ec";
const ARCHIVE: &[u8] = include_bytes!("../vendor/exiftool-13.59_64.zip");

pub fn ensure_exiftool() -> Result<PathBuf> {
    if !cfg!(windows) {
        bail!("the bundled ExifTool executable supports Windows only");
    }
    let actual = hex::encode(Sha256::digest(ARCHIVE));
    if actual != EXIFTOOL_ARCHIVE_SHA256 {
        bail!("embedded ExifTool archive hash mismatch");
    }
    let local_app_data = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .ok_or_else(|| anyhow!("LOCALAPPDATA is not set"))?;
    let cache = local_app_data.join("GPSLog").join("exiftool").join(format!(
        "{}-{}",
        EXIFTOOL_VERSION,
        &actual[..12]
    ));
    let marker = cache.join(".complete");
    if marker.exists() {
        return find_executable(&cache);
    }
    fs::create_dir_all(&cache)?;
    let mut archive =
        ZipArchive::new(Cursor::new(ARCHIVE)).context("invalid embedded ExifTool archive")?;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index)?;
        let Some(relative) = entry.enclosed_name() else {
            bail!("unsafe path in embedded ExifTool archive");
        };
        let output = cache.join(relative);
        if entry.is_dir() {
            fs::create_dir_all(&output)?;
        } else {
            if let Some(parent) = output.parent() {
                fs::create_dir_all(parent)?;
            }
            let mut file = fs::File::create(&output)?;
            io::copy(&mut entry, &mut file)?;
            file.sync_all()?;
        }
    }
    let source = find_k_executable(&cache)?;
    let destination = source.with_file_name("exiftool.exe");
    if !destination.exists() {
        fs::rename(&source, &destination)?;
    }
    let mut complete = fs::File::create(&marker)?;
    writeln!(complete, "version={EXIFTOOL_VERSION}")?;
    writeln!(complete, "sha256={EXIFTOOL_ARCHIVE_SHA256}")?;
    complete.sync_all()?;
    Ok(destination)
}

pub fn version(executable: &Path) -> Result<String> {
    let output = command(executable).arg("-ver").output()?;
    checked_stdout(output, "read ExifTool version").map(|value| value.trim().to_owned())
}

pub fn read_metadata(executable: &Path, raw_files: &[PathBuf]) -> Result<Vec<ExifMetadata>> {
    let mut result = Vec::new();
    for batch in raw_files.chunks(40) {
        let mut arguments = vec![
            "-json",
            "-n",
            "-api",
            "LargeFileSupport=1",
            "-SubSecDateTimeOriginal",
            "-DateTimeOriginal",
            "-OffsetTimeOriginal",
            "-CreateDate",
            "-Model",
            "-GPSLatitude",
            "-GPSLongitude",
            "-GPSAltitude",
            "-GPSAltitudeRef",
            "-GPSHPositioningError",
            "-GPSDateTime",
            "-XMP-photoshop:Country",
            "-XMP-photoshop:State",
            "-XMP-photoshop:City",
            "-XMP-iptcCore:Location",
        ]
        .into_iter()
        .map(str::to_owned)
        .collect::<Vec<_>>();
        for path in batch {
            arguments.push(path.to_string_lossy().into_owned());
        }
        let output = run_with_utf8_argfile(executable, &arguments)?;
        let stdout = checked_stdout(output, "read RAW metadata")?;
        let mut parsed: Vec<ExifMetadata> =
            serde_json::from_str(&stdout).context("invalid ExifTool JSON output")?;
        result.append(&mut parsed);
    }
    Ok(result)
}

pub fn write_location(executable: &Path, target: &Path, location: &PlannedLocation) -> Result<()> {
    let latitude_ref = if location.latitude < 0.0 { "S" } else { "N" };
    let longitude_ref = if location.longitude < 0.0 { "W" } else { "E" };
    let utc = location.gps_timestamp_utc;
    let mut arguments = vec![
        "-overwrite_original".to_owned(),
        "-P".to_owned(),
        format!("-GPSLatitude={:.9}", location.latitude.abs()),
        format!("-GPSLatitudeRef={latitude_ref}"),
        format!("-GPSLongitude={:.9}", location.longitude.abs()),
        format!("-GPSLongitudeRef={longitude_ref}"),
        format!(
            "-GPSDateStamp={:04}:{:02}:{:02}",
            utc.year(),
            utc.month(),
            utc.day()
        ),
        format!(
            "-GPSTimeStamp={:02}:{:02}:{:02}.{:03}",
            utc.hour(),
            utc.minute(),
            utc.second(),
            utc.timestamp_subsec_millis()
        ),
    ];
    if let Some(altitude) = location.altitude_meters {
        arguments.push(format!("-GPSAltitude={:.3}", altitude.abs()));
        arguments.push(format!(
            "-GPSAltitudeRef={}",
            if altitude < 0.0 {
                "Below Sea Level"
            } else {
                "Above Sea Level"
            }
        ));
    }
    if let Some(error) = location.horizontal_error_meters {
        arguments.push(format!("-GPSHPositioningError={error:.3}"));
    }
    if let Some(admin) = &location.admin {
        if !admin.country.is_empty() {
            arguments.push(format!("-XMP-photoshop:Country={}", admin.country));
        }
        if !admin.province.is_empty() {
            arguments.push(format!("-XMP-photoshop:State={}", admin.province));
        }
        if !admin.city.is_empty() {
            arguments.push(format!("-XMP-photoshop:City={}", admin.city));
        }
        let sublocation = if !admin.name.is_empty() {
            &admin.name
        } else {
            &admin.district
        };
        if !sublocation.is_empty() {
            arguments.push(format!("-XMP-iptcCore:Location={sublocation}"));
        }
    }
    arguments.push(target.to_string_lossy().into_owned());
    let output = run_with_utf8_argfile(executable, &arguments)?;
    checked_stdout(output, "write RAW metadata")?;
    Ok(())
}

pub fn verify_location(executable: &Path, target: &Path, expected: &PlannedLocation) -> Result<()> {
    let metadata = read_metadata(executable, &[target.to_path_buf()])?
        .into_iter()
        .next()
        .ok_or_else(|| anyhow!("ExifTool returned no verification record"))?;
    let latitude = metadata
        .gps_latitude
        .ok_or_else(|| anyhow!("GPSLatitude missing after write"))?;
    let longitude = metadata
        .gps_longitude
        .ok_or_else(|| anyhow!("GPSLongitude missing after write"))?;
    if (latitude - expected.latitude).abs() > 0.000_001
        || (longitude - expected.longitude).abs() > 0.000_001
    {
        bail!(
            "GPS verification mismatch: expected {}, {}, got {latitude}, {longitude}",
            expected.latitude,
            expected.longitude
        );
    }
    if let Some(expected_altitude) = expected.altitude_meters {
        let actual_altitude = metadata
            .gps_altitude
            .ok_or_else(|| anyhow!("GPS altitude missing after write"))?;
        let signed_altitude = if metadata.gps_altitude_ref == Some(1) {
            -actual_altitude.abs()
        } else {
            actual_altitude
        };
        if (expected_altitude - signed_altitude).abs() > 0.2 {
            bail!("GPS altitude verification mismatch");
        }
    }
    if let Some(expected_error) = expected.horizontal_error_meters {
        let actual_error = metadata
            .gps_horizontal_error
            .ok_or_else(|| anyhow!("GPS horizontal error missing after write"))?;
        if (expected_error - actual_error).abs() > 0.2 {
            bail!("GPS horizontal error verification mismatch");
        }
    }
    let actual_time = metadata
        .gps_date_time
        .as_deref()
        .ok_or_else(|| anyhow!("GPS UTC date/time missing after write"))?;
    let (actual_naive, actual_offset) = parse_exif_local(actual_time)
        .with_context(|| format!("invalid GPS UTC date/time after write: {actual_time:?}"))?;
    let actual_utc = actual_offset
        .ok_or_else(|| anyhow!("GPS date/time after write has no UTC offset"))?
        .from_local_datetime(&actual_naive)
        .single()
        .ok_or_else(|| anyhow!("GPS date/time after write is invalid"))?
        .with_timezone(&Utc);
    if (actual_utc - expected.gps_timestamp_utc)
        .num_milliseconds()
        .unsigned_abs()
        > 1
    {
        bail!("GPS UTC date/time verification mismatch");
    }
    if let Some(admin) = &expected.admin {
        verify_text("country", &admin.country, metadata.country.as_deref())?;
        verify_text("province/state", &admin.province, metadata.state.as_deref())?;
        verify_text("city", &admin.city, metadata.city.as_deref())?;
        let sublocation = if admin.name.is_empty() {
            &admin.district
        } else {
            &admin.name
        };
        verify_text("sublocation", sublocation, metadata.location.as_deref())?;
    }
    Ok(())
}

fn verify_text(field: &str, expected: &str, actual: Option<&str>) -> Result<()> {
    if !expected.is_empty() && actual != Some(expected) {
        bail!(
            "XMP {field} verification mismatch: expected {expected:?}, got {:?}",
            actual
        );
    }
    Ok(())
}

fn find_executable(root: &Path) -> Result<PathBuf> {
    WalkDir::new(root)
        .into_iter()
        .filter_map(Result::ok)
        .map(|entry| entry.into_path())
        .find(|path| {
            path.file_name()
                .is_some_and(|name| name.eq_ignore_ascii_case("exiftool.exe"))
        })
        .ok_or_else(|| anyhow!("cached ExifTool executable is missing"))
}

fn find_k_executable(root: &Path) -> Result<PathBuf> {
    WalkDir::new(root)
        .into_iter()
        .filter_map(Result::ok)
        .map(|entry| entry.into_path())
        .find(|path| {
            path.file_name()
                .is_some_and(|name| name.eq_ignore_ascii_case("exiftool(-k).exe"))
        })
        .ok_or_else(|| anyhow!("embedded ExifTool executable is missing"))
}

fn checked_stdout(output: Output, action: &str) -> Result<String> {
    if !output.status.success() {
        bail!(
            "failed to {action}: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

fn run_with_utf8_argfile(executable: &Path, arguments: &[String]) -> Result<Output> {
    let directory = executable
        .parent()
        .ok_or_else(|| anyhow!("ExifTool executable has no parent directory"))?;
    let mut argfile = NamedTempFile::new_in(directory)?;
    for argument in arguments {
        if argument.chars().any(|value| matches!(value, '\r' | '\n')) {
            bail!("ExifTool argument contains a line break");
        }
        writeln!(argfile, "{argument}")?;
    }
    argfile.flush()?;
    argfile.as_file_mut().sync_all()?;
    let file_name = argfile
        .path()
        .file_name()
        .ok_or_else(|| anyhow!("ExifTool argument file has no file name"))?;
    let output = command(executable)
        .current_dir(directory)
        .args([
            "-charset",
            "filename=UTF8",
            "-charset",
            "exiftool=UTF8",
            "-@",
        ])
        .arg(file_name)
        .output()?;
    Ok(output)
}

fn command(executable: &Path) -> Command {
    let command = Command::new(executable);
    #[cfg(windows)]
    let command = {
        use std::os::windows::process::CommandExt;
        let mut command = command;
        command.creation_flags(0x0800_0000);
        command
    };
    command
}
