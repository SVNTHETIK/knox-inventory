-- The Health tab through its states, on the stub game of tab_stubs.lua (tools/health/test.ps1,
-- 2026-11-10): healthy, a bleeding deep cut, the card under the mouse, the part on the table, a step
-- played to the end, the quick treatment left to the character, Esc, the kit (a click, a double click),
-- the right button (menus, turning), glass with a fracture under a dirty bandage, the mini-games off,
-- a game with others. Only that the tab's Lua goes through without an error and does what it says.

local P, W, G, Model, Care = KnoxHealthPanel, KnoxHealthWounds, KnoxHealthGames, KnoxHealthModel, KnoxHealthCare
local pass = 0

local function check(name, ok, info)
    if ok then pass = pass + 1
    else
        FAIL = FAIL + 1
        say("FAIL " .. name .. (info and (": " .. tostring(info)) or ""))
    end
end

-- the model and the game's menus: not this test's
local AV = {dir = nil}
function AV:setDirection(d) self.dir = d end
function AV:isMouseOver() return false end
Model.create = function(parent) parent:addChild(AV) return AV end
Model.update = function() end
Care.openMenu = function(player, bp) MENUS[#MENUS + 1] = "part:" .. bp:getType().name end

PLAYER = newPlayer()
local panel = P:new(0, 0, 1880, 928, 0, nil)
panel:initialise()
panel:createChildren()
panel:shown()

local function has(s)
    for _, d in ipairs(DRAWN) do if d == s or string.find(d, s, 1, true) then return true end end
    return false
end

local function frame(dt)
    dt = dt or 1 / 60
    NOW = NOW + math.floor(dt * 1000 + 0.5)
    RUN_ACTIONS(dt)
    DRAWN = {}
    local ok, err = pcall(function()
        panel:prerender()
        panel:render()
    end)
    if not ok then
        FAIL = FAIL + 1
        say("FAIL frame: " .. tostring(err))
        error("stop")
    end
end

local function frames(n, dt) for _ = 1, n do frame(dt) end end

local function hitOf(kind, a)
    for i = 1, panel.nHits do
        local r = panel.hitPool[i]
        if r.kind == kind and (a == nil or r.a == a) then return r end
    end
    return nil
end

local function click(x, y, right)
    MX, MY = x, y
    frame()
    if right then
        panel:onRightMouseDown(x, y)
        panel:onRightMouseUp(x, y)
    else
        BUTTONS[0] = true
        panel:onMouseDown(x, y)
        BUTTONS[0] = false
        panel:onMouseUp(x, y)
    end
    frame()
end

local function clickHit(kind, a, right)
    local r = hitOf(kind, a)
    if not r then
        check("a place to click: " .. kind .. " " .. tostring(a), false)
        return false
    end
    click(r.x + math.floor(r.w / 2), r.y + math.floor(r.h / 2), right)
    return true
end

-- where a part is on the model (the panel at 0,0; the cell 48: the model's box as drawBody puts it)
local function partXY(index)
    local s, fx, fy = Model.frame(384 + 59, 64, 350, 800)
    local x, y = Model.partPoint(index, s, fx, fy)
    return math.floor(x + 0.5), math.floor(y + 0.5)
end

local bp = PLAYER.parts[4]   -- ForeArm_R (index 3)

-- 1. nothing wrong ------------------------------------------------------------------------------
frames(3)
check("healthy: the list says no wounds", has("IGUI_KNOXHP_NOWOUNDSTITLE"))
check("healthy: the state's title", has("IGUI_KNOXHP_STATE"))
check("healthy: the trend is fine", has("IGUI_KNOXHP_TRENDFINE"))
check("healthy: the kit's groups", has("IGUI_KNOXHP_G_WRAP") and has("IGUI_KNOXHP_G_PILLS"))
check("healthy: the organism rows", has("IGUI_KnoxHP_Stat_hunger") and has("36IGUI_KnoxHP_Decimal9"))

-- 2. a bleeding deep cut on the right forearm ------------------------------------------------------
bp.f.isDeepWounded, bp.f.getDeepWoundTime, bp.f.getBleedingTime, bp.f.getHealth = true, 10, 5, 62
PLAYER.health = 80
PLAYER.moods.BLEEDING, PLAYER.moods.PAIN, PLAYER.moods.STRESS = 2, 2, 1
panel.dirty = true
frames(3)
local recs = W.parts(PLAYER)
check("a wound: one record", #recs == 1, #recs)
local rec = recs[1]
local ids = ""
for _, s in ipairs(rec.steps) do ids = ids .. s.id .. "," end
check("a deep cut: clean, stitch, bandage", ids == "clean,stitch,bandage,", ids)
check("a deep cut: bleeding look", rec.look.style == "bleed", rec.look.style)
check("the list row: its part", has("FOREARM_R"))
check("the list row: next step", has("IGUI_KnoxHP_Next") and has("igui_knoxhp_step_clean"))
check("the list row: the tag", has("IGUI_KNOXHP_TAG_BLEED"))
check("the trend: worse", has("IGUI_KNOXHP_TRENDWORSE"))
check("the moodles drawn", has("M_BLEEDING") and has("M_PAIN"))
check("a row's picture", SCENES[101] ~= nil and SCENES[101].view == 2)
check("the loss caption", has("IGUI_KnoxHP_LossAll(20)"))

-- 3. the card under the mouse ------------------------------------------------------------------------
local x, y = partXY(3)
MX, MY = x, y
frames(2)
check("hover: the part found", panel.hoverPart == 3, panel.hoverPart)
frames(12)
check("hover: the card", has("IGUI_KNOXHP_PARTHP") and SCENES[2] ~= nil and SCENES[2].view == 1)
check("hover: the card's keys", has("IGUI_KnoxHP_ToTable"))
-- a part with nothing wrong: its card says so
local hx, hy = partXY(8)
MX, MY = hx, hy
frames(12)
check("hover healthy: the card says none", has("IGUI_KnoxHP_CardNone"), panel.hoverPart)

-- 4. on the table ------------------------------------------------------------------------------------
click(x, y)
check("click: the part on the table", panel.sel == 3, panel.sel)
frames(2)
check("table: the back button", has("IGUI_KNOXHP_ALLWOUNDS"))
check("table: the picture", SCENES[1] ~= nil and SCENES[1].view == 0)
check("table: the steps", has("IGUI_KNOXHP_STEP_CLEAN") and has("IGUI_KNOXHP_STEP_STITCH"))
check("table: quick button", has("IGUI_KNOXHP_QUICKTREAT"))
check("table: the step's item", has("n_alcoholwipes"))
check("table: the note", has("IGUI_KnoxHP_QuickGames"))

-- 5. the step played to the end: the action done at once ------------------------------------------------
clickHit("step", 1)
check("step: a session", G.session(PLAYER) ~= nil)
check("step: the action queued", Q.queue[1] ~= nil and Q.queue[1].name == "disinfect", Q.queue[1] and Q.queue[1].name)
check("step: the game started", GAME ~= nil and GAME.kind == 0)
frames(3)
check("step: its normal time set (8 s at first aid 2)", Q.queue[1] and Q.queue[1].maxTime == math.floor(8 * 0.94 * 60 + 0.5),
    Q.queue[1] and Q.queue[1].maxTime)
check("step: the game's lines", has("IGUI_KNOXHP_HOWCLEAN") and has("IGUI_KnoxHP_ShakeYes"))
check("step: the bottom hints with Esc", panel:hintsStamp() == "health+esc" and #panel:hints() == 5)
-- clicks elsewhere wait
clickHit("quick")
check("step: the quick button waits", #Q.queue == 1)
PLAY_WIN = true
frames(5)
check("won: the action forced", DONE_ACTIONS[#DONE_ACTIONS] == "disinfect" and bp.f.getAlcoholLevel == 10)
frames(120)
check("won: the session over", G.session(PLAYER) == nil)
PLAY_WIN = false
frames(2)
rec = panel.D.byIndex[3]
check("won: the step done", rec.steps[1].done == true)
check("won: next is stitch", rec.cur and rec.cur.id == "stitch", rec.cur and rec.cur.id)

-- 6. quick treatment, not played: the character finishes each step in its time ------------------------
clickHit("quick")
check("quick: started", G.session(PLAYER) ~= nil and Q.queue[1] and Q.queue[1].name == "stitch")
local guard = 0
while (G.session(PLAYER) or #Q.queue > 0) and guard < 4000 do
    frame(1 / 30)
    guard = guard + 1
end
frames(3)
rec = panel.D.byIndex[3]
check("quick: stitched and bandaged", bp.f.stitched == true and bp.f.bandaged == true, tostring(bp.f.stitched) .. "/" .. tostring(bp.f.bandaged))
check("quick: nothing left", rec and rec.cur == nil, rec and rec.cur and rec.cur.id)
check("quick: the button says nothing to do", has("IGUI_KNOXHP_BTNNOTHING"))
check("quick: games auto", GAMES_STARTED == 3, GAMES_STARTED)

-- 7. Esc: a step stops, then the table empties ----------------------------------------------------------
bp.f.bandaged, bp.f.getBandageLife = true, 0   -- (the bandage dirty)
panel.dirty = true
frames(2)
rec = panel.D.byIndex[3]
check("dirty bandage: unb first", rec.cur and rec.cur.id == "unb", rec.cur and rec.cur.id)
clickHit("step", 1)
check("unb: started", G.session(PLAYER) ~= nil)
check("esc: stops the step", panel:escape() == true and G.session(PLAYER) == nil and #Q.queue == 0)
check("esc: the game gone", GAME == nil)
check("esc: then the table", panel:escape() == true and panel.sel == nil)
check("esc: then the screen's", panel:escape() == false)

-- 8. the kit ------------------------------------------------------------------------------------------
click(x, y)
frames(2)
local tileBand
for i = 1, panel.nHits do
    local r = panel.hitPool[i]
    if r.kind == "kit" and r.a == "Base.Bandage" then tileBand = r end
end
check("kit: the bandage's tile", tileBand ~= nil)
if tileBand then
    MX, MY = tileBand.x + 5, tileBand.y + 5
    frames(3)
    check("kit: its box", has("N_BANDAGE") and has("IGUI_KnoxHP_Desc_Bandage"))
end
local tilePills
for i = 1, panel.nHits do
    local r = panel.hitPool[i]
    if r.kind == "kit" and r.a == "Base.Pills" then tilePills = r end
end
check("kit: the pills' tile", tilePills ~= nil)
if tilePills then
    MX, MY = tilePills.x + 5, tilePills.y + 5
    frames(2)
    check("kit: pills say take", has("IGUI_KnoxHP_Take"))
    panel:onMouseDown(MX, MY) panel:onMouseUp(MX, MY)
    NOW = NOW + 100
    panel:onMouseDown(MX, MY) panel:onMouseUp(MX, MY)
    check("kit: a double click takes a pill", #TAKEN == 1)
end
-- a click on the kit's thing does the step on the table (the dirty bandage off: a fresh one next)
bp.f.bandaged = false
panel.dirty = true
frames(2)
check("bandage off: bandage next", panel.D.byIndex[3].cur and panel.D.byIndex[3].cur.id == "bandage")
if tileBand then
    click(tileBand.x + 5, tileBand.y + 5)
    check("kit: the step with the clicked thing", G.session(PLAYER) ~= nil and Q.queue[1] and Q.queue[1].name == "bandage"
        and Q.queue[1].args[5] == true and Q.queue[1].item.type == "Bandage", Q.queue[1] and Q.queue[1].name)
    panel:escape()
end
-- the right button on it: the game's menu of the thing
if tileBand then
    click(tileBand.x + 5, tileBand.y + 5, true)
    check("kit: right button, the thing's menu", MENUS[#MENUS] == "item", MENUS[#MENUS])
end

-- 9. the right button on the body: the part's menu; held and dragged: turned --------------------------
panel:escape()
frames(2)
click(x, y, true)
check("rmb: the part's menu", MENUS[#MENUS] == "part:ForeArm_R", MENUS[#MENUS])
MX, MY = x, y
frame()
BUTTONS[1] = true
panel:onRightMouseDown(x, y)
MX = x + 90
frames(2)
BUTTONS[1] = false
local before = #MENUS
panel:onRightMouseUp(MX, MY)
check("rmb drag: turned", AV.dir ~= nil and AV.dir ~= "S", AV.dir)
check("rmb drag: no menu", #MENUS == before)
frames(2)
check("turned: no part under the mouse (hit map from the front only)", panel.hoverPart == nil)
AV.knoxDir = nil
AV.dir = "S"

-- 10. glass and a fracture under a dirty bandage, on the leg ---------------------------------------------
local leg = PLAYER.parts[14]   -- LowerLeg_R (13)
leg.f.haveGlass, leg.f.getFractureTime, leg.f.isCut, leg.f.getCutTime, leg.f.getHealth = true, 30, true, 5, 70
leg.f.bandaged, leg.f.getBandageLife = true, 0
panel.dirty = true
frames(2)
local lrec = panel.D.byIndex[13]
local lids = ""
for _, s in ipairs(lrec.steps) do lids = lids .. s.id .. "," end
check("glass+fracture: the steps", lids == "unb,tw,clean,bandage,splint,", lids)
panel:select(13)
frames(3)
check("glass+fracture: the table", has("IGUI_KNOXHP_STEP_UNB"))
check("glass+fracture: a list of two", #panel.D.recs == 2)

-- 11. the mini-games off -------------------------------------------------------------------------------
SWITCH["health.games"] = false
local started = GAMES_STARTED
clickHit("step", 1)
check("games off: a step without a game", G.session(PLAYER) ~= nil and GAMES_STARTED == started)
frames(3)
check("games off: the game's own time", Q.queue[1] and Q.queue[1].maxTime == 150, Q.queue[1] and Q.queue[1].maxTime)
check("games off: the step says doing", has("IGUI_KnoxHP_StepDoing"))
guard = 0
while G.session(PLAYER) and guard < 2000 do frame(1 / 30) guard = guard + 1 end
check("games off: done", leg.f.bandaged == false and G.session(PLAYER) == nil)
SWITCH["health.games"] = nil

-- 12. a game with others: no stretching, no early end -----------------------------------------------------
CLIENT = true
panel.dirty = true
frames(2)
lrec = panel.D.byIndex[13]
check("mp: glass next", lrec.cur and lrec.cur.id == "tw", lrec.cur and lrec.cur.id)
clickHit("step", 2)
frames(3)
check("mp: the game's own time", Q.queue[1] and Q.queue[1].maxTime == 150 and GAME and GAME.van == 150 / 60)
PLAY_WIN = true
frames(5)
check("mp: won, not forced", Q.queue[1] ~= nil and not Q.queue[1].forced)
PLAY_WIN = false
guard = 0
while G.session(PLAYER) and guard < 2000 do frame(1 / 30) guard = guard + 1 end
check("mp: done in its time", leg.f.haveGlass == false)
CLIENT = false

-- 13. back to the list, scrolled; the healed part off the list ------------------------------------------
clickHit("back")
check("back: the list", panel.sel == nil)
panel:onMouseWheel(1)
frames(3)
bp.f.isDeepWounded, bp.f.getBleedingTime, bp.f.bandaged, bp.f.stitched, bp.f.getDeepWoundTime = false, 0, false, false, 0
bp.f.getAlcoholLevel = 0
panel.dirty = true
frames(3)
check("healed: off the list", panel.D.byIndex[3] == nil and #panel.D.recs == 1)

-- 14. the order is advice, not a lock (the author 2026-11-11): a bandage before the cleaning -------------
local up = PLAYER.parts[6]
up.f.isDeepWounded, up.f.getDeepWoundTime, up.f.getBleedingTime = true, 10, 5
panel.dirty = true
frames(3)
panel:select(5)
frames(3)
local urec = panel.selRec
local bi
for i, s in ipairs(urec and urec.steps or {}) do if s.id == "bandage" then bi = i end end
check("order: cleaning first", urec and urec.cur and urec.cur.id == "clean", urec and urec.cur and urec.cur.id)
check("order: bandage open now", bi and panel.D.sel.steps[bi].state == "free", bi and panel.D.sel.steps[bi].state)
Q.queue = {}
clickHit("step", bi)
frames(2)
check("order: bandage before cleaning", Q.queue[1] and Q.queue[1].name == "bandage", Q.queue[1] and Q.queue[1].name)
panel:escape()
frames(2)

-- 15. a done step clicked is taken back, with its game (the author 2026-11-11) ---------------------------
local arm = PLAYER.parts[3]   -- ForeArm_L (2)
arm.f.scratched, arm.f.getScratchTime, arm.f.getFractureTime = true, 5, 30
arm.f.bandaged, arm.f.getBandageLife, arm.f.getSplintFactor = true, 1, 1
Q.queue = {}
panel.dirty = true
frames(2)
panel:select(2)
frames(3)
local arec = panel.selRec
local abi, asi
for i, s in ipairs(arec and arec.steps or {}) do
    if s.id == "bandage" then abi = i end
    if s.id == "splint" then asi = i end
end
check("undo: bandage and splint done", abi and asi and arec.steps[abi].done and arec.steps[asi].done)
check("undo: they can be taken back", abi and panel.D.sel.steps[abi].undo == true and panel.D.sel.steps[asi].undo == true)
clickHit("step", abi)
check("undo: the bandage off queued", Q.queue[1] and Q.queue[1].name == "bandage" and Q.queue[1].args[5] == false,
    Q.queue[1] and Q.queue[1].name)
check("undo: the bandage-off game, a clean bandage", GAME and GAME.kind == 3 and GAME.item == "band", GAME and GAME.kind)
check("undo: a session", G.session(PLAYER) ~= nil and G.session(PLAYER).step == "xbandage")
PLAY_WIN = true
frames(5)
frames(120)
PLAY_WIN = false
check("undo: the bandage off", arm.f.bandaged == false and G.session(PLAYER) == nil)
frames(2)
clickHit("step", asi)
check("undo: the splint-off game on the fracture", GAME and GAME.kind == 7 and GAME.wk == 5, GAME and (GAME.kind .. "/" .. GAME.wk))
check("undo: the picture pinned to the fracture", panel.selRec and panel.selRec.kind == "fracture", panel.selRec and panel.selRec.kind)
PLAY_WIN = true
frames(5)
frames(120)
PLAY_WIN = false
check("undo: the splint off", arm.f.getSplintFactor == 0 and G.session(PLAYER) == nil, arm.f.getSplintFactor)
check("undo: the picture free again", KnoxHealthWounds.force[PLAYER] == nil)

say(string.format("health tab: %d passed, %d failed", pass, FAIL))
