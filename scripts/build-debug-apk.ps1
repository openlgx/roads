<#
.SYNOPSIS
  Build a debug APK for sideload / adb install (field testing).

.OUTPUTS
  app\build\outputs\apk\debug\app-debug.apk
#>
$ErrorActionPreference = "Stop"

function Write-BuildLog {
    param(
        [ValidateSet("OK", "INFO", "ERROR")]
        [string]$Level,
        [string]$Message
    )
    $p = switch ($Level) {
        "OK" { "[OK]   " }
        "INFO" { "[INFO] " }
        "ERROR" { "[ERROR]" }
    }
    Write-Host "$p $Message"
}

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location -LiteralPath $RepoRoot

Write-BuildLog "INFO" "Repo root: $RepoRoot"
Write-BuildLog "INFO" "Running: .\gradlew.bat :app:assembleDebug"

& (Join-Path $RepoRoot "gradlew.bat") ":app:assembleDebug"
if ($LASTEXITCODE -ne 0) {
    Write-BuildLog "ERROR" "Gradle assembleDebug failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}

$apk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) {
    Write-BuildLog "ERROR" "Expected APK not found: $apk"
    exit 1
}

Write-BuildLog "OK" "Debug APK: $apk"
Write-BuildLog "INFO" "Install: .\scripts\install-debug-apk.ps1 [-DeviceSerial SERIAL] [-LaunchApp]"
