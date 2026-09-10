param(
    [string]$OutputDirectory = "dist"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    & .\gradlew.bat testDebugUnitTest lintDebug assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Android build failed" }
    & cargo fmt --all -- --check
    if ($LASTEXITCODE -ne 0) { throw "Rust formatting check failed" }
    & cargo test --workspace --all-targets
    if ($LASTEXITCODE -ne 0) { throw "Rust tests failed" }
    & cargo clippy --workspace --all-targets -- -D warnings
    if ($LASTEXITCODE -ne 0) { throw "Rust Clippy failed" }
    & cargo build --release --bin gpslog-geotag
    if ($LASTEXITCODE -ne 0) { throw "Rust release build failed" }

    $output = Join-Path $repo $OutputDirectory
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo "app\build\outputs\apk\debug\app-debug.apk") -Destination (Join-Path $output "GPSLog-1.0.0-debug.apk") -Force
    Copy-Item -LiteralPath (Join-Path $repo "target\release\gpslog-geotag.exe") -Destination (Join-Path $output "gpslog-geotag.exe") -Force
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $output "GPSLog-1.0.0-debug.apk"),(Join-Path $output "gpslog-geotag.exe")
} finally {
    Pop-Location
}
