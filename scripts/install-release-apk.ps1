<#
.SYNOPSIS
  Install the signed release APK on a USB-connected (or adb-visible) device.

.PARAMETER DeviceSerial
  Optional. When multiple devices are attached, set this from `adb devices`.

.PARAMETER ApkPath
  Optional. Defaults to app\build\outputs\apk\release\app-release.apk

.PARAMETER LaunchApp
  If set, starts MainActivity after install.
#>
param(
    [string]$DeviceSerial,
    [string]$ApkPath,
    [switch]$LaunchApp
)

$ErrorActionPreference = "Continue"
. "$PSScriptRoot\check-android-env.ps1"
. "$PSScriptRoot\adb-install-helpers.ps1"

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$envInfo = Get-AndroidAdbOnlyEnvironmentInfo -RepoRoot $RepoRoot
if (-not $envInfo.Ok) {
    Write-AndroidAdbOnlyReport -Info $envInfo
    exit 1
}

$apk = $ApkPath
if ([string]::IsNullOrWhiteSpace($apk)) {
    $apk = Join-Path $RepoRoot "app\build\outputs\apk\release\app-release.apk"
}
if (-not (Test-Path -LiteralPath $apk)) {
    Write-InstallLog "ERROR" "APK not found: $apk"
    Write-InstallLog "INFO" "Build first: .\scripts\build-release-apk.ps1"
    exit 1
}

$adb = $envInfo.AdbPath
$serial = Resolve-SingleAdbDevice -AdbPath $adb -PreferredSerial $DeviceSerial

Write-InstallLog "INFO" "Device: $serial"
Write-InstallLog "INFO" "APK: $apk"
Write-InstallLog "INFO" "adb install -r (upgrade if same signing key)"

$p = Start-Process -FilePath $adb -ArgumentList @("-s", $serial, "install", "-r", $apk) -Wait -PassThru -NoNewWindow
if ($p.ExitCode -ne 0) {
    Write-InstallLog "ERROR" "adb install failed (exit $($p.ExitCode))"
    exit $p.ExitCode
}

Write-InstallLog "OK" "Installed org.openlgx.roads (release)"

if ($LaunchApp) {
    Write-InstallLog "INFO" "Launching MainActivity…"
    & $adb -s $serial shell am start -n "org.openlgx.roads/.MainActivity" | Out-Null
}
