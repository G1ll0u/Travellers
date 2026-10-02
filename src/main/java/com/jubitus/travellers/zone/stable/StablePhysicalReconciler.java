package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneRoles;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.Iterator;
import java.util.UUID;

/**
 * The live pass over a Stable's box (only while it is live, see {@link StableCoordinator}): sync the
 * chest mirror, keep every linked horse's entry in step with it, claim newcomers (wild horses, foals,
 * the owner's own tame horse led in), drop horses that vanished, and spawn every entry still held as
 * data. Containment for linked horses is {@link StableContainmentSweep}.
 */
final class StablePhysicalReconciler {

    private StablePhysicalReconciler() {
    }

    static void reconcile(WorldServer world, PlayerZoneManager mgr, PlayerZoneRecord zone,
                          StableCoordinator coord) {
        StableZoneData data = (zone.data instanceof StableZoneData) ? (StableZoneData) zone.data : null;
        if (data == null) return;

        syncChestMirror(world, zone, data);

        int margin = TravellersConfig.STABLES.containmentMarginBlocks;
        AxisAlignedBB scanBox = zone.box(margin);

        long now = world.getTotalWorldTime();
        boolean dirty = false;

        for (EntityHorse horse : world.getEntitiesWithinAABB(EntityHorse.class, scanBox)) {
            // A horse that took its fatal hit this tick is StableHorseDeathHandler's business.
            if (horse.isDead || horse.getHealth() <= 0.0F) continue;
            UUID id = horse.getUniqueID();

            StableHorseEntry entry;
            UUID tag = StableCoordinator.getPoolTag(horse);
            if (tag != null) {
                // Tagged: only ever the entity its entry links (the join guard refuses any other).
                entry = data.findByPoolId(tag);
                if (entry == null || !id.equals(entry.liveEntityUUID)) continue;
            } else {
                entry = data.findByLiveEntity(id);
                if (entry == null) {
                    entry = tryClaim(world, zone, data, horse, id);
                    if (entry == null) continue;
                    dirty = true;
                }
                StableCoordinator.setPoolTag(horse, entry.poolId);
            }

            // Pool state is owned by StableWork: push it onto the entity, then mirror the entity back.
            StableHorseSync.pushEntryOntoHorse(entry, horse, zone.ownerUUID);
            entry.tamed = horse.isTame();
            entry.saddled = horse.isHorseSaddled();
            entry.temper = horse.getTemper();
            entry.growingAge = horse.getGrowingAge();
            NBTTagCompound freshNbt = new NBTTagCompound();
            horse.writeToNBT(freshNbt);
            entry.horseNbt = freshNbt;
            entry.lastAgeSyncTick = now;

            if (StableHorseAI.needsInvariants(horse)) {
                StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
            }
            StableContainmentGuard.enforce(horse, zone);
        }

        dirty |= coord.dropVanishedHorses(world, zone, data);

        Iterator<StableHorseEntry> it = data.pool.iterator();
        while (it.hasNext()) {
            StableHorseEntry entry = it.next();
            if (entry.liveEntityUUID != null || entry.horseNbt == null) continue;
            if (StableMaterializer.isDeadSnapshot(entry.horseNbt)) {
                it.remove(); // a dead horse, not a dormant one
                dirty = true;
                continue;
            }
            if (StableMaterializer.materializeEntry(world, zone, entry) != null) dirty = true;
        }

        if (dirty) mgr.markDirty();
    }

    /** Auto-claim a wild horse, a foal with no entry (a birth from this pen's own parents), or the
     * owner's own tame unbonded horse led back in on foot. Null = not claimable this pass. */
    private static StableHorseEntry tryClaim(WorldServer world, PlayerZoneRecord zone, StableZoneData data,
                                             EntityHorse horse, UUID id) {
        // Never a ridden horse or someone's mount (the owner keeps its vanilla owner tag after taking
        // one out, so riding it through the pen would otherwise re-pool it mid-ride).
        if (horse.isBeingRidden() || TravellerMountSupport.isBondedMount(horse)) return null;
        boolean wild = !horse.isTame();
        boolean bredFoal = horse.isChild();
        boolean reclaimedByOwner = !wild && !bredFoal && zone.ownerUUID.equals(horse.getOwnerUniqueId());
        if (!wild && !bredFoal && !reclaimedByOwner) return null;

        // Area guard-rail only blocks new outside intake - a foal is the pool's own growth.
        if (!bredFoal && !StableCapacity.hasRoomFor(zone)) return null;

        StableHorseEntry entry = new StableHorseEntry();
        entry.liveEntityUUID = id;
        entry.origin = wild ? StableHorseEntry.Origin.WILD_CLAIMED
                : bredFoal ? StableHorseEntry.Origin.BRED
                : StableHorseEntry.Origin.RECLAIMED_BY_OWNER;
        entry.reservedAtTick = world.getTotalWorldTime();
        data.pool.add(entry);
        StableWatchLog.record(world, zone, StableWatchLog.Kind.INTAKE,
                StableWatchLog.shortId(entry.poolId) + " " + entry.origin, true);
        return entry;
    }

    // -------------------------------------------------------------------------
    // Chest mirror sync (loaded only)
    // -------------------------------------------------------------------------

    private static void syncChestMirror(WorldServer world, PlayerZoneRecord zone, StableZoneData data) {
        StableChestMirror mirror = data.chestMirror;
        removeFromChests(world, zone, Items.WHEAT, mirror.drainSpent(Items.WHEAT));
        removeFromChests(world, zone, Items.SADDLE, mirror.drainSpent(Items.SADDLE));
        removeFromChests(world, zone, Items.GOLDEN_CARROT, mirror.drainSpent(Items.GOLDEN_CARROT));
        removeFromChests(world, zone, Items.GOLDEN_APPLE, mirror.drainSpent(Items.GOLDEN_APPLE));
        mirror.importCounts(
                countInChests(world, zone, Items.WHEAT),
                countInChests(world, zone, Items.SADDLE),
                countInChests(world, zone, Items.GOLDEN_CARROT),
                countInChests(world, zone, Items.GOLDEN_APPLE));
    }

    private static int countInChests(WorldServer world, PlayerZoneRecord zone, Item item) {
        int total = 0;
        for (BlockPos p : zone.getRolePositions(ZoneRoles.CHEST)) {
            if (!world.isBlockLoaded(p)) continue;
            TileEntity te = world.getTileEntity(p);
            if (!(te instanceof IInventory)) continue;
            IInventory inv = (IInventory) te;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
            }
        }
        return total;
    }

    private static void removeFromChests(WorldServer world, PlayerZoneRecord zone, Item item, int amount) {
        if (amount <= 0) return;
        for (BlockPos p : zone.getRolePositions(ZoneRoles.CHEST)) {
            if (amount <= 0) return;
            if (!world.isBlockLoaded(p)) continue;
            TileEntity te = world.getTileEntity(p);
            if (!(te instanceof IInventory)) continue;
            IInventory inv = (IInventory) te;
            for (int i = 0; i < inv.getSizeInventory() && amount > 0; i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.isEmpty() || s.getItem() != item) continue;
                int take = Math.min(amount, s.getCount());
                s.shrink(take);
                amount -= take;
                inv.setInventorySlotContents(i, s);
                inv.markDirty();
            }
        }
        // Leftover just means the player emptied the chest between the offscreen spend and this
        // reconcile - the work was already done, nothing to claw back.
    }
}
