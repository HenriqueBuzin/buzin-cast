param(
    [Parameter(Mandatory=$true)][string]$Fire,
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../outputs/cache-refresh'),
    [string[]]$CacheLabels = @('Limpar cache', 'Clear cache', 'Cache leeren'),
    [switch]$Restart,
    [switch]$OpenScreenshot
)

$ErrorActionPreference = "Stop"
$adb = $AdbPath
$configFile = Join-Path $PSScriptRoot 'firesticks.local.json'
$device = $Fire
if (Test-Path -LiteralPath $configFile) {
    $devices = Get-Content -Raw -LiteralPath $configFile | ConvertFrom-Json
    $entry = $devices.PSObject.Properties[$Fire]
    if ($entry) { $device = [string]$entry.Value }
}
if ($device -match '^\d{1,3}(\.\d{1,3}){3}$') { $device += ':5555' }
$remoteXml = "/sdcard/buzincast-appstore-settings.xml"
$remoteShot = "/sdcard/buzincast-lista-apps.png"
$localXml = Join-Path $env:TEMP "buzincast-appstore-settings-$([guid]::NewGuid()).xml"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$screenshot = Join-Path $OutputDirectory "BuzinCast-lista-apps-$stamp.png"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB não encontrado em: $adb"
}

# Sem bloco param() de propósito: assim -a, -d e -p chegam intactos ao adb.
function Invoke-Adb {
    & $adb -s $device @args
    if ($LASTEXITCODE -ne 0) {
        throw "O comando ADB falhou: $($args -join ' ')"
    }
}

# Para as verificações durante o reboot, em que o adb pode falhar temporariamente.
function Invoke-AdbQuiet {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        & $adb -s $device @args 2>$null
    } finally {
        $ErrorActionPreference = $previous
    }
}

Write-Host "Conectando ao Fire TV selecionado..." -ForegroundColor Cyan
if ($device.Contains(':')) {
    & $adb connect $device | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Não foi possível conectar ao Fire TV." }
}

$version = Invoke-Adb shell dumpsys package com.buzincast.tv |
    Select-String "versionCode=|versionName=" |
    Select-Object -First 2
Write-Host "BuzinCast instalado:" -ForegroundColor Green
$version | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

Write-Host "Abrindo as opções da Appstore..."
Invoke-Adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.amazon.venezia | Out-Null
Start-Sleep -Seconds 3
Invoke-Adb shell uiautomator dump $remoteXml | Out-Null
Invoke-Adb pull $remoteXml $localXml | Out-Null

$xmlText = Get-Content -Raw -LiteralPath $localXml
[xml]$ui = $xmlText
$cacheNodes = @($ui.SelectNodes('//node') | Where-Object { $CacheLabels -ccontains $_.text })
if ($cacheNodes.Count -ne 1 -or $cacheNodes[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
    throw "Não foi encontrado um único botão de limpar cache. Confira o idioma e use -CacheLabels. Nenhum botão foi pressionado."
}
$cacheX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
$cacheY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)

Write-Host "Limpando somente o cache da Appstore..." -ForegroundColor Yellow
Invoke-Adb shell input tap $cacheX $cacheY | Out-Null
Start-Sleep -Seconds 2

Invoke-Adb shell uiautomator dump $remoteXml | Out-Null
Invoke-Adb pull $remoteXml $localXml | Out-Null
$xmlText = Get-Content -Raw -LiteralPath $localXml
if ($xmlText -match 'resource-id="com.amazon.tv.settings.v2:id/cache_size"[^>]*text="([^"]+)"') {
    Write-Host "Cache da Appstore: $($Matches[1])" -ForegroundColor Green
}

if ($Restart) {
    Write-Host "Reiniciando o Fire TV..." -ForegroundColor Yellow
    Invoke-Adb reboot | Out-Null
    Start-Sleep -Seconds 10

    $connected = $false
    for ($attempt = 1; $attempt -le 24; $attempt++) {
        if ($device.Contains(':')) { & $adb connect $device | Out-Null }
        $state = "$(Invoke-AdbQuiet get-state)".Trim()
        if ($state -eq "device") {
            $connected = $true
            break
        }
        Start-Sleep -Seconds 5
    }
    if (-not $connected) {
        throw "O Fire TV não reconectou após a reinicialização."
    }

    for ($attempt = 1; $attempt -le 18; $attempt++) {
        $boot = "$(Invoke-AdbQuiet shell getprop sys.boot_completed)".Trim()
        if ($boot -eq "1") { break }
        Start-Sleep -Seconds 5
    }
}

Write-Host "Abrindo a lista de aplicativos..."
Invoke-Adb shell am start -n com.amazon.venezia/.grid.AppsGridLauncherActivity | Out-Null
Start-Sleep -Seconds 8
Invoke-Adb shell screencap -p $remoteShot | Out-Null
Invoke-Adb pull $remoteShot $screenshot | Out-Null
Invoke-Adb shell rm $remoteShot | Out-Null
Invoke-Adb shell rm $remoteXml | Out-Null
Remove-Item -LiteralPath $localXml -Force -ErrorAction SilentlyContinue

Write-Host "Captura salva em:" -ForegroundColor Green
Write-Host $screenshot
Write-Host "Se a arte continuar antiga, a imagem ainda não foi atualizada nos servidores do Live App Testing." -ForegroundColor Yellow
if ($OpenScreenshot) { Start-Process -FilePath $screenshot }
