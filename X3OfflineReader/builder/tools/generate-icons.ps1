[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$assets = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\assets'))
$pngPath = Join-Path $assets 'tingdu-workshop-icon.png'
$icoPath = Join-Path $assets 'tingdu-workshop-icon.ico'
$validationRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\build\icon-v22-validation'))
New-Item -ItemType Directory -Force -Path $assets | Out-Null
New-Item -ItemType Directory -Force -Path $validationRoot | Out-Null

function New-RoundedPath([float]$x,[float]$y,[float]$width,[float]$height,[float]$radius) {
    $path = New-Object Drawing.Drawing2D.GraphicsPath
    $diameter = $radius * 2
    $path.AddArc($x,$y,$diameter,$diameter,180,90)
    $path.AddArc($x+$width-$diameter,$y,$diameter,$diameter,270,90)
    $path.AddArc($x+$width-$diameter,$y+$height-$diameter,$diameter,$diameter,0,90)
    $path.AddArc($x,$y+$height-$diameter,$diameter,$diameter,90,90)
    $path.CloseFigure()
    return $path
}

$bitmap = [Drawing.Bitmap]::new(512,512,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.CompositingMode=[Drawing.Drawing2D.CompositingMode]::SourceCopy
$graphics.Clear([Drawing.Color]::Transparent)
$graphics.CompositingMode=[Drawing.Drawing2D.CompositingMode]::SourceOver
$ochre = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml('#D8A142'))
$warmWhite = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml('#F7F3EA'))
$charcoal = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml('#2F3437'))

$panel = New-RoundedPath 46 46 420 420 84
$graphics.FillPath($ochre,$panel)
$window = New-RoundedPath 96 104 320 304 32
$graphics.FillPath($warmWhite,$window)
$graphics.FillRectangle($ochre,120,160,272,224)
foreach($x in 106,136,166){$graphics.FillEllipse($charcoal,$x,126,20,20)}

$leftBook = [Drawing.PointF[]]@(
    [Drawing.PointF]::new(136,224),[Drawing.PointF]::new(178,214),[Drawing.PointF]::new(216,224),[Drawing.PointF]::new(240,240),
    [Drawing.PointF]::new(240,384),[Drawing.PointF]::new(212,366),[Drawing.PointF]::new(176,358),[Drawing.PointF]::new(136,369)
)
$rightBook = [Drawing.PointF[]]@(
    [Drawing.PointF]::new(376,224),[Drawing.PointF]::new(334,214),[Drawing.PointF]::new(296,224),[Drawing.PointF]::new(272,240),
    [Drawing.PointF]::new(272,384),[Drawing.PointF]::new(300,366),[Drawing.PointF]::new(336,358),[Drawing.PointF]::new(376,369)
)
$graphics.FillPolygon($warmWhite,$leftBook)
$graphics.FillPolygon($warmWhite,$rightBook)
foreach($bar in @(@(176,280,16,52),@(204,258,16,96),@(232,240,16,132),@(260,258,16,96),@(288,280,16,52))){
    $shape=New-RoundedPath $bar[0] $bar[1] $bar[2] $bar[3] 9
    $graphics.FillPath($charcoal,$shape)
    $shape.Dispose()
}
$bitmap.Save($pngPath,[Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose();$bitmap.Dispose();$panel.Dispose();$window.Dispose();$ochre.Dispose();$warmWhite.Dispose();$charcoal.Dispose()

$sourceBitmap=[Drawing.Bitmap]::FromFile($pngPath)
$sizes=@(16,24,32,48,64,128,256)
$entries=@()
$report=@('听读工坊 Windows icon v2.2','size cornerAlpha centerAlpha opaqueBoundsRatio')
foreach($size in $sizes){
    $scaled=[Drawing.Bitmap]::new($size,$size,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $renderer=[Drawing.Graphics]::FromImage($scaled)
    $renderer.CompositingMode=[Drawing.Drawing2D.CompositingMode]::SourceCopy
    $renderer.Clear([Drawing.Color]::Transparent)
    $renderer.CompositingMode=[Drawing.Drawing2D.CompositingMode]::SourceOver
    $renderer.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $renderer.PixelOffsetMode=[Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $renderer.DrawImage($sourceBitmap,0,0,$size,$size)
    [int]$last=$size-1
    foreach($point in @(@(0,0),@($last,0),@(0,$last),@($last,$last))){if($scaled.GetPixel($point[0],$point[1]).A -ne 0){throw "Icon $size px corner alpha is not zero"}}
    if($scaled.GetPixel([int]($size/2),[int]($size/2)).A -ne 255){throw "Icon $size px center is not opaque"}
    $minX=$size;$minY=$size;$maxX=-1;$maxY=-1
    for($y=0;$y -lt $size;$y++){for($x=0;$x -lt $size;$x++){if($scaled.GetPixel($x,$y).A -gt 8){$minX=[Math]::Min($minX,$x);$minY=[Math]::Min($minY,$y);$maxX=[Math]::Max($maxX,$x);$maxY=[Math]::Max($maxY,$y)}}}
    $ratio=[Math]::Max(($maxX-$minX+1)/$size,($maxY-$minY+1)/$size)
    if($size -ge 48 -and ($ratio -lt 0.81 -or $ratio -gt 0.85)){throw "Icon $size px opaque bounds ratio $ratio is outside 0.81..0.85"}
    if($size -lt 48 -and ($ratio -lt 0.75 -or $ratio -gt 0.90)){throw "Icon $size px opaque bounds ratio $ratio is outside small-size tolerance"}
    $scaled.Save((Join-Path $validationRoot ("$size.png")),[Drawing.Imaging.ImageFormat]::Png)
    $report+=("{0} 0 255 {1:N4}" -f $size,$ratio)
    $memory=New-Object IO.MemoryStream
    $scaled.Save($memory,[Drawing.Imaging.ImageFormat]::Png)
    $entries+=,@($size,$memory.ToArray())
    $memory.Dispose();$renderer.Dispose();$scaled.Dispose()
}
$sourceBitmap.Dispose()
$stream = [IO.File]::Create($icoPath)
$writer = New-Object IO.BinaryWriter $stream
$writer.Write([UInt16]0);$writer.Write([UInt16]1);$writer.Write([UInt16]$entries.Count)
$offset=6+16*$entries.Count
foreach($entry in $entries){
    $dimension=if($entry[0] -eq 256){0}else{$entry[0]}
    $writer.Write([Byte]$dimension);$writer.Write([Byte]$dimension);$writer.Write([Byte]0);$writer.Write([Byte]0)
    $writer.Write([UInt16]1);$writer.Write([UInt16]32);$writer.Write([UInt32]$entry[1].Length);$writer.Write([UInt32]$offset)
    $offset+=$entry[1].Length
}
foreach($entry in $entries){$writer.Write([byte[]]$entry[1])}
$writer.Dispose()
$report | Set-Content -LiteralPath (Join-Path $validationRoot 'validation.txt') -Encoding UTF8
Write-Host "Generated and alpha-validated: $icoPath"
