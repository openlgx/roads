<#
.SYNOPSIS
  Run OLGX Roads on an Android emulator from the command line (Windows-first).

.EXAMPLE
  .\scripts\dev-android.ps1 -Avd "Pixel_8_API_34"

.EXAMPLE
  .\scripts\dev-android.ps1 -Avd "Pixel_8_API_34" -LaunchApp
#>
param(
    [string]$Avd,
    [switch]$LaunchApp
)

# adb/gradle frequently emit non-terminating errors while devices are booting; handle flow explicitly.
$ErrorActionPreference = "Continue"

function Write-DevLog {
    param(
        [ValidateSet("OK", "INFO", "WARN", "ERROR")]
        [string]$Level,
        [string]$Message
    )
    $prefix = switch ($Level) {
        "OK" { "[OK]   " }
        "INFO" { "[INFO] " }
        "WARN" { "[WARN] " }
        "ERROR" { "[ERROR]" }
    }
    Write-Host "$prefix $Message"
}

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location -LiteralPath $RepoRoot

# Functions: Get-AndroidEnvironmentInfo, Write-AndroidEnvironmentReport, Write-DevLog (duplicate OK)
. "$PSScriptRoot\check-android-env.ps1"

$envInfo = Get-AndroidEnvironmentInfo -RepoRoot $RepoRoot
if (-not $envInfo.Ok) {
    Write-AndroidEnvironmentReport -Info $envInfo
    exit 1
}
foreach ($w in $envInfo.Warnings) {
    Write-DevLog "WARN" $w
}
Write-DevLog "OK" "Environment OK (sdk: $($envInfo.SdkRoot))"

$adb = $envInfo.AdbPath
$emulator = $envInfo.EmulatorPath

# --- AVD list / selection ---
Write-DevLog "INFO" "Listing AVDs (emulator -list-avds)..."
# Some SDK builds write harmless banners to stderr; merge streams for parsing.
$avdLines = & $emulator -list-avds 2>&1
if ($LASTEXITCODE -ne 0 -and -not $avdLines) {
    Write-DevLog "ERROR" "emulator -list-avds failed. Is the Android Emulator package installed?"
    exit 1
}
$avdNames = @(
    $avdLines | ForEach-Object {
        if ($null -ne $_) { "$_".Trim() }
    } | Where-Object { $_ -ne "" }
)

if ($avdNames.Count -eq 0) {
    Write-DevLog "ERROR" "No AVDs found. Create one in Android Studio (Device Manager) or with avdmanager."
    Write-DevLog "INFO" "See: docs/android-dev-setup.md"
    exit 1
}

$selected = $Avd
if ([string]::IsNullOrWhiteSpace($selected)) {
    if ($avdNames.Count -eq 1) {
        $selected = $avdNames[0]
        Write-DevLog "INFO" "Using the only installed AVD: $selected"
    }
    else {
        Write-DevLog "ERROR" "Multiple AVDs are installed; choose one with -Avd."
        Write-DevLog "INFO" "Installed AVDs:"
        foreach ($n in $avdNames) {
            Write-Host "       - $n"
        }
        Write-DevLog "INFO" 'Example: .\scripts\dev-android.ps1 -Avd "Pixel_8_API_34"'
        exit 1
    }
}
else {
    if ($avdNames -notcontains $selected) {
        Write-DevLog "ERROR" "AVD `"$selected`" not found."
        Write-DevLog "INFO" "Installed AVDs:"
        foreach ($n in $avdNames) {
            Write-Host "       - $n"
        }
        exit 1
    }
}

# --- Detect running emulator devices ---
function Get-AdbDeviceSerials {
    param([string]$AdbPath)
    $raw = & $AdbPath devices 2>&1
    $serials = New-Object System.Collections.Generic.List[string]
    foreach ($line in $raw) {
        if ($line -match "^\s*$" -or $line -like "List of devices*") { continue }
        $parts = $line -split "\s+", 3
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
            [void]$serials.Add($parts[0])
        }
    }
    return ,$serials.ToArray()
}

$serials = Get-AdbDeviceSerials -AdbPath $adb
$hasRunningEmulator = $false
foreach ($s in $serials) {
    if ($s -like "emulator-*") {
        $hasRunningEmulator = $true
        break
    }
}

if (-not $hasRunningEmulator) {
    Write-DevLog "INFO" "Starting emulator in a new process: $selected"
    # Separate process; do not block this window.
    Start-Process -FilePath $emulator -ArgumentList "@$selected" -WorkingDirectory (Split-Path -Parent $emulator) | Out-Null
}
else {
    Write-DevLog "INFO" "An emulator device is already visible to adb; continuing."
}

Write-DevLog "INFO" "Waiting for adb device..."
& $adb wait-for-device 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-DevLog "ERROR" "adb wait-for-device failed."
    exit 1
}

$allSerials = Get-AdbDeviceSerials -AdbPath $adb
$emuSerials = @($allSerials | Where-Object { $_ -like "emulator-*" })
if ($emuSerials.Count -eq 0) {
    Write-DevLog "ERROR" "No emulator serial was found in adb devices (expected emulator-5554, etc.)."
    Write-DevLog "INFO" "Run: adb devices"
    exit 1
}
if ($emuSerials.Count -gt 1) {
    Write-DevLog "WARN" "Multiple emulators detected; using first serial for boot check, install, and launch: $($emuSerials[0])"
}
$targetSerial = $emuSerials[0]
Write-DevLog "INFO" "Using adb device serial: $targetSerial"

$prevAndroidSerial = $env:ANDROID_SERIAL
$env:ANDROID_SERIAL = $targetSerial

Write-DevLog "INFO" "Waiting for Android boot to complete (sys.boot_completed)..."
$bootTimeoutSec = 180
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$bootOk = $false
while ($sw.Elapsed.TotalSeconds -lt $bootTimeoutSec) {
    try {
        $prop = (& $adb -s $targetSerial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($prop -eq "1") {
            $bootOk = $true
            break
        }
    }
    catch {
        # ignore transient adb errors while emulator is still coming up
    }
    Start-Sleep -Seconds 2
}

if (-not $bootOk) {
    Write-DevLog "ERROR" "Timed out after $bootTimeoutSec s waiting for boot completion."
    Write-DevLog "INFO" "Check the emulator window for errors, or try: adb devices"
    if ($null -eq $prevAndroidSerial) { Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue } else { $env:ANDROID_SERIAL = $prevAndroidSerial }
    exit 1
}
Write-DevLog "OK" "Boot completed."

Write-DevLog "INFO" "Installing debug build: .\gradlew.bat :app:installDebug (ANDROID_SERIAL=$($env:ANDROID_SERIAL))"
$gradleExit = 1
Push-Location $RepoRoot
try {
    & .\gradlew.bat :app:installDebug
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($gradleExit -ne 0) {
    Write-DevLog "ERROR" "Gradle installDebug failed (exit code $gradleExit)."
    Write-DevLog "INFO" "Try running: .\gradlew.bat :app:installDebug --stacktrace"
    if ($null -eq $prevAndroidSerial) { Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue } else { $env:ANDROID_SERIAL = $prevAndroidSerial }
    exit $gradleExit
}
Write-DevLog "OK" "installDebug succeeded."

if ($LaunchApp) {
    $appId = $null
    $gradlePath = Join-Path $RepoRoot "app\build.gradle.kts"
    if (Test-Path -LiteralPath $gradlePath) {
        $txt = Get-Content -LiteralPath $gradlePath -Raw
        if ($txt -match 'applicationId\s*=\s*"([^"]+)"') {
            $appId = $Matches[1]
        }
    }
    if ([string]::IsNullOrWhiteSpace($appId)) {
        Write-DevLog "WARN" "Could not parse applicationId from app/build.gradle.kts; skipping launch."
    }
    else {
        Write-DevLog "INFO" "Launching app package: $appId"
        # Reliable enough for dev: triggers launcher intent without hardcoding activity class.
        & $adb -s $targetSerial shell monkey -p $appId -c android.intent.category.LAUNCHER 1
        if ($LASTEXITCODE -ne 0) {
            Write-DevLog "WARN" "monkey launch returned non-zero exit; try opening the app manually."
        }
        else {
            Write-DevLog "OK" "Launch triggered."
        }
    }
}
else {
    Write-DevLog "INFO" "Skipped app launch (pass -LaunchApp to auto-start)."
}

if ($null -eq $prevAndroidSerial) { Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue } else { $env:ANDROID_SERIAL = $prevAndroidSerial }

Write-Host ""
Write-DevLog "OK" "Done."
exit 0
