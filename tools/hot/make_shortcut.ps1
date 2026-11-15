# Makes the desktop shortcut "Knox DEV" (the author's request 2026-11-10): runs launch.ps1
# (Steam if needed, then the game on JetBrains Runtime for the DEV reloader), with the Knox
# logo as its icon. The icon (knox_dev.ico) is built here from tools\art: the full logo for
# 48 px and up, only the cross below that (the logo's lettering is unreadable at 16-32 px).
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path (Split-Path $PSScriptRoot)
$ico = Join-Path $PSScriptRoot 'knox_dev.ico'
$full = [Drawing.Image]::FromFile((Join-Path $root 'tools\art\knox_logo_avatar.png'))
$mark = [Drawing.Image]::FromFile((Join-Path $root 'tools\art\knox_logo_mark.png'))

$pngs = @()
foreach ($s in 256, 128, 64, 48, 32, 24, 16) {
    $src = if ($s -ge 48) { $full } else { $mark }
    $bmp = New-Object Drawing.Bitmap $s, $s
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'HighQuality'; $g.PixelOffsetMode = 'HighQuality'
    $g.DrawImage($src, 0, 0, $s, $s)
    $g.Dispose()
    $ms = New-Object IO.MemoryStream
    $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    $pngs += , @($s, $ms.ToArray())
}
$full.Dispose(); $mark.Dispose()

# (ICO with PNG entries: header, one directory entry per size, then the images)
$out = New-Object IO.MemoryStream
$w = New-Object IO.BinaryWriter $out
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$pngs.Count)
$offset = 6 + 16 * $pngs.Count
foreach ($p in $pngs) {
    $d = if ($p[0] -ge 256) { 0 } else { $p[0] }
    $w.Write([byte]$d); $w.Write([byte]$d); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$p[1].Length); $w.Write([uint32]$offset)
    $offset += $p[1].Length
}
foreach ($p in $pngs) { $w.Write($p[1]) }
$w.Flush()
[IO.File]::WriteAllBytes($ico, $out.ToArray())

$lnk = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Knox DEV.lnk'
$sh = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk)
$sh.TargetPath = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$sh.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $PSScriptRoot 'launch.ps1')`""
$sh.WorkingDirectory = $PSScriptRoot
$sh.IconLocation = "$ico,0"
$sh.Description = 'Project Zomboid with Knox DEV reloader (JetBrains Runtime)'
$sh.Save()
"Shortcut: $lnk"
