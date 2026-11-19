param(
    [Parameter(Mandatory=$true)][string]$RegionalAppstoreAssets,
    [string]$Python = 'python',
    [string]$OutputDirectory = 'outputs/fireos-release-manifests',
    [ValidateSet('legacy.general','legacy.nodownload','compat.general','compat.nodownload','modern.general','modern.nodownload')]
    [string[]]$Editions = @('legacy.general','legacy.nodownload','compat.general','compat.nodownload','modern.general','modern.nodownload')
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$regionalAssets = (Resolve-Path -LiteralPath $RegionalAppstoreAssets).Path
if (-not (Test-Path -LiteralPath (Join-Path $regionalAssets 'AppstoreAuthenticationKey.pem') -PathType Leaf)) {
    throw 'Provide the DE/AT app public key directory.'
}
if (($Editions | Select-Object -Unique).Count -ne $Editions.Count) { throw 'Do not repeat editions.' }
$plan = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $repo 'gradle/fireos-release.properties')) {
    if ($line -match '^([^#=]+)=(.*)$') { $plan[$Matches[1].Trim()] = $Matches[2].Trim() }
}
Push-Location $repo
try {
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $destination = (Resolve-Path -LiteralPath $OutputDirectory).Path
    $reportPath = Join-Path $destination 'report.json'
    [IO.File]::WriteAllText($reportPath, '{"status":"running","completeSet":false,"deviceTargetingVerified":false}', [Text.UTF8Encoding]::new($false))
    & $Python (Join-Path $PSScriptRoot 'check_fireos_release.py') *> (Join-Path $destination 'plan-check.log')
    if ($LASTEXITCODE -ne 0) { throw 'Invalid coordinated release plan; inspect plan-check.log.' }
    $verifyArgs = @((Join-Path $PSScriptRoot 'check_fireos_release.py'))
    foreach ($edition in $Editions) {
        $target, $region = $edition.Split('.')
        $gradleArgs = @('--no-daemon','--console=plain')
        if ($target -eq 'legacy') {
            $versionName = $plan['versionName'] + '-legacy' + $(if ($region -eq 'nodownload') {'-nodownload'} else {''})
            $gradleArgs += @('-PbuzinFireOsLegacy=true','-PbuzinLegacyStore=true',
                "-PbuzinLegacyVersionCode=$($plan["$edition.versionCode"])", "-PbuzinLegacyVersionName=$versionName",
                ':fireos-legacy:processReleaseManifest')
            $buildDirectory = 'fireos-legacy/build-store'
        } else {
            if ($target -eq 'compat') { $gradleArgs += '-PbuzinLegacy=true' }
            $gradleArgs += ':app:processReleaseManifest'
            $buildDirectory = 'app/build' + $(if ($target -eq 'compat') {'-legacy'} else {''})
        }
        if ($region -eq 'nodownload') {
            $gradleArgs += "-PbuzinRegionalAppstoreAssets=$regionalAssets"
            $buildDirectory += '-nodownload'
        }
        & (Join-Path $repo 'gradlew.bat') @gradleArgs *> (Join-Path $destination "$edition.log")
        if ($LASTEXITCODE -ne 0) { throw "Manifest generation failed for $edition; inspect its log in the output directory." }
        $source = Join-Path $repo "$buildDirectory/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
        $copy = Join-Path $destination "$edition.xml"
        Copy-Item -LiteralPath $source -Destination $copy
        $verifyArgs += @('--manifest', "$edition=$copy")
        Write-Host "Generated $edition manifest"
    }
    if ($Editions.Count -eq 6) { $verifyArgs += '--require-all' }
    $verifyArgs += @('--output', $reportPath)
    & $Python @verifyArgs
    if ($LASTEXITCODE -ne 0) { throw 'Coordinated Fire OS identity checks failed.' }
} catch {
    if ($reportPath) {
        $failure = @{status='failed';completeSet=$false;deviceTargetingVerified=$false;error=$_.Exception.Message}
        [IO.File]::WriteAllText($reportPath, ($failure | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    }
    throw
} finally { Pop-Location }
