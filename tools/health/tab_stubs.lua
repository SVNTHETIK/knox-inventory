-- The game under the Health tab (tools/health/test.ps1, 2026-11-10): KnoxHealthCU, KnoxHealthData,
-- KnoxHealthCare, KnoxHealthModel, KnoxHealthWounds, KnoxHealthGames and KnoxHealthPanel are the real
-- ones; the game, the layout kit (KnoxTheme) and Knox's Java are stubs that check what they are given.
-- Not the game: what this says is that the tab's Lua runs through its states without an error.

local DOLL_SRC = readfile(DOLL)
require = function(p)
    if p == "Knox/KnoxHealthModelData" then return loadstring(DOLL_SRC)() end
end

NOW, HOURS = 100000, 100
MX, MY = -100, -100
BUTTONS, SHIFT, CLIENT = {}, false, false
SOUNDS, HALO, MENUS, TAKEN = 0, nil, {}, {}
DRAWN = {}       -- the texts drawn this frame
DRAWS = 0
FAIL = 0

local realPrint = print
say = realPrint
print = function(s)
    s = tostring(s)
    if string.find(s, "[Knox]", 1, true) then
        realPrint("FAIL printed: " .. s)
        FAIL = FAIL + 1
    end
end

function getTimestampMs() return NOW end
-- (the snapshots, tab_snap.lua: the words in Russian from the mod's own translation)
WORDS_RU = nil
if RU_JSON then
    WORDS_RU = {}
    for line in string.gmatch(readfile(RU_JSON), "[^\n]+") do
        local k, v = string.match(line, '^%s*"([%w_%.]+)"%s*:%s*"(.*)"%s*,?%s*$')
        if k then WORDS_RU[k] = (string.gsub(string.gsub(v, '\\"', '"'), "\\n", " ")) end
    end
end
function getText(k, a, b, c)
    if WORDS_RU and WORDS_RU[k] then
        local s = WORDS_RU[k]
        s = string.gsub(s, "%%1", tostring(a or ""))
        s = string.gsub(s, "%%2", tostring(b or ""))
        s = string.gsub(s, "%%3", tostring(c or ""))
        return s
    end
    if a == nil then return k end
    return k .. "(" .. tostring(a) .. (b ~= nil and ("," .. tostring(b)) or "") .. (c ~= nil and ("," .. tostring(c)) or "") .. ")"
end
-- what is drawn, for a snapshot (REC a list: each draw added to it)
REC = nil
local function rec(e) if REC then REC[#REC + 1] = e end end
RECORD = rec
function getTextOrNull(k) return k end
function isClient() return CLIENT end
function isShiftKeyDown() return SHIFT end
function isMouseButtonDown(b) return BUTTONS[b] == true end
function getMouseX() return MX end
function getMouseY() return MY end
function getSoundManager() return {playUISound = function() SOUNDS = SOUNDS + 1 end} end
function getTexture(p) return {path = p} end
function instanceof(o, c) return type(o) == "table" and o.__class == c end
function getPlayerContextMenu() return {isAnyVisible = function() return false end} end
function getSpecificPlayer() return PLAYER end
function getGameTime() return {getWorldAgeHours = function() return HOURS end} end
function getPlayerInfoPanel() return nil end
HaloTextHelper = {addBadText = function(p, s) HALO = s end}
IsoDirections = {S = "S", SW = "SW", W = "W", NW = "NW", N = "N", NE = "NE", E = "E", SE = "SE"}
Perks = {Doctor = "Doctor"}
MoodleType = setmetatable({}, {__index = function(t, k) return k end})
CharacterStat = setmetatable({}, {__index = function(t, k) return k end})
ItemTag = {}
Events = setmetatable({}, {__index = function(t, k)
    local e = {Add = function() end, Remove = function() end}
    rawset(t, k, e)
    return e
end})
UIFont = {Large = "Large", Medium = "Medium", Small = "Small"}

function list(t)
    return {size = function() return #t end, get = function(_, i) return t[i + 1] end, t = t}
end

-- Body parts ------------------------------------------------------------------------------------
local NAMES = {"Hand_L", "Hand_R", "ForeArm_L", "ForeArm_R", "UpperArm_L", "UpperArm_R", "Torso_Upper", "Torso_Lower",
    "Head", "Neck", "Groin", "UpperLeg_L", "UpperLeg_R", "LowerLeg_L", "LowerLeg_R", "Foot_L", "Foot_R"}
BodyPartType = {}
for i, n in ipairs(NAMES) do BodyPartType[n] = {name = n, index = i - 1} end
function BodyPartType.ToIndex(t) return t.index end
function BodyPartType.getDisplayName(t) return t.name end
function BodyPartType.FromIndex(i) return BodyPartType[NAMES[i + 1]] end
function BodyPartType.ToString(t) return t.name end

local BP = {}
BP.__index = BP
local NUM = {"getHealth", "getBurnTime", "getFractureTime", "getSplintFactor", "getBleedingTime", "getBandageLife",
    "getAlcoholLevel", "getPlantainFactor", "getComfreyFactor", "getGarlicFactor", "getStitchTime", "getScratchTime",
    "getCutTime", "getDeepWoundTime", "getBiteTime", "getWoundInfectionLevel"}
local BOOL = {"isDeepWounded", "bitten", "isCut", "scratched", "haveGlass", "haveBullet", "bandaged", "stitched",
    "isInfectedWound", "isNeedBurnWash", "bleeding"}
for _, n in ipairs(NUM) do BP[n] = function(self) return self.f[n] or 0 end end
for _, n in ipairs(BOOL) do BP[n] = function(self) return self.f[n] == true end end
function BP:getType() return self.t end
function BP:HasInjury()
    local f = self.f
    return f.isDeepWounded or f.isCut or f.scratched or f.bitten or f.haveGlass or (f.getBurnTime or 0) > 0
        or (f.getFractureTime or 0) > 0 or (f.getBleedingTime or 0) > 0 or false
end

function newPart(i)
    return setmetatable({t = BodyPartType[NAMES[i + 1]], f = {getHealth = 100}}, BP)
end

-- Items ----------------------------------------------------------------------------------------
local IT = {}
IT.__index = IT
function IT:getType() return self.type end
function IT:getFullType() return "Base." .. self.type end
function IT:getName() return self.type end
function IT:getDisplayName() return "N_" .. self.type end
function IT:getBandagePower() return self.bandage or 0 end
function IT:getDisplayCategory() return self.cat or "FirstAid" end
function IT:IsDrainable() return self.drain == true end
function IT:getAlcoholPower() return self.alc or 0 end
function IT:hasTag() return false end
function IT:hasComponent() return false end
function IT:getContainer() return self.container end
function IT:getTex() return {path = "Item_" .. self.type} end
function IT:isBroken() return false end
function IT:IsInventoryContainer() return self.__class == "InventoryContainer" end
function IT:getModule() return "Base" end
function IT:getInventory() return self.inv end

local function container(items, owner)
    local c = {items = items}
    function c.getItems() return list(c.items) end
    function c.getContainingItem() return owner end
    for _, it in ipairs(items) do it.container = c end
    return c
end

function item(type, props)
    local it = setmetatable(props or {}, IT)
    it.type = type
    return it
end

-- The player ------------------------------------------------------------------------------------
function newPlayer()
    local p = {parts = {}, md = {}, moods = {}, stats = {HUNGER = 0.22, THIRST = 0.58, FATIGUE = 0.35, TEMPERATURE = 36.9},
               health = 100, perk = 2}
    for i = 0, 16 do p.parts[i + 1] = newPart(i) end
    local bag = item("Bag_Schoolbag", {cat = "Bag"})
    bag.__class = "InventoryContainer"
    p.bagItems = {item("Bandage", {bandage = 4}), item("AlcoholWipes", {drain = true, alc = 4}), item("SutureNeedle")}
    bag.inv = container(p.bagItems, bag)
    p.pocket = {item("Bandage", {bandage = 4}), item("Pills"), item("Pills"), item("Tweezers"), item("Splint"), bag}
    p.inv = container(p.pocket, nil)
    function p:getBodyDamage()
        return {getBodyParts = function() return list(p.parts) end, getHealth = function() return p.health end,
                getTemperature = function() return 36.9 end}
    end
    function p:getModData() return p.md end
    function p:getMoodles()
        return {getMoodleLevel = function(_, t) return p.moods[t] or 0 end, getGoodBadNeutral = function() return 2 end,
                getMoodleDisplayString = function(_, t) return "M_" .. t end,
                getMoodleDescriptionString = function(_, t) return "D_" .. t end}
    end
    function p:getStats() return {get = function(_, s) return p.stats[s] or 0 end} end
    function p:getPerkLevel() return p.perk end
    function p:getDescriptor() return {getForename = function() return "Kate" end, getSurname = function() return "Smith" end} end
    function p:getHoursSurvived() return 30 end
    function p:getPlayerNum() return 0 end
    function p:getInventory() return p.inv end
    function p:isFemale() return false end
    function p:getVehicle() return nil end
    function p:isDead() return false end
    return p
end

-- The timed actions -------------------------------------------------------------------------------
Q = {queue = {}}
ISTimedActionQueue = {}
function ISTimedActionQueue.getTimedActionQueue() return Q end
function ISTimedActionQueue.add(a) table.insert(Q.queue, a) end
function ISTimedActionQueue.clear() Q.queue = {} end

local ACT = {}
ACT.__index = ACT
function ACT:getJobDelta() return self.t and math.min(1, self.t / self.maxTime) or 0 end
function ACT:forceComplete() self.forced = true; COMPLETE(self) end
function COMPLETE(a)
    for i, q in ipairs(Q.queue) do
        if q == a then table.remove(Q.queue, i) break end
    end
    a.effect(a)
    DONE_ACTIONS[#DONE_ACTIONS + 1] = a.name
end
DONE_ACTIONS = {}
--- The queue's first action runs dt seconds (its time in ticks, 60 a second).
function RUN_ACTIONS(dt)
    local a = Q.queue[1]
    if not a then return end
    if not a.action then a.action, a.t = {java = true}, 0 end
    a.t = a.t + dt * 60
    if a.t >= a.maxTime then COMPLETE(a) end
end

local function action(name, itemArg, bpArg, effect)
    return {new = function(self, ...)
        local args = {...}
        local a = setmetatable({name = name, maxTime = 150, args = args, effect = function(a) effect(a, args[bpArg], args) end}, ACT)
        a.item = itemArg and args[itemArg] or nil
        a.bodyPart = args[bpArg]
        return a
    end}
end
ISApplyBandage = action("bandage", 3, 4, function(a, bp, args)
    if args[5] then
        bp.f.bandaged, bp.f.getBandageLife = true, 1
    else
        bp.f.bandaged, bp.f.getBandageLife = false, 0
    end
end)
ISDisinfect = action("disinfect", 3, 4, function(a, bp) bp.f.getAlcoholLevel = 10 end)
ISStitch = action("stitch", 3, 4, function(a, bp, args) bp.f.stitched = args[5] == true end)
ISRemoveGlass = action("glass", nil, 3, function(a, bp) bp.f.haveGlass = false end)
ISRemoveBullet = action("bullet", nil, 3, function(a, bp) bp.f.haveBullet = false end)
ISCleanBurn = action("burn", 3, 4, function(a, bp) bp.f.isNeedBurnWash = false end)
ISSplint = action("splint", 4, 5, function(a, bp, args) bp.f.getSplintFactor = args[6] and 1 or 0 end)

ISInventoryPaneContextMenu = {
    getContainers = function(p) return list({p.inv}) end,
    transferIfNeeded = function() end,
    takePill = function(it) TAKEN[#TAKEN + 1] = it end,
    eatItem = function(it) TAKEN[#TAKEN + 1] = it end,
    createMenu = function() MENUS[#MENUS + 1] = "item" end,
}

-- The UI ----------------------------------------------------------------------------------------
ISPanel = {}
ISPanel.__index = ISPanel
function ISPanel:derive(name)
    local c = setmetatable({}, {__index = self})
    c.Type = name
    return c
end
function ISPanel:new(x, y, w, h)
    local o = {x = x, y = y, width = w, height = h, children = {}, visible = true}
    setmetatable(o, ISPanel)
    return o
end
function ISPanel:initialise() end
function ISPanel:addChild(c) table.insert(self.children, c) end
function ISPanel:getWidth() return self.width end
function ISPanel:getHeight() return self.height end
function ISPanel:getMouseX() return MX - self.x end
function ISPanel:getMouseY() return MY - self.y end
function ISPanel:isMouseOver() return MX >= self.x and MY >= self.y and MX < self.x + self.width and MY < self.y + self.height end
function ISPanel:setVisible(v) self.visible = v end
function ISPanel:getIsVisible() return self.visible end
local function num(v, what)
    if type(v) ~= "number" or v ~= v then error("not a number: " .. what .. " = " .. tostring(v)) end
end
function ISPanel:drawTextureScaled(tex, x, y, w, h, a)
    if not tex then error("drawTextureScaled: no texture") end
    num(x, "x") num(y, "y") num(w, "w") num(h, "h") num(a, "a")
    DRAWS = DRAWS + 1
    rec({k = "tex", x = x, y = y, w = w, h = h, a = a, tex = tex.canvas and ("pic " .. tex.canvas) or (tex.path or "?")})
end
ISPanel.drawTextureScaledAspect = ISPanel.drawTextureScaled
function ISPanel:setStencilRect(x, y, w, h) num(x, "sx") num(y, "sy") num(w, "sw") num(h, "sh") self.stencil = true end
function ISPanel:clearStencilRect() self.stencil = false end

-- The layout kit (KnoxTheme), checking its arguments ------------------------------------------------
KnoxTheme = {}
local T = KnoxTheme
T.cell, T.pad, T.javaFonts, SWITCH = 48, 12, true, {}
local function rgb(r, g, b) return {r = r, g = g, b = b} end
T.color = setmetatable({window = rgb(0.07, 0.08, 0.08), header = rgb(0.1, 0.1, 0.1), border = rgb(0.16, 0.18, 0.18),
    cell = rgb(0.05, 0.06, 0.06), line = rgb(0.14, 0.16, 0.16), tileEdge = rgb(0.24, 0.25, 0.25), tileFrame = rgb(0.36, 0.38, 0.36),
    text = rgb(0.85, 0.84, 0.78), bright = rgb(0.93, 0.91, 0.85), dim = rgb(0.56, 0.57, 0.54), accent = rgb(0.83, 0.76, 0.54),
    ok = rgb(0.42, 0.63, 0.31), bad = rgb(0.75, 0.27, 0.24), warn = rgb(0.79, 0.64, 0.29), buttonBg = rgb(0.12, 0.14, 0.14),
    ink = rgb(0.08, 0.08, 0.08), screen = rgb(0.04, 0.05, 0.05)},
    {__index = function(_, k) error("T.color." .. tostring(k) .. " does not exist") end})
function T.on() return true end
function T.switch(k) return SWITCH[k] ~= false end
function T.makeFont(style, size)
    num(size, "font size")
    return {knox = true, style = style, size = size, key = style .. "_" .. math.floor(size)}
end
T.makeBigFont = T.makeFont
T.fontText, T.fontCaps, T.fontHeader, T.fontTile = T.makeFont("body", 15), T.makeFont("head", 14), T.makeFont("title", 20), T.makeFont("head", 12)
function T.bgAlpha() return 1 end
function T.upper(s) return string.upper(s) end
function T.anim(t) return math.max(0, math.min(1, t)) end
local function font(f)
    if f == nil then error("no font") end
    return f
end
local function col(c, what)
    if type(c) ~= "table" or type(c.r) ~= "number" then error("not a colour: " .. tostring(what)) end
end
function T.measure(f, s) font(f) return math.floor(string.len(s or "") * f.size * (f.style == "body" and 0.44 or 0.5) + 0.5) end
function T.fontH(f) font(f) return 16 end
function T.capH(f) font(f) return 11 end
function T.inkBox(f, s) font(f) return 0, 2, string.len(s or "") * 7, 13 end
function T.wrap(s, f, w)
    font(f) num(w, "wrap width")
    local out, line = {}, ""
    for word in string.gmatch(s or "", "%S+") do
        local try = line == "" and word or (line .. " " .. word)
        if line ~= "" and T.measure(f, try) > w then
            out[#out + 1] = line
            line = word
        else
            line = try
        end
    end
    if line ~= "" then out[#out + 1] = line end
    return out
end
function T.fit(s, f, w) font(f) num(w, "fit width") if type(s) ~= "string" then error("fit: not a string") end return s end
function T.label(ui, s, r, c, opts)
    if type(s) ~= "string" then error("label: not a string: " .. tostring(s)) end
    for i = 1, 4 do num(r[i], "label rect " .. i .. " of " .. s) end
    col(c, "label " .. s)
    font(opts and opts.font)
    DRAWN[#DRAWN + 1] = s
    rec({k = "text", s = s, x = r[1], y = r[2], w = r[3], h = r[4], c = c, f = opts.font, align = opts.align, a = opts.a})
    return T.measure(opts.font, s)
end
function T.spaced(ui, s, r, c, opts)
    if type(s) ~= "string" then error("spaced: not a string") end
    for i = 1, 4 do num(r[i], "spaced rect " .. i) end
    col(c, "spaced " .. s)
    font(opts and opts.font)
    DRAWN[#DRAWN + 1] = s
    rec({k = "text", s = s, x = r[1], y = r[2], w = r[3], h = r[4], c = c, f = opts.font, align = opts.align or "centre",
         a = opts.a, ls = opts.ls})
    return T.measure(opts.font, s) + (opts.ls or 0) * (string.len(s) - 1)
end
function T.rect(ui, x, y, w, h, c, a)
    num(x, "rect x") num(y, "rect y") num(w, "rect w") num(h, "rect h") col(c, "rect")
    if a ~= nil then num(a, "rect a") end
    DRAWS = DRAWS + 1
    rec({k = "rect", x = x, y = y, w = w, h = h, c = c, a = a or 1})
end
function T.border(ui, x, y, w, h, c, a)
    num(x, "border x") num(y, "border y") num(w, "border w") num(h, "border h") col(c, "border")
    rec({k = "border", x = x, y = y, w = w, h = h, c = c, a = a or 1, t = 1})
end
function T.tileFrame(ui, x, y, w, h, c, a)
    num(x, "frame x") num(y, "frame y") num(w, "frame w") num(h, "frame h") col(c, "frame")
    rec({k = "border", x = x, y = y, w = w, h = h, c = c, a = a or 1, t = 2})
end
function T.itemIcon(ui, it, x, y, s, a)
    if type(it) ~= "table" or not it.getTex then error("itemIcon: not an item") end
    num(x, "icon x") num(y, "icon y") num(s, "icon s")
    rec({k = "tex", x = x, y = y, w = s, h = s, a = a or 1, tex = "Item_" .. it.type})
end
function T.greyIcon(tex) return tex end
function T.outlinedRight(ui, s, x, y, c, f, a)
    num(x, "ox") num(y, "oy") col(c, "outlined")
    DRAWN[#DRAWN + 1] = s
    rec({k = "text", s = s, x = x - 200, y = y, w = 200, h = 16, c = c, f = f, align = "right", a = a})
end
function T.keyHint(ui, x, y, key, label, h, a)
    num(x, "hint x") num(y, "hint y") num(h, "hint h")
    DRAWN[#DRAWN + 1] = key
    DRAWN[#DRAWN + 1] = label
    local kw = string.len(key) * 7 + 12
    rec({k = "rect", x = x, y = y, w = kw, h = h, c = T.color.header, a = a or 1})
    rec({k = "border", x = x, y = y, w = kw, h = h, c = T.color.tileEdge, a = a or 1, t = 1})
    rec({k = "text", s = key, x = x, y = y, w = kw, h = h, c = T.color.bright, f = T.fontTile, align = "centre", a = a})
    rec({k = "text", s = label, x = x + kw + 6, y = y, w = 0, h = h, c = T.color.dim, f = T.fontText, align = "left", a = a})
    return x + kw + 6 + T.measure(T.fontText, label)
end
function T.mixInto(out, a, b, k)
    out.r, out.g, out.b = a.r + (b.r - a.r) * k, a.g + (b.g - a.g) * k, a.b + (b.b - a.b) * k
    return out
end

-- Knox's Java (the pictures, the mini-game) --------------------------------------------------------
SCENES, GAME = {}, nil
function knoxWoundOk() return true end
function knoxWoundScene(id, w, h, view, kind, flags, progress, rgbv)
    num(id, "id") num(w, "w") num(h, "h") num(view, "view") num(kind, "kind") num(flags, "flags") num(progress, "progress") num(rgbv, "rgb")
    SCENES[id] = {view = view, kind = kind, flags = flags}
end
function knoxWoundTex(id) return {canvas = id} end
local WORDS = {n = 2, "KOZHA", 10, 20, 0xd79e8c, 1, 9, 1, 600, 0.8, "MID", 100, 40, 0xffffff, 0.8, 11, 0, 700, 0}
function knoxWoundTexts() return WORDS end
function knoxWoundEcg(id, w, h, bpm, rgbv) num(bpm, "bpm") num(rgbv, "ecg rgb") end
function knoxWoundBpm(b) num(b, "bpm") end
function knoxWoundGameStart(id, kind, wk, cleaned, it, skill, van)
    num(kind, "game kind") num(wk, "wound kind") num(skill, "skill") num(van, "van")
    if type(it) ~= "string" then error("game item not a string") end
    GAME = {kind = kind, wk = wk, item = it, van = van, prog = 0, t = 0, slips = 0, stage = 0, ended = false, done = false, won = false, auto = false}
    GAMES_STARTED = (GAMES_STARTED or 0) + 1
    return true
end
function knoxWoundGameTick(id, mx, my, down, pressed, dt, shake)
    num(mx, "game mx") num(my, "game my") num(dt, "dt") num(shake, "shake")
    if not GAME then return false end
    GAME.t = GAME.t + dt
    if PLAY_WIN and not GAME.ended then GAME.prog, GAME.won, GAME.ended = 1, true, true end
    if GAME.ended then GAME.endT = (GAME.endT or 0) + dt; if GAME.endT > 1.6 then GAME.done = true end end
    return true
end
function knoxWoundGameGet(id, what)
    if not GAME then return -1 end
    if what == "end" then return GAME.ended and 1 or 0 end
    if what == "done" then return GAME.done and 1 or 0 end
    if what == "won" then return GAME.won and 1 or 0 end
    if what == "auto" then return GAME.auto and 1 or 0 end
    return GAME[what] or 0
end
function knoxWoundGameHow() return "HowClean" end
function knoxWoundGameVan(id, v) num(v, "van") if GAME then GAME.van = v end end
function knoxWoundGameAuto() if GAME then GAME.auto, GAME.ended = true, true end end
function knoxWoundGameStop() GAME = nil end
function knoxActionMaxTime(a, t) num(t, "max time") a.maxSet = t return true end
