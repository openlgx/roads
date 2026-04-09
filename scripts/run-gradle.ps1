<#
.SYNOPSIS
  Run repo gradlew.bat with JAVA_HOME set when possible (fixes "JAVA_HOME is not set" on Windows).
.EXAMPLE
  .\scripts\run-gradle.ps1 :app:testDebugUnitTest
  .\scripts\run-gradle.ps1 :app:assembleDebug
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$gradlew = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path -LiteralPath $gradlew)) {
    Write-Error "gradlew.bat not found at: $gradlew"
}

$candidates = @(
    $env:JAVA_HOME
    (Join-Path $env:ProgramFiles "Android\Android Studio\jbr")
    (Join-Path $env:ProgramFiles "Android\Android Studio\jre")
    (Join-Path $env:LocalAppData "Programs\Android\Android Studio\jbr")
)

foreach ($candidate in $candidates) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $javaExe = Join-Path $candidate "bin\java.exe"
    if (Test-Path -LiteralPath $javaExe) {
        if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
            $env:JAVA_HOME = $candidate
            Write-Host "[run-gradle] Using JAVA_HOME=$candidate" -ForegroundColor DarkGray
        }
        break
    }
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    Write-Warning "JAVA_HOME is not set and no Studio JBR was found. Install Android Studio or set JAVA_HOME to JDK 17."
    Write-Warning "Example (current session): `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'"
}

& $gradlew @GradleArgs
exit $LASTEXITCODE
