package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.horse.HorseSnapshots;
import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Hands a hired worker a ready (tamed + saddled) horse from a Stable's pool via Get Equipment.
 * Deliberately NBT-first: a pool horse's entire state already IS its NBT (see {@link StableHorseEntry}),
 * so fulfilling this needs zero chunk loading and zero entity spawning when both the requesting
 * worker and the horse are virtual - at that point the whole exchange is just data changing hands,
 * exactly the same principle {@code WorkerEquipExchanger}'s virtual path uses for chest items.
 */
public final class WorkerHorsePickup {

    private WorkerHorsePickup() {
    }

    /**
     * Claims a ready horse's NBT from the zone's pool, removing it from the pool - or null if none is
     * ready yet (the worker simply waits, same as an empty-chest Get Equipment today). If the claimed
     * entry has a live materialized entity, it is despawned here: ownership of its state is
     * transferring to the new bond, not its position, so there is nothing left for it to be.
     */
    @Nullable
    public static NBTTagCompound claimReadyHorse(WorldServer world, PlayerZoneRecord zone) {
        if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) return null;
        StableZoneData data = (StableZoneData) zone.data;

        // Never take the pool below its configured reserve - a Stable must always keep enough horses
        // to still be a going concern (breeding pair, taming stock) even after Get Equipment runs.
        if (data.pool.size() <= TravellersConfig.STABLES.minPoolReserve) return null;

        StableHorseEntry chosen = null;
        // Prefer an entry with no live entity - nothing to tear down, purely a data handoff. Doesn't
        // require horseNbt here: StableCoordinator.reconcilePhysical keeps it continuously current on
        // every live pool horse now, so any tamed+saddled entry ends up eligible below regardless.
        for (StableHorseEntry e : data.pool) {
            if (e.tamed && e.saddled && e.liveEntityUUID == null) {
                chosen = e;
                break;
            }
        }
        if (chosen == null) {
            for (StableHorseEntry e : data.pool) {
                if (e.tamed && e.saddled && e.horseNbt != null) {
                    chosen = e;
                    break;
                }
            }
        }
        if (chosen == null) return null;

        data.pool.remove(chosen);
        com.jubitus.travellers.zone.stable.StableWatchLog.record(world, zone,
                com.jubitus.travellers.zone.stable.StableWatchLog.Kind.LEAVE,
                com.jubitus.travellers.zone.stable.StableWatchLog.shortId(chosen.poolId)
                        + " equipped to a worker", true);

        if (chosen.liveEntityUUID != null) {
            Entity live = world.getEntityFromUuid(chosen.liveEntityUUID);
            if (live instanceof EntityHorse && !live.isDead) {
                // Refresh the NBT one last time before despawning, so nothing since the last
                // reconcile pass (health regen, growth, ...) is lost.
                NBTTagCompound fresh = new NBTTagCompound();
                live.writeToNBT(fresh);
                chosen.horseNbt = fresh;
                live.setDead();
            }
        }

        // Strip the UUID unconditionally, not just on the just-despawned-live branch above: a
        // dormant entry's horseNbt was last written by StableCoordinator.reconcilePhysical's
        // continuous live-sync (never stripped there either, since that copy is only ever meant to
        // sit in the pool, not be respawned from immediately) and can still carry the UUID of a horse
        // that - for any reason a scan ever desyncs from the physical world - is genuinely still
        // alive elsewhere. Handing that NBT to an immediate respawn (bondSpecificMount here, or
        // restoreMount later for a virtual pickup) would collide with the still-registered original
        // and WorldServer.canAddEntity silently refuses the join - no exception, just a follower that
        // quietly never gets mounted. Every other site in this codebase that captures NBT for
        // imminent reuse already strips this; this was the one that didn't.
        if (chosen.horseNbt != null) {
            HorseSnapshots.stripIdentity(chosen.horseNbt);
            // Also drop the pool-identity marker: the horse is leaving the pool for good, it must not
            // reappear associated with an entry that no longer exists.
            HorseMarkers.clearPoolId(chosen.horseNbt);
        }

        PlayerZoneManager.get(world).markDirty();
        return chosen.horseNbt;
    }

    /**
     * Extends a leader's own Get Equipment claim (see {@link #claimReadyHorse}) to every still-
     * unmounted worker in its escort group - loaded or virtual - mirroring the precedent
     * {@code WorkerEquipExchanger.exchangeWithFollowers} already set for the chest-item exchange:
     * one member reaching the destination equips the whole group, followers don't each need to
     * travel there individually. Claims and bonds one horse per follower until either the group is
     * fully mounted or the pool runs dry - remaining unmounted followers simply wait for a future
     * Get Equipment order once more horses are ready. Returns how many followers got a horse.
     */
    public static int claimForGroupFollowers(WorldServer world, PlayerZoneRecord zone, UUID leaderUUID) {
        MinecraftServer server = world.getMinecraftServer();
        if (server == null) return 0;
        int claimed = 0;

        for (WorldServer ws : server.worlds) {
            for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class, e ->
                    e != null && !e.isDead && e.isHiredFollower()
                            && leaderUUID.equals(e.getEscortLeaderUUID()))) {
                if (w.getMountUUID() != null) continue;
                NBTTagCompound horseNbt = claimReadyHorse(world, zone);
                if (horseNbt == null) return claimed; // pool exhausted
                // Defense in depth: bondSpecificMount can still fail to actually spawn (any other
                // collision this UUID-strip didn't anticipate) - don't silently strand this follower
                // on foot with the horse simply gone from the pool. Give it back and stop for this
                // round rather than losing stock one bonding failure at a time.
                if (!TravellerMountSupport.bondSpecificMount(w, horseNbt)) {
                    returnUnclaimedHorse(world, zone, horseNbt);
                    return claimed;
                }
                claimed++;
            }
        }

        VirtualTravellerManager vwm = VirtualTravellerManager.get(world);
        for (VirtualTravellerRecord r : vwm.getRecordsView()) {
            if (!r.isHiredFollower || !leaderUUID.equals(r.escortLeaderUUID) || r.mounted) continue;
            NBTTagCompound horseNbt = claimReadyHorse(world, zone);
            if (horseNbt == null) return claimed;
            r.mountNbt = horseNbt;
            r.mounted = true;
            claimed++;
        }
        return claimed;
    }

    /**
     * A worker reached the Stable of a Get Equipment order: it claims a horse if it wants one, and its
     * group followers are equipped too. {@code attachHorse} binds the claimed horse to the worker (a live
     * bond or a virtual record) and returns false if that failed, which puts the horse back in the pool.
     */
    public static void equipAtStable(WorldServer world, PlayerZoneRecord zone, UUID workerId,
                                     @Nullable UUID ownerId, boolean wantsHorse,
                                     java.util.function.Predicate<NBTTagCompound> attachHorse) {
        boolean gotHorse = false;
        if (wantsHorse) {
            NBTTagCompound horseNbt = claimReadyHorse(world, zone);
            if (horseNbt != null) {
                gotHorse = attachHorse.test(horseNbt);
                // Spawn genuinely failed (a UUID collision): don't lose the horse from the pool's count.
                if (!gotHorse) returnUnclaimedHorse(world, zone, horseNbt);
            }
        }
        // Whoever this order was sent to arriving equips the whole group, as for chest gear.
        int followerHorses = claimForGroupFollowers(world, zone, workerId);
        if (wantsHorse && !gotHorse) notifyNothingToEquip(world, zone, ownerId);
        int horses = followerHorses + (gotHorse ? 1 : 0);
        // Silent only when the "no horse spare" notice above already told the owner.
        if (horses > 0 || !wantsHorse) {
            MinecraftServer srv = world.getMinecraftServer();
            int total = 1 + (srv != null && ownerId != null
                    ? com.jubitus.travellers.follow.group.WorkerGroupService.countFollowers(srv, ownerId, workerId) : 0);
            com.jubitus.travellers.notify.MissionNotifications.horsesTaken(srv, ownerId,
                    new com.jubitus.travellers.notify.NoticePlace(zone.center, world.provider.getDimension(), workerId),
                    horses, total, stableName(zone));
        }
    }

    /** Toast the owner when a Get Equipment order reached a Stable that had no saddled horse spare to
     * hand out (empty of ready stock, or only its reserve left) - otherwise the worker just walks
     * there and back with nothing and no explanation. Fire once per order, from the arrival site. */
    public static void notifyNothingToEquip(net.minecraft.world.World world, PlayerZoneRecord zone, UUID ownerUUID) {
        net.minecraft.server.MinecraftServer srv = world.getMinecraftServer();
        if (srv == null || ownerUUID == null) return;
        com.jubitus.travellers.notify.NotificationService.notifyOfflineAt(srv, ownerUUID,
                com.jubitus.travellers.notify.NotificationDefs.STABLE_POOL_TOO_SMALL_TO_EQUIP,
                new com.jubitus.travellers.notify.NoticePlace(zone.center, world.provider.getDimension(), null), null,
                stableName(zone));
    }

    /** The Stable's player-given name, or a plain fallback. */
    public static String stableName(PlayerZoneRecord zone) {
        return (zone.name == null || zone.name.isEmpty()) ? "Stable" : zone.name;
    }

    /** Puts a claimed-but-not-actually-bonded horse back into the pool as a fresh dormant entry,
     * rather than letting it vanish from the count entirely - used when {@code bondSpecificMount}
     * fails after the horse was already removed from the pool. */
    public static void returnUnclaimedHorse(WorldServer world, PlayerZoneRecord zone, NBTTagCompound horseNbt) {
        if (!(zone.data instanceof StableZoneData)) return;
        ((StableZoneData) zone.data).addCapturedHorse(world, horseNbt,
                StableHorseEntry.Origin.RELEASED_FROM_DEAD_RIDER, // closest existing "recovered stock" meaning
                world.getTotalWorldTime());
        PlayerZoneManager.get(world).markDirty();
    }
}
