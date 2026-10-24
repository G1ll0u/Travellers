package com.jubitus.travellers.village;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import org.millenaire.common.world.MillWorldData;
import org.millenaire.common.world.UserProfile;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.culture.Culture;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.item.TradeGood;
import org.millenaire.common.ui.ContainerTrade;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.BuildingProject;
import org.millenaire.common.village.ConstructionIP;
import org.millenaire.common.village.buildingmanagers.ResManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The one place items move between the mod and a Millénaire settlement's stores: a hired worker's
 * cargo sold or donated, and a wandering worker's gift to the village's construction. Millénaire's
 * own store/count calls read the chest tile entities, which loads their chunks; every operation here
 * loads the chest chunks itself, temporarily, and releases them afterwards. Server thread only.
 */
public final class MillGoods {

    private static final int MIN_REP_TO_TRADE = -1024;
    private static final int DONATION_SEARCH_RADIUS = 64;

    private MillGoods() {
    }

    /** What a cargo delivery did, for the worker to report and pay out. */
    public static final class Delivery {
        /** False when no settlement stands at the target. */
        public boolean reached;
        /** The village refused to trade (under attack, chests unlocked, reputation too low). */
        public boolean refused;
        /** Some cargo stayed in the worker's inventory. */
        public boolean unsold;
        public int items;
        public int money;
        public int reputation;
        public String villageName = "";
        /** Display name -> {quantity, value}, insertion-ordered. */
        public final Map<String, int[]> traded = new LinkedHashMap<>();
    }

    /**
     * Sells (or donates) the whole cargo to the settlement at {@code villageCenter} as its employer
     * would at the town hall, online or not: an offline employer gets the village's base prices, and
     * money/reputation are credited all the same. Sold items leave {@code cargo}; unsellable ones stay.
     */
    public static Delivery deliverCargo(WorldServer world, BlockPos villageCenter, IItemHandlerModifiable cargo,
                                        UUID employerId, boolean donate) {
        Delivery out = new Delivery();
        Building th = MillSettlements.townHall(world, villageCenter);
        if (th == null) return out;
        out.reached = true;
        out.villageName = th.getVillageQualifiedName();

        UserProfile profile = MillSettlements.profile(world, employerId);
        if (profile == null || th.culture == null || !canTrade(th, profile)) {
            out.refused = true;
            out.unsold = true;
            return out;
        }
        MinecraftServer server = world.getMinecraftServer();
        EntityPlayerMP online = server == null ? null : server.getPlayerList().getPlayerByUUID(employerId);

        List<Chunk> borrowed = borrowChestChunks(world, th);
        try {
            trade(th, cargo, profile, online, donate, out);
            th.invalidateInventoryCache();
            if (online != null) th.sendChestPackets(online);
        } finally {
            LoadedChunks.release(world, borrowed);
        }
        return out;
    }

    /**
     * Hands every chest of the settlement at {@code center} (all its buildings) to {@code use}, chunks
     * borrowed for the call and the stock cache refreshed after. Returns false when no settlement stands there.
     */
    public static boolean withVillageChests(WorldServer world, BlockPos center, Consumer<List<IInventory>> use) {
        Building th = MillSettlements.townHall(world, center);
        if (th == null) return false;
        List<Building> buildings = th.getBuildings();
        List<Chunk> borrowed = new ArrayList<>();
        try {
            List<IInventory> chests = new ArrayList<>();
            for (Building b : buildings) {
                if (b == null) continue;
                borrowed.addAll(borrowChestChunks(world, b));
                ResManager res = b.getResManager();
                if (res == null || res.chests == null) continue;
                for (Point p : res.chests) {
                    IInventory chest = p.getMillChest(world);
                    if (chest != null) chests.add(chest);
                }
            }
            use.accept(chests);
            for (IInventory chest : chests) chest.markDirty();
            for (Building b : buildings) {
                if (b != null) b.invalidateInventoryCache();
            }
        } finally {
            LoadedChunks.release(world, borrowed);
        }
        return true;
    }

    /**
     * A wandering worker's gift to the construction needs of the settlement at (or within 64 blocks
     * of) {@code stop}. Returns false when there is no settlement there.
     */
    public static boolean donateToConstruction(WorldServer world, BlockPos stop) {
        MillSettlements.Settlement s = MillSettlements.nearest(world, stop, DONATION_SEARCH_RADIUS);
        Building th = s == null ? null : s.townHall(world);
        if (th == null) return false;

        Map<InvItem, Integer> needs = constructionNeeds(th);
        if (needs.isEmpty()) return true;

        int maxDistinctItems = Math.max(1, TravellersConfig.MERCHANT.merchantMaxDistinctItems);
        double giveFraction = TravellersConfig.MERCHANT.merchantGiveFraction > 0
                ? TravellersConfig.MERCHANT.merchantGiveFraction : 1.0 / 12.0;
        int minBulk = Math.max(1, TravellersConfig.MERCHANT.merchantMinBulkAmount);
        int minSmall = Math.max(1, TravellersConfig.MERCHANT.merchantMinSmallAmount);

        List<Chunk> borrowed = borrowChestChunks(world, th);
        try {
            int donated = 0;
            for (Map.Entry<InvItem, Integer> e : needs.entrySet()) {
                if (donated >= maxDistinctItems) break;
                InvItem ii = e.getKey();
                int totalReq = e.getValue();
                Item item = ii.getItem();
                int missing = totalReq - th.countGoods(item, ii.meta);
                if (missing <= 0) continue;

                boolean smallStack = new ItemStack(item, 1, ii.meta).getMaxStackSize() <= 16;
                int slice = Math.max(1, (int) Math.floor(totalReq * giveFraction));
                int toGive = Math.min(missing, Math.max(slice, smallStack ? minSmall : minBulk));
                if (toGive > 0 && th.storeGoods(item, ii.meta, toGive) > 0) donated++;
            }
        } finally {
            LoadedChunks.release(world, borrowed);
        }
        return true;
    }

    /** Total items required by the current goal, every construction in progress and every wall
     * project (not net of what the village already holds). */
    public static Map<InvItem, Integer> constructionNeeds(Building th) {
        Map<InvItem, Integer> needs = new LinkedHashMap<>();
        addCost(needs, th.getCurrentGoalBuildingPlan());
        List<ConstructionIP> cips = th.getConstructionsInProgress();
        if (cips != null) {
            for (ConstructionIP cip : cips) {
                if (cip != null && cip.getBuildingLocation() != null) addCost(needs, cip.getBuildingLocation().getPlan());
            }
        }
        List<BuildingProject> projects = th.getFlatProjectList();
        if (projects != null) {
            for (BuildingProject project : projects) {
                if (project == null) continue;
                BuildingPlan next = project.getNextBuildingPlan(false);
                BuildingPlan initial = project.getPlan(0, 0);
                if (next != null && next.isWallSegment) addCost(needs, next);
                else if (initial != null && initial.isWallSegment) addCost(needs, initial);
            }
        }
        return needs;
    }

    /** One line per needed item: required, held, missing. Reads chest contents, so loads their chunks. */
    public static List<String> describeNeeds(WorldServer world, Building th) {
        List<String> lines = new ArrayList<>();
        List<Chunk> borrowed = borrowChestChunks(world, th);
        try {
            for (Map.Entry<InvItem, Integer> e : constructionNeeds(th).entrySet()) {
                InvItem ii = e.getKey();
                int held = th.countGoods(ii.getItem(), ii.meta);
                lines.add(ii.getName() + " (meta " + ii.meta + "): needed " + e.getValue()
                        + ", held " + held + ", missing " + Math.max(0, e.getValue() - held));
            }
        } finally {
            LoadedChunks.release(world, borrowed);
        }
        return lines;
    }

    private static void addCost(Map<InvItem, Integer> needs, @Nullable BuildingPlan plan) {
        if (plan == null || plan.resCost == null) return;
        for (Map.Entry<InvItem, Integer> e : plan.resCost.entrySet()) {
            InvItem ii = e.getKey();
            if (ii == null || ii.getItem() == null || e.getValue() == null || e.getValue() <= 0) continue;
            needs.merge(ii, e.getValue(), Integer::sum);
        }
    }

    // Millénaire's own seller check: not under attack, chests locked, reputation above the floor.
    private static boolean canTrade(Building th, UserProfile profile) {
        return !th.underAttack && th.chestLocked && profile.getReputation(th) >= MIN_REP_TO_TRADE;
    }

    private static void trade(Building th, IItemHandlerModifiable cargo, UserProfile profile,
                              @Nullable EntityPlayer online, boolean donate, Delivery out) {
        Culture culture = th.culture;
        for (int slot = 0; slot < cargo.getSlots(); slot++) {
            ItemStack stack = cargo.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            int meta = stack.getMetadata();
            String displayName = stack.getDisplayName();

            TradeGood good = culture.getTradeGood(InvItem.createInvItem(item, meta));
            int unitPrice = good == null ? 0 : buyingPrice(good, th, online);
            if (!donate && unitPrice <= 0) {
                out.unsold = true;
                continue;
            }

            int stored = storeStack(th, stack);
            if (stored <= 0) {
                if (!donate) out.unsold = true;
                continue;
            }
            cargo.extractItem(slot, stored, false);
            profile.adjustLanguage(culture.key, stored);

            int value = unitPrice * stored;
            int rep = donate ? value * ContainerTrade.DONATION_REP_MULTIPLIER : value;
            int money = donate ? 0 : value;
            out.reputation += rep;
            out.money += money;
            if (rep != 0 || money != 0) {
                out.items += stored;
                int[] agg = out.traded.computeIfAbsent(displayName, k -> new int[2]);
                agg[0] += stored;
                agg[1] += Math.max(money, rep);
            }
        }
        if (out.reputation != 0) profile.adjustReputation(th, out.reputation);
    }

    // Puts the exact stack (NBT included: book text, enchantments, names, damage) into the building's
    // chests; Millénaire's storeGoods rebuilds items from id + meta only. Returns how many went in.
    private static int storeStack(Building th, ItemStack stack) {
        ResManager res = th.getResManager();
        if (res == null || res.chests == null || stack.isEmpty()) return 0;
        List<IInventory> chests = new ArrayList<>();
        for (Point p : res.chests) {
            IInventory chest = p.getMillChest(th.world);
            if (chest != null) chests.add(chest);
        }
        int left = stack.getCount();
        for (IInventory chest : chests) {
            int limit = Math.min(chest.getInventoryStackLimit(), stack.getMaxStackSize());
            for (int i = 0; i < chest.getSizeInventory() && left > 0; i++) {
                ItemStack in = chest.getStackInSlot(i);
                if (in.isEmpty() || in.getCount() >= limit || !ItemStack.areItemsEqual(in, stack)
                        || !ItemStack.areItemStackTagsEqual(in, stack)) continue;
                int add = Math.min(left, limit - in.getCount());
                in.grow(add);
                chest.setInventorySlotContents(i, in);
                left -= add;
            }
        }
        for (IInventory chest : chests) {
            int limit = Math.min(chest.getInventoryStackLimit(), stack.getMaxStackSize());
            for (int i = 0; i < chest.getSizeInventory() && left > 0; i++) {
                if (!chest.getStackInSlot(i).isEmpty() || !chest.isItemValidForSlot(i, stack)) continue;
                ItemStack put = stack.copy();
                put.setCount(Math.min(left, limit));
                chest.setInventorySlotContents(i, put);
                left -= put.getCount();
            }
        }
        int stored = stack.getCount() - left;
        if (stored > 0) {
            for (IInventory chest : chests) chest.markDirty();
            th.invalidateInventoryCache();
        }
        return stored;
    }

    // Millénaire's dynamic price (online employer only), else the village type's base price, else the raw base price.
    private static int buyingPrice(TradeGood good, Building th, @Nullable EntityPlayer employer) {
        int price = 0;
        if (employer != null) {
            try {
                price = good.getCalculatedBuyingPrice(th, employer);
            } catch (NullPointerException npe) {
                price = 0;
            }
        }
        if (price <= 0) price = good.getBasicBuyingPrice(th);
        if (price <= 0) price = good.getBasicBuyingPrice(null);
        return price;
    }

    private static List<Chunk> borrowChestChunks(WorldServer world, Building b) {
        List<Chunk> borrowed = new ArrayList<>();
        ResManager res = b.getResManager();
        if (res == null || res.chests == null) return borrowed;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Point p : res.chests) {
            int cx = p.getiX() >> 4, cz = p.getiZ() >> 4;
            if (seen.add(((long) cx << 32) | (cz & 0xFFFFFFFFL))) LoadedChunks.loadTemporarily(world, cx, cz, borrowed);
        }
        return borrowed;
    }
}
