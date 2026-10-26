package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.unit.Relocate;
import com.google.common.base.Predicate;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZonePlacement;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import java.util.List;
import java.util.UUID;
import static com.jubitus.travellers.zone.stable.StableHandSupport.*;

/** Helpers and per-tick scratch shared by the palfrenier phase classes. */
final class StableHandSupport {

    static final double INTERACT_RANGE_SQ = 2.5D * 2.5D;
    /** Snap-hop watchdog for walking to a work spot (horse / chest): a palfrenier that can't route
     * into a walled structure past this many ticks, while within {@link #APPROACH_HOP_MAX_SQ}, is
     * teleported to a standable cell right next to the target. */
    static final long APPROACH_TIMEOUT = 100L;
    private static final double APPROACH_HOP_MAX_SQ = 18.0D * 18.0D;
    /** A palfrenier that still hasn't reached its work target after this long abandons it and
     * briefly avoids it, so one unreachable animal can't freeze its progress forever. */
    static final long MOVE_GIVE_UP_TICKS = 400L;
    private static final long AVOID_TICKS = 1200L;

    // Per-tick scratch (server thread only, valid only between begin() and end()) - lets the deep
    // helpers write StableWatchLog lines without every phase method threading the zone through.
    private WorldServer tickWorld;
    private PlayerZoneRecord tickZone;

    void begin(WorldServer world, PlayerZoneRecord zone) {
        this.tickWorld = world;
        this.tickZone = zone;
    }

    void end() {
        this.tickWorld = null;
        this.tickZone = null;
    }

    static StableHorseEntry poolEntry(StableZoneData data, EntityHorse horse) {
        return data.findByLiveEntity(horse.getUniqueID());
    }

    EntityHorse resolveTargetHorse(WorldServer world, PalfrenierState st) {
        if (world == null || st.targetHorseUUID == null) return null;
        Entity e = world.getEntityFromUuid(st.targetHorseUUID);
        return (e instanceof EntityHorse && !e.isDead) ? (EntityHorse) e : null;
    }

    EntityHorse resolvePoolHorse(WorldServer world, StableZoneData data, UUID poolId) {
        if (poolId == null) return null;
        StableHorseEntry e = data.findByPoolId(poolId);
        if (e == null || e.liveEntityUUID == null) return null;
        Entity ent = world.getEntityFromUuid(e.liveEntityUUID);
        return (ent instanceof EntityHorse && !ent.isDead) ? (EntityHorse) ent : null;
    }

    void startTarget(PalfrenierState st, UUID horseId, PalfrenierState.Phase phase, long now) {
        st.targetHorseUUID = horseId;
        st.phase = phase;
        st.phaseEndTick = 0L;
        st.targetSince = now;
        st.approachStartTick = -1L;
    }

    boolean isAvoided(PalfrenierState st, UUID horseId, long now) {
        return st.avoidHorseUUID != null && st.avoidHorseUUID.equals(horseId) && now < st.avoidUntil;
    }

    /** True (and abandons + briefly avoids the target) if the palfrenier still isn't within reach
     * of {@code (tx,ty,tz)} after {@link #MOVE_GIVE_UP_TICKS}. */
    boolean giveUpUnreachable(EntityTravellerWorker worker, PalfrenierState st,
                                      double tx, double ty, double tz, long now) {
        if (st.targetSince < 0L || now - st.targetSince <= MOVE_GIVE_UP_TICKS) return false;
        if (worker.getDistanceSq(tx, ty, tz) <= INTERACT_RANGE_SQ) return false;
        if (st.targetHorseUUID != null) {
            st.avoidHorseUUID = st.targetHorseUUID;
            st.avoidUntil = now + AVOID_TICKS;
        }
        if (worker.world instanceof WorldServer) {
            abandonChore((WorldServer) worker.world, worker, st);
        } else {
            reset(st);
        }
        logHand(worker, "gave up an unreachable target");
        st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
        return true;
    }

    void reset(PalfrenierState st) {
        st.phase = PalfrenierState.Phase.DECIDE;
        st.targetHorseUUID = null;
        st.targetChest = null;
        st.phaseEndTick = 0L;
        st.chestOpen = false;
        st.nextDecideTick = 0L;
        st.bedApproachStartTick = -1L;
        st.approachStartTick = -1L;
        st.targetSince = -1L;
        st.breedPoolIdA = null;
        st.breedPoolIdB = null;
        st.breedFeedBeats = 0L;
        // avoidHorseUUID / avoidUntil deliberately kept - they self-expire.
        // session / sessionUntilTick are NOT chore state - left untouched here.
    }

    /** Cleanly drop whatever chore is mid-flight: shut any chest this palfrenier left open, put down
     * a carried prop, step off a non-bonded horse, and return to DECIDE. Every "target left the pool
     * / unreachable / interrupted" bail-out routes through here, so none of them can leave a chest
     * lid stuck open or the palfrenier riding a horse it was only taming. */
    void abandonChore(WorldServer world, EntityTravellerWorker worker, PalfrenierState st) {
        if (st.chestOpen && st.targetChest != null) closeChestVisual(world, st.targetChest);
        clearHeldItem(worker);
        dismountIfRidingLoose(worker);
        reset(st);
    }

    void dismountIfRidingLoose(EntityTravellerWorker worker) {
        Entity mount = worker.getRidingEntity();
        if (mount instanceof EntityHorse && !HorseMarkers.isBondedMount(mount)) {
            worker.dismountRidingEntity();
        }
    }

    /**
     * Walk toward {@code (tx,ty,tz)}; returns true once within {@link #INTERACT_RANGE_SQ}. If the
     * navigator can't close the gap within {@link #APPROACH_TIMEOUT} and we're within
     * {@link #APPROACH_HOP_MAX_SQ}, snap to a standable cell right next to the target - vanilla
     * routinely returns a best-effort path that ends at the exterior wall of an enclosed target.
     */
    boolean approachWorkSpot(EntityTravellerWorker worker, PalfrenierState st,
                                     double tx, double ty, double tz, long now) {
        double dsq = worker.getDistanceSq(tx, ty, tz);
        if (dsq <= INTERACT_RANGE_SQ) {
            worker.getNavigator().clearPath();
            st.approachStartTick = -1L;
            return true;
        }
        if (st.approachStartTick < 0L) st.approachStartTick = now;
        worker.getNavigator().tryMoveToXYZ(tx, ty, tz, com.jubitus.travellers.unit.UnitSpeed.travelNav());
        if (now - st.approachStartTick > APPROACH_TIMEOUT && dsq <= APPROACH_HOP_MAX_SQ
                && worker.world instanceof WorldServer) {
            BlockPos spot = ZonePlacement.findHitboxStandableNear((WorldServer) worker.world, tx, ty, tz, worker);
            if (spot != null) {
                Relocate.teleport(worker, spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
                logHand(worker, "snap-hopped to a walled-in work spot");
            }
            st.approachStartTick = -1L; // re-approach fresh next tick if the hop didn't land in range
        }
        return false;
    }

    void clearHeldItem(EntityTravellerWorker worker) {
        ItemStack cur = worker.getHeldItemMainhand();
        if (cur.isEmpty()) return;
        boolean prop = cur.getItem() == Items.LEAD;
        for (Item item : StableChestMirror.ITEMS) prop |= cur.getItem() == item;
        if (prop) worker.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /** Writes a palfrenier-lifecycle line to {@link StableWatchLog} using the per-tick scratch. */
    void logHand(EntityTravellerWorker worker, String event) {
        if (tickWorld == null || tickZone == null) return;
        String name = worker.getName();
        StableWatchLog.hand(tickWorld, tickZone, (name == null || name.isEmpty()) ? "Palfrenier" : name, event);
    }

    void spawnHearts(WorldServer world, EntityHorse horse) {
        world.spawnParticle(EnumParticleTypes.HEART, horse.posX,
                horse.posY + horse.height * 0.7D, horse.posZ, 3, 0.4D, 0.4D, 0.4D, 0.0D);
    }

    void openChestVisual(WorldServer world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te == null) return;
        world.addBlockEvent(pos, te.getBlockType(), 1, 1);
    }

    void closeChestVisual(WorldServer world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te == null) return;
        world.addBlockEvent(pos, te.getBlockType(), 1, 0);
    }

    BlockPos nearestChest(EntityTravellerWorker worker, List<BlockPos> chests) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : chests) {
            double d = worker.getDistanceSq(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    EntityHorse findNearest(WorldServer world, PlayerZoneRecord zone, EntityTravellerWorker worker,
                                    Predicate<EntityHorse> filter) {
        List<EntityHorse> found = world.getEntitiesWithinAABB(EntityHorse.class, zone.box(0), filter);
        EntityHorse nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (EntityHorse h : found) {
            double d = worker.getDistanceSq(h);
            if (d < bestDist) {
                bestDist = d;
                nearest = h;
            }
        }
        return nearest;
    }

    boolean breedingFeedAvailable(WorldServer world, PlayerZoneRecord zone) {
        return breedingFeedItem(world, zone) != null;
    }

    /** The breeding feed the chest currently has at least two of (carrot preferred), or null. */
    Item breedingFeedItem(WorldServer world, PlayerZoneRecord zone) {
        RealChestSource chest = new RealChestSource(world, zone);
        if (chest.available(Items.GOLDEN_CARROT) >= 2) return Items.GOLDEN_CARROT;
        if (chest.available(Items.GOLDEN_APPLE) >= 2) return Items.GOLDEN_APPLE;
        return null;
    }

    int countItem(WorldServer world, PlayerZoneRecord zone, Item item) {
        return new RealChestSource(world, zone).available(item);
    }
}
