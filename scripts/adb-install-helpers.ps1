<#
  Shared helpers for adb install scripts. Dot-source from install-*.ps1
#>

function Write-InstallLog {
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

function Get-AdbDeviceSerials {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AdbPath
    )
    $raw = & $AdbPath devices 2>&1
    $serials = New-Object System.Collections.Generic.List[string]
    foreach ($line in $raw) {
        $t = "$line".Trim()
        if ($t -match "^(\S+)\s+device$") {
            [void]$serials.Add($Matches[1])
        }
    }
    return ,$serials.ToArray()
}

function Resolve-SingleAdbDevice {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AdbPath,
        [string]$PreferredSerial
    )
    $serials = Get-AdbDeviceSerials -AdbPath $AdbPath
    if ($serials.Count -eq 0) {
        Write-InstallLog "ERROR" "No device in 'device' state. Enable USB debugging, connect USB, then: adb devices"
        exit 1
    }
    if (-not [string]::IsNullOrWhiteSpace($PreferredSerial)) {
        if ($serials -contains $PreferredSerial) {
            return $PreferredSerial
        }
        Write-InstallLog "ERROR" "Serial not in 'device' state or unknown: $PreferredSerial"
        exit 1
    }
    if ($serials.Count -eq 1) {
        return $serials[0]
    }
    Write-InstallLog "ERROR" "Multiple devices attached. Use -DeviceSerial or disconnect extras."
    foreach ($s in $serials) {
        Write-InstallLog "INFO" "  $s"
    }
    Write-InstallLog "INFO" "List: .\scripts\list-android-devices.ps1"
    exit 1
}
