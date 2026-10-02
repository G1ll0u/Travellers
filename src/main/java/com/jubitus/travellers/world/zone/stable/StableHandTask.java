package com.jubitus.travellers.world.zone.stable;

import com.google.common.base.Predicate;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.ambient.EntityAIStopAndLookAtPlayer;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.world.camp.CampsiteManager;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZonePlacement;
import com.jubitus.travellers.world.zone.ZoneRoles;
import com.jubitus.travellers.world.zone.ZoneType;
import com.jubitus.travellers.world.zone.duty.WorkerDutyBehavior;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The loaded palfrenier: walks its Stable and <b>does the work</b> - approaches and feeds an untamed
 * horse, rides it and gets bucked off, fetches a saddle from the chest and puts it on, brings golden
 * carrots to a breeding pair. Each of those actions calls the shared rules in {@link StableWork}
 * against a {@link RealChestSource}, so the effect is real (the entity tames, the chest shrinks, a
 * foal is born) and the palfrenier is the visible cause of it.
 *
 * <p>The palfrenier alternates two <b>sessions</b> (see {@link PalfrenierState.Session}): a timed
 * WORKING session running the chore state machine below, and a timed ON_BREAK session where it just
 * ambles the pen and looks around - no chores are considered while on a break. Durations come from
 * {@code TravellersConfig.STABLES.workSessionTicks}/{@code breakDurationTicks}, randomised per
 * palfrenier so a stable doesn't rest all at once.
 *
 * <p>The offscreen counterpart is {@link StableWork#timerPass}, driven from {@code StableCoordinator}
 * only while no loaded palfrenier is on station - the two never advance the same pool at once.
 *
 * <p>A palfrenier with no valid bed ({@code StableZoneData.bedByWorker}) does only the ambient
 * "social" behaviour here, never the labour - matching the coordinator, which counts it as not on
 * station.
 *
 * <p>Per-worker sequencing state lives in {@link PalfrenierState}, keyed by worker UUID in {@link
 * #STATES} - deliberately not persisted, see that class's javadoc. This class is a singleton shared
 * by every palfrenier (one instance in {@code DutyDefs}); the only per-call mutable state is the
 * {@link #tickWorld}/{@link #tickZone}/{@link #tickWorker} scratch, valid only inside one
 * {@code tick()} call on the server thread.
 */
public final class StableHandTask implements WorkerDutyBehavior {

    private static final double INTERACT_RANGE_SQ = 2.5D * 2.5D;
    private static final double SLEEP_ARRIVE_SQ = 4.0D;
    private static final double SLEEP_TELEPORT_MAX_SQ = 14.0D * 14.0D;
    private static final long SLEEP_APPROACH_TIMEOUT = 100L;
    /** Snap-hop watchdog for walking to a work spot (horse / chest): a palfrenier that can't route
     * into a walled structure past this many ticks, while within {@link #APPROACH_HOP_MAX_SQ}, is
     * teleported to a standable cell right next to the target. */
    private static final long APPROACH_TIMEOUT = 100L;
    private static final double APPROACH_HOP_MAX_SQ = 18.0D * 18.0D;
    /** A palfrenier that still hasn't reached its work target after this long abandons it and
     * briefly avoids it, so one unreachable animal can't freeze its progress forever. */
    private static final long MOVE_GIVE_UP_TICKS = 400L;
    private static final long AVOID_TICKS = 1200L;
    private static final int BREED_FEED_BEATS = 3;
    private static final double BREAK_LOOK_RANGE = 10.0D;

    private static final Map<UUID, PalfrenierState> STATES = new ConcurrentHashMap<>();

    // Per-tick scratch (server thread only, valid only within one tick() call) - lets the deep
    // helpers write StableWatchLog lines without every phase method threading the zone through.
    private WorldServer tickWorld;
    private PlayerZoneRecord tickZone;

    @Override
    public void tick(EntityTravellerWorker worker) {
        if (!TravellersConfig.STABLES.enabled) return;
        World world = worker.world;
        if (!(world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) world;
        long now = world.getTotalWorldTime();

        com.jubitus.travellers.world.zone.ZoneRef ref = worker.getZoneRef();
        UUID ownerId = worker.getFollowOwnerId();
        if (ref == null || !ref.isDuty() || ownerId == null) return;

        PlayerZoneRecord zone = ref.resolve(ownerId);
        if (zone == null || zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) return;
        StableZoneData data = (StableZoneData) zone.data;

        PalfrenierState st = STATES.computeIfAbsent(worker.getUniqueID(), id -> new PalfrenierState());

        this.tickWorld = ws;
        this.tickZone = zone;
        try {
            tickBody(ws, worker, zone, data, st, now);
        } finally {
            this.tickWorld = null;
            this.tickZone = null;
        }
    }

    @Override
    public void stop(EntityTravellerWorker worker) {
        PalfrenierState st = STATES.get(worker.getUniqueID());
        if (st != null && worker.world instanceof WorldServer) abandonChore((WorldServer) worker.world, worker, st);
        else dismountIfRidingLoose(worker);
    }

    private void tickBody(WorldServer ws, EntityTravellerWorker worker, PlayerZoneRecord zone,
                          StableZoneData data, PalfrenierState st, long now) {
        if (worker.isInCombat()) {
            // Combat AI owns the worker now - get out of bed and off any non-bonded horse so it can
            // actually fight, then stand aside for the movement mutex.
            if (worker.isSleepingInBed()) worker.wakeUpTraveller();
            dismountIfRidingLoose(worker);
            if (st.phase != PalfrenierState.Phase.DECIDE) {
                logHand(worker, "chore interrupted by combat");
                abandonChore(ws, worker, st);
            }
            return;
        }

        // A palfrenier arriving with its own bonded mount surrenders it into the pool it now tends -
        // riding around while doing stable chores looks absurd. Self-limiting: clears getMountUUID().
        if (worker.getMountUUID() != null) {
            surrenderOwnMount(ws, worker, zone, data);
            return;
        }

        BlockPos myBed = data.bedByWorker.get(worker.getUniqueID());
        boolean night = CampsiteManager.isNight(ws);

        if (night && myBed != null) {
            dismountIfRidingLoose(worker);
            tickNight(worker, st, myBed, now);
            return;
        }
        if (worker.isSleepingInBed()) {
            // Woke into daytime, or the bed was lost overnight - get up before anything else.
            worker.wakeUpTraveller();
            reset(st);
        }

        // ---- Session rhythm: alternate timed WORKING and ON_BREAK ----
        advanceSession(ws, worker, st, now);
        if (st.session == PalfrenierState.Session.ON_BREAK) {
            tickBreak(ws, worker, zone, st, now);
            return;
        }

        // ---- WORKING ----
        switch (st.phase) {
            case DECIDE:
                decide(ws, worker, zone, data, st, now);
                break;
            case FETCH_FEED:
                fetchFeed(ws, worker, zone, data, st, now);
                break;
            case MOVE_TO_HORSE:
                moveToHorse(ws, worker, zone, data, st, now);
                break;
            case FEED_HORSE:
                feedHorse(ws, worker, zone, data, st, now);
                break;
            case MOUNT_HORSE:
                mountHorse(ws, worker, zone, data, st, now);
                break;
            case RIDE_HORSE:
                rideHorse(ws, worker, zone, data, st, now);
                break;
            case DISMOUNT_PAUSE:
                dismountPause(st, now);
                break;
            case MOVE_TO_CHEST_FOR_SADDLE:
                moveToChestForSaddle(ws, worker, zone, data, st, now);
                break;
            case OPEN_CHEST_TAKE_SADDLE:
                openChestTakeSaddle(ws, worker, zone, st, now);
                break;
            case CARRY_SADDLE_TO_HORSE:
                carrySaddleToHorse(ws, worker, data, st, now);
                break;
            case APPLY_SADDLE:
                applySaddle(ws, worker, zone, data, st, now);
                break;
            case FETCH_CARROTS:
                fetchCarrots(ws, worker, zone, data, st, now);
                break;
            case MOVE_TO_PAIR:
                moveToPair(ws, worker, data, st, now);
                break;
            case FEED_PAIR:
                feedPair(ws, worker, zone, data, st, now);
                break;
            case AMBIENT_PET_HORSE:
                ambientPetHorse(worker, st, now);
                break;
            case AMBIENT_IDLE_PAUSE:
                ambientIdlePause(worker, st, now);
                break;
            case GO_TO_BED:
            case SLEEP:
                // Only ever driven from tickNight; reaching here means it's daytime now.
                reset(st);
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Session rhythm: alternate WORKING and ON_BREAK
    // -------------------------------------------------------------------------

    private void advanceSession(WorldServer world, EntityTravellerWorker worker, PalfrenierState st, long now) {
        if (st.sessionUntilTick == 0L) {
            st.session = PalfrenierState.Session.WORKING;
            st.sessionUntilTick = now + rollSession(worker, TravellersConfig.STABLES.workSessionTicks);
            logHand(worker, "on station");
            return;
        }
        if (now < st.sessionUntilTick) return;

        if (st.session == PalfrenierState.Session.WORKING) {
            abandonChore(world, worker, st); // drop whatever was mid-flight, cleanly
            st.session = PalfrenierState.Session.ON_BREAK;
            st.sessionUntilTick = now + rollSession(worker, TravellersConfig.STABLES.breakDurationTicks);
            st.breakNextMoveTick = now;
            logHand(worker, "taking a break (" + ((st.sessionUntilTick - now) / 20L) + "s)");
        } else {
            st.session = PalfrenierState.Session.WORKING;
            st.sessionUntilTick = now + rollSession(worker, TravellersConfig.STABLES.workSessionTicks);
            reset(st);
            worker.getNavigator().clearPath();
            logHand(worker, "back to work");
        }
    }

    /** {@code base} randomised by +/-25% so palfreniers desync. */
    private long rollSession(EntityTravellerWorker worker, int base) {
        int span = Math.max(1, base / 2);
        return base + worker.getRNG().nextInt(span) - span / 2;
    }

    /** ON_BREAK: amble to a fresh spot in the pen now and then, and look about naturally - at a
     * nearby living thing if there is one, otherwise the horizon. No chores, no chest, no work scan. */
    private void tickBreak(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                           PalfrenierState st, long now) {
        clearHeldItem(worker);
        if (now >= st.breakNextMoveTick) {
            int iv = TravellersConfig.STABLES.breakWanderIntervalTicks;
            st.breakNextMoveTick = now + iv + worker.getRNG().nextInt(Math.max(1, iv));
            BlockPos spot = randomStandableInZone(world, zone, worker);
            if (spot != null) {
                worker.getNavigator().tryMoveToXYZ(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.7D);
            }
            double angle = (Math.PI * 2D) * worker.getRNG().nextDouble();
            st.idleLookX = Math.cos(angle);
            st.idleLookZ = Math.sin(angle);
            EntityLivingBase look = worker.getRNG().nextBoolean() ? nearestLookable(world, worker) : null;
            st.breakLookUUID = look != null ? look.getUniqueID() : null;
        }
        Entity look = st.breakLookUUID != null ? world.getEntityFromUuid(st.breakLookUUID) : null;
        if (look instanceof EntityLivingBase && look.isEntityAlive()
                && worker.getDistanceSq(look) <= BREAK_LOOK_RANGE * BREAK_LOOK_RANGE) {
            worker.getLookHelper().setLookPositionWithEntity(look, 30.0F, 30.0F);
        } else {
            worker.getLookHelper().setLookPosition(worker.posX + st.idleLookX,
                    worker.posY + worker.getEyeHeight(), worker.posZ + st.idleLookZ,
                    (float) worker.getHorizontalFaceSpeed(), (float) worker.getVerticalFaceSpeed());
        }
    }

    private BlockPos randomStandableInZone(WorldServer world, PlayerZoneRecord zone, EntityTravellerWorker worker) {
        for (int tries = 0; tries < 6; tries++) {
            double rx = zone.center.getX() + 0.5D + (worker.getRNG().nextDouble() * 2.0D - 1.0D) * zone.halfWidth;
            double rz = zone.center.getZ() + 0.5D + (worker.getRNG().nextDouble() * 2.0D - 1.0D) * zone.halfDepth;
            BlockPos spot = ZonePlacement.findHitboxStandableNear(world, rx, zone.center.getY(), rz, worker);
            if (spot != null) return spot;
        }
        return null;
    }

    private EntityLivingBase nearestLookable(WorldServer world, EntityTravellerWorker worker) {
        AxisAlignedBB box = worker.getEntityBoundingBox().grow(BREAK_LOOK_RANGE, 3.0D, BREAK_LOOK_RANGE);
        EntityLivingBase best = null;
        double bestSq = Double.MAX_VALUE;
        for (EntityLivingBase e : world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (e == worker || !e.isEntityAlive()) continue;
            double d = worker.getDistanceSq(e);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Night: walk to the assigned bed and lie down (cosmetic pose, like camps)
    // -------------------------------------------------------------------------

    private void tickNight(EntityTravellerWorker worker, PalfrenierState st, BlockPos bed, long now) {
        if (worker.isSleepingInBed()) {
            st.phase = PalfrenierState.Phase.SLEEP;
            return;
        }
        if (st.phase != PalfrenierState.Phase.GO_TO_BED) {
            st.phase = PalfrenierState.Phase.GO_TO_BED;
            st.bedApproachStartTick = now;
            st.targetHorseUUID = null;
            clearHeldItem(worker);
            logHand(worker, "heading to bed");
        }
        worker.getLookHelper().setLookPosition(bed.getX() + 0.5D, bed.getY() + 0.5D, bed.getZ() + 0.5D, 30.0F, 30.0F);
        double dsq = worker.getDistanceSq(bed.getX() + 0.5D, bed.getY() + 0.5D, bed.getZ() + 0.5D);
        if (dsq <= SLEEP_ARRIVE_SQ) {
            if (worker.trySleepInBed(bed)) {
                st.phase = PalfrenierState.Phase.SLEEP;
                worker.getNavigator().clearPath();
            }
            return;
        }
        // Beds have partial collision and are usually boxed in - pathfinding often stalls just short,
        // or returns a best-effort route that ends outside the bedroom wall. After the timeout, lie
        // down anyway if within teleport range (trySleepInBed snaps to the bed's own spot).
        if (now - st.bedApproachStartTick > SLEEP_APPROACH_TIMEOUT && dsq <= SLEEP_TELEPORT_MAX_SQ) {
            if (worker.trySleepInBed(bed)) {
                st.phase = PalfrenierState.Phase.SLEEP;
                worker.getNavigator().clearPath();
            }
            return;
        }
        if (worker.getNavigator().noPath()) {
            worker.getNavigator().tryMoveToXYZ(bed.getX() + 0.5D, bed.getY(), bed.getZ() + 0.5D, 1.0D);
        }
    }

    // -------------------------------------------------------------------------
    // Decide what to do next
    // -------------------------------------------------------------------------

    private void decide(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                        StableZoneData data, PalfrenierState st, long now) {
        if (now < st.nextDecideTick) return;

        Set<UUID> claimed = claimedHorsesByOthers(world, worker);

        EntityHorse untamed = findNearest(world, zone, worker,
                h -> !h.isTame() && poolEntry(data, h) != null
                        && !claimed.contains(h.getUniqueID())
                        && !isAvoided(st, h.getUniqueID(), now));
        List<BlockPos> chests = zone.getRolePositions(ZoneRoles.CHEST);
        if (untamed != null) {
            boolean fetch = !chests.isEmpty() && countItem(world, zone, Items.WHEAT) > 0;
            startTarget(st, untamed.getUniqueID(),
                    fetch ? PalfrenierState.Phase.FETCH_FEED : PalfrenierState.Phase.MOVE_TO_HORSE, now);
            if (fetch) st.targetChest = nearestChest(worker, chests);
            return;
        }

        // Only walk to the chest when a saddle is actually there right now.
        if (!chests.isEmpty() && countItem(world, zone, Items.SADDLE) > 0) {
            EntityHorse unsaddled = findNearest(world, zone, worker,
                    h -> h.isTame() && !h.isChild() && !h.isHorseSaddled()
                            && poolEntry(data, h) != null
                            && !claimed.contains(h.getUniqueID())
                            && !isAvoided(st, h.getUniqueID(), now));
            if (unsaddled != null) {
                st.targetChest = nearestChest(worker, chests);
                startTarget(st, unsaddled.getUniqueID(), PalfrenierState.Phase.MOVE_TO_CHEST_FOR_SADDLE, now);
                return;
            }
        }

        // Deliberate breeding - throttled like the offscreen timer's own check, not every decide.
        // The caps (player-wide + per-pen area) are checked HERE, before any chest trip or heart
        // animation, so a palfrenier at cap never mimes a breeding StableWork.breed would refuse.
        if (now >= st.nextBreedCheckTick) {
            st.nextBreedCheckTick = now + TravellersConfig.STABLES.breedingCheckIntervalTicks;
            if (StableWork.canBreedNow(world, zone, data, now) && breedingFeedAvailable(world, zone)) {
                StableHorseEntry[] pair = StableWork.firstEligiblePair(data, now);
                if (pair != null) {
                    st.breedPoolIdA = pair[0].poolId;
                    st.breedPoolIdB = pair[1].poolId;
                    st.breedFeedBeats = 0L;
                    st.targetChest = chests.isEmpty() ? null : nearestChest(worker, chests);
                    st.phase = st.targetChest != null
                            ? PalfrenierState.Phase.FETCH_CARROTS : PalfrenierState.Phase.MOVE_TO_PAIR;
                    st.phaseEndTick = 0L;
                    st.chestOpen = false;
                    st.targetSince = now;
                    st.approachStartTick = -1L;
                    logHand(worker, "breeding a pair");
                    return;
                }
            } else if (StableWork.eligiblePairExists(data, now)) {
                // A pair is ready but a cap blocks it - note it once per check interval.
                StableWatchLog.record(world, zone, StableWatchLog.Kind.HELD, "breeding: cap reached", true);
            }
        }

        // Nothing pressing: stay a palfrenier without inventing busywork.
        EntityHorse anyHorse = findNearest(world, zone, worker, h -> true);
        if (anyHorse != null && worker.getRNG().nextBoolean()) {
            st.targetHorseUUID = anyHorse.getUniqueID();
            st.phase = PalfrenierState.Phase.AMBIENT_PET_HORSE;
        } else {
            st.phase = PalfrenierState.Phase.AMBIENT_IDLE_PAUSE;
        }
        st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
    }

    // -------------------------------------------------------------------------
    // Chest trips: walk over, mime taking supplies, carry them to the work
    // -------------------------------------------------------------------------

    private void fetchFeed(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                           StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = resolveTargetHorse(world, st);
        if (horse == null || horse.isTame() || poolEntry(data, horse) == null) {
            abandonChore(world, worker, st);
            return;
        }
        if (countItem(world, zone, Items.WHEAT) <= 0) {
            // Wheat gone before we got there - just tame dry. Shut the chest if we already opened it.
            if (st.chestOpen && st.targetChest != null) closeChestVisual(world, st.targetChest);
            st.chestOpen = false;
            st.phase = PalfrenierState.Phase.MOVE_TO_HORSE;
            st.targetSince = now;
            st.approachStartTick = -1L;
            return;
        }
        if (walkToChestAndGrab(world, worker, zone, st, now, Items.WHEAT)) {
            st.phase = PalfrenierState.Phase.MOVE_TO_HORSE;
            st.targetSince = now;
            st.approachStartTick = -1L;
        }
    }

    private void fetchCarrots(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                              StableZoneData data, PalfrenierState st, long now) {
        if (!StableWork.eligibleForBreeding(data.findByPoolId(st.breedPoolIdA), now)
                || !StableWork.eligibleForBreeding(data.findByPoolId(st.breedPoolIdB), now)) {
            abandonChore(world, worker, st);
            return;
        }
        Item feed = breedingFeedItem(world, zone);
        if (feed == null) {
            abandonChore(world, worker, st);
            st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
            return;
        }
        if (walkToChestAndGrab(world, worker, zone, st, now, feed)) {
            st.phase = PalfrenierState.Phase.MOVE_TO_PAIR;
            st.targetSince = now;
            st.approachStartTick = -1L;
        }
    }

    /** Walk to {@code st.targetChest}, open it, hold {@code prop}, close it. Returns true the tick
     * the prop is in hand and the chest is shut; false while still working (or after a give-up,
     * which resets the state itself). */
    private boolean walkToChestAndGrab(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                                       PalfrenierState st, long now, Item prop) {
        BlockPos chest = st.targetChest;
        if (chest == null) return true;
        double cx = chest.getX() + 0.5D, cy = chest.getY() + 0.5D, cz = chest.getZ() + 0.5D;
        if (!st.chestOpen) {
            if (giveUpUnreachable(worker, st, cx, cy, cz, now)) return false;
            if (!approachWorkSpot(worker, st, cx, cy, cz, now)) return false;
            worker.getLookHelper().setLookPosition(cx, cy, cz, 30.0F, 30.0F);
            openChestVisual(world, chest);
            st.chestOpen = true;
            st.phaseEndTick = now + TravellersConfig.STABLES.chestOpenTicks;
            return false;
        }
        worker.getLookHelper().setLookPosition(cx, cy, cz, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return false;
        worker.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(prop));
        closeChestVisual(world, chest);
        st.chestOpen = false;
        return true;
    }

    // -------------------------------------------------------------------------
    // Taming: approach, feed (real temper step), occasional mount/buck beat
    // -------------------------------------------------------------------------

    private void moveToHorse(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                             StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = resolveTargetHorse(world, st);
        if (horse == null || horse.isTame() || poolEntry(data, horse) == null) {
            abandonChore(world, worker, st);
            return;
        }
        if (giveUpUnreachable(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        if (!approachWorkSpot(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        st.phase = PalfrenierState.Phase.FEED_HORSE;
        st.phaseEndTick = 0L;
    }

    private void feedHorse(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                           StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = resolveTargetHorse(world, st);
        StableHorseEntry entry = horse == null ? null : poolEntry(data, horse);
        if (horse == null || horse.isTame() || entry == null) {
            abandonChore(world, worker, st);
            return;
        }
        if (worker.getDistanceSq(horse) > INTERACT_RANGE_SQ) {
            st.phase = PalfrenierState.Phase.MOVE_TO_HORSE;
            return;
        }
        worker.getLookHelper().setLookPositionWithEntity(horse, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return;

        RealChestSource chest = new RealChestSource(world, zone);
        // Keep the wheat visibly in hand between beats while there's stock (it was fetched from the
        // chest), drop it once the supply runs out.
        if (chest.available(Items.WHEAT) > 0) {
            worker.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.WHEAT));
        } else {
            clearHeldItem(worker);
        }
        worker.doInteractSwing(EnumHand.MAIN_HAND);

        StableWork.TameResult r = StableWork.tameStep(world, zone, entry, chest, 0);
        if (r.fed && horse.getHealth() < horse.getMaxHealth()) horse.heal(2.0F);
        StableHorseSync.pushEntryOntoHorse(entry, horse, zone.ownerUUID);
        PlayerZoneManager.get(world).markDirty();
        if (entry.tamed) {
            clearHeldItem(worker);
            reset(st);
            return;
        }

        st.phaseEndTick = now + TravellersConfig.STABLES.tamingAttemptIntervalTicks;
        // Between feeding beats, occasionally ride the horse and get thrown - the buck-off is the
        // visibly big step toward taming (bonus temper).
        if (worker.getRidingEntity() == null && worker.getRNG().nextInt(3) == 0) {
            st.phase = PalfrenierState.Phase.MOUNT_HORSE;
        }
    }

    private void mountHorse(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                            StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = resolveTargetHorse(world, st);
        if (horse == null || horse.isTame() || poolEntry(data, horse) == null) {
            abandonChore(world, worker, st);
            return;
        }
        if (worker.getDistanceSq(horse) > INTERACT_RANGE_SQ) {
            st.phase = PalfrenierState.Phase.MOVE_TO_HORSE;
            return;
        }
        if (worker.getRidingEntity() != null || horse.isBeingRidden()) {
            // Already mounted, or another palfrenier got on first - skip the ride beat.
            st.phase = PalfrenierState.Phase.FEED_HORSE;
            st.phaseEndTick = now + TravellersConfig.STABLES.tamingAttemptIntervalTicks;
            return;
        }
        worker.getNavigator().clearPath();
        worker.getLookHelper().setLookPositionWithEntity(horse, 30.0F, 30.0F);
        clearHeldItem(worker);
        worker.startRiding(horse, false);
        st.phase = PalfrenierState.Phase.RIDE_HORSE;
        st.phaseEndTick = now + TravellersConfig.STABLES.tamingMountRideTicks;
    }

    private void rideHorse(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                           StableZoneData data, PalfrenierState st, long now) {
        Entity mount = worker.getRidingEntity();
        if (mount == null) {
            st.phase = PalfrenierState.Phase.DISMOUNT_PAUSE;
            st.phaseEndTick = now + 15L;
            return;
        }
        if (now < st.phaseEndTick) return;

        double dx = worker.posX - mount.posX;
        double dz = worker.posZ - mount.posZ;
        double len = Math.max(0.001D, Math.sqrt(dx * dx + dz * dz));
        worker.dismountRidingEntity();
        world.playSound(null, mount.posX, mount.posY, mount.posZ, SoundEvents.ENTITY_HORSE_ANGRY,
                SoundCategory.NEUTRAL, 1.0F, 1.0F);
        // A modest hop, not a catapult - it used to fling the palfrenier onto roofs / into walls.
        worker.motionX += (dx / len) * 0.15D;
        worker.motionZ += (dz / len) * 0.15D;
        worker.motionY += 0.28D;
        worker.velocityChanged = true;

        StableHorseEntry entry = (mount instanceof EntityHorse) ? poolEntry(data, (EntityHorse) mount) : null;
        if (entry != null && !entry.tamed) {
            RealChestSource chest = new RealChestSource(world, zone);
            StableWork.tameStep(world, zone, entry, chest, TravellersConfig.STABLES.tamingBuckBonusTemper);
            StableHorseSync.pushEntryOntoHorse(entry, (EntityHorse) mount, zone.ownerUUID);
            PlayerZoneManager.get(world).markDirty();
            if (entry.tamed) {
                reset(st);
                return;
            }
        }
        st.phase = PalfrenierState.Phase.DISMOUNT_PAUSE;
        st.phaseEndTick = now + 15L;
    }

    private void dismountPause(PalfrenierState st, long now) {
        if (now < st.phaseEndTick) return;
        st.phase = PalfrenierState.Phase.FEED_HORSE;
        st.phaseEndTick = now;
    }

    // -------------------------------------------------------------------------
    // Saddling: chest visit, carry saddle over, put it on (real chest / entity)
    // -------------------------------------------------------------------------

    private void moveToChestForSaddle(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                                      StableZoneData data, PalfrenierState st, long now) {
        BlockPos chest = st.targetChest;
        EntityHorse horse = resolveTargetHorse(world, st);
        if (chest == null || horse == null || poolEntry(data, horse) == null) {
            abandonChore(world, worker, st);
            return;
        }
        if (giveUpUnreachable(worker, st, chest.getX() + 0.5D, chest.getY() + 0.5D, chest.getZ() + 0.5D, now)) return;
        if (!approachWorkSpot(worker, st, chest.getX() + 0.5D, chest.getY() + 0.5D, chest.getZ() + 0.5D, now)) return;
        worker.getLookHelper().setLookPosition(chest.getX() + 0.5D, chest.getY() + 0.5D, chest.getZ() + 0.5D, 30.0F, 30.0F);
        st.phase = PalfrenierState.Phase.OPEN_CHEST_TAKE_SADDLE;
        st.chestOpen = false;
    }

    private void openChestTakeSaddle(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                                     PalfrenierState st, long now) {
        if (!st.chestOpen) {
            if (countItem(world, zone, Items.SADDLE) <= 0 || st.targetChest == null) {
                abandonChore(world, worker, st);
                st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
                return;
            }
            openChestVisual(world, st.targetChest);
            st.chestOpen = true;
            st.phaseEndTick = now + TravellersConfig.STABLES.chestOpenTicks;
        }
        BlockPos chest = st.targetChest;
        worker.getLookHelper().setLookPosition(chest.getX() + 0.5D, chest.getY() + 0.5D, chest.getZ() + 0.5D, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return;

        worker.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.SADDLE)); // carried prop; consumed on apply
        closeChestVisual(world, st.targetChest);
        st.chestOpen = false;
        st.phase = PalfrenierState.Phase.CARRY_SADDLE_TO_HORSE;
        st.targetSince = now;
    }

    private void carrySaddleToHorse(WorldServer world, EntityTravellerWorker worker, StableZoneData data,
                                    PalfrenierState st, long now) {
        EntityHorse horse = resolveTargetHorse(world, st);
        if (horse == null || !horse.isTame() || horse.isHorseSaddled() || poolEntry(data, horse) == null) {
            abandonChore(world, worker, st);
            return;
        }
        if (giveUpUnreachable(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        if (!approachWorkSpot(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        st.phase = PalfrenierState.Phase.APPLY_SADDLE;
        st.phaseEndTick = now + 10L;
    }

    private void applySaddle(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                             StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = resolveTargetHorse(world, st);
        StableHorseEntry entry = horse == null ? null : poolEntry(data, horse);
        if (horse == null || horse.isHorseSaddled() || entry == null) {
            abandonChore(world, worker, st);
            return;
        }
        worker.getLookHelper().setLookPositionWithEntity(horse, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return;
        worker.doInteractSwing(EnumHand.MAIN_HAND);

        if (StableWork.saddle(world, zone, entry, new RealChestSource(world, zone))) {
            horse.replaceItemInInventory(400, new ItemStack(Items.SADDLE));
            PlayerZoneManager.get(world).markDirty();
        }
        // Whether or not a saddle was still there (the player may have emptied the chest mid-carry),
        // this attempt is done - drop the prop and re-decide.
        abandonChore(world, worker, st);
        st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
    }

    // -------------------------------------------------------------------------
    // Breeding: bring golden carrots to an eligible pair, then a foal is born
    // -------------------------------------------------------------------------

    private void moveToPair(WorldServer world, EntityTravellerWorker worker, StableZoneData data,
                            PalfrenierState st, long now) {
        EntityHorse a = resolvePoolHorse(world, data, st.breedPoolIdA);
        EntityHorse b = resolvePoolHorse(world, data, st.breedPoolIdB);
        if (a == null || b == null
                || !StableWork.eligibleForBreeding(data.findByPoolId(st.breedPoolIdA), now)
                || !StableWork.eligibleForBreeding(data.findByPoolId(st.breedPoolIdB), now)) {
            abandonChore(world, worker, st);
            return;
        }
        double mx = (a.posX + b.posX) * 0.5D, my = (a.posY + b.posY) * 0.5D, mz = (a.posZ + b.posZ) * 0.5D;
        if (st.targetSince >= 0L && now - st.targetSince > MOVE_GIVE_UP_TICKS
                && worker.getDistanceSq(mx, my, mz) > INTERACT_RANGE_SQ * 4.0D) {
            abandonChore(world, worker, st);
            st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
            return;
        }
        if (!approachWorkSpot(worker, st, mx, my, mz, now)) return;
        st.phase = PalfrenierState.Phase.FEED_PAIR;
        st.phaseEndTick = 0L;
    }

    private void feedPair(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                          StableZoneData data, PalfrenierState st, long now) {
        EntityHorse a = resolvePoolHorse(world, data, st.breedPoolIdA);
        EntityHorse b = resolvePoolHorse(world, data, st.breedPoolIdB);
        StableHorseEntry ea = data.findByPoolId(st.breedPoolIdA);
        StableHorseEntry eb = data.findByPoolId(st.breedPoolIdB);
        if (a == null || b == null || !StableWork.eligibleForBreeding(ea, now)
                || !StableWork.eligibleForBreeding(eb, now)) {
            abandonChore(world, worker, st);
            return;
        }
        worker.getLookHelper().setLookPositionWithEntity((st.breedFeedBeats % 2 == 0) ? a : b, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return;

        // A cap may have filled between decide() and here (another pen bred, a horse tamed elsewhere).
        // Re-check once per beat, before the heart animation, rather than mime a breeding breed()
        // would just refuse. (breed() keeps its own final check as the last-tick backstop.)
        if (!StableWork.canBreedNow(world, zone, data, now)) {
            abandonChore(world, worker, st);
            st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
            return;
        }

        RealChestSource chest = new RealChestSource(world, zone);
        Item feed = chest.available(Items.GOLDEN_CARROT) >= 2 ? Items.GOLDEN_CARROT
                : chest.available(Items.GOLDEN_APPLE) >= 2 ? Items.GOLDEN_APPLE : null;
        if (feed == null) {
            abandonChore(world, worker, st);
            st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
            return;
        }
        worker.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(feed));
        worker.doInteractSwing(EnumHand.MAIN_HAND);
        spawnHearts(world, a);
        spawnHearts(world, b);
        st.breedFeedBeats++;
        st.phaseEndTick = now + 20L;

        if (st.breedFeedBeats >= BREED_FEED_BEATS) {
            clearHeldItem(worker);
            StableHorseEntry foal = StableWork.breed(world, zone, data, chest, now, ea, eb);
            if (foal != null) {
                // Let StableMaterializer place it (ring search from the pen centre, well inside the
                // loaded area) - teleporting it next to a parent risked dropping it near the zone
                // edge where vanilla's 32-block tick gate freezes it: no gravity, no AI, floating.
                StableMaterializer.materializeEntry(world, zone, foal);
                spawnHearts(world, a);
                spawnHearts(world, b);
            }
            PlayerZoneManager.get(world).markDirty();
            reset(st);
        }
    }

    // -------------------------------------------------------------------------
    // Ambient flavour when there's nothing to do
    // -------------------------------------------------------------------------

    private void ambientPetHorse(EntityTravellerWorker worker, PalfrenierState st, long now) {
        EntityHorse horse = resolveTargetHorse(worker.world instanceof WorldServer ? (WorldServer) worker.world : null, st);
        if (horse == null) {
            reset(st);
            return;
        }
        if (st.approachStartTick >= 0L && now - st.approachStartTick > APPROACH_TIMEOUT * 2
                && worker.getDistanceSq(horse) > INTERACT_RANGE_SQ) {
            reset(st);
            return;
        }
        if (!approachWorkSpot(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        worker.getLookHelper().setLookPositionWithEntity(horse, 30.0F, 30.0F);
        if (st.phaseEndTick == 0L) {
            st.phaseEndTick = now + 20L + worker.getRNG().nextInt(20);
            return;
        }
        if (now < st.phaseEndTick) return;
        worker.doInteractSwing(EnumHand.MAIN_HAND);
        reset(st);
    }

    /** A genuine break beat: stand still and look off in a random direction, exactly like vanilla's
     * own {@code EntityAILookIdle} (which never runs for a worker on duty). Re-set every tick. */
    private void ambientIdlePause(EntityTravellerWorker worker, PalfrenierState st, long now) {
        if (st.phaseEndTick == 0L) {
            double angle = (Math.PI * 2D) * worker.getRNG().nextDouble();
            st.idleLookX = Math.cos(angle);
            st.idleLookZ = Math.sin(angle);
            st.phaseEndTick = now + 40L + worker.getRNG().nextInt(60);
        }
        worker.getLookHelper().setLookPosition(worker.posX + st.idleLookX, worker.posY + worker.getEyeHeight(),
                worker.posZ + st.idleLookZ, (float) worker.getHorizontalFaceSpeed(), (float) worker.getVerticalFaceSpeed());
        if (now < st.phaseEndTick) return;
        reset(st);
    }

    // -------------------------------------------------------------------------
    // Own-mount surrender
    // -------------------------------------------------------------------------

    private void surrenderOwnMount(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                                   StableZoneData data) {
        StableMountRelease.surrenderLoadedMount(world, worker, zone, data);
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static StableHorseEntry poolEntry(StableZoneData data, EntityHorse horse) {
        return data.findByLiveEntity(horse.getUniqueID());
    }

    private EntityHorse resolveTargetHorse(WorldServer world, PalfrenierState st) {
        if (world == null || st.targetHorseUUID == null) return null;
        Entity e = world.getEntityFromUuid(st.targetHorseUUID);
        return (e instanceof EntityHorse && !e.isDead) ? (EntityHorse) e : null;
    }

    private EntityHorse resolvePoolHorse(WorldServer world, StableZoneData data, UUID poolId) {
        if (poolId == null) return null;
        StableHorseEntry e = data.findByPoolId(poolId);
        if (e == null || e.liveEntityUUID == null) return null;
        Entity ent = world.getEntityFromUuid(e.liveEntityUUID);
        return (ent instanceof EntityHorse && !ent.isDead) ? (EntityHorse) ent : null;
    }

    private void startTarget(PalfrenierState st, UUID horseId, PalfrenierState.Phase phase, long now) {
        st.targetHorseUUID = horseId;
        st.phase = phase;
        st.phaseEndTick = 0L;
        st.targetSince = now;
        st.approachStartTick = -1L;
    }

    private boolean isAvoided(PalfrenierState st, UUID horseId, long now) {
        return st.avoidHorseUUID != null && st.avoidHorseUUID.equals(horseId) && now < st.avoidUntil;
    }

    /** True (and abandons + briefly avoids the target) if the palfrenier still isn't within reach
     * of {@code (tx,ty,tz)} after {@link #MOVE_GIVE_UP_TICKS}. */
    private boolean giveUpUnreachable(EntityTravellerWorker worker, PalfrenierState st,
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

    /** The set of pool horses another palfrenier is currently working, computed once per {@link
     * #decide}. Iterating {@link #STATES} here also opportunistically drops entries whose worker is
     * dead or off duty - the only cleanup this map ever gets. */
    private Set<UUID> claimedHorsesByOthers(WorldServer world, EntityTravellerWorker self) {
        Set<UUID> out = new HashSet<>();
        Iterator<Map.Entry<UUID, PalfrenierState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PalfrenierState> en = it.next();
            if (en.getKey().equals(self.getUniqueID())) continue;
            Entity oe = world.getEntityFromUuid(en.getKey());
            if (!(oe instanceof EntityTravellerWorker) || oe.isDead
                    || !((EntityTravellerWorker) oe).isOnDuty()) {
                it.remove();
                continue;
            }
            UUID t = en.getValue().targetHorseUUID;
            if (t != null) out.add(t);
        }
        return out;
    }

    private void reset(PalfrenierState st) {
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
    private void abandonChore(WorldServer world, EntityTravellerWorker worker, PalfrenierState st) {
        if (st.chestOpen && st.targetChest != null) closeChestVisual(world, st.targetChest);
        clearHeldItem(worker);
        dismountIfRidingLoose(worker);
        reset(st);
    }

    private void dismountIfRidingLoose(EntityTravellerWorker worker) {
        Entity mount = worker.getRidingEntity();
        if (mount instanceof EntityHorse && !TravellerMountSupport.isBondedMount(mount)) {
            worker.dismountRidingEntity();
        }
    }

    /**
     * Walk toward {@code (tx,ty,tz)}; returns true once within {@link #INTERACT_RANGE_SQ}. If the
     * navigator can't close the gap within {@link #APPROACH_TIMEOUT} and we're within
     * {@link #APPROACH_HOP_MAX_SQ}, snap to a standable cell right next to the target - vanilla
     * routinely returns a best-effort path that ends at the exterior wall of an enclosed target.
     */
    private boolean approachWorkSpot(EntityTravellerWorker worker, PalfrenierState st,
                                     double tx, double ty, double tz, long now) {
        double dsq = worker.getDistanceSq(tx, ty, tz);
        if (dsq <= INTERACT_RANGE_SQ) {
            worker.getNavigator().clearPath();
            st.approachStartTick = -1L;
            return true;
        }
        if (st.approachStartTick < 0L) st.approachStartTick = now;
        worker.getNavigator().tryMoveToXYZ(tx, ty, tz, 1.0D);
        if (now - st.approachStartTick > APPROACH_TIMEOUT && dsq <= APPROACH_HOP_MAX_SQ
                && worker.world instanceof WorldServer) {
            BlockPos spot = ZonePlacement.findHitboxStandableNear((WorldServer) worker.world, tx, ty, tz, worker);
            if (spot != null) {
                worker.getNavigator().clearPath();
                worker.setPositionAndUpdate(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
                logHand(worker, "snap-hopped to a walled-in work spot");
            }
            st.approachStartTick = -1L; // re-approach fresh next tick if the hop didn't land in range
        }
        return false;
    }

    private void clearHeldItem(EntityTravellerWorker worker) {
        ItemStack cur = worker.getHeldItemMainhand();
        if (!cur.isEmpty() && (cur.getItem() == Items.WHEAT || cur.getItem() == Items.SADDLE
                || cur.getItem() == Items.LEAD || cur.getItem() == Items.GOLDEN_CARROT
                || cur.getItem() == Items.GOLDEN_APPLE)) {
            worker.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /** Writes a palfrenier-lifecycle line to {@link StableWatchLog} using the per-tick scratch. */
    private void logHand(EntityTravellerWorker worker, String event) {
        if (tickWorld == null || tickZone == null) return;
        String name = worker.getName();
        StableWatchLog.hand(tickWorld, tickZone, (name == null || name.isEmpty()) ? "Palfrenier" : name, event);
    }

    private void spawnHearts(WorldServer world, EntityHorse horse) {
        world.spawnParticle(EnumParticleTypes.HEART, horse.posX,
                horse.posY + horse.height * 0.7D, horse.posZ, 3, 0.4D, 0.4D, 0.4D, 0.0D);
    }

    private void openChestVisual(WorldServer world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te == null) return;
        world.addBlockEvent(pos, te.getBlockType(), 1, 1);
    }

    private void closeChestVisual(WorldServer world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te == null) return;
        world.addBlockEvent(pos, te.getBlockType(), 1, 0);
    }

    private BlockPos nearestChest(EntityTravellerWorker worker, List<BlockPos> chests) {
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

    private EntityHorse findNearest(WorldServer world, PlayerZoneRecord zone, EntityTravellerWorker worker,
                                    Predicate<EntityHorse> filter) {
        List<EntityHorse> found = world.getEntitiesWithinAABB(EntityHorse.class, zoneBox(zone), filter);
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

    private AxisAlignedBB zoneBox(PlayerZoneRecord zone) {
        BlockPos c = zone.center;
        return new AxisAlignedBB(
                c.getX() - zone.halfWidth, c.getY() - zone.halfHeight, c.getZ() - zone.halfDepth,
                c.getX() + zone.halfWidth + 1, c.getY() + zone.halfHeight + 1, c.getZ() + zone.halfDepth + 1);
    }

    private boolean breedingFeedAvailable(WorldServer world, PlayerZoneRecord zone) {
        return breedingFeedItem(world, zone) != null;
    }

    /** The breeding feed the chest currently has at least two of (carrot preferred), or null. */
    private Item breedingFeedItem(WorldServer world, PlayerZoneRecord zone) {
        RealChestSource chest = new RealChestSource(world, zone);
        if (chest.available(Items.GOLDEN_CARROT) >= 2) return Items.GOLDEN_CARROT;
        if (chest.available(Items.GOLDEN_APPLE) >= 2) return Items.GOLDEN_APPLE;
        return null;
    }

    private int countItem(WorldServer world, PlayerZoneRecord zone, Item item) {
        return new RealChestSource(world, zone).available(item);
    }
}
