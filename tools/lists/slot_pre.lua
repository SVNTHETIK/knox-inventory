-- What KnoxEquipment.lua needs to load outside the game, over tools/settings/stubs.lua
-- (tools/lists/slot.lua): its slot is a plain element, body locations named plainly.
ISUIElement = ISPanel
KnoxFootprint = {norm = function(s)
    s = string.gsub(string.lower(tostring(s)), "^.*[:%.]", "")
    return (string.gsub(s, "[^%a%d]", ""))
end}   -- (as KnoxFootprint.norm)
function instanceof() return false end
KnoxTheme.refresh()
