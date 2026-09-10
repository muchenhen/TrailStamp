use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, anyhow, bail};
use chrono::{DateTime, NaiveDateTime};
use serde_json::{Map, Value};
use tempfile::NamedTempFile;
use walkdir::WalkDir;

use crate::models::PlannedLocation;

#[derive(Debug, Clone)]
pub struct SitePhoto {
    pub path: PathBuf,
    pub stem: String,
    pub local_time: Option<NaiveDateTime>,
    pub gps: Option<(f64, f64)>,
}

#[derive(Debug, Clone)]
pub struct SiteMatch {
    pub path: Option<PathBuf>,
    pub method: String,
    pub existing_gps: Option<(f64, f64)>,
    pub conflict: bool,
}

pub fn resolve_album(site_repo: &Path, album: &Path) -> Result<PathBuf> {
    let site_repo = dunce::canonicalize(site_repo)
        .with_context(|| format!("site repository does not exist: {}", site_repo.display()))?;
    let photos_root = dunce::canonicalize(site_repo.join("content").join("photos"))
        .context("site repository has no content/photos directory")?;
    let candidate = if album.is_absolute() {
        album.to_path_buf()
    } else {
        photos_root.join(album)
    };
    let resolved = dunce::canonicalize(&candidate)
        .with_context(|| format!("album directory does not exist: {}", candidate.display()))?;
    if !resolved.starts_with(&photos_root) || resolved == photos_root {
        bail!("--album must identify one directory below content/photos");
    }
    if !resolved.is_dir() {
        bail!("album is not a directory: {}", resolved.display());
    }
    Ok(resolved)
}

pub fn load_album(album: &Path) -> Result<Vec<SitePhoto>> {
    let mut photos = Vec::new();
    for entry in fs::read_dir(album)? {
        let entry = entry?;
        let path = entry.path();
        if !path.is_file()
            || !path
                .extension()
                .is_some_and(|value| value.eq_ignore_ascii_case("json"))
        {
            continue;
        }
        let value: Value = serde_json::from_slice(&fs::read(&path)?)
            .with_context(|| format!("invalid site JSON {}", path.display()))?;
        let local_time = value
            .pointer("/exif/create_date")
            .and_then(Value::as_str)
            .and_then(parse_site_time)
            .or_else(|| {
                value
                    .get("date")
                    .and_then(Value::as_str)
                    .and_then(parse_site_time)
            });
        let gps = existing_gps(&value);
        photos.push(SitePhoto {
            stem: path
                .file_stem()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned(),
            path: dunce::canonicalize(&path)?,
            local_time,
            gps,
        });
    }
    photos.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(photos)
}

pub fn match_site_photo(
    raw_stem: &str,
    camera_local_time: Option<NaiveDateTime>,
    photos: &[SitePhoto],
) -> SiteMatch {
    let exact: Vec<_> = photos
        .iter()
        .filter(|photo| photo.stem.eq_ignore_ascii_case(raw_stem))
        .collect();
    if exact.len() == 1 {
        return matched(exact[0], "exact RAW stem");
    }
    if exact.len() > 1 {
        return conflict(format!("conflict: {} exact stem candidates", exact.len()));
    }
    let suffix = format!("-{raw_stem}");
    let suffix_matches: Vec<_> = photos
        .iter()
        .filter(|photo| {
            photo
                .stem
                .to_ascii_lowercase()
                .ends_with(&suffix.to_ascii_lowercase())
        })
        .collect();
    if suffix_matches.len() == 1 {
        return matched(suffix_matches[0], "site stem ends with RAW stem");
    }
    if suffix_matches.len() > 1 {
        return conflict(format!(
            "conflict: {} suffix stem candidates",
            suffix_matches.len()
        ));
    }
    if let Some(camera_time) = camera_local_time {
        let time_matches: Vec<_> = photos
            .iter()
            .filter(|photo| {
                photo.local_time.is_some_and(|value| {
                    (value - camera_time).num_milliseconds().unsigned_abs() <= 1_000
                })
            })
            .collect();
        if time_matches.len() == 1 {
            return matched(time_matches[0], "site timestamp within 1 second");
        }
        if time_matches.len() > 1 {
            return conflict(format!(
                "conflict: {} timestamp candidates",
                time_matches.len()
            ));
        }
    }
    conflict("conflict: no site JSON candidate".into())
}

pub fn apply_location(path: &Path, planned: &PlannedLocation, overwrite: bool) -> Result<bool> {
    let bytes = fs::read(path)?;
    let mut root: Value = serde_json::from_slice(&bytes)?;
    let root_object = root
        .as_object_mut()
        .ok_or_else(|| anyhow!("site JSON root is not an object"))?;
    let location = root_object
        .entry("location")
        .or_insert_with(|| Value::Object(Map::new()))
        .as_object_mut()
        .ok_or_else(|| anyhow!("site location field is not an object"))?;
    let mut changed = false;
    let current_gps = location.get("gps").and_then(value_gps);
    if let Some((latitude, longitude)) = current_gps {
        let same = (latitude - planned.latitude).abs() <= 0.000_001
            && (longitude - planned.longitude).abs() <= 0.000_001;
        if !same && !overwrite {
            bail!("site JSON already contains different GPS coordinates");
        }
        if !same {
            location.insert("gps".into(), gps_value(planned.latitude, planned.longitude));
            changed = true;
        }
    } else {
        location.insert("gps".into(), gps_value(planned.latitude, planned.longitude));
        changed = true;
    }
    if let Some(admin) = &planned.admin {
        changed |= set_string(location, "name", &admin.name, overwrite, true);
        changed |= set_string(location, "city", &admin.city, overwrite, false);
        changed |= set_string(location, "country", &admin.country, overwrite, false);
        changed |= set_string(location, "province", &admin.province, overwrite, false);
        changed |= set_string(location, "district", &admin.district, overwrite, false);
    }
    if changed {
        atomic_write_json(path, &root)?;
    }
    Ok(changed)
}

pub fn atomic_write_json(path: &Path, value: &Value) -> Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| anyhow!("JSON path has no parent"))?;
    let mut temporary = NamedTempFile::new_in(parent)?;
    serde_json::to_writer_pretty(&mut temporary, value)?;
    temporary.write_all(b"\n")?;
    temporary.as_file_mut().sync_all()?;
    temporary.persist(path).map_err(|error| error.error)?;
    #[cfg(not(windows))]
    {
        let directory = fs::File::open(parent)?;
        directory.sync_all()?;
    }
    Ok(())
}

pub fn list_photo_json(site_repo: &Path) -> Result<Vec<PathBuf>> {
    let root = site_repo.join("content").join("photos");
    Ok(WalkDir::new(root)
        .into_iter()
        .collect::<std::result::Result<Vec<_>, _>>()?
        .into_iter()
        .filter(|entry| {
            entry.file_type().is_file()
                && entry
                    .path()
                    .extension()
                    .is_some_and(|value| value.eq_ignore_ascii_case("json"))
        })
        .map(|entry| entry.into_path())
        .collect())
}

fn matched(photo: &SitePhoto, method: &str) -> SiteMatch {
    SiteMatch {
        path: Some(photo.path.clone()),
        method: method.into(),
        existing_gps: photo.gps,
        conflict: false,
    }
}

fn conflict(method: String) -> SiteMatch {
    SiteMatch {
        path: None,
        method,
        existing_gps: None,
        conflict: true,
    }
}

fn parse_site_time(value: &str) -> Option<NaiveDateTime> {
    let normalized = if value.len() >= 19 && value.as_bytes().get(4) == Some(&b':') {
        format!(
            "{}-{}-{}",
            value.get(..4)?,
            value.get(5..7)?,
            value.get(8..)?
        )
    } else {
        value.to_owned()
    };
    if let Ok(parsed) = DateTime::parse_from_rfc3339(&normalized) {
        return Some(parsed.naive_local());
    }
    let prefix = normalized.get(..normalized.len().min(19))?;
    NaiveDateTime::parse_from_str(prefix, "%Y-%m-%d %H:%M:%S")
        .ok()
        .or_else(|| NaiveDateTime::parse_from_str(prefix, "%Y-%m-%dT%H:%M:%S").ok())
}

fn existing_gps(value: &Value) -> Option<(f64, f64)> {
    value.pointer("/location/gps").and_then(value_gps)
}

fn value_gps(value: &Value) -> Option<(f64, f64)> {
    let latitude = value.get("latitude")?.as_f64()?;
    let longitude = value.get("longitude")?.as_f64()?;
    (latitude.is_finite()
        && longitude.is_finite()
        && (-90.0..=90.0).contains(&latitude)
        && (-180.0..=180.0).contains(&longitude))
    .then_some((latitude, longitude))
}

fn gps_value(latitude: f64, longitude: f64) -> Value {
    serde_json::json!({ "latitude": latitude, "longitude": longitude })
}

fn set_string(
    location: &mut Map<String, Value>,
    key: &str,
    value: &str,
    overwrite: bool,
    allow_empty: bool,
) -> bool {
    if value.is_empty() && !allow_empty {
        return false;
    }
    let existing = location
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or_default();
    if existing.is_empty() || (overwrite && existing != value) {
        location.insert(key.into(), Value::String(value.into()));
        true
    } else {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{Admin, PlannedLocation};
    use chrono::TimeZone;

    #[test]
    fn maps_exact_suffix_and_time_and_rejects_multiple_candidates() {
        let time =
            NaiveDateTime::parse_from_str("2026-08-08 12:00:00", "%Y-%m-%d %H:%M:%S").unwrap();
        let photos = vec![
            SitePhoto {
                path: "20260808-DSC_0001.json".into(),
                stem: "20260808-DSC_0001".into(),
                local_time: Some(time),
                gps: None,
            },
            SitePhoto {
                path: "other.json".into(),
                stem: "other".into(),
                local_time: Some(time + chrono::Duration::seconds(5)),
                gps: None,
            },
        ];
        assert_eq!(
            match_site_photo("DSC_0001", None, &photos).path.as_deref(),
            Some(Path::new("20260808-DSC_0001.json"))
        );
        assert_eq!(
            match_site_photo("UNKNOWN", Some(time), &photos)
                .path
                .as_deref(),
            Some(Path::new("20260808-DSC_0001.json"))
        );
        let duplicated = vec![photos[0].clone(), photos[0].clone()];
        assert!(match_site_photo("DSC_0001", None, &duplicated).conflict);
        assert_eq!(parse_site_time("日期完全无效且长度足够触发前缀解析"), None);
    }

    #[test]
    fn atomically_fills_missing_location_and_preserves_existing_fields() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("photo.json");
        fs::write(
            &path,
            "{\"title\":\"keep\",\"location\":{\"city\":\"已有城市\"}}\n",
        )
        .unwrap();
        let planned = PlannedLocation {
            latitude: 31.0,
            longitude: 121.0,
            altitude_meters: None,
            horizontal_error_meters: Some(10.0),
            gps_timestamp_utc: chrono::Utc.with_ymd_and_hms(2026, 8, 8, 0, 0, 0).unwrap(),
            admin: Some(Admin {
                country: "中国".into(),
                province: "上海市".into(),
                city: "上海市".into(),
                district: "徐汇区".into(),
                name: "".into(),
                ..Default::default()
            }),
        };
        assert!(apply_location(&path, &planned, false).unwrap());
        let value: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(value["title"], "keep");
        assert_eq!(value["location"]["city"], "已有城市");
        assert_eq!(value["location"]["gps"]["latitude"], 31.0);
        assert_eq!(value["location"]["district"], "徐汇区");
    }

    #[test]
    fn refuses_different_existing_gps_without_changing_the_file() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("photo.json");
        let original = b"{\"location\":{\"gps\":{\"latitude\":30.0,\"longitude\":120.0}}}\n";
        fs::write(&path, original).unwrap();
        let planned = PlannedLocation {
            latitude: 31.0,
            longitude: 121.0,
            altitude_meters: None,
            horizontal_error_meters: Some(10.0),
            gps_timestamp_utc: chrono::Utc.with_ymd_and_hms(2026, 8, 8, 0, 0, 0).unwrap(),
            admin: None,
        };
        assert!(apply_location(&path, &planned, false).is_err());
        assert_eq!(fs::read(&path).unwrap(), original);
        assert!(apply_location(&path, &planned, true).unwrap());
        let value: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(value["location"]["gps"]["latitude"], 31.0);
        assert_eq!(value["location"]["gps"]["longitude"], 121.0);
    }
}
