<#
.SYNOPSIS
  List adb-attached devices (USB + emulators) in "device" state.
#>
$ErrorActionPreference = "Continue"

. "$PSScriptRoot\check-android-env.ps1"

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$envInfo = Get-AndroidAdbOnlyEnvironmentInfo -RepoRoot $RepoRoot
if (-not $envInfo.Ok) {
    Write-AndroidAdbOnlyReport -Info $envInfo
    exit 1
}

$adb = $envInfo.AdbPath
Write-Host ""
Write-Host "[INFO] adb devices -l"
Write-Host ""
& $adb devices -l
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "[ERROR] adb devices failed (exit $code)"
    exit $code
}
Write-Host ""
Write-Host ""
Write-Host "[INFO] Use -DeviceSerial with install scripts when more than one device is listed."
