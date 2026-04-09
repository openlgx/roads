<#
.SYNOPSIS
  Inspect Android SDK tooling (adb, emulator) and repo gradlew.
  Dot-source this file to reuse Get-AndroidEnvironmentInfo from dev-android.ps1.
#>

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

function Get-AndroidSdkRootCandidates {
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    $out = New-Object System.Collections.Generic.List[string]

    foreach ($p in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        $full = try { (Resolve-Path -LiteralPath $p -ErrorAction Stop).Path } catch { $null }
        if ($full -and $seen.Add($full)) { [void]$out.Add($full) }
    }

    $common = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"),
        "C:\Android\sdk",
        "C:\Android\Sdk"
    )
    foreach ($c in $common) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        if (-not (Test-Path -LiteralPath $c)) { continue }
        $full = try { (Resolve-Path -LiteralPath $c -ErrorAction Stop).Path } catch { $null }
        if ($full -and $seen.Add($full)) { [void]$out.Add($full) }
    }

    return ,$out.ToArray()
}

function Get-AndroidEnvironmentInfo {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    $info = [ordered]@{
        Ok                = $true
        RepoRoot          = $RepoRoot
        SdkRoot           = $null
        AdbPath           = $null
        EmulatorPath      = $null
        GradlewBat        = $null
        Missing           = New-Object System.Collections.Generic.List[string]
        Warnings          = New-Object System.Collections.Generic.List[string]
    }

    $candidates = Get-AndroidSdkRootCandidates
    foreach ($sdk in $candidates) {
        $adb = Join-Path $sdk "platform-tools\adb.exe"
        $emu = Join-Path $sdk "emulator\emulator.exe"
        if ((Test-Path -LiteralPath $adb) -and (Test-Path -LiteralPath $emu)) {
            $info.SdkRoot = $sdk
            $info.AdbPath = $adb
            $info.EmulatorPath = $emu
            break
        }
    }

    if (-not $info.SdkRoot) {
        $info.Ok = $false
        [void]$info.Missing.Add("Android SDK (could not find a folder containing both platform-tools\adb.exe and emulator\emulator.exe)")
        [void]$info.Missing.Add("Set ANDROID_HOME or ANDROID_SDK_ROOT to your SDK path (Android Studio: SDK Manager shows the path).")
    } elseif (-not (Test-Path -LiteralPath $info.AdbPath)) {
        $info.Ok = $false
        [void]$info.Missing.Add("adb.exe not found at: $($info.AdbPath) (install Android SDK Platform-Tools)")
    } elseif (-not (Test-Path -LiteralPath $info.EmulatorPath)) {
        $info.Ok = $false
        [void]$info.Missing.Add("emulator.exe not found at: $($info.EmulatorPath) (install Android Emulator via SDK Manager)")
    }

    $gradlewBat = Join-Path $RepoRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlewBat)) {
        $info.Ok = $false
        [void]$info.Missing.Add("gradlew.bat not found at repo root: $gradlewBat")
    }
    $info.GradlewBat = $gradlewBat

    $javaHome = $env:JAVA_HOME
    $studioJbr = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
    if ([string]::IsNullOrWhiteSpace($javaHome)) {
        if (Test-Path -LiteralPath (Join-Path $studioJbr "bin\java.exe")) {
            [void]$info.Warnings.Add(
                "JAVA_HOME is not set. Gradle will fail from a plain shell. Fix: `$env:JAVA_HOME = '$studioJbr' " +
                "or run: .\scripts\run-gradle.ps1 <tasks> (see README Prerequisites)."
            )
        } else {
            [void]$info.Warnings.Add(
                "JAVA_HOME is not set and Android Studio JBR was not found at $studioJbr. Install JDK 17 or Android Studio."
            )
        }
    } elseif (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
        [void]$info.Warnings.Add("JAVA_HOME is set but bin\java.exe was not found under: $javaHome")
    }

    return [pscustomobject]$info
}

function Get-AndroidAdbOnlyEnvironmentInfo {
    <#
    .SYNOPSIS
      Resolve adb + gradlew for physical-device workflows (no emulator required).
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    $info = [ordered]@{
        Ok         = $true
        RepoRoot   = $RepoRoot
        SdkRoot    = $null
        AdbPath    = $null
        GradlewBat = $null
        Missing    = New-Object System.Collections.Generic.List[string]
        Warnings   = New-Object System.Collections.Generic.List[string]
    }

    $candidates = Get-AndroidSdkRootCandidates
    foreach ($sdk in $candidates) {
        $adb = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $adb) {
            $info.SdkRoot = $sdk
            $info.AdbPath = $adb
            break
        }
    }

    if (-not $info.AdbPath) {
        $info.Ok = $false
        [void]$info.Missing.Add("adb.exe not found (install Android SDK Platform-Tools; set ANDROID_HOME if needed)")
    }

    $gradlewBat = Join-Path $RepoRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlewBat)) {
        $info.Ok = $false
        [void]$info.Missing.Add("gradlew.bat not found at repo root: $gradlewBat")
    }
    $info.GradlewBat = $gradlewBat

    $javaHome = $env:JAVA_HOME
    $studioJbr = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
    if ([string]::IsNullOrWhiteSpace($javaHome)) {
        if (Test-Path -LiteralPath (Join-Path $studioJbr "bin\java.exe")) {
            [void]$info.Warnings.Add(
                "JAVA_HOME is not set. Use `$env:JAVA_HOME = '$studioJbr' or .\scripts\run-gradle.ps1 (see README)."
            )
        } else {
            [void]$info.Warnings.Add("JAVA_HOME is not set. Install JDK 17 or Android Studio.")
        }
    } elseif (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
        [void]$info.Warnings.Add("JAVA_HOME is set but bin\java.exe was not found under: $javaHome")
    }

    return [pscustomobject]$info
}

function Write-AndroidAdbOnlyReport {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Info
    )

    Write-Host ""
    Write-DevLog "INFO" "ADB / Gradle check (physical device workflow)"
    Write-DevLog "INFO" "Repo root: $($Info.RepoRoot)"
    if ($Info.SdkRoot) {
        Write-DevLog "OK" "ANDROID SDK: $($Info.SdkRoot)"
        Write-DevLog "OK" "adb: $($Info.AdbPath)"
    }
    if ($Info.GradlewBat -and (Test-Path -LiteralPath $Info.GradlewBat)) {
        Write-DevLog "OK" "gradlew.bat: $($Info.GradlewBat)"
    }
    foreach ($w in $Info.Warnings) {
        Write-DevLog "WARN" $w
    }
    if ($Info.Missing.Count -gt 0) {
        foreach ($m in $Info.Missing) {
            Write-DevLog "ERROR" $m
        }
        Write-DevLog "INFO" "See: docs/android-real-device-testing.md"
        Write-Host ""
    }
}

function Write-AndroidEnvironmentReport {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Info
    )

    Write-Host ""
    Write-DevLog "INFO" "Android / repo environment check"
    Write-DevLog "INFO" "Repo root: $($Info.RepoRoot)"
    if ($Info.SdkRoot) {
        Write-DevLog "OK" "ANDROID SDK: $($Info.SdkRoot)"
        Write-DevLog "OK" "adb: $($Info.AdbPath)"
        Write-DevLog "OK" "emulator: $($Info.EmulatorPath)"
    }
    if ($Info.GradlewBat -and (Test-Path -LiteralPath $Info.GradlewBat)) {
        Write-DevLog "OK" "gradlew.bat: $($Info.GradlewBat)"
    }

    foreach ($w in $Info.Warnings) {
        Write-DevLog "WARN" $w
    }

    if ($Info.Missing.Count -gt 0) {
        foreach ($m in $Info.Missing) {
            Write-DevLog "ERROR" $m
        }
        Write-Host ""
        Write-DevLog "INFO" "Install Android Studio (or SDK command-line tools), then install:"
        Write-DevLog "INFO" "  - Android SDK Platform-Tools (adb)"
        Write-DevLog "INFO" "  - Android Emulator + at least one system image"
        Write-DevLog "INFO" "See: docs/android-dev-setup.md"
        Write-Host ""
    }
}

# Standalone entry (skip when dot-sourced)
if ($MyInvocation.InvocationName -ne ".") {
    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
    $report = Get-AndroidEnvironmentInfo -RepoRoot $repoRoot
    Write-AndroidEnvironmentReport -Info $report
    if (-not $report.Ok) {
        exit 1
    }
    exit 0
}
