use anyhow::{Context, Result, anyhow, bail};
use chrono::{
    DateTime, Duration, FixedOffset, LocalResult, NaiveDateTime, Offset, TimeZone, Timelike, Utc,
};
use chrono_tz::Tz;

use crate::models::ExifMetadata;

#[derive(Debug, Clone)]
pub struct CameraTimestamp {
    pub source: String,
    pub original: String,
    pub local_naive: NaiveDateTime,
    pub utc: DateTime<Utc>,
    pub warnings: Vec<String>,
}

pub fn parse_camera_timestamp(
    metadata: &ExifMetadata,
    timezone: Tz,
    clock_offset: Duration,
) -> Result<Option<CameraTimestamp>> {
    let (source, raw) = if let Some(value) = metadata.sub_sec_date_time_original.as_deref() {
        ("SubSecDateTimeOriginal", value)
    } else if let Some(value) = metadata.date_time_original.as_deref() {
        ("DateTimeOriginal", value)
    } else if let Some(value) = metadata.create_date.as_deref() {
        ("CreateDate", value)
    } else {
        return Ok(None);
    };
    let (local, inline_offset) =
        parse_exif_local(raw).with_context(|| format!("invalid {source} value {raw:?}"))?;
    let corrected = local + clock_offset;
    let explicit_offset = inline_offset.or_else(|| {
        metadata
            .offset_time_original
            .as_deref()
            .and_then(|value| parse_fixed_offset(value).ok())
    });
    let mut warnings = Vec::new();
    let utc = if let Some(offset) = explicit_offset {
        offset
            .from_local_datetime(&corrected)
            .single()
            .ok_or_else(|| anyhow!("camera timestamp is invalid for fixed offset"))?
            .with_timezone(&Utc)
    } else {
        match timezone.from_local_datetime(&corrected) {
            LocalResult::Single(value) => value.with_timezone(&Utc),
            LocalResult::Ambiguous(first, second) => {
                warnings.push(format!(
                    "ambiguous local time in {timezone}; chose earlier UTC candidate over {}",
                    second.with_timezone(&Utc)
                ));
                first.with_timezone(&Utc)
            }
            LocalResult::None => {
                bail!("camera timestamp falls in a timezone transition gap for {timezone}")
            }
        }
    };
    Ok(Some(CameraTimestamp {
        source: source.into(),
        original: raw.into(),
        local_naive: local,
        utc,
        warnings,
    }))
}

pub fn parse_clock_offset(value: &str) -> Result<Duration> {
    if value.len() != 9
        || !value.is_ascii()
        || !matches!(&value[0..1], "+" | "-")
        || &value[3..4] != ":"
        || &value[6..7] != ":"
    {
        bail!("clock offset must use +HH:MM:SS or -HH:MM:SS");
    }
    let hours: i64 = value[1..3].parse()?;
    let minutes: i64 = value[4..6].parse()?;
    let seconds: i64 = value[7..9].parse()?;
    if minutes > 59 || seconds > 59 {
        bail!("clock offset minutes and seconds must be <= 59");
    }
    let sign = if value.starts_with('-') { -1 } else { 1 };
    Ok(Duration::seconds(
        sign * (hours * 3600 + minutes * 60 + seconds),
    ))
}

pub fn parse_exif_local(value: &str) -> Result<(NaiveDateTime, Option<FixedOffset>)> {
    let value = value.trim();
    if value.len() < 19 || !value.is_ascii() {
        bail!("EXIF timestamp must contain at least 19 ASCII characters");
    }
    let base = &value[..19];
    let mut local = NaiveDateTime::parse_from_str(base, "%Y:%m:%d %H:%M:%S")?;
    let suffix = &value[19..];
    let offset_index = suffix
        .char_indices()
        .find(|(_, character)| matches!(character, '+' | '-' | 'Z'))
        .map(|(index, _)| index);
    let fraction = &suffix[..offset_index.unwrap_or(suffix.len())];
    if !fraction.is_empty() {
        let digits = fraction
            .strip_prefix('.')
            .filter(|digits| {
                !digits.is_empty() && digits.chars().all(|value| value.is_ascii_digit())
            })
            .ok_or_else(|| anyhow!("invalid EXIF subsecond suffix"))?;
        let nanos: u32 = format!("{digits:0<9}")[..9].parse()?;
        local = local
            .with_nanosecond(nanos)
            .ok_or_else(|| anyhow!("invalid subsecond"))?;
    }
    let offset = match offset_index {
        Some(index) if &suffix[index..] == "Z" => Some(Utc.fix()),
        Some(index) => Some(parse_fixed_offset(&suffix[index..])?),
        None => None,
    };
    Ok((local, offset))
}

pub fn parse_fixed_offset(value: &str) -> Result<FixedOffset> {
    if value == "Z" {
        return Ok(Utc.fix());
    }
    if value.len() != 6
        || !value.is_ascii()
        || !matches!(&value[0..1], "+" | "-")
        || &value[3..4] != ":"
    {
        bail!("invalid UTC offset {value:?}");
    }
    let hours: i32 = value[1..3].parse()?;
    let minutes: i32 = value[4..6].parse()?;
    if hours > 23 || minutes > 59 {
        bail!("invalid UTC offset {value:?}");
    }
    let seconds = hours * 3600 + minutes * 60;
    if value.starts_with('-') {
        FixedOffset::west_opt(seconds).ok_or_else(|| anyhow!("invalid UTC offset"))
    } else {
        FixedOffset::east_opt(seconds).ok_or_else(|| anyhow!("invalid UTC offset"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_subseconds_offsets_and_clock_correction() {
        let metadata = ExifMetadata {
            source_file: "a.NEF".into(),
            sub_sec_date_time_original: Some("2026:08:08 12:00:00.250+08:00".into()),
            date_time_original: None,
            offset_time_original: None,
            create_date: None,
            camera_model: None,
            gps_latitude: None,
            gps_longitude: None,
            gps_altitude: None,
            gps_altitude_ref: None,
            country: None,
            state: None,
            city: None,
            location: None,
            gps_horizontal_error: None,
            gps_date_time: None,
        };
        let parsed = parse_camera_timestamp(
            &metadata,
            chrono_tz::Asia::Shanghai,
            parse_clock_offset("+00:00:01").unwrap(),
        )
        .unwrap()
        .unwrap();
        assert_eq!(parsed.utc.to_rfc3339(), "2026-08-08T04:00:01.250+00:00");
    }

    #[test]
    fn parses_positive_and_negative_clock_offsets() {
        assert_eq!(parse_clock_offset("+01:02:03").unwrap().num_seconds(), 3723);
        assert_eq!(parse_clock_offset("-00:00:05").unwrap().num_seconds(), -5);
        assert!(parse_clock_offset("01:02:03").is_err());
        assert!(parse_clock_offset("时区偏移无效").is_err());
    }

    #[test]
    fn rejects_invalid_timestamp_suffixes_and_timezone_gaps() {
        assert!(parse_exif_local("2026:08:08 12:00:00.bad").is_err());
        assert!(parse_exif_local("2026:08:08 12:00:00.").is_err());
        assert!(parse_exif_local("日期完全无效且长度超过十九字节").is_err());
        assert!(parse_fixed_offset("时区无效").is_err());

        let metadata = ExifMetadata {
            source_file: "a.NEF".into(),
            date_time_original: Some("2026:03:08 02:30:00".into()),
            sub_sec_date_time_original: None,
            offset_time_original: None,
            create_date: None,
            camera_model: None,
            gps_latitude: None,
            gps_longitude: None,
            gps_altitude: None,
            gps_altitude_ref: None,
            country: None,
            state: None,
            city: None,
            location: None,
            gps_horizontal_error: None,
            gps_date_time: None,
        };
        assert!(
            parse_camera_timestamp(&metadata, chrono_tz::America::New_York, Duration::zero())
                .is_err()
        );
    }

    #[test]
    fn daylight_saving_ambiguity_is_explicitly_warned() {
        let mut metadata = ExifMetadata {
            source_file: "a.NEF".into(),
            sub_sec_date_time_original: None,
            date_time_original: Some("2026:11:01 01:30:00".into()),
            offset_time_original: None,
            create_date: None,
            camera_model: None,
            gps_latitude: None,
            gps_longitude: None,
            gps_altitude: None,
            gps_altitude_ref: None,
            country: None,
            state: None,
            city: None,
            location: None,
            gps_horizontal_error: None,
            gps_date_time: None,
        };
        let parsed =
            parse_camera_timestamp(&metadata, chrono_tz::America::New_York, Duration::zero())
                .unwrap()
                .unwrap();
        assert_eq!(parsed.warnings.len(), 1);
        metadata.offset_time_original = Some("-05:00".into());
        let explicit =
            parse_camera_timestamp(&metadata, chrono_tz::America::New_York, Duration::zero())
                .unwrap()
                .unwrap();
        assert!(explicit.warnings.is_empty());
    }
}
