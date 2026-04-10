# Smoke-check SQL migrations for obvious issues (no DB required).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$migrations = Join-Path $root "sql\migrations\*.sql"
$files = Get-ChildItem -Path $migrations
if ($files.Count -eq 0) { throw "No migration files found." }
foreach ($f in $files) {
  $c = Get-Content -Raw -Path $f.FullName
  if ($c -match "recording Session_id") { throw "Syntax typo in $($f.Name)" }
}
Write-Host "OK: $($files.Count) migration file(s) basic check passed."
