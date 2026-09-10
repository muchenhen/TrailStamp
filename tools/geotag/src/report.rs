use std::{fs, path::Path};

use anyhow::Result;

use crate::models::{Confidence, ExecutionPlan, PreviewSummary};

pub fn summarize(plan: &ExecutionPlan) -> PreviewSummary {
    let mut summary = PreviewSummary {
        total_raw_files: plan.photos.len(),
        ..Default::default()
    };
    for photo in &plan.photos {
        match photo.confidence {
            Confidence::High => summary.high_confidence += 1,
            Confidence::Low => summary.low_confidence += 1,
            Confidence::Unmatched => summary.unmatched += 1,
        }
        if photo.site_mapping.starts_with("conflict:") {
            summary.conflicts += 1;
        }
        if photo.existing_raw_gps || photo.existing_site_location {
            summary.existing_locations += 1;
        }
        if photo.eligible {
            summary.eligible += 1;
        }
    }
    summary
}

pub fn write_report(directory: &Path, plan: &ExecutionPlan) -> Result<PreviewSummary> {
    fs::create_dir_all(directory)?;
    let plan_json = serde_json::to_string_pretty(plan)? + "\n";
    fs::write(directory.join("plan.json"), plan_json)?;
    let summary = summarize(plan);
    fs::write(
        directory.join("summary.json"),
        serde_json::to_string_pretty(&summary)? + "\n",
    )?;
    fs::write(directory.join("report.html"), render_html(plan, &summary))?;
    Ok(summary)
}

fn render_html(plan: &ExecutionPlan, summary: &PreviewSummary) -> String {
    let mut rows = String::new();
    for photo in &plan.photos {
        let location = photo.location.as_ref();
        let warnings = photo.warnings.join("; ");
        let class = match photo.confidence {
            Confidence::High => "high",
            Confidence::Low => "low",
            Confidence::Unmatched => "none",
        };
        rows.push_str(&format!(
            "<tr class=\"{class}\"><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>\n",
            escape(&photo.output_file_name),
            escape(photo.camera_local_time.as_deref().unwrap_or("—")),
            escape(&photo.corrected_timestamp_utc.map(|value| value.to_rfc3339()).unwrap_or_else(|| "—".into())),
            escape(&format!("{:?}", photo.confidence).to_lowercase()),
            escape(&location.map(|value| format!("{:.7}, {:.7}", value.latitude, value.longitude)).unwrap_or_else(|| "—".into())),
            escape(&location.and_then(|value| value.horizontal_error_meters).map(|value| format!("{value:.1} m")).unwrap_or_else(|| "—".into())),
            escape(photo.site_json_path.as_ref().map(|value| value.display().to_string()).as_deref().unwrap_or(&photo.site_mapping)),
            escape(&warnings),
        ));
    }
    format!(
        r#"<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>GPSLog 照片地理标记预览</title><style>
:root{{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;color:#152019;background:#f5faf6}}body{{margin:0;padding:2rem}}h1{{margin-top:0}}.summary{{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:.75rem;margin:1.5rem 0}}.metric{{background:white;border:1px solid #d8e5dc;border-radius:12px;padding:1rem}}.metric b{{font-size:1.6rem;display:block}}table{{width:100%;border-collapse:collapse;background:white;font-size:.88rem}}th,td{{border:1px solid #d8e5dc;padding:.55rem;text-align:left;vertical-align:top}}th{{position:sticky;top:0;background:#e9f5ed}}tr.low{{background:#fff8dd}}tr.none{{background:#fbe8e8}}code{{word-break:break-all}}.note{{color:#56635b}}</style></head>
<body><h1>GPSLog 照片地理标记预览</h1><p class="note">生成于 {} · ExifTool {} · 预览不会修改 RAW 或网站。</p>
<div class="summary"><div class="metric"><b>{}</b>RAW</div><div class="metric"><b>{}</b>高置信度</div><div class="metric"><b>{}</b>低置信度</div><div class="metric"><b>{}</b>未匹配</div><div class="metric"><b>{}</b>冲突</div><div class="metric"><b>{}</b>可执行</div></div>
<table><thead><tr><th>RAW</th><th>相机时间</th><th>修正 UTC</th><th>置信度</th><th>坐标</th><th>精度</th><th>网站 JSON</th><th>警告</th></tr></thead><tbody>{rows}</tbody></table>
<script>document.querySelectorAll('th').forEach((th,i)=>th.addEventListener('click',()=>{{const b=th.closest('table').tBodies[0];[...b.rows].sort((a,c)=>a.cells[i].innerText.localeCompare(c.cells[i].innerText)).forEach(r=>b.appendChild(r))}}))</script></body></html>"#,
        plan.created_at.to_rfc3339(),
        plan.exiftool_version,
        summary.total_raw_files,
        summary.high_confidence,
        summary.low_confidence,
        summary.unmatched,
        summary.conflicts,
        summary.eligible,
    )
}

fn escape(value: &str) -> String {
    html_escape::encode_text(value).into_owned()
}

#[cfg(test)]
mod tests {
    use chrono::TimeZone;

    use super::*;
    use crate::models::PreviewParameters;

    #[test]
    fn identical_plan_produces_identical_report_files() {
        let plan = ExecutionPlan {
            schema: "gpslog.geotag-plan/v1".into(),
            created_at: chrono::Utc.with_ymd_and_hms(2026, 8, 8, 0, 0, 0).unwrap(),
            tool_version: "1.0.0".into(),
            exiftool_version: "13.59".into(),
            parameters: PreviewParameters {
                track: "track.json".into(),
                raw_dir: "raw".into(),
                site_repo: "site".into(),
                album: "album".into(),
                timezone: "Asia/Shanghai".into(),
                clock_offset: "+00:00:00".into(),
                include_low_confidence: false,
                overwrite_existing: false,
            },
            track_sha256: "00".repeat(32),
            photos: Vec::new(),
        };
        let first = tempfile::tempdir().unwrap();
        let second = tempfile::tempdir().unwrap();
        write_report(first.path(), &plan).unwrap();
        write_report(second.path(), &plan).unwrap();
        for file in ["plan.json", "summary.json", "report.html"] {
            assert_eq!(
                fs::read(first.path().join(file)).unwrap(),
                fs::read(second.path().join(file)).unwrap(),
                "{file} was not deterministic"
            );
        }
    }
}
