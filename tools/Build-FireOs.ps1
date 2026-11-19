param(
    [ValidateSet('modern','compat','probe','legacy')][string]$Target = 'modern',
    [string]$PosixBin,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string[]]$Abis,
    [switch]$Check,
    [switch]$Instrumentation,
    [switch]$NoDownload,
    [switch]$Release,
    [int]$VersionCode,
    [string]$VersionName,
    [string]$RegionalAppstoreAssets
)
$ErrorActionPreference = 'Stop'
if ($Instrumentation -and $Target -ne 'legacy') { throw '-Instrumentation currently applies only to the native API17 frontend.' }
if ($Release -and ($Target -eq 'probe' -or $Instrumentation)) { throw 'The probe and instrumentation are available only for debug builds.' }
if ($Release -and $Target -eq 'legacy' -and ($VersionCode -lt 1 -or [string]::IsNullOrWhiteSpace($VersionName))) {
    throw 'Pass an explicit -VersionCode and -VersionName for the legacy store candidate.'
}
if (-not $Release -and ($RegionalAppstoreAssets -or $VersionCode -or $VersionName)) { throw 'Store keys and version arguments require -Release.' }
if ($Release -and $Target -ne 'legacy' -and ($PSBoundParameters.ContainsKey('VersionCode') -or $PSBoundParameters.ContainsKey('VersionName'))) {
    throw 'For compat/modern releases, set the version in gradle/fireos-release.properties.'
}
if ($RegionalAppstoreAssets) {
    $RegionalAppstoreAssets = (Resolve-Path -LiteralPath $RegionalAppstoreAssets).Path
    if (-not (Test-Path -LiteralPath (Join-Path $RegionalAppstoreAssets 'AppstoreAuthenticationKey.pem') -PathType Leaf)) { throw 'Missing regional Appstore public key.' }
    $NoDownload = $true
}
if ($Release -and $NoDownload -and -not $RegionalAppstoreAssets) { throw 'The DE/AT store candidate requires -RegionalAppstoreAssets.' }
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
function Assert-PreviewApk([string]$Path) {
    $previewArchive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        if (-not $previewArchive.GetEntry('AndroidManifest.xml') -or -not $previewArchive.GetEntry('classes.dex')) {
            throw "APK is incomplete: $Path"
        }
    } finally { $previewArchive.Dispose() }
}
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
        $buildArguments += '-PbuzinFireOsLegacy=true'
        if ($Release) {
            $buildArguments += @('-PbuzinLegacyStore=true',"-PbuzinLegacyVersionCode=$VersionCode","-PbuzinLegacyVersionName=$VersionName",':fireos-legacy:assembleRelease')
            if ($RegionalAppstoreAssets) { $buildArguments += "-PbuzinRegionalAppstoreAssets=$RegionalAppstoreAssets" }
        } else { $buildArguments += ':fireos-legacy:assembleDebug' }
        if ($Instrumentation) { $buildArguments += ':fireos-legacy:assembleDebugAndroidTest' }
        if ($Check) { $buildArguments += @('-PbuzinFireOs3Probe=true',':fireos3-probe:assembleDebug',':fireos3-probe:testDebugUnitTest',':fireos3-probe:lintDebug',':fireos3-runtime:testDebugUnitTest',':fireos3-runtime:lintDebug',':fireos-legacy:lintDebug',':fireos-legacy:testDebugUnitTest',':dlna-domain:test',':receiver-core:check') }
        if ($Release -and $Check) { $buildArguments += @(':fireos-legacy:lintRelease',':fireos3-runtime:lintRelease') }
    } else {
        if ($Target -eq 'compat') { $buildArguments += '-PbuzinLegacy=true' }
        if ($Abis) { $buildArguments += '-PbuzinAbis='+($Abis -join ',') }
        if ($RegionalAppstoreAssets) { $buildArguments += "-PbuzinRegionalAppstoreAssets=$RegionalAppstoreAssets" }
        $buildType = if ($Release) {'Release'} else {'Debug'}
        $buildArguments += ":app:assemble$buildType"
        if ($Check) { $buildArguments += @(":app:lint$buildType",':receiver-core:check') }
    }
    Push-Location $repo
    try { & ./gradlew.bat @buildArguments; $buildResult = $LASTEXITCODE }
    finally { Pop-Location }
    if ($buildResult -ne 0) { throw "Gradle failed with exit code $buildResult." }
    if ($Release -and $Target -in @('compat','modern')) {
        $region = if ($NoDownload) {'nodownload'} else {'general'}
        $buildDirectory = 'app/build' + $(if ($Target -eq 'compat') {'-legacy'} else {''}) + $(if ($NoDownload) {'-nodownload'} else {''})
        $releaseDirectory = Join-Path $repo "$buildDirectory/outputs/apk/release"
        $metadata = Get-Content -LiteralPath (Join-Path $releaseDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
        $plan = Get-Content -LiteralPath (Join-Path $repo 'gradle/fireos-release.properties') -Raw | ConvertFrom-StringData
        $expectedId = if ($NoDownload) {'com.buzincast.tv.deat'} else {'com.buzincast.tv'}
        $expectedCode = [int]$plan["$Target.$region.versionCode"]
        $expectedName = $plan['versionName'] + $(if ($Target -eq 'compat') {'-compat'} else {''}) + $(if ($NoDownload) {'-nodownload'} else {''})
        if ($metadata.applicationId -ne $expectedId -or $metadata.elements.Count -ne 1 -or $metadata.elements[0].outputFile -like '*unsigned*') {
            throw 'Expected one signed store APK with the planned application ID.'
        }
        if ($expectedCode -lt 1 -or $metadata.elements[0].versionCode -ne $expectedCode -or $metadata.elements[0].versionName -ne $expectedName) {
            throw 'The release build version differs from the coordinated plan.'
        }
        $sourceApk = Join-Path $releaseDirectory $metadata.elements[0].outputFile
        Assert-PreviewApk $sourceApk
        $export = Join-Path $repo 'outputs/fireos/coordinated'
        New-Item -ItemType Directory -Force -Path $export | Out-Null
        $finalApk = Join-Path $export "$Target.$region.apk"
        $temporaryApk = "$finalApk.$([guid]::NewGuid()).tmp"
        try {
            Copy-Item -LiteralPath $sourceApk -Destination $temporaryApk
            if ((Get-FileHash -LiteralPath $sourceApk).Hash -ne (Get-FileHash -LiteralPath $temporaryApk).Hash) {
                throw 'Exported store candidate differs from build output.'
            }
            Move-Item -LiteralPath $temporaryApk -Destination $finalApk -Force
        } finally {
            if (Test-Path -LiteralPath $temporaryApk) { Remove-Item -LiteralPath $temporaryApk }
        }
        Write-Host "Signed store candidate exported to $finalApk"
    }
    if ($Target -eq 'legacy') {
        if ($Release) {
            $storeBuild = Join-Path $repo ('fireos-legacy/build-store'+$(if ($NoDownload) {'-nodownload'} else {''}))
            $storeMetadata = Get-Content -LiteralPath (Join-Path $storeBuild 'outputs/apk/release/output-metadata.json') -Raw | ConvertFrom-Json
            $storeId = if ($NoDownload) {'com.buzincast.tv.deat'} else {'com.buzincast.tv'}
            if ($storeMetadata.applicationId -ne $storeId -or $storeMetadata.elements[0].versionCode -ne $VersionCode -or $storeMetadata.elements[0].versionName -ne $VersionName) { throw 'Unexpected store candidate identity/version.' }
            $storeSource = Join-Path $storeBuild 'outputs/apk/release/fireos-legacy-release.apk'
            Assert-PreviewApk $storeSource
            $storeExport = Join-Path $repo ('outputs/fireos/legacy-store'+$(if ($NoDownload) {'-nodownload'} else {''}))
            New-Item -ItemType Directory -Path $storeExport -Force | Out-Null
            $storeApk = Join-Path $storeExport 'fireos-legacy-release.apk'
            Copy-Item -LiteralPath $storeSource -Destination $storeApk
            if ((Get-FileHash -LiteralPath $storeSource).Hash -ne (Get-FileHash -LiteralPath $storeApk).Hash) { throw 'Exported store candidate differs from build output.' }
            $artifact = [ordered]@{preview=$false;submissionReady=$false;applicationId=$storeId;versionName=$VersionName;versionCode=$VersionCode;
                builtAtUtc=[DateTime]::UtcNow.ToString('o');apk='fireos-legacy-release.apk';sha256=(Get-FileHash -LiteralPath $storeApk -Algorithm SHA256).Hash.ToLowerInvariant();
                pending=@('Validate the Amazon license through the Appstore on a supported physical Stick','Review device targeting and version codes alongside the modern/compat APKs')}
            [IO.File]::WriteAllText((Join-Path $storeExport 'artifact.json'),($artifact | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
            Write-Host "Signed legacy store candidate exported to $storeExport (not submitted)"
            return
        }
        # A project-wide buildDirectory switch can make Gradle remove the other
        # edition's APK as stale output. Export completed previews outside it.
        $legacyBuild = Join-Path $repo ('fireos-legacy/'+$(if ($NoDownload) {'build-nodownload'} else {'build'}))
        $legacyExport = Join-Path $repo ('outputs/fireos/legacy-preview'+$(if ($NoDownload) {'-nodownload'} else {''}))
        $legacyMetadata = Get-Content -LiteralPath (Join-Path $legacyBuild 'outputs/apk/debug/output-metadata.json') -Raw | ConvertFrom-Json
        $expectedId = if ($NoDownload) {'com.buzincast.tv.deat.legacy.preview'} else {'com.buzincast.tv.legacy.preview'}
        if ($legacyMetadata.applicationId -ne $expectedId) { throw 'Unexpected legacy APK application ID.' }
        New-Item -ItemType Directory -Path $legacyExport -Force | Out-Null
        $legacyApk = Join-Path $legacyExport 'fireos-legacy-debug.apk'
        $legacyApkSource = Join-Path $legacyBuild 'outputs/apk/debug/fireos-legacy-debug.apk'
        Assert-PreviewApk $legacyApkSource
        Copy-Item -LiteralPath $legacyApkSource -Destination $legacyApk
        if ((Get-FileHash -LiteralPath $legacyApkSource).Hash -ne (Get-FileHash -LiteralPath $legacyApk).Hash) { throw 'Exported APK differs from the build output.' }
        $artifact = [ordered]@{preview=$true;applicationId=$expectedId;versionName=$legacyMetadata.elements[0].versionName;
            versionCode=$legacyMetadata.elements[0].versionCode;builtAtUtc=[DateTime]::UtcNow.ToString('o');
            apk='fireos-legacy-debug.apk';sha256=(Get-FileHash -LiteralPath $legacyApk -Algorithm SHA256).Hash.ToLowerInvariant()}
        $legacyTestApk = Join-Path $legacyExport 'fireos-legacy-debug-androidTest.apk'
        if ($Instrumentation) {
            $legacyTestSource = Join-Path $legacyBuild 'outputs/apk/androidTest/debug/fireos-legacy-debug-androidTest.apk'
            Assert-PreviewApk $legacyTestSource
            Copy-Item -LiteralPath $legacyTestSource -Destination $legacyTestApk
            if ((Get-FileHash -LiteralPath $legacyTestSource).Hash -ne (Get-FileHash -LiteralPath $legacyTestApk).Hash) { throw 'Exported test APK differs from the build output.' }
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
