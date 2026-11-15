-- The grid with no limit (KnoxGrid.free, the switch "grid.free"; the author 2026-11-15,
-- PROMPT_FREE_GRID.md, mockup free_grid.html Z1/G1/K1/S1), outside the game
-- (tools/freegrid/test.ps1): with it on nothing is left out and the grid grows down with its
-- zone under the things; with it off (the default) the grid is as before; switching back and
-- forth loses nothing; Compact leaves no hole a thing would go up into; a bag's tile grows
-- with its weight.

local G, O = KnoxGrid, KnoxOrder
local pass, fail = 0, 0
local function check(name, ok, detail)
    if ok then pass = pass + 1 else
        fail = fail + 1
        print("FAIL " .. name .. " : " .. tostring(detail))
    end
end

local clock = 1000
function getTimestampMs() clock = clock + 1 return clock end
function getText(key) return key end
function instanceof(obj, class) return type(obj) == "table" and obj.classes ~= nil and obj.classes[class] == true end

local function list(t)
    local l = {t = t}
    function l:size() return #self.t end
    function l:get(i) return self.t[i + 1] end
    return l
end

local nextId = 1
local function container(capacity, kind)
    local c = {items = {}, capacity = capacity, kind = kind or "bag"}
    function c:getItems() return list(self.items) end
    function c:getContainingItem() return self.holder end
    function c:getType() return self.kind end
    function c:getParent() return nil end
    function c:getCapacity() return self.capacity end
    function c:getCapacityWeight()
        local kg = 0
        for _, it in ipairs(self.items) do kg = kg + it:getUnequippedWeight() end
        return kg
    end
    function c:add(it) table.insert(self.items, it); it.container = self; return it end
    function c:remove(it)
        for i, x in ipairs(self.items) do if x == it then table.remove(self.items, i) return end end
    end
    return c
end

local function thing(ft, kg)
    local it = {ft = ft, md = {}, o = {cat = "misc"}, classes = {InventoryItem = true}, id = nextId, kg = kg or 0.5}
    nextId = nextId + 1
    function it:getFullType() return self.ft end
    function it:getModData() return self.md end
    function it:hasModData() return true end
    function it:getContainer() return self.container end
    function it:getFluidContainer() return nil end
    function it:getConditionMax() return 0 end
    function it:getCondition() return 0 end
    function it:getMaxAmmo() return 0 end
    function it:isHidden() return false end
    function it:isEquipped() return false end
    function it:getAttachedSlot() return -1 end
    function it:getID() return self.id end
    function it:getDisplayName() return self.ft end
    function it:getActualWeight() return self.kg end
    function it:getUnequippedWeight() return self.kg end
    function it:getTex() return nil end
    return it
end
local SIZES = {}
local realGet, realStack = KnoxFootprint.get, KnoxFootprint.stack
KnoxFootprint.get = function(item) local s = SIZES[item.ft]; if s then return s[1], s[2] end return realGet(item) end
KnoxFootprint.stack = function(item) local s = SIZES[item.ft]; if s then return s[3] or 1 end return realStack(item) end
KnoxFootprint.isSmoke = function() return false end
G.isStackable = function(item) local s = SIZES[item.ft]; return s ~= nil and (s[3] or 1) > 1 end

SIZES["T.Bottle"] = {1, 2}
SIZES["T.Book"] = {2, 2}
SIZES["T.Box"] = {4, 4}
SIZES["T.Can"] = {1, 1}
SIZES["T.Rifle"] = {1, 6}

local function bagOf(ft, cap)
    local bag = thing(ft)
    bag.classes.InventoryContainer = true
    bag.inv = container(cap)
    bag.inv.holder = bag
    function bag:getInventory() return self.inv end
    return bag
end

local naked = {inv = container(50, "none")}
function naked:getInventory() return self.inv end
function naked:getWornItems() return list({}) end
function naked:getPrimaryHandItem() return nil end
function naked:getSecondaryHandItem() return nil end
function naked:isEquipped() return false end

local function free(on) KnoxTheme.settings["grid.free"] = on and "1" or "0" end

--- No two tiles on one cell, every tile inside the columns.
local function sound(L)
    local seen = {}
    for _, e in ipairs(L.placed) do
        local w, h = G.dims(e, e.r)
        if e.x < 0 or e.y < 0 or e.x + w > L.cols then return false, e.item.ft .. " out at " .. e.x .. "," .. e.y end
        for y = e.y, e.y + h - 1 do
            for x = e.x, e.x + w - 1 do
                local k = y * L.cols + x
                if seen[k] then return false, "two on " .. x .. "," .. y end
                seen[k] = true
            end
        end
    end
    return true
end
local function bottomOf(L)
    local b = 0
    for _, e in ipairs(L.placed) do
        local _, h = G.dims(e, e.r)
        if not e.overflow then b = math.max(b, e.y + h) end
    end
    return b
end

-- 1. off by default: as before
free(false)
KnoxTheme.settings["grid.free"] = nil
check("off by default", G.free() == false, tostring(G.free()))
do
    local bag = bagOf("Base.Bag_Schoolbag", 15)
    local total = G.totalCells(bag.inv, naked)
    for _ = 1, total do bag.inv:add(thing("T.Bottle")) end   -- twice the cells
    local L = G.build(bag.inv, naked)
    check("off: things left out lie in the red rows", #L.overflow > 0, #L.overflow)
    check("off: no zone", not L.free, "free")
    check("off: the rows under the grid take nothing", not L:fits(0, L.baseRows, 1, 1) or L.rows > L.baseRows, "fits")
    check("off: compartments kept", G.shapeOf(bag.inv, naked) ~= nil, "no shape")
end

-- 2. on: nothing left out, the grid down to its things, the zone under them
free(true)
do
    local bag = bagOf("Base.Bag_Schoolbag", 15)
    check("on: no compartments", G.shapeOf(bag.inv, naked) == nil, "shape")
    local L0 = G.build(bag.inv, naked)
    local cols, rows = G.getSize(bag.inv, naked)
    check("on: empty - its own size and the zone", L0.rows == rows + G.ZONE and L0.minRows == rows, L0.rows .. " / " .. rows)
    local total = G.totalCells(bag.inv, naked)
    for _ = 1, total do bag.inv:add(thing("T.Bottle")) end
    bag.inv:add(thing("T.Rifle"))
    local L = G.build(bag.inv, naked)
    check("on: nothing left out", #L.overflow == 0, #L.overflow)
    check("on: every thing placed", #L.placed == total + 1, #L.placed .. " of " .. (total + 1))
    local ok, why = sound(L)
    check("on: no two things on one cell", ok, why)
    local b = bottomOf(L)
    check("on: grown past its own size", b > rows, b .. " <= " .. rows)
    check("on: the zone under the last thing", L.rows == math.max(rows, b) + G.ZONE and L.baseRows == L.rows, L.rows .. " vs " .. b)
    check("on: as wide as before", L.cols == cols, L.cols .. " ~= " .. cols)
    check("on: a block under the last row fits", L:fits(0, L.rows + 3, 4, 4), "no")
    -- a 4x4 in a full grid: the first free place is under the things
    local fx, fy = L:findFree({item = thing("T.Box"), items = {}, w = 4, h = 4})
    check("on: a 4x4 always finds a free place", fx ~= nil and L:fits(fx, fy, 4, 4), tostring(fx) .. "," .. tostring(fy))
    -- the places stay: built again, every thing where it was
    local L2 = G.build(bag.inv, naked)
    local same = true
    for _, e in ipairs(L.placed) do
        for _, e2 in ipairs(L2.placed) do
            if e2.item == e.item and (e2.x ~= e.x or e2.y ~= e.y) then same = false end
        end
    end
    check("on: built again, the same places", same, "moved")

    -- 3. off again: nothing lost, what doesn't fit lies in the red rows (what the compartments
    -- take gets a place in them - a new one: the free grid's has no compartments)
    free(false)
    local L3 = G.build(bag.inv, naked)
    check("back off: every thing shown", #L3.placed == total + 1, #L3.placed)
    check("back off: the rest in the red rows", #L3.overflow > 0, #L3.overflow)
    free(true)
    local L4 = G.build(bag.inv, naked)
    local ok4, why4 = sound(L4)
    check("on again: every thing in the grid", #L4.placed == total + 1 and #L4.overflow == 0, #L4.placed .. " / " .. #L4.overflow)
    check("on again: sound", ok4, why4)
    -- a bag with no compartments: a thing that had a place keeps it both ways
    local plain = bagOf("T.PlainBag", 15)
    local realGrid = KnoxFootprint.bagGrid
    KnoxFootprint.bagGrid = function(item, ...) if item.ft == "T.PlainBag" then return 4, 3 end return realGrid(item, ...) end
    local pk = G.containerKey(plain.inv, naked)
    -- (the grid 4x3 full, one more far down: in Tetris it has no room)
    local top = plain.inv:add(thing("T.Can"))
    top.md.knox = {k = pk, x = 2, y = 0, r = 0}
    for _ = 1, 11 do plain.inv:add(thing("T.Can")) end
    local deep = plain.inv:add(thing("T.Can"))
    deep.md.knox = {k = pk, x = 1, y = 9, r = 0}
    G.build(plain.inv, naked)
    free(false)
    local Lp = G.build(plain.inv, naked)
    local topAt, deepOver
    for _, e in ipairs(Lp.placed) do
        if e.item == top then topAt = e.x == 2 and e.y == 0 end
        if e.item == deep then deepOver = e.overflow == true end
    end
    check("plain off: the one in the grid stays", topAt, "moved")
    check("plain off: the deep one in the red rows, its place kept", deepOver and deep.md.knox.y == 9, tostring(deepOver))
    free(true)
    local Lq = G.build(plain.inv, naked)
    local deepBack = false
    for _, e in ipairs(Lq.placed) do if e.item == deep then deepBack = e.x == 1 and e.y == 9 end end
    check("plain on again: the deep one back at its place", deepBack, "moved")

    -- 4. things taken out: the empty rows at the bottom go, never under its own size
    while #bag.inv.items > 2 do bag.inv:remove(bag.inv.items[#bag.inv.items]) end
    local L5 = G.build(bag.inv, naked)
    check("emptied: back to its size and the zone", L5.rows == math.max(rows, bottomOf(L5)) + G.ZONE, L5.rows)
end

-- 5. Compact: no thing could go up, every thing kept
do
    local bag = bagOf("Base.Bag_ALICEpack", 25)
    local cols = G.getSize(bag.inv, naked)
    local items = {}
    for i = 1, 14 do items[i] = bag.inv:add(thing(i % 3 == 0 and "T.Book" or (i % 3 == 1 and "T.Bottle" or "T.Can"))) end
    -- scattered with holes, far down
    local y = 0
    for i, it in ipairs(items) do
        local s = SIZES[it.ft]
        it.md.knox = {k = G.containerKey(bag.inv, naked), x = (i * 3) % (cols - s[1] + 1), y = y, r = 0}
        y = y + s[2] + 1
    end
    local L = G.build(bag.inv, naked)
    local b0 = bottomOf(L)
    O.compact({layout = L, container = bag.inv, markDirty = function() end})
    local L2 = G.build(bag.inv, naked)
    local ok, why = sound(L2)
    check("compact: no two on one cell", ok, why)
    check("compact: every thing kept", #L2.placed == #items and #L2.overflow == 0, #L2.placed)
    check("compact: shorter", bottomOf(L2) < b0, bottomOf(L2) .. " >= " .. b0)
    local up = nil
    for _, e in ipairs(L2.placed) do
        local w, h = G.dims(e, e.r)
        for yy = 0, e.y - 1 do
            for xx = 0, L2.cols - w do
                if L2:fits(xx, yy, w, h, e) then up = e.item.ft .. " " .. e.x .. "," .. e.y .. " -> " .. xx .. "," .. yy end
            end
        end
    end
    check("compact: no hole a thing would go up into", up == nil, up)
end

-- 6. a bag's tile by its weight (S1): empty its own, full by weight its grid's, never smaller as it fills
do
    local bag = bagOf("Base.Bag_Schoolbag", 10)
    local w0, h0 = 3, 3
    local pw, ph = G.weightedBag(bag.inv, w0, h0)
    check("bag: empty - its own size", pw == w0 and ph == h0, pw .. "x" .. ph)
    local grew, shrank, last = false, false, w0 * h0
    for _ = 1, 20 do
        bag.inv:add(thing("T.Can", 0.5))
        local w, h = G.weightedBag(bag.inv, w0, h0)
        if w * h < last then shrank = true end
        if w * h > w0 * h0 then grew = true end
        last = w * h
    end
    check("bag: grows with the weight", grew, last)
    check("bag: never smaller as it fills", not shrank, "shrank")
    local total = G.totalCells(bag.inv, nil)
    check("bag: full - about its grid's cells", last >= total and last <= total * 1.6, last .. " vs " .. total)
    bag.inv:add(thing("T.Can", 50))
    local w2, h2 = G.weightedBag(bag.inv, w0, h0)
    check("bag: over its limit - no bigger than full", w2 * h2 == last, (w2 * h2) .. " vs " .. last)
    check("bag in a bag: never refused for its growth", G.nestRefusal(thing("T.Can"), bag.inv, naked) == nil, "refused")
end

-- 7. a floor of 400 things never placed: one build lays them all out, the next keeps them (time)
do
    local floor = container(50, "floor")
    for i = 1, 400 do floor:add(thing(i % 4 == 0 and "T.Bottle" or "T.Can")) end
    local L = G.build(floor, naked)
    local L2 = G.build(floor, naked)
    check("floor: all 400 placed", #L.placed == 400 and #L.overflow == 0, #L.placed)
    local ok, why = sound(L2)
    check("floor: sound", ok, why)
end

-- 8. pockets: whole rows, no limit
do
    KnoxGrid.pocketCells = function() return 10 end
    local cols, rows, limit = G.getSize(naked.inv, naked)
    check("pockets: whole rows", limit == nil and rows == math.ceil(10 / cols), tostring(limit))
end

print(string.format("DONE pass=%d fail=%d", pass, fail))
