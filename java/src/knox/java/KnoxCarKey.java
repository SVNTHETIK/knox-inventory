package knox.java;

import java.util.ArrayList;

import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.inventory.types.Key;
import zombie.network.GameClient;
import zombie.vehicles.BaseVehicle;

/**
 * A key works wherever it is on the character (the author 2026-11-13, bench "ign_bag": "ключ
 * просто работал, если он имеется у игрока где-либо... Он должен оставаться в ней, если он
 * изначально там был"). The game looks for a key in the main inventory and on the key rings
 * lying there only (ItemContainer.haveThisKeyId); a key in a bag, or on a key ring in a bag,
 * opened nothing and could not go in the ignition. Now a character's main inventory also
 * looks into its bags (a few levels down). The ignition takes the key out of where it was
 * and, taken out, the key goes back there - the game itself sends it to the main inventory
 * unless its key ring lies at the top.
 *
 * Single player only: in multiplayer the server checks the key itself (its own, unpatched
 * game), so a client finding the key deeper would only fall out of step with it.
 * Patch bodies touch only public members of this class (ZombieBuddy inlines them).
 */
public final class KnoxCarKey {
    private KnoxCarKey() {}

    /** How deep into bags in bags the search goes (the main inventory is 0). */
    public static final int DEPTH = 3;
    /** The key's mod data: the ID of the bag (or key ring) the ignition took it from. */
    public static final String FROM = "knoxKeyFrom";
    /** The key being taken out of the ignition (between the patch's enter and exit). */
    public static InventoryItem leaving;

    /** The key with `id` inside the bags of `inv` (not its own top level), or null. */
    public static InventoryItem deep(ItemContainer inv, int id) {
        if (GameClient.client || inv == null) return null;
        IsoGameCharacter chr = inv.getCharacter();
        if (chr == null || chr.getInventory() != inv) return null;
        try {
            return find(inv, id, 0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static InventoryItem find(ItemContainer c, int id, int depth) {
        ArrayList<InventoryItem> items = c.getItems();
        if (depth > 0) {
            for (int i = 0; i < items.size(); i++) {
                InventoryItem it = items.get(i);
                if (it instanceof Key && ((Key) it).getKeyId() == id) return it;
            }
        }
        if (depth >= DEPTH) return null;
        for (int i = 0; i < items.size(); i++) {
            InventoryItem it = items.get(i);
            if (it instanceof InventoryContainer) {
                ItemContainer in = ((InventoryContainer) it).getInventory();
                if (in != null && in != c) {
                    InventoryItem k = find(in, id, depth + 1);
                    if (k != null) return k;
                }
            }
        }
        return null;
    }

    /** The bag (or key ring) with the item ID `id` anywhere in `c`, or null. */
    private static InventoryContainer bag(ItemContainer c, int id, int depth) {
        ArrayList<InventoryItem> items = c.getItems();
        for (int i = 0; i < items.size(); i++) {
            InventoryItem it = items.get(i);
            if (it instanceof InventoryContainer) {
                if (it.getID() == id) return (InventoryContainer) it;
                ItemContainer in = ((InventoryContainer) it).getInventory();
                if (depth < DEPTH && in != null && in != c) {
                    InventoryContainer b = bag(in, id, depth + 1);
                    if (b != null) return b;
                }
            }
        }
        return null;
    }

    /** The key goes in: note the bag it comes from when that bag is not at the top. */
    public static void goingIn(BaseVehicle v) {
        try {
            IsoGameCharacter chr = v.getDriver();
            if (GameClient.client || chr == null || v.isHotwired() || v.isKeysInIgnition()) return;
            ItemContainer inv = chr.getInventory();
            InventoryItem k = inv.haveThisKeyId(v.getKeyId());
            if (k == null) return;
            ItemContainer from = k.getContainer();
            InventoryItem holder = from != null ? from.getContainingItem() : null;
            KahluaTable md = k.getModData();
            if (holder != null && holder.getContainer() != inv) md.rawset(FROM, (double) holder.getID());
            else md.rawset(FROM, null);
        } catch (RuntimeException e) {
            // the game goes on as without Knox
        }
    }

    /** The key comes out: remember which (the game empties the ignition). */
    public static void goingOut(BaseVehicle v) {
        leaving = null;
        try {
            if (GameClient.client || !v.isKeysInIgnition() || v.ignitionSwitch == null) return;
            ArrayList<InventoryItem> in = v.ignitionSwitch.getItems();
            if (!in.isEmpty() && in.get(0).hasModData() && in.get(0).getModData().rawget(FROM) != null) leaving = in.get(0);
        } catch (RuntimeException e) {
            leaving = null;
        }
    }

    /** The key is out, in the main inventory: back into the bag it came from, if still on the character. */
    public static void cameOut(BaseVehicle v) {
        InventoryItem k = leaving;
        leaving = null;
        if (k == null) return;
        try {
            IsoGameCharacter chr = v.getDriver();
            if (chr == null) return;
            ItemContainer inv = chr.getInventory();
            Object from = k.getModData().rawget(FROM);
            k.getModData().rawset(FROM, null);
            if (k.getContainer() != inv || !(from instanceof Double)) return;
            InventoryContainer b = bag(inv, ((Double) from).intValue(), 0);
            if (b == null || b.getInventory() == null) return;
            inv.DoRemoveItem(k);
            b.getInventory().addItem(k);
        } catch (RuntimeException e) {
            // the key stays in the main inventory, as the game left it
        }
    }

    @Patch(className = "zombie.inventory.ItemContainer", methodName = "haveThisKeyId")
    public static class Look {
        @Patch.OnExit
        public static void exit(@Patch.This ItemContainer self, @Patch.Argument(0) int id,
                                @Patch.Return(readOnly = false) InventoryItem ret) {
            if (ret == null) ret = KnoxCarKey.deep(self, id);
        }
    }

    @Patch(className = "zombie.vehicles.BaseVehicle", methodName = "setKeysInIgnition")
    public static class Ignition {
        @Patch.OnEnter
        public static void enter(@Patch.This BaseVehicle self, @Patch.Argument(0) boolean in) {
            if (in) KnoxCarKey.goingIn(self);
            else KnoxCarKey.goingOut(self);
        }

        @Patch.OnExit
        public static void exit(@Patch.This BaseVehicle self, @Patch.Argument(0) boolean in) {
            if (!in) KnoxCarKey.cameOut(self);
        }
    }
}
