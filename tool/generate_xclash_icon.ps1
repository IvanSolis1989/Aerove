param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$PreviewDirectory
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if (-not ('XClashIconExport' -as [type])) {
    Add-Type -ReferencedAssemblies @(
        [System.Drawing.Bitmap].Assembly.Location,
        [System.Drawing.Color].Assembly.Location
        [System.Drawing.Image].GetInterfaces().Assembly.Location
    ) -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

public sealed class XClashIconMetrics {
    public int Width, Height, MinX, MinY, MaxX, MaxY;
    public long Transparent, Partial, Opaque;
    public double Radius;
    public double VisibleRadius;
}

public static class XClashIconExport {
    public static void Export(string source, string destination, int size, float scale, string mask) {
        int samples = size <= 320 ? 4 : 1;
        int workingSize = size * samples;
        using (var original = new Bitmap(source))
        using (var canvas = new Bitmap(workingSize, workingSize, PixelFormat.Format32bppArgb)) {
            using (var graphics = Graphics.FromImage(canvas)) {
                graphics.Clear(mask == "none" ? Color.Transparent : Color.FromArgb(250, 250, 252));
                graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
                graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
                float length = workingSize * scale;
                float offset = (workingSize - length) / 2;
                graphics.DrawImage(original, new RectangleF(offset, offset, length, length));
            }
            if (mask != "none") {
                int exponent = mask == "circle" ? 2 : 4;
                for (int y = 0; y < workingSize; y++) {
                    double dy = Math.Abs((y + 0.5) * 2 / workingSize - 1);
                    for (int x = 0; x < workingSize; x++) {
                        double dx = Math.Abs((x + 0.5) * 2 / workingSize - 1);
                        if (Math.Pow(dx, exponent) + Math.Pow(dy, exponent) > 1)
                            canvas.SetPixel(x, y, Color.Transparent);
                    }
                }
            }
            using (var output = new Bitmap(size, size, PixelFormat.Format32bppArgb)) {
                using (var graphics = Graphics.FromImage(output)) {
                    graphics.CompositingMode = CompositingMode.SourceCopy;
                    graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
                    graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
                    graphics.DrawImage(canvas, new Rectangle(0, 0, size, size));
                }
                output.Save(destination, ImageFormat.Png);
            }
        }
    }

    public static XClashIconMetrics Inspect(string path) {
        using (var image = new Bitmap(path)) {
            var result = new XClashIconMetrics {
                Width = image.Width, Height = image.Height, MinX = image.Width, MinY = image.Height
            };
            for (int y = 0; y < image.Height; y++) {
                for (int x = 0; x < image.Width; x++) {
                    int alpha = image.GetPixel(x, y).A;
                    if (alpha == 0) { result.Transparent++; continue; }
                    if (alpha == 255) result.Opaque++; else result.Partial++;
                    result.MinX = Math.Min(result.MinX, x); result.MinY = Math.Min(result.MinY, y);
                    result.MaxX = Math.Max(result.MaxX, x); result.MaxY = Math.Max(result.MaxY, y);
                    double dx = x + 0.5 - image.Width / 2.0;
                    double dy = y + 0.5 - image.Height / 2.0;
                    result.Radius = Math.Max(result.Radius, Math.Sqrt(dx * dx + dy * dy));
                    if (alpha >= 16)
                        result.VisibleRadius = Math.Max(result.VisibleRadius, Math.Sqrt(dx * dx + dy * dy));
                }
            }
            return result;
        }
    }
}
'@
}

$source = Join-Path $RepositoryRoot 'assets_source/images/icon/xclash-liquid-glass.png'
$resources = Join-Path $RepositoryRoot 'android/app/src/main/res'
$metrics = [XClashIconExport]::Inspect($source)
if ($metrics.Width -ne $metrics.Height -or $metrics.Transparent -eq 0) {
    throw 'The artist original must be square with transparency.'
}
$safeRadius = $metrics.VisibleRadius * 76 / $metrics.Width
if ($safeRadius -gt 33) {
    throw "The 16dp inset exceeds the adaptive 66dp safe zone: radius $safeRadius dp."
}

$outputs = [System.Collections.Generic.List[object]]::new()
function Export-Icon([string]$Path, [int]$Size, [single]$Scale, [string]$Mask) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [XClashIconExport]::Export($source, $Path, $Size, $Scale, $Mask)
    $outputs.Add([pscustomobject]@{ Path = $Path; Size = $Size })
}

Export-Icon (Join-Path $RepositoryRoot 'assets/images/icon.png') 1024 1 'none'
Export-Icon (Join-Path $resources 'drawable-nodpi/xclash_liquid_glass.png') 1024 1 'none'
$densities = [ordered]@{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }
foreach ($density in $densities.Keys) {
    foreach ($qualifier in @("mipmap-$density", "mipmap-television-$density")) {
        $directory = Join-Path $resources $qualifier
        Export-Icon (Join-Path $directory 'ic_launcher.png') $densities[$density] 0.84 'superellipse'
        $old = Join-Path $directory 'ic_launcher.webp'
        if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old }
    }
    $directory = Join-Path $resources "mipmap-$density"
    Export-Icon (Join-Path $directory 'ic_launcher_round.png') $densities[$density] 0.84 'circle'
    $old = Join-Path $directory 'ic_launcher_round.webp'
    if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old }
}

$foreground = @'
<?xml version="1.0" encoding="utf-8"?>
<inset xmlns:android="http://schemas.android.com/apk/res/android"
    android:insetLeft="14.814815%"
    android:insetTop="14.814815%"
    android:insetRight="14.814815%"
    android:insetBottom="14.814815%">
    <bitmap
        android:src="@drawable/xclash_liquid_glass"
        android:gravity="fill"
        android:filter="true" />
</inset>
'@
$adaptive = @'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'@
$background = @'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FAFAFC</color>
</resources>
'@
$xmlOutputs = @{
    'drawable/ic_launcher_foreground.xml' = $foreground
    'drawable/ic_launcher_foreground_tv.xml' = $foreground
    'mipmap-anydpi-v26/ic_launcher.xml' = $adaptive
    'mipmap-anydpi-v26/ic_launcher_round.xml' = $adaptive
    'mipmap-television-anydpi-v26/ic_launcher.xml' = $adaptive
    'values/ic_launcher_background.xml' = $background
}
foreach ($relative in $xmlOutputs.Keys) {
    [System.IO.File]::WriteAllText((Join-Path $resources $relative), $xmlOutputs[$relative] + "`n")
}
if (-not (Test-Path -LiteralPath (Join-Path $resources 'drawable/ic_launcher_monochrome.xml'))) {
    throw 'The preserved original monochrome vector is missing.'
}
foreach ($relative in $xmlOutputs.Keys) {
    $document = [xml](Get-Content -Raw -LiteralPath (Join-Path $resources $relative))
    foreach ($match in [regex]::Matches($document.OuterXml, '@drawable/([a-z0-9_]+)')) {
        $name = $match.Groups[1].Value
        if (-not (Test-Path (Join-Path $resources "drawable/$name.xml")) -and
            -not (Test-Path (Join-Path $resources "drawable-nodpi/$name.png"))) {
            throw "Unresolved drawable: $name"
        }
    }
}
foreach ($variant in @('values-v27', 'values-night-v27')) {
    $document = [xml](Get-Content -Raw -LiteralPath (Join-Path $resources "$variant/styles.xml"))
    $icon = $document.resources.style.item | Where-Object name -EQ 'android:windowSplashScreenAnimatedIcon'
    if ($icon.'#text' -ne '@drawable/ic_launcher_foreground') {
        throw "Unexpected splash icon in $variant"
    }
}

if ($PreviewDirectory) {
    Export-Icon (Join-Path $PreviewDirectory 'adaptive-circle.png') 320 (76.0 / 72) 'circle'
    Export-Icon (Join-Path $PreviewDirectory 'adaptive-superellipse.png') 320 (76.0 / 72) 'superellipse'
    Export-Icon (Join-Path $PreviewDirectory 'legacy-48.png') 48 0.84 'superellipse'
}

Write-Output "Artist: $($metrics.Width)x$($metrics.Height); adaptive radius at alpha>=16: $safeRadius dp / 33 dp"
Write-Output "All original alpha retained; alpha>0 radius: $($metrics.Radius * 76 / $metrics.Width) dp"
foreach ($entry in $outputs) {
    $actual = [XClashIconExport]::Inspect($entry.Path)
    if ($actual.Width -ne $entry.Size -or $actual.Height -ne $entry.Size -or
        $actual.Transparent -eq 0 -or $actual.Partial -eq 0) {
        throw "Invalid exported PNG: $($entry.Path)"
    }
    [pscustomobject]@{
        Path = $entry.Path
        Width = $actual.Width
        Height = $actual.Height
        Transparent = $actual.Transparent
        Partial = $actual.Partial
        Opaque = $actual.Opaque
    }
}
