package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.travel.mount.TravellerMountSupport;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneRoles;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The settle-gated heavy pass over a loaded Stable's box: sync the chest mirror against real chests,
 * rescan beds, then scan for {@code EntityHorse} entities to claim wild arrivals / relink pool
 * horses by their {@code POOL_ID_TAG} / drop stale links after a grace period / trickle one dormant
 * entry back into the world per pass. Split out of {@code StableCoordinator}; per-tick containment
 * for horses this pass already knows lives in {@link StableContainmentSweep} instead, ungated.
 */
final class StablePhysicalReconciler {

    private StablePhysicalReconciler() {
    }

    static void reconcile(WorldServer world, PlayerZoneManager mgr, PlayerZoneRecord zone,
                          StableCoordinator coord, boolean rescanBedsNow) {
        StableZoneData data = (zone.data instanceof StableZoneData) ? (StableZoneData) zone.data : null;
        if (data == null) return;

        syncChestMirror(world, zone, data);
        if (rescanBedsNow && StableBedService.rescanBeds(world, zone, data)) mgr.markDirty();

        int margin = TravellersConfig.STABLES.containmentMarginBlocks;
        AxisAlignedBB scanBox = new AxisAlignedBB(
                zone.center.getX() - zone.halfWidth - margin,
                zone.center.getY() - zone.halfHeight - margin,
                zone.center.getZ() - zone.halfDepth - margin,
                zone.center.getX() + zone.halfWidth + 1 + margin,
                zone.center.getY() + zone.halfHeight + 1 + margin,
                zone.center.getZ() + zone.halfDepth + 1 + margin);

        long now = world.getTotalWorldTime();
        List<EntityHorse> found = world.getEntitiesWithinAABB(EntityHorse.class, scanBox);
        boolean dirty = false;

        Set<UUID> stillHere = new HashSet<>();
        Map<UUID, EntityHorse> claimedThisPass = new HashMap<>();
        for (EntityHorse horse : found) {
            // A horse that took its fatal hit this tick but hasn't been purged yet must never be
            // treated as a live pool member - StableHorseDeathHandler is the authority for "gone".
            if (horse.isDead || horse.getHealth() <= 0.0F) continue;

            UUID tagPoolId = StableCoordinator.getPoolTag(horse);
            if (tagPoolId != null && claimedThisPass.putIfAbsent(tagPoolId, horse) != null) {
                // Two live horses carrying one entry's tag in a single pass: a proven duplicate.
                horse.setDead();
                dirty = true;
                continue;
            }

            UUID id = horse.getUniqueID();
            stillHere.add(id);
            StableHorseEntry entry = data.findByLiveEntity(id);

            if (entry == null && tagPoolId != null) {
                // Carries our identity marker - one of ours reappearing after whatever broke the
                // liveEntityUUID link. Relink rather than minting a second entry.
                entry = data.findByPoolId(tagPoolId);
                if (entry != null) entry.liveEntityUUID = id;
            }

            if (entry == null) {
                entry = tryClaim(world, zone, data, horse, id, now);
                if (entry == null) continue;
                dirty = true;
            }

            StableCoordinator.setPoolTag(horse, entry.poolId);
            coord.missingSince().remove(entry.poolId);

            // Pool state is owned by StableWork and kept advancing while the chunk was
            // unloaded; the reloaded entity is stale, so push the entry onto it, never read back.
            StableHorseSync.pushEntryOntoHorse(entry, horse, zone.ownerUUID);
            entry.tamed = horse.isTame();
            entry.saddled = horse.isHorseSaddled();
            entry.temper = horse.getTemper();
            entry.growingAge = horse.getGrowingAge();
            NBTTagCompound freshNbt = new NBTTagCompound();
            horse.writeToNBT(freshNbt);
            entry.horseNbt = freshNbt;
            entry.lastAgeSyncTick = world.getTotalWorldTime();
            coord.trackLiveHorse(id);

            if (StableHorseAI.needsInvariants(horse)) {
                StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
            }
            StableContainmentGuard.enforce(horse, zone.center, zone.halfWidth, zone.halfDepth);
        }

        // Drop stale live-entity links, but only after a grace period - a world finishing (re)load
        // can read a Stable's chunk as loaded a tick or two before its saved entities are back in
        // the live list; clearing the link immediately let the materialize pass below spawn a
        // substitute right before the real horse caught up (the duplication bug).
        for (StableHorseEntry entry : data.pool) {
            if (entry.liveEntityUUID == null || stillHere.contains(entry.liveEntityUUID)) continue;
            Long since = coord.missingSince().get(entry.poolId);
            if (since == null) {
                coord.missingSince().put(entry.poolId, now);
                continue;
            }
            if (now - since < TravellersConfig.STABLES.missingHorseGraceTicks) continue;
            coord.forgetLiveHorse(entry.liveEntityUUID);
            entry.liveEntityUUID = null;
            coord.missingSince().remove(entry.poolId);
            dirty = true;
        }

        // Materialize every ready dormant entry this pass - a Stable the player walks back to should
        // show the horses it grew (foals bred offscreen especially) all at once, not one per tick.
        // The pool is capped (per-worker + per-pen area), so this can't be a large burst.
        Iterator<StableHorseEntry> it = data.pool.iterator();
        while (it.hasNext()) {
            StableHorseEntry entry = it.next();
            if (entry.liveEntityUUID != null || entry.horseNbt == null) continue;
            if (StableMaterializer.isDeadSnapshot(entry.horseNbt)) {
                // A dead horse, not a dormant one - drop it instead of resurrecting a corpse.
                it.remove();
                dirty = true;
                continue;
            }
            EntityHorse spawned = StableMaterializer.materializeEntry(world, zone, entry);
            if (spawned != null) {
                entry.liveEntityUUID = spawned.getUniqueID();
                coord.trackLiveHorse(spawned.getUniqueID());
                dirty = true;
            }
        }

        if (dirty) mgr.markDirty();
    }

    /** Auto-claim a wild horse, a foal with no entry (a birth from this pen's own parents), or the
     * owner's own tamed unbonded horse led back in. Null = not claimable this pass. */
    private static StableHorseEntry tryClaim(WorldServer world, PlayerZoneRecord zone, StableZoneData data,
                                             EntityHorse horse, UUID id, long now) {
        boolean wild = !horse.isTame();
        boolean bredFoal = horse.isChild();
        // The vanilla owner tag is set once a horse enters the pool (StableHorseSync) and is never
        // cleared when the owner later takes it out (detachHorseFromPool only drops the pool entry
        // and the mod's own tag) - so a horse the owner is actively riding away still reads as
        // "my own tamed horse" here. Without the isBeingRidden() exclusion, the very next reconcile
        // pass after mounting one's own pool horse - practically guaranteed to still be within the
        // zone's scan box seconds later - silently reclaimed it right back into the pool, and
        // containment then fought the player's own riding input (desyncs, snapping back on dismount).
        boolean reclaimedByOwner = !wild && !bredFoal && !horse.isBeingRidden()
                && !TravellerMountSupport.isBondedMount(horse)
                && zone.ownerUUID.equals(horse.getOwnerUniqueId());
        if (!wild && !bredFoal && !reclaimedByOwner) return null;

        if (reclaimedByOwner) {
            // A tagless tame owner-horse: before minting a brand-new entry (the last remaining
            // duplication vector - a pool horse whose ForgeData tag was wiped comes back looking
            // exactly like this), try to recognise it as a dormant entry that is currently missing
            // its horse. Only relink when exactly one entry plausibly matches; 0 or >1 -> mint.
            StableHorseEntry relink = findLostEntryFor(data, horse, now);
            if (relink != null) {
                relink.liveEntityUUID = id;
                return relink;
            }
        }

        // Area guard-rail only blocks new outside intake - a bred foal is the pool's own already-gated
        // growth, so it is still claimed even at cap rather than left loose in its own pen.
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

    /** The single dormant pool entry (no live horse) that plausibly IS {@code horse}, or null if
     * none or more than one match. Matches on tamed/saddled state and, when the entry's snapshot
     * carries a position, on being within {@link #RELINK_RADIUS} blocks of it. Entries whose horse
     * was materialized moments ago are skipped so a just-spawned substitute is never adopted. */
    private static StableHorseEntry findLostEntryFor(StableZoneData data, EntityHorse horse, long now) {
        StableHorseEntry match = null;
        for (StableHorseEntry e : data.pool) {
            if (e.liveEntityUUID != null || e.horseNbt == null) continue;
            if (e.tamed != horse.isTame() || e.saddled != horse.isHorseSaddled()) continue;
            if (StableMaterializer.spawnedRecently(e.poolId, now)) continue;
            double[] pos = readPos(e.horseNbt);
            if (pos != null) {
                double dx = pos[0] - horse.posX, dz = pos[2] - horse.posZ;
                if (dx * dx + dz * dz > RELINK_RADIUS * RELINK_RADIUS) continue;
            }
            if (match != null) return null; // ambiguous - do not guess
            match = e;
        }
        return match;
    }

    private static final double RELINK_RADIUS = 24.0D;

    private static double[] readPos(NBTTagCompound nbt) {
        if (!nbt.hasKey("Pos", 9)) return null;
        net.minecraft.nbt.NBTTagList l = nbt.getTagList("Pos", 6);
        if (l.tagCount() < 3) return null;
        return new double[]{l.getDoubleAt(0), l.getDoubleAt(1), l.getDoubleAt(2)};
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
