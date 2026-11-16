param(
    [Parameter(Mandatory=$true)][string]$Fire,
    [ValidateSet('all','store','production')][string]$Suite = 'all',
    [ValidateSet('both','normal','deat')][string]$Edition = 'both',
    [Alias('Idiomas')][string[]]$Locales,
    [string]$OutputDirectory,
    [string]$AdbPath,
    [switch]$RestoreProduction
)
$ErrorActionPreference = 'Stop'
$pythonCandidates = @(
    (Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'),
    'python'
)
$pythonForCapture = $null
foreach ($candidate in $pythonCandidates) {
    if (Get-Command $candidate -ErrorAction SilentlyContinue) {
        & $candidate -c 'import PIL' 2>$null
        if ($LASTEXITCODE -eq 0) { $pythonForCapture = $candidate; break }
    }
}
if (-not $pythonForCapture) { throw 'Python com Pillow precisa estar instalado.' }
$captureArguments = @((Join-Path $PSScriptRoot 'capture_store_screenshots.py'), '--device', $Fire, '--suite', $Suite, '--edition', $Edition)
if ($Locales) { $captureArguments += '--locales'; $captureArguments += $Locales }
if ($OutputDirectory) { $captureArguments += @('--output', $OutputDirectory) }
if ($AdbPath) { $captureArguments += @('--adb', $AdbPath) }
if ($RestoreProduction) { $captureArguments += '--restore-production' }
& $pythonForCapture @captureArguments
exit $LASTEXITCODE
