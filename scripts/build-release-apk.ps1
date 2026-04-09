<#
.SYNOPSIS
  Build a signed release APK for field trials (requires signing config).

  Signing: keystore.properties at repo root or ROADS_* env vars.
  See: docs/android-release-signing.md

.OUTPUTS
  app\build\outputs\apk\release\app-release.apk
#>
$ErrorActionPreference = "Stop"

function Write-BuildLog {
    param(
        [ValidateSet("OK", "INFO", "WARN", "ERROR")]
        [string]$Level,
        [string]$Message
    )
    $p = switch ($Level) {
        "OK" { "[OK]   " }
        "INFO" { "[INFO] " }
        "WARN" { "[WARN] " }
        "ERROR" { "[ERROR]" }
    }
    Write-Host "$p $Message"
}

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location -LiteralPath $RepoRoot

$props = Join-Path $RepoRoot "keystore.properties"
$hasFile = Test-Path -LiteralPath $props
$hasEnv = @(
    $env:ROADS_STORE_FILE,
    $env:ROADS_STORE_PASSWORD,
    $env:ROADS_KEY_ALIAS,
    $env:ROADS_KEY_PASSWORD
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Measure-Object | Select-Object -ExpandProperty Count

if (-not $hasFile -and $hasEnv -lt 4) {
    Write-BuildLog "ERROR" "Release signing is not configured."
    Write-BuildLog "INFO" "Create keystore.properties from keystore.properties.example, or set ROADS_STORE_FILE, ROADS_STORE_PASSWORD, ROADS_KEY_ALIAS, ROADS_KEY_PASSWORD."
    Write-BuildLog "INFO" "Docs: docs/android-release-signing.md"
    exit 1
}

Write-BuildLog "INFO" "Repo root: $RepoRoot"
Write-BuildLog "INFO" "Running: .\gradlew.bat :app:assembleRelease"

& (Join-Path $RepoRoot "gradlew.bat") ":app:assembleRelease"
if ($LASTEXITCODE -ne 0) {
    Write-BuildLog "ERROR" "Gradle assembleRelease failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}

$apk = Join-Path $RepoRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $apk)) {
    Write-BuildLog "ERROR" "Expected APK not found: $apk"
    exit 1
}

Write-BuildLog "OK" "Release APK: $apk"
Write-BuildLog "INFO" "Install: .\scripts\install-release-apk.ps1 [-DeviceSerial SERIAL] [-LaunchApp]"
Write-BuildLog "WARN" "Distribute only to trusted testers; this build is not from Play Store."
