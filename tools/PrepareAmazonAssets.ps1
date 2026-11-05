param([Parameter(Mandatory=$true)][string]$CaptureDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetRoot = Join-Path $PSScriptRoot '..\store\amazon\images'
$captureRoot = Join-Path $assetRoot 'screenshots\pt-BR'
New-Item -ItemType Directory -Path $captureRoot -Force | Out-Null

function Export-StorePng([string]$InputPath, [string]$OutputPath, [int]$Width, [int]$Height) {
    $sourceImage = [System.Drawing.Image]::FromFile($InputPath)
    $outputImage = [System.Drawing.Bitmap]::new($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $drawing = [System.Drawing.Graphics]::FromImage($outputImage)
    try {
        $drawing.Clear([System.Drawing.ColorTranslator]::FromHtml('#081323'))
        $drawing.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $drawing.DrawImage($sourceImage, [System.Drawing.Rectangle]::new(0, 0, $Width, $Height))
        $outputImage.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $drawing.Dispose(); $outputImage.Dispose(); $sourceImage.Dispose() }
}

& (Join-Path $PSScriptRoot 'GenerateBrandAssets.ps1') -StoreDirectory $assetRoot
foreach ($captureName in @('01-conectar.png','02-configuracoes.png','03-sobre.png')) {
    $capturePath = Join-Path $CaptureDirectory $captureName
    $captureImage = [System.Drawing.Image]::FromFile($capturePath)
    try {
        if ($captureImage.Width -ne 1920 -or $captureImage.Height -ne 1080) { throw "Capture must be native 1920x1080: $captureName" }
    } finally { $captureImage.Dispose() }
    # Native-size re-encoding to RGB24; no cropping, overlays or UI modification.
    Export-StorePng $capturePath (Join-Path $captureRoot $captureName) 1920 1080
}

# Code-rendered background using the app's palette and signal-orbit motif.
# Amazon backgrounds must not embed titles or logos.
$background = [System.Drawing.Bitmap]::new(1920, 1080, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$backgroundDrawing = [System.Drawing.Graphics]::FromImage($background)
$backgroundRectangle = [System.Drawing.Rectangle]::new(0,0,1920,1080)
$backgroundBrush = [System.Drawing.Drawing2D.LinearGradientBrush]::new($backgroundRectangle,
    [System.Drawing.ColorTranslator]::FromHtml('#081019'), [System.Drawing.ColorTranslator]::FromHtml('#173B40'), 18.0)
try {
    $backgroundDrawing.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $backgroundDrawing.FillRectangle($backgroundBrush,$backgroundRectangle)
    foreach ($orbitRadius in @(180,300,420,540)) {
        $orbitPen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(48,124,244,205),3)
        try { $backgroundDrawing.DrawEllipse($orbitPen,1410-$orbitRadius,520-$orbitRadius,2*$orbitRadius,2*$orbitRadius) }
        finally { $orbitPen.Dispose() }
    }
    $signalBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(210,124,244,205))
    try { $backgroundDrawing.FillEllipse($signalBrush,1395,505,30,30) } finally { $signalBrush.Dispose() }
    $background.Save((Join-Path $assetRoot 'fire-tv-background-1920x1080.png'),[System.Drawing.Imaging.ImageFormat]::Png)
} finally { $backgroundBrush.Dispose(); $backgroundDrawing.Dispose(); $background.Dispose() }
Write-Output 'Amazon assets exported from approved artwork and real Fire TV screenshots.'
