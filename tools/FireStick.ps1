param(
    [Parameter(Mandatory=$true)][string]$Fire,
    [Parameter(Mandatory=$true)]
    [ValidateSet('Info','Install','Open','Stop','Screenshot','Apps','Cache','Capture','Uninstall')][string]$Action,
    [ValidateSet('both','normal','deat')][string]$Edition = 'both',
    [string]$ApkPath,
    [Alias('Idiomas')][string[]]$Locales,
    [ValidateSet('all','store','production')][string]$Suite = 'all',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../outputs/firestick-tools'),
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'),
    [switch]$Restart,
    [switch]$RestoreProduction
)
$ErrorActionPreference = 'Stop'
if ($Action -eq 'Cache') {
    & (Join-Path $PSScriptRoot 'Atualizar-logo-BuzinCast.ps1') -Fire $Fire -AdbPath $AdbPath -OutputDirectory $OutputDirectory -Restart:$Restart
    exit $LASTEXITCODE
}
if ($Action -eq 'Capture') {
    $captureOptions = @{ Fire=$Fire; Suite=$Suite; Edition=$Edition; Locales=$Locales; OutputDirectory=$OutputDirectory; AdbPath=$AdbPath; RestoreProduction=$RestoreProduction }
    & (Join-Path $PSScriptRoot 'Test-FireStick.ps1') @captureOptions
    exit $LASTEXITCODE
}
if (-not (Test-Path -LiteralPath $AdbPath)) { throw 'ADB não encontrado. Informe -AdbPath.' }
$device = $Fire
$configFile = Join-Path $PSScriptRoot 'firesticks.local.json'
if (Test-Path -LiteralPath $configFile) {
    $devices = Get-Content -Raw -LiteralPath $configFile | ConvertFrom-Json
    $entry = $devices.PSObject.Properties[$Fire]
    if ($entry) { $device = [string]$entry.Value }
}
if ($device -match '^\d{1,3}(\.\d{1,3}){3}$') { $device += ':5555' }
$package = if ($Edition -eq 'deat') { 'com.buzincast.tv.deat' } else { 'com.buzincast.tv' }
function Invoke-Device {
    $result = & $AdbPath -s $device @args
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -match '(?m)^(Failure|Error:)') {
        throw "Operação ADB falhou: $($result -join ' ')"
    }
    $result
}
if ($Action -eq 'Install' -and (-not $ApkPath -or -not (Test-Path -LiteralPath $ApkPath -PathType Leaf))) {
    throw 'Informe -ApkPath apontando para um APK existente.'
}
if ($device.Contains(':')) {
    & $AdbPath connect $device | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao conectar ao aparelho selecionado.' }
}
switch ($Action) {
    Info {
        'Modelo: ' + ((Invoke-Device shell getprop ro.product.model) -join '').Trim()
        'API: ' + ((Invoke-Device shell getprop ro.build.version.sdk) -join '').Trim()
        Invoke-Device shell dumpsys package $package | Select-String 'versionCode=|versionName='
    }
    Install { Invoke-Device install -r (Resolve-Path -LiteralPath $ApkPath).Path }
    Open { Invoke-Device shell input keyevent 224 | Out-Null; Invoke-Device shell am start -n "$package/io.github.jqssun.airplay.MainActivity" }
    Stop { Invoke-Device shell am force-stop $package }
    Apps { Invoke-Device shell am start -n com.amazon.venezia/.grid.AppsGridLauncherActivity }
    Uninstall { Invoke-Device uninstall $package }
    Screenshot {
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
        $remoteShot = '/sdcard/buzin-tools-' + [guid]::NewGuid().ToString('N') + '.png'
        $destination = Join-Path $OutputDirectory ('screen-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.png')
        try {
            Invoke-Device shell screencap -p $remoteShot | Out-Null
            Invoke-Device pull $remoteShot $destination | Out-Null
            Write-Output $destination
        } finally { Invoke-Device shell rm $remoteShot | Out-Null }
    }
}
