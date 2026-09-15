Add-Type -AssemblyName System.Drawing
$here = $PSScriptRoot
$lines = Get-Content (Join-Path $here 'guide_content.txt') -Encoding UTF8
$title = $lines[0]; $sub = $lines[1]; $foot = $lines[2]
$steps = @()
for ($i = 3; $i -lt $lines.Count; $i++) {
    if ($lines[$i].Trim().Length -eq 0) { continue }
    $parts = $lines[$i] -split '\|\|'
    $steps += ,@($parts[0], $parts[1])
}
$W = 1080; $margin = 56; $cardW = $W - 2 * $margin; $pad = 30; $numD = 64
$textX = $margin + $pad + $numD + 26
$textW = $cardW - $pad * 2 - $numD - 26
function New-FontN($name, $size, $style) {
    try { return New-Object System.Drawing.Font($name, $size, $style, [System.Drawing.GraphicsUnit]::Pixel) }
    catch { return New-Object System.Drawing.Font('SimSun', $size, $style, [System.Drawing.GraphicsUnit]::Pixel) }
}
$fTitle = New-FontN 'Microsoft YaHei' 50 ([System.Drawing.FontStyle]::Bold)
$fSub = New-FontN 'Microsoft YaHei' 30 ([System.Drawing.FontStyle]::Regular)
$fStep = New-FontN 'Microsoft YaHei' 34 ([System.Drawing.FontStyle]::Bold)
$fBody = New-FontN 'Microsoft YaHei' 28 ([System.Drawing.FontStyle]::Regular)
$fNum = New-FontN 'Microsoft YaHei' 34 ([System.Drawing.FontStyle]::Bold)
$fFoot = New-FontN 'Microsoft YaHei' 26 ([System.Drawing.FontStyle]::Regular)
$tmp = New-Object System.Drawing.Bitmap(8, 8)
$mg = [System.Drawing.Graphics]::FromImage($tmp)
$sf = [System.Drawing.StringFormat]::GenericTypographic
function Wrap-Text($text, $font, $maxW, $g, $sf) {
    $out = New-Object System.Collections.ArrayList
    foreach ($para in ($text -split "`n")) {
        $cur = ''
        foreach ($ch in $para.ToCharArray()) {
            $try = $cur + $ch
            if ($g.MeasureString($try, $font, [int]::MaxValue, $sf).Width -gt $maxW -and $cur.Length -gt 0) {
                [void]$out.Add($cur); $cur = [string]$ch
            } else { $cur = $try }
        }
        [void]$out.Add($cur)
    }
    return ,$out
}
$probe = $title.Substring(0, 1)
$bodyLineH = [Math]::Ceiling($mg.MeasureString($probe, $fBody, [int]::MaxValue, $sf).Height) + 8
$titleH = [Math]::Ceiling($mg.MeasureString($probe, $fTitle, [int]::MaxValue, $sf).Height)
$headerH = 96 + $titleH + 20 + 42
$y = $headerH
$plan = @()
foreach ($s in $steps) {
    $bodyLines = Wrap-Text $s[1] $fBody $textW $mg $sf
    $cardH = $pad * 2 + 44 + 10 + $bodyLines.Count * $bodyLineH
    $plan += ,@($s[0], $s[1], $cardH, $bodyLines.Count)
    $y += $cardH + 24
}
$H = $y + 40 + 46 + 70
$bmp = New-Object System.Drawing.Bitmap($W, $H)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$g.Clear([System.Drawing.Color]::FromArgb(255, 11, 15, 20))
$cText = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 230, 237, 243))
$cDim = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 139, 148, 158))
$cCard = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 22, 27, 34))
$cAccent = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 34, 197, 94))
$cAccDk = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 22, 101, 52))
$penLine = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 48, 54, 61), 2)
function Round-Rect($x, $y, $w, $h, $r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, $r * 2, $r * 2, 180, 90)
    $p.AddArc($x + $w - $r * 2, $y, $r * 2, $r * 2, 270, 90)
    $p.AddArc($x + $w - $r * 2, $y + $h - $r * 2, $r * 2, $r * 2, 0, 90)
    $p.AddArc($x, $y + $h - $r * 2, $r * 2, $r * 2, 90, 90)
    $p.CloseFigure()
    return $p
}
$g.DrawString($title, $fTitle, $cText, [float]$margin, [float]80, $sf)
$g.DrawString($sub, $fSub, $cDim, [float]$margin, [float](80 + $titleH + 12), $sf)
$g.FillRectangle($cAccent, [float]$margin, [float]($headerH - 26), 120, 6)
$y = $headerH
$idx = 0
foreach ($p in $plan) {
    $idx++
    $cardH = $p[2]
    $path = Round-Rect $margin $y $cardW $cardH 22
    $g.FillPath($cCard, $path)
    $g.DrawPath($penLine, $path)
    $cy = $y + $pad + 22
    $g.FillEllipse($cAccDk, [float]($margin + $pad), [float]($cy - $numD / 2), [float]$numD, [float]$numD)
    $numStr = [string]$idx
    $nsz = $g.MeasureString($numStr, $fNum, [int]::MaxValue, $sf)
    $g.DrawString($numStr, $fNum, $cAccent, [float]($margin + $pad + ($numD - $nsz.Width) / 2), [float]($cy - $nsz.Height / 2), $sf)
    $g.DrawString($p[0], $fStep, $cText, [float]$textX, [float]($y + $pad - 2), $sf)
    $lines2 = Wrap-Text $p[1] $fBody $textW $mg $sf
    $ly = $y + $pad + 44 + 10
    foreach ($ln in $lines2) {
        $g.DrawString($ln, $fBody, $cDim, [float]$textX, [float]$ly, $sf)
        $ly += $bodyLineH
    }
    $y += $cardH + 24
}
$g.DrawString($foot, $fFoot, $cDim, [float]$margin, [float]($H - 78), $sf)
$g.Dispose(); $mg.Dispose(); $tmp.Dispose()
$out = Join-Path $here '..\app\res\drawable-nodpi\guide.png'
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host ("guide.png written: {0} x {1}" -f $W, $H)
