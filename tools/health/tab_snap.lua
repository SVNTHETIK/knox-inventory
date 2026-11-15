-- Snapshots of the Health tab's layout from the offline run (tools/health/snap.ps1, 2026-11-10): what the
-- real KnoxHealthPanel draws on the stub game (tab_stubs.lua), each rectangle and line of text, written as
-- an SVG page of 1920x1080 to set beside the mockup's shots. Not the game: no 3D model, the wound pictures
-- are grey boxes, the text measured roughly (Knox's own fonts are not here) - for the places, not the look.

local P, Model, Care = KnoxHealthPanel, KnoxHealthModel, KnoxHealthCare

local AV = {}
function AV:setDirection(d) self.dir = d end
function AV:isMouseOver() return false end
Model.create = function(parent) parent:addChild(AV) return AV end
Model.update = function() end
Care.openMenu = function() end

PLAYER = newPlayer()
local panel = P:new(0, 0, 1880, 928, 0, nil)
panel:initialise()
panel:createChildren()
panel:shown()

local function frame(dt)
    dt = dt or 1 / 60
    NOW = NOW + math.floor(dt * 1000 + 0.5)
    RUN_ACTIONS(dt)
    panel:prerender()
    panel:render()
end

local function hex(c)
    local function h(v) return string.format("%02x", math.max(0, math.min(255, math.floor(v * 255 + 0.5)))) end
    return "#" .. h(c.r) .. h(c.g) .. h(c.b)
end

local function esc(s)
    s = string.gsub(s, "&", "&amp;")
    s = string.gsub(s, "<", "&lt;")
    return (string.gsub(s, ">", "&gt;"))
end

local WEIGHT = {title = 700, head = 600, body = 500}

local function svg(list)
    local o = {}
    o[#o + 1] = '<!doctype html><html><head><meta charset="utf-8">'
    o[#o + 1] = '<link href="https://fonts.googleapis.com/css2?family=Fira+Sans+Extra+Condensed:wght@500;600;700&display=block" rel="stylesheet">'
    o[#o + 1] = '<style>html,body{margin:0;background:#050606}text{font-family:"Fira Sans Extra Condensed",sans-serif}</style></head><body>'
    o[#o + 1] = '<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080" style="display:block;background:#0b0c0c">'
    o[#o + 1] = '<rect x="0" y="0" width="1920" height="56" fill="#121414"/><rect x="0" y="1012" width="1920" height="68" fill="#121414"/>'
    o[#o + 1] = '<text x="20" y="1040" font-size="13" fill="#8f9289">offline layout of KnoxHealthPanel.lua (stub game, not the game)</text>'
    o[#o + 1] = '<g transform="translate(20,72)">'
    for _, e in ipairs(list) do
        local a = e.a or 1
        if e.k == "rect" then
            o[#o + 1] = string.format('<rect x="%d" y="%d" width="%d" height="%d" fill="%s" fill-opacity="%.3f"/>', e.x, e.y,
                math.max(0, e.w), math.max(0, e.h), hex(e.c), a)
        elseif e.k == "border" then
            local t = e.t
            o[#o + 1] = string.format('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="none" stroke="%s" stroke-width="%d" stroke-opacity="%.3f"/>',
                e.x + t / 2, e.y + t / 2, math.max(0, e.w - t), math.max(0, e.h - t), hex(e.c), t, a)
        elseif e.k == "tex" then
            local name = e.tex
            if string.sub(name, 1, 4) == "pic " then
                o[#o + 1] = string.format('<rect x="%d" y="%d" width="%d" height="%d" fill="#1b1f1f" fill-opacity="%.3f" stroke="#3c4141" stroke-dasharray="4 4"/>',
                    e.x, e.y, e.w, e.h, a)
                o[#o + 1] = string.format('<text x="%d" y="%d" font-size="12" fill="#5b615c" text-anchor="middle">%s</text>',
                    e.x + e.w / 2, e.y + e.h / 2 + 4, name)
            else
                local base = string.match(name, "([%w_]+)%.png$") or name
                local dir = string.find(name, "Moodles", 1, true) and "../../moodles/" or "../../icons/"
                o[#o + 1] = string.format('<image href="%s%s.png" x="%d" y="%d" width="%d" height="%d" opacity="%.3f" style="image-rendering:pixelated"/>',
                    dir, base, e.x, e.y, e.w, e.h, a)
            end
        elseif e.k == "text" and e.s ~= "" then
            local f = e.f or {size = 14, style = "body"}
            local size = f.size or 14
            local x, anchor = e.x, "start"
            if e.align == "centre" then x, anchor = e.x + e.w / 2, "middle"
            elseif e.align == "right" then x, anchor = e.x + e.w, "end" end
            local y = e.y + e.h / 2 + size * 0.36
            o[#o + 1] = string.format('<text x="%.1f" y="%.1f" font-size="%.1f" font-weight="%d" fill="%s" fill-opacity="%.3f" text-anchor="%s"%s>%s</text>',
                x, y, size, WEIGHT[f.style] or 500, hex(e.c), a, anchor,
                e.ls and string.format(' letter-spacing="%.1f"', e.ls) or "", esc(e.s))
        end
    end
    o[#o + 1] = '</g></svg></body></html>'
    return table.concat(o, "\n")
end

local function snap(name)
    REC = {}
    frame()
    writefile(OUT .. "/" .. name .. ".html", svg(REC))
    REC = nil
end

local function partXY(index)
    local s, fx, fy = Model.frame(384 + 59, 64, 350, 800)
    local x, y = Model.partPoint(index, s, fx, fy)
    return math.floor(x + 0.5), math.floor(y + 0.5)
end

-- the mockup's state: a bleeding deep cut on the left forearm, a broken right shin, glass in the
-- right hand, an infected scratch on the chest under a dirty bandage
local arm, shin, hand, chest = PLAYER.parts[3], PLAYER.parts[15], PLAYER.parts[2], PLAYER.parts[7]
arm.f.isDeepWounded, arm.f.getDeepWoundTime, arm.f.getBleedingTime, arm.f.getHealth = true, 10, 5, 48
shin.f.getFractureTime, shin.f.getHealth = 40, 70
hand.f.haveGlass, hand.f.isCut, hand.f.getCutTime, hand.f.getHealth = true, true, 4, 80
chest.f.scratched, chest.f.getScratchTime, chest.f.isInfectedWound, chest.f.bandaged, chest.f.getBandageLife, chest.f.getHealth =
    true, 4, true, true, 0, 85
PLAYER.health = 64
PLAYER.moods.BLEEDING, PLAYER.moods.PAIN, PLAYER.moods.INJURED, PLAYER.moods.STRESS, PLAYER.moods.THIRST = 2, 2, 2, 1, 1
MX, MY = -100, -100
for _ = 1, 3 do frame() end
snap("1_overview")
local x, y = partXY(2)
MX, MY = x, y
for _ = 1, 20 do frame() end
snap("2_hover_wound")
panel:select(2)
MX, MY = 1500, 900
for _ = 1, 40 do frame() end
snap("4_table_wound")
-- a step being played
for i = 1, panel.nHits do
    local r = panel.hitPool[i]
    if r.kind == "step" and r.a == 1 then MX, MY = r.x + 50, r.y + 30 end
end
frame()
panel:onMouseDown(MX, MY)
panel:onMouseUp(MX, MY)
MX, MY = 1300, 300
for _ = 1, 60 do frame() end
snap("g1_clean")
panel:escape()
-- the kit's thing under the mouse
for _ = 1, 3 do frame() end
for i = 1, panel.nHits do
    local r = panel.hitPool[i]
    if r.kind == "kit" and r.a == "Base.Bandage" then MX, MY = r.x + 10, r.y + 10 end
end
for _ = 1, 3 do frame() end
snap("k_kit_hover")
-- the fracture on the table
panel:select(14)
MX, MY = 1500, 900
for _ = 1, 40 do frame() end
snap("6_table_fracture")
say("snapshots written to " .. OUT)
