# The health tab's wound pictures beside the mockup's (the author, 2026-11-10): the mockup's shots
# for each state (Edge without a window, .claude/mockups/health/shot.ps1), the game's own drawing of
# the same states (KnoxWoundArt / KnoxWoundGame from java/src, run by WoundShots.java - no game
# needed), and the sheet "mockup | game" in .claude/mockups/health/shots/sync/.
#   tools\health\shots.ps1            all states, the mockup's shots taken anew
#   tools\health\shots.ps1 -NoMockup  only the game's side (the mockup's shots kept)
param([switch]$NoMockup)
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot)
$jdk = "C:\Users\pielo\Zomboid\tools\jdk-25\bin"
$mock = Join-Path $root ".claude\mockups\health"
$shots = Join-Path $mock "shots\v3"
$out = Join-Path $mock "shots\sync"
$build = Join-Path $env:TEMP "knox_woundshots"
$words = Join-Path $root "42\media\lua\shared\Translate\RU\IG_UI.json"

if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force $build | Out-Null
New-Item -ItemType Directory -Force $out | Out-Null
$src = @(
    (Join-Path $root "java\src\knox\java\KnoxWoundArt.java"),
    (Join-Path $root "java\src\knox\java\KnoxWoundGame.java"),
    (Join-Path $PSScriptRoot "WoundShots.java"))
& "$jdk\javac.exe" -encoding UTF-8 -d $build @src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

if (-not $NoMockup) {
    # (the mockup is built from its sources first, so the shots are of its latest state)
    & python -I (Join-Path $mock "tool\make_v3.py")
    $list = Join-Path $build "states.txt"
    & "$jdk\java.exe" -cp $build WoundShots $shots $out $words $list
    foreach ($line in (Get-Content $list -Encoding UTF8)) {
        if (-not $line) { continue }
        $name, $query = $line -split "`t", 2
        & (Join-Path $mock "shot.ps1") -Page "health_v3.html" -Name ("v3\hs_" + $name) -Query $query | Out-Null
    }
}
& "$jdk\java.exe" "-Djava.awt.headless=true" -cp $build WoundShots $shots $out $words
if ($LASTEXITCODE -ne 0) { throw "WoundShots failed" }
Write-Output (Join-Path $out "sheet_mockup_game.png")
