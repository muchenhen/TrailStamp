param(
    [Parameter(Mandatory = $true)][string]$RawFile,
    [Parameter(Mandatory = $true)][string]$Album,
    [Parameter(Mandatory = $true)][string]$SiteRepo,
    [Parameter(Mandatory = $true)][string]$Track,
    [switch]$UpdateSite
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$run = Join-Path $repo ("reports\e2e-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$inputDirectory = Join-Path $run "raw-input"
$reportDirectory = Join-Path $run "report"
$outputDirectory = Join-Path $run "raw-output"
New-Item -ItemType Directory -Path $inputDirectory,$reportDirectory | Out-Null

$originalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $RawFile).Hash
$inputCopy = Join-Path $inputDirectory (Split-Path -Leaf $RawFile)
Copy-Item -LiteralPath $RawFile -Destination $inputCopy
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $inputCopy).Hash -ne $originalHash) { throw "Input copy hash mismatch" }

Push-Location $repo
try {
    & cargo run --quiet --release --bin gpslog-geotag -- preview --track $Track --raw-dir $inputDirectory --site-repo $SiteRepo --album $Album --timezone Asia/Shanghai --clock-offset "+00:00:00" --report-dir $reportDirectory
    if ($LASTEXITCODE -ne 0) { throw "Preview failed" }
    $applyArguments = @("run", "--quiet", "--release", "--bin", "gpslog-geotag", "--", "apply", "--plan", (Join-Path $reportDirectory "plan.json"), "--raw-out", $outputDirectory)
    if ($UpdateSite) { $applyArguments += "--update-site" }
    & cargo @applyArguments
    if ($LASTEXITCODE -ne 0) { throw "Apply failed" }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $RawFile).Hash -ne $originalHash) { throw "Original RAW changed" }
    Write-Output "Report: $reportDirectory"
    Write-Output "Original SHA-256: $originalHash"
} finally {
    Pop-Location
}
