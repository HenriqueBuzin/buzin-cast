Add-Type -AssemblyName System.Drawing
$repo=(Split-Path -Parent $PSScriptRoot)
$source=[Drawing.Image]::FromFile("$repo\app\src\main\res\drawable-nodpi\buzin_icon.png")
try {
 foreach($variant in @(@('mdpi',48),@('hdpi',72),@('xhdpi',96),@('xxhdpi',144),@('xxxhdpi',192))) {
  $dir="$repo\app\src\main\res\mipmap-$($variant[0])"; [IO.Directory]::CreateDirectory($dir) | Out-Null
  $bmp=New-Object Drawing.Bitmap([int]$variant[1],[int]$variant[1]); $g=[Drawing.Graphics]::FromImage($bmp)
  try {$g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic; $g.DrawImage($source,0,0,$bmp.Width,$bmp.Height); $bmp.Save("$dir\buzin_launcher.png",[Drawing.Imaging.ImageFormat]::Png)} finally {$g.Dispose();$bmp.Dispose()}
 }
} finally {$source.Dispose()}
$source=[Drawing.Image]::FromFile("$repo\app\src\main\res\drawable-nodpi\buzin_banner.png")
$dir="$repo\app\src\main\res\drawable-xhdpi"; [IO.Directory]::CreateDirectory($dir) | Out-Null
$bmp=New-Object Drawing.Bitmap(320,180); $g=[Drawing.Graphics]::FromImage($bmp)
try {$g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic; $g.DrawImage($source,0,0,320,180); $bmp.Save("$dir\buzin_tv_banner.png",[Drawing.Imaging.ImageFormat]::Png)} finally {$g.Dispose();$bmp.Dispose();$source.Dispose()}
# TV launchers can display a landscape icon; keep the square variants for phones.
$source=[Drawing.Image]::FromFile("$repo\app\src\main\res\drawable-nodpi\buzin_banner.png")
try {
 foreach($variant in @(@('mdpi',160,90),@('hdpi',240,135),@('xhdpi',320,180),@('xxhdpi',480,270),@('xxxhdpi',640,360))) {
  $dir="$repo\app\src\main\res\mipmap-television-$($variant[0])"; [IO.Directory]::CreateDirectory($dir) | Out-Null
  $bmp=New-Object Drawing.Bitmap([int]$variant[1],[int]$variant[2]); $g=[Drawing.Graphics]::FromImage($bmp)
  try {$g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic; $g.DrawImage($source,0,0,$bmp.Width,$bmp.Height); $bmp.Save("$dir\buzin_launcher.png",[Drawing.Imaging.ImageFormat]::Png)} finally {$g.Dispose();$bmp.Dispose()}
 }
} finally {$source.Dispose()}
