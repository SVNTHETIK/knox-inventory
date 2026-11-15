# The Health tab's Lua through its states on a stub game (2026-11-10): the real KnoxHealthCU, KnoxHealthData,
# KnoxHealthCare, KnoxHealthModel, KnoxHealthWounds, KnoxHealthGames and KnoxHealthPanel over tab_stubs.lua
# (the game, the layout kit, Knox's Java), driven by tab_test.lua. Not the game: only that it runs and does
# what it says. Prints "N passed, M failed".
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$game = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
$java = "C:\Users\pielo\Zomboid\tools\jdk-25\bin\java.exe"
$run = Join-Path $root "java\tools\luacheck\LuaRun.java"
$knox = Join-Path $root "42\media\lua\client\Knox"
$files = 'KnoxHealthCU.lua', 'KnoxHealthData.lua', 'KnoxHealthCare.lua', 'KnoxHealthModel.lua', 'KnoxHealthWounds.lua',
    'KnoxHealthGames.lua', 'KnoxHealthPanel.lua' | ForEach-Object { Join-Path $knox $_ }
$doll = Join-Path $knox 'KnoxHealthModelData.lua'
Push-Location $game
try {
    & $java -cp "$game\projectzomboid.jar" $run (Join-Path $PSScriptRoot "tab_stubs.lua") @files `
        (Join-Path $PSScriptRoot "tab_test.lua") "DOLL=$doll"
} finally { Pop-Location }
