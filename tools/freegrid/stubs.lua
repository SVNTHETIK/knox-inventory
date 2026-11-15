-- What KnoxGrid.lua and KnoxOrder.lua touch outside the game (tools/freegrid/test.ps1):
-- the theme's settings (the switch "grid.free") and categories, locks, sounds.
require = function() end
KnoxTheme = {
    settings = {},
    category = {wpn = 1, ammo = 1, med = 1, food = 1, tool = 1, mat = 1, clo = 1, info = 1, misc = 1},
    choice = function(key) return KnoxTheme.settings[key] end,
    switch = function(key) return KnoxTheme.settings[key] == "1" end,
    categoryOf = function(item) return item.o and item.o.cat or "misc" end,
    saveSettings = function() end,
    animOn = function() return true end,
}
KnoxActions = {isLocked = function(e) return false end}
function getSoundManager() return {playUISound = function() end} end
function getSpecificPlayer() return nil end
function isClient() return false end
