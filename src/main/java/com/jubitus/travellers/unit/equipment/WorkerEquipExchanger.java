package com.jubitus.travellers.unit.equipment;

import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.notify.MissionNotifications;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillGoods;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneRoles;
import com.jubitus.travellers.zone.ZoneType;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Get Equipment at a Barrack or a controlled village: the leader and its followers swap their gear for
 * better weapons, bows and armour from the chests, judged by {@link TravellerEquipmentPolicy}. Weapons
 * live in the backpack (the hand only shows a ghost copy); displaced items go back to the chests.
 */
public final class WorkerEquipExchanger {

    private static final EntityEquipmentSlot[] ARMOR_SLOTS = {
        EntityEquipmentSlot.HEAD,
        EntityEquipmentSlot.CHEST,
        EntityEquipmentSlot.LEGS,
        EntityEquipmentSlot.FEET
    };

    private WorkerEquipExchanger() {}

    /** Returns {workers that got something, workers considered} (leader plus followers). */
    public static int[] exchangeWithFollowers(EntityTravellerWorker leader, WorldServer world,
                                              BlockPos target, EquipTargetKind kind) {
        int[] result = {0, 1};
        withChests(world, leader, target, kind, chests -> {
            if (!chests.isEmpty()) applyToGroup(leader, world, w -> equipFromChests(w, chests), result);
        });
        return result;
    }

    /** Drops the group's backpack surplus into the chests; returns {workers who dropped, workers considered, items stored, items left}. */
    public static int[] dumpWithFollowers(EntityTravellerWorker leader, WorldServer world,
                                          BlockPos target, EquipTargetKind kind) {
        int[] result = {0, 1, 0, 0};
        withChests(world, leader, target, kind, chests -> {
            if (chests.isEmpty()) return;
            applyToGroup(leader, world, w -> {
                int[] d = WorkerItemDumper.dump(w, chests);
                result[2] += d[0];
                result[3] += d[1];
                return d[0] > 0;
            }, result);
        });
        return result;
    }

    /** Runs the chest mission {@code kind} for the leader's group and tells the owner the outcome. */
    public static void run(EntityTravellerWorker leader, WorldServer world, BlockPos target, EquipTargetKind kind) {
        if (kind.isDump()) {
            int[] r = dumpWithFollowers(leader, world, target, kind);
            MissionNotifications.dumped(world.getMinecraftServer(), leader.getFollowOwnerId(), r[2], r[3], r[0]);
        } else {
            int[] r = exchangeWithFollowers(leader, world, target, kind);
            MissionNotifications.equipped(world.getMinecraftServer(), leader.getFollowOwnerId(), r[0], r[1]);
        }
    }

    private static void withChests(WorldServer world, EntityTravellerWorker leader, BlockPos target,
                                   EquipTargetKind kind, Consumer<List<IInventory>> use) {
        if (kind.chestKind() == EquipTargetKind.CONTROLLED_VILLAGE) {
            MillGoods.withVillageChests(world, target, use);
        } else {
            withBarrackChests(world, leader.getFollowOwnerId(), target, use);
        }
    }

    /** Applies {@code action} (true = changed something) to the leader, then to every follower, loaded or virtual. */
    private static void applyToGroup(EntityTravellerWorker leader, WorldServer world,
                                     Predicate<EntityTravellerWorker> action, int[] result) {
        if (action.test(leader)) result[0]++;

        UUID leaderUUID = leader.getUniqueID();
        MinecraftServer srv = world.getMinecraftServer();
        if (srv != null) {
            for (WorldServer ws : srv.worlds) {
                for (Entity e : new ArrayList<>(ws.loadedEntityList)) {
                    if (!(e instanceof EntityTravellerWorker)) continue;
                    EntityTravellerWorker follower = (EntityTravellerWorker) e;
                    if (!leaderUUID.equals(follower.getEscortLeaderUUID())) continue;
                    result[1]++;
                    if (action.test(follower)) result[0]++;
                }
            }
        }

        VirtualTravellerManager vwm = VirtualTravellerManager.get(world);
        boolean dirty = false;
        for (VirtualTravellerRecord r : vwm.getAllRecords()) {
            if (!leaderUUID.equals(r.escortLeaderUUID)) continue;
            EntityTravellerWorker temp = new EntityTravellerWorker(world);
            temp.readFromNBT(r.entityNbt);
            temp.setUniqueId(r.uuid);
            result[1]++;
            if (!action.test(temp)) continue;
            result[0]++;
            NBTTagCompound updatedNbt = new NBTTagCompound();
            temp.writeToNBT(updatedNbt);
            r.entityNbt = updatedNbt;
            r.invalidateNbtCaches();
            dirty = true;
        }
        if (dirty) vwm.markDirty();
    }

    /** Hands the owner's Barrack chests to {@code use}, the Barrack's chunks borrowed for the call. */
    private static void withBarrackChests(WorldServer world, UUID ownerId, BlockPos center,
                                          Consumer<List<IInventory>> use) {
        PlayerZoneRecord rec = ownerId == null ? null : PlayerZoneManager.get(world).getByOwnerAndCenter(ownerId, center);
        if (rec == null || rec.type != ZoneType.BARRACK) return;

        List<Chunk> borrowed = new ArrayList<>();
        try {
            for (int cx = (center.getX() - rec.halfWidth) >> 4; cx <= (center.getX() + rec.halfWidth) >> 4; cx++) {
                for (int cz = (center.getZ() - rec.halfDepth) >> 4; cz <= (center.getZ() + rec.halfDepth) >> 4; cz++) {
                    LoadedChunks.loadTemporarily(world, cx, cz, borrowed);
                }
            }
            // Refreshed so newly placed chests count.
            rec.setRolePositions(ZoneRoles.CHEST,
                    PlayerZoneManager.findCoffers(world, rec.center, rec.halfWidth, rec.halfDepth, rec.halfHeight));
            PlayerZoneManager.get(world).markDirty();

            List<IInventory> chests = new ArrayList<>();
            for (BlockPos p : rec.getRolePositions(ZoneRoles.CHEST)) {
                TileEntity te = world.getTileEntity(p);
                if (te instanceof IInventory) chests.add((IInventory) te);
            }
            use.accept(chests);
            for (IInventory chest : chests) chest.markDirty();
        } finally {
            LoadedChunks.release(world, borrowed);
        }
    }

    private static boolean equipFromChests(EntityTravellerWorker worker, List<IInventory> chests) {
        boolean changed = false;
        for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
            changed |= takeArmor(worker, chests, slot);
        }
        changed |= takeMeleeWeapon(worker, chests);
        changed |= takeBow(worker, chests);
        changed |= takeShield(worker, chests);
        return changed;
    }

    private static boolean takeArmor(EntityTravellerWorker worker, List<IInventory> chests, EntityEquipmentSlot slot) {
        TravellerEquipmentManager eq = worker.equipment;
        Pick best = null;
        for (IInventory chest : chests) {
            for (int i = 0; i < chest.getSizeInventory(); i++) {
                ItemStack s = chest.getStackInSlot(i);
                if (s.isEmpty() || eq.isGhost(s) || !eq.isArmorUpgrade(s, slot)) continue;
                double rank = TravellerEquipmentPolicy.armorRank(s, slot);
                if (best == null || rank > best.rank) best = new Pick(chest, i, rank);
            }
        }
        if (best == null) return false;

        ItemStack taken = best.chest.decrStackSize(best.slot, 1);
        ItemStack old = worker.getItemStackFromSlot(slot);
        worker.setItemStackToSlot(slot, taken);
        if (!old.isEmpty()) giveBack(worker, chests, old);
        return true;
    }

    private static boolean takeMeleeWeapon(EntityTravellerWorker worker, List<IInventory> chests) {
        TravellerEquipmentManager eq = worker.equipment;
        Pick best = null;
        for (IInventory chest : chests) {
            for (int i = 0; i < chest.getSizeInventory(); i++) {
                ItemStack s = chest.getStackInSlot(i);
                if (s.isEmpty() || eq.isGhost(s) || !TravellerEquipmentManager.isWeapon(s) || !eq.isWeaponUpgrade(s)) continue;
                double rank = TravellerEquipmentPolicy.weaponRank(s);
                if (best == null || rank > best.rank) best = new Pick(chest, i, rank);
            }
        }
        if (best == null) return false;

        ItemStackHandler pack = worker.inventory;
        ItemStack taken = best.chest.decrStackSize(best.slot, 1);
        ItemStack left = ItemHandlerHelper.insertItemStacked(pack, taken, false);
        if (!left.isEmpty()) {
            // Backpack full: the weapon stays in the chest.
            giveBack(worker, chests, left);
            return false;
        }
        // Only the new weapon is kept: older real melee weapons (backpack, or a real one left in hand) go to the chests.
        boolean newOneSeen = false;
        for (int i = 0; i < pack.getSlots(); i++) {
            ItemStack s = pack.getStackInSlot(i);
            if (!TravellerEquipmentManager.isWeapon(s) || TravellerEquipmentManager.isBow(s)) continue;
            if (!newOneSeen && ItemStack.areItemStacksEqual(s, taken)) {
                newOneSeen = true;
                continue;
            }
            giveBack(worker, chests, pack.extractItem(i, s.getCount(), false));
        }
        ItemStack hand = worker.getHeldItemMainhand();
        if (!hand.isEmpty() && !eq.isGhost(hand) && TravellerEquipmentManager.isWeapon(hand)) {
            worker.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            giveBack(worker, chests, hand);
        }
        return true;
    }

    private static boolean takeBow(EntityTravellerWorker worker, List<IInventory> chests) {
        TravellerEquipmentManager eq = worker.equipment;
        Pick best = null;
        for (IInventory chest : chests) {
            for (int i = 0; i < chest.getSizeInventory(); i++) {
                ItemStack s = chest.getStackInSlot(i);
                if (s.isEmpty() || eq.isGhost(s) || !eq.isBowUpgrade(s)) continue;
                double rank = TravellerEquipmentPolicy.weaponRank(s);
                if (best == null || rank > best.rank) best = new Pick(chest, i, rank);
            }
        }
        if (best == null) return false;

        ItemStackHandler pack = worker.inventory;
        for (int i = 0; i < pack.getSlots(); i++) {
            if (!TravellerEquipmentManager.isBow(pack.getStackInSlot(i))) continue;
            ItemStack old = pack.getStackInSlot(i);
            pack.setStackInSlot(i, best.chest.decrStackSize(best.slot, 1));
            giveBack(worker, chests, old);
            return true;
        }
        return false;
    }

    private static boolean takeShield(EntityTravellerWorker worker, List<IInventory> chests) {
        TravellerEquipmentManager eq = worker.equipment;
        Pick best = null;
        for (IInventory chest : chests) {
            for (int i = 0; i < chest.getSizeInventory(); i++) {
                ItemStack s = chest.getStackInSlot(i);
                if (s.isEmpty() || !eq.wantsShield(s)) continue;
                double rank = TravellerEquipmentPolicy.shieldRank(s);
                if (best == null || rank > best.rank) best = new Pick(chest, i, rank);
            }
        }
        if (best == null) return false;

        ItemStack old = worker.getHeldItemOffhand();
        worker.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, best.chest.decrStackSize(best.slot, 1));
        if (!old.isEmpty()) giveBack(worker, chests, old);
        return true;
    }

    /** Puts a displaced item into the chests, else the backpack, else drops it at the worker's feet. */
    private static void giveBack(EntityTravellerWorker worker, List<IInventory> chests, ItemStack stack) {
        ItemStack left = stack;
        for (IInventory chest : chests) {
            left = insert(chest, left);
            if (left.isEmpty()) return;
        }
        left = ItemHandlerHelper.insertItemStacked(worker.inventory, left, false);
        if (!left.isEmpty()) worker.entityDropItem(left, 0.0F);
    }

    private static ItemStack insert(IInventory chest, ItemStack stack) {
        ItemStack left = stack.copy();
        for (int i = 0; i < chest.getSizeInventory() && !left.isEmpty(); i++) {
            if (!chest.getStackInSlot(i).isEmpty() || !chest.isItemValidForSlot(i, left)) continue;
            int n = Math.min(left.getCount(), Math.min(chest.getInventoryStackLimit(), left.getMaxStackSize()));
            ItemStack put = left.splitStack(n);
            chest.setInventorySlotContents(i, put);
        }
        return left;
    }

    private static final class Pick {
        final IInventory chest;
        final int slot;
        final double rank;

        Pick(IInventory chest, int slot, double rank) {
            this.chest = chest;
            this.slot = slot;
            this.rank = rank;
        }
    }
}
