Add-Type -AssemblyName System.Drawing
$repo=(Split-Path -Parent $PSScriptRoot)
$source=[Drawing.Image]::FromFile("$repo\app\src\main\res\drawable-nodpi\buzin_fire_icon.png")
try {
 foreach($variant in @(@('mdpi',128),@('hdpi',192),@('xhdpi',256),@('xxhdpi',384),@('xxxhdpi',512))) {
  $dir="$repo\app\src\main\res\mipmap-$($variant[0])"; [IO.Directory]::CreateDirectory($dir) | Out-Null
  $bmp=New-Object Drawing.Bitmap([int]$variant[1],[int]$variant[1]); $g=[Drawing.Graphics]::FromImage($bmp)
  try {$g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic; $a=New-Object Drawing.Imaging.ImageAttributes; $a.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY); try {$g.DrawImage($source,[Drawing.Rectangle]::new(0,0,$bmp.Width,$bmp.Height),0,0,$source.Width,$source.Height,[Drawing.GraphicsUnit]::Pixel,$a)} finally {$a.Dispose()}; $bmp.Save("$dir\buzin_launcher.png",[Drawing.Imaging.ImageFormat]::Png)} finally {$g.Dispose();$bmp.Dispose()}
 }
} finally {$source.Dispose()}
$source=[Drawing.Image]::FromFile("$repo\app\src\main\res\drawable-nodpi\buzin_banner.png")
$dir="$repo\app\src\main\res\drawable-xhdpi"; [IO.Directory]::CreateDirectory($dir) | Out-Null
$bmp=New-Object Drawing.Bitmap(320,180); $g=[Drawing.Graphics]::FromImage($bmp)
try {$g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic; $g.DrawImage($source,0,0,320,180); $bmp.Save("$dir\buzin_tv_banner.png",[Drawing.Imaging.ImageFormat]::Png)} finally {$g.Dispose();$bmp.Dispose();$source.Dispose()}
# Keep a square TV icon: Fire OS squeezes landscape APK icons into a square.
$source=[Drawing.Image]::FromFile("$repo\app\src\main\res\drawable-nodpi\buzin_fire_icon.png")
try {
 foreach($variant in @(@('mdpi',128,128),@('hdpi',192,192),@('xhdpi',256,256),@('xxhdpi',384,384),@('xxxhdpi',512,512))) {
  $dir="$repo\app\src\main\res\mipmap-television-$($variant[0])"; [IO.Directory]::CreateDirectory($dir) | Out-Null
  $bmp=New-Object Drawing.Bitmap([int]$variant[1],[int]$variant[2]); $g=[Drawing.Graphics]::FromImage($bmp)
  try {$g.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic; $a=New-Object Drawing.Imaging.ImageAttributes; $a.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY); try {$g.DrawImage($source,[Drawing.Rectangle]::new(0,0,$bmp.Width,$bmp.Height),0,0,$source.Width,$source.Height,[Drawing.GraphicsUnit]::Pixel,$a)} finally {$a.Dispose()}; $bmp.Save("$dir\buzin_launcher.png",[Drawing.Imaging.ImageFormat]::Png)} finally {$g.Dispose();$bmp.Dispose()}
 }
} finally {$source.Dispose()}
