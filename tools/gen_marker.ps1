Add-Type -AssemblyName System.Drawing
$S = 256
$bmp = New-Object System.Drawing.Bitmap($S, $S)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.Color]::Transparent)
$accent = [System.Drawing.Color]::FromArgb(255, 34, 197, 94)
for ($i = 0; $i -lt 16; $i++) {
    $r = 124 - $i * 3.4
    $a = [int](6 + $i * 2.2)
    $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb($a, $accent.R, $accent.G, $accent.B))
    $g.FillEllipse($b, [float]($S/2 - $r), [float]($S/2 - $r), [float]($r*2), [float]($r*2))
    $b.Dispose()
}
$dark = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(110, 11, 15, 20))
$g.FillEllipse($dark, [float]($S/2 - 70), [float]($S/2 - 70), 140, 140)
$white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$g.FillEllipse($white, [float]($S/2 - 62), [float]($S/2 - 62), 124, 124)
$green = New-Object System.Drawing.SolidBrush($accent)
$g.FillEllipse($green, [float]($S/2 - 44), [float]($S/2 - 44), 88, 88)
$hl = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(120, 190, 255, 210))
$g.FillEllipse($hl, [float]($S/2 - 28), [float]($S/2 - 30), 30, 24)
$g.Dispose()
$out = Join-Path $PSScriptRoot '..\app\res\drawable-nodpi\ic_my_location.png'
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host ("marker png written: {0} bytes" -f (Get-Item $out).Length)
