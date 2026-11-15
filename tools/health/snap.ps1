# The Health tab's layout from the offline run, beside the mockup (2026-11-10): tab_snap.lua writes what the
# real KnoxHealthPanel draws on the stub game as SVG pages, Edge takes a still of each (1920x1080) into
# .claude\mockups\health\shots\game_layout\. Not the game: no 3D model, grey boxes for the wound pictures,
# text measured roughly - for the places of things, not the look.
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$game = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
$java = "C:\Users\pielo\Zomboid\tools\jdk-25\bin\java.exe"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$run = Join-Path $root "java\tools\luacheck\LuaRun.java"
$knox = Join-Path $root "42\media\lua\client\Knox"
$out = Join-Path $root ".claude\mockups\health\shots\game_layout"
New-Item -ItemType Directory -Force $out | Out-Null
$files = 'KnoxHealthCU.lua', 'KnoxHealthData.lua', 'KnoxHealthCare.lua', 'KnoxHealthModel.lua', 'KnoxHealthWounds.lua',
    'KnoxHealthGames.lua', 'KnoxHealthPanel.lua' | ForEach-Object { Join-Path $knox $_ }
$ru = Join-Path $root "42\media\lua\shared\Translate\RU\IG_UI.json"
Push-Location $game
try {
    & $java -cp "$game\projectzomboid.jar" $run (Join-Path $PSScriptRoot "tab_stubs.lua") @files `
        (Join-Path $PSScriptRoot "tab_snap.lua") "DOLL=$(Join-Path $knox 'KnoxHealthModelData.lua')" "RU_JSON=$ru" "OUT=$out"
} finally { Pop-Location }
foreach ($h in Get-ChildItem $out -Filter *.html) {
    $png = [IO.Path]::ChangeExtension($h.FullName, ".png")
    $url = "file:///" + ($h.FullName -replace '\\', '/' -replace ' ', '%20')
    Start-Process -FilePath $edge -ArgumentList @("--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
        "--force-device-scale-factor=1", "--window-size=1920,1080", "--virtual-time-budget=3000", "--screenshot=`"$png`"", "`"$url`"") -Wait
    Write-Output $png
}
