param(
    [ValidateSet('modern','compat','probe','legacy')][string]$Target = 'modern',
    [string]$PosixBin,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string[]]$Abis,
    [switch]$Check,
    [switch]$Instrumentation,
    [switch]$NoDownload
)
$ErrorActionPreference = 'Stop'
if ($Instrumentation -and $Target -ne 'legacy') { throw '-Instrumentation currently applies only to the native API17 frontend.' }
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if ($PosixBin) {
    foreach ($utility in @('bash.exe','make.exe','perl.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PosixBin $utility))) {
            throw "Missing $utility in -PosixBin. Use a complete POSIX build environment."
        }
    }
}
$previousPath = $env:PATH
$previousNdk = $env:ANDROID_NDK_ROOT
$repo = Split-Path $PSScriptRoot -Parent
try {
    if ($PosixBin) { $env:PATH = $PosixBin + [IO.Path]::PathSeparator + $env:PATH }
    foreach ($utility in @('bash','make','perl','java')) {
        $command = Get-Command $utility -ErrorAction Stop
        if ($utility -eq 'bash' -and $command.Source -match 'Windows[\\/]system32|WindowsApps') {
            throw 'The Windows/WSL bash launcher cannot build these Windows NDK artifacts. Pass -PosixBin.'
        }
    }
    $catalog = Get-Content -LiteralPath (Join-Path $repo 'gradle/libs.versions.toml') -Raw
    $ndkVersion = if ($Target -in @('probe','legacy')) { '23.2.8568313' } else {
        [regex]::Match($catalog, '(?m)^ndk\s*=\s*"([^"]+)"').Groups[1].Value
    }
    $env:ANDROID_NDK_ROOT = Join-Path $SdkRoot ('ndk/'+$ndkVersion)
    if (-not (Test-Path -LiteralPath $env:ANDROID_NDK_ROOT -PathType Container)) {
        throw "Required NDK $ndkVersion is not installed in the selected SDK."
    }
    $buildArguments = @('--no-daemon','--console=plain')
    if ($NoDownload) { $buildArguments += '-PbuzinNoDownload=true' }
    if ($Target -eq 'probe') {
        if ($Abis) { throw 'The probe tests its fixed ARMv7/x86 pair; -Abis applies to the app targets.' }
        $buildArguments += @('-PbuzinFireOs3Probe=true',':fireos3-probe:assembleDebug')
        if ($Check) { $buildArguments += @(':fireos3-runtime:testDebugUnitTest',':fireos3-runtime:lintDebug',':fireos3-probe:testDebugUnitTest',':fireos3-probe:lintDebug') }
    } elseif ($Target -eq 'legacy') {
        if ($Abis) { throw 'The API17 runtime uses its fixed ARMv7/x86 pair.' }
        $buildArguments += @('-PbuzinFireOsLegacy=true',':fireos-legacy:assembleDebug')
        if ($Instrumentation) { $buildArguments += ':fireos-legacy:assembleDebugAndroidTest' }
        if ($Check) { $buildArguments += @('-PbuzinFireOs3Probe=true',':fireos3-probe:assembleDebug',':fireos3-probe:testDebugUnitTest',':fireos3-probe:lintDebug',':fireos3-runtime:testDebugUnitTest',':fireos3-runtime:lintDebug',':fireos-legacy:lintDebug',':fireos-legacy:testDebugUnitTest',':dlna-domain:test',':receiver-core:check') }
    } else {
        if ($Target -eq 'compat') { $buildArguments += '-PbuzinLegacy=true' }
        if ($Abis) { $buildArguments += '-PbuzinAbis='+($Abis -join ',') }
        $buildArguments += ':app:assembleDebug'
        if ($Check) { $buildArguments += @(':app:lintDebug',':receiver-core:check') }
    }
    Push-Location $repo
    try { & ./gradlew.bat @buildArguments; $buildResult = $LASTEXITCODE }
    finally { Pop-Location }
    if ($buildResult -ne 0) { throw "Gradle failed with exit code $buildResult." }
    if ($Target -eq 'legacy') {
        # A project-wide buildDirectory switch can make Gradle remove the other
        # edition's APK as stale output. Export completed previews outside it.
        $legacyBuild = Join-Path $repo ('fireos-legacy/'+$(if ($NoDownload) {'build-nodownload'} else {'build'}))
        $legacyExport = Join-Path $repo ('outputs/fireos/legacy-preview'+$(if ($NoDownload) {'-nodownload'} else {''}))
        $legacyMetadata = Get-Content -LiteralPath (Join-Path $legacyBuild 'outputs/apk/debug/output-metadata.json') -Raw | ConvertFrom-Json
        $expectedId = if ($NoDownload) {'com.buzincast.tv.deat.legacy.preview'} else {'com.buzincast.tv.legacy.preview'}
        if ($legacyMetadata.applicationId -ne $expectedId) { throw 'Unexpected legacy APK application ID.' }
        New-Item -ItemType Directory -Path $legacyExport -Force | Out-Null
        $legacyApk = Join-Path $legacyExport 'fireos-legacy-debug.apk'
        Copy-Item -LiteralPath (Join-Path $legacyBuild 'outputs/apk/debug/fireos-legacy-debug.apk') -Destination $legacyApk
        $artifact = [ordered]@{preview=$true;applicationId=$expectedId;versionName=$legacyMetadata.elements[0].versionName;
            versionCode=$legacyMetadata.elements[0].versionCode;builtAtUtc=[DateTime]::UtcNow.ToString('o');
            apk='fireos-legacy-debug.apk';sha256=(Get-FileHash -LiteralPath $legacyApk -Algorithm SHA256).Hash.ToLowerInvariant()}
        $legacyTestApk = Join-Path $legacyExport 'fireos-legacy-debug-androidTest.apk'
        if ($Instrumentation) {
            Copy-Item -LiteralPath (Join-Path $legacyBuild 'outputs/apk/androidTest/debug/fireos-legacy-debug-androidTest.apk') -Destination $legacyTestApk
            $artifact['testApk']='fireos-legacy-debug-androidTest.apk'
            $artifact['testSha256']=(Get-FileHash -LiteralPath $legacyTestApk -Algorithm SHA256).Hash.ToLowerInvariant()
        } elseif (Test-Path -LiteralPath $legacyTestApk -PathType Leaf) {
            Remove-Item -LiteralPath $legacyTestApk
        }
        [IO.File]::WriteAllText((Join-Path $legacyExport 'artifact.json'),($artifact | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
        Write-Host "Legacy debug preview exported to $legacyExport"
    }
} finally {
    $env:PATH = $previousPath
    $env:ANDROID_NDK_ROOT = $previousNdk
}
