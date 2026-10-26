package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneRoles;
import com.jubitus.travellers.zone.ZoneType;
import com.jubitus.travellers.zone.duty.WorkerDutyBehavior;
import static com.jubitus.travellers.zone.stable.StableHandSupport.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
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
 * The loaded palfrenier: walks its Stable and <b>does the work</b> - feeds a hurt horse until it is
 * healed, approaches and feeds an untamed
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
 * scratch in {@link StableHandSupport}, valid only inside one
 * {@code tick()} call on the server thread. The phases live in {@link StableChores}, {@link StableTaming}
 * and {@link StableAmbience}.
 */
public final class StableHandTask implements WorkerDutyBehavior {

    private static final Map<UUID, PalfrenierState> STATES = new ConcurrentHashMap<>();

    private final StableHandSupport support = new StableHandSupport();
    private final StableChores chores = new StableChores(support);
    private final StableTaming taming = new StableTaming(support);
    private final StableAmbience ambience = new StableAmbience(support);

    @Override
    public void tick(EntityTravellerWorker worker) {
        if (!TravellersConfig.STABLES.enabled) return;
        World world = worker.world;
        if (!(world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) world;
        long now = world.getTotalWorldTime();

        com.jubitus.travellers.zone.ZoneRef ref = worker.getZoneRef();
        UUID ownerId = worker.getFollowOwnerId();
        if (ref == null || !ref.isDuty() || ownerId == null) return;

        PlayerZoneRecord zone = ref.resolve(ownerId);
        if (zone == null || zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) return;
        StableZoneData data = (StableZoneData) zone.data;

        PalfrenierState st = STATES.computeIfAbsent(worker.getUniqueID(), id -> new PalfrenierState());

        support.begin(ws, zone);
        try {
            tickBody(ws, worker, zone, data, st, now);
        } finally {
            support.end();
        }
    }

    @Override
    public void stop(EntityTravellerWorker worker) {
        PalfrenierState st = STATES.get(worker.getUniqueID());
        if (st != null && worker.world instanceof WorldServer) support.abandonChore((WorldServer) worker.world, worker, st);
        else support.dismountIfRidingLoose(worker);
    }

    private void tickBody(WorldServer ws, EntityTravellerWorker worker, PlayerZoneRecord zone,
                          StableZoneData data, PalfrenierState st, long now) {
        if (worker.isInCombat()) {
            // Combat AI owns the worker now - get out of bed and off any non-bonded horse so it can
            // actually fight, then stand aside for the movement mutex.
            if (worker.sleep.isSleeping()) worker.sleep.wakeUp();
            support.dismountIfRidingLoose(worker);
            if (st.phase != PalfrenierState.Phase.DECIDE) {
                support.logHand(worker, "chore interrupted by combat");
                support.abandonChore(ws, worker, st);
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

        if (myBed == null) {
            // No bed: social idle only, never labour (the coordinator doesn't count it as a hand).
            if (worker.sleep.isSleeping()) worker.sleep.wakeUp();
            if (st.phase != PalfrenierState.Phase.DECIDE) support.abandonChore(ws, worker, st);
            ambience.tickBreak(ws, worker, zone, st, now);
            return;
        }

        if (night) {
            support.dismountIfRidingLoose(worker);
            ambience.tickNight(worker, st, myBed, now);
            return;
        }
        if (worker.sleep.isSleeping()) {
            // Woke into daytime, or the bed was lost overnight - get up before anything else.
            worker.sleep.wakeUp();
            support.reset(st);
        }

        // ---- Session rhythm: alternate timed WORKING and ON_BREAK ----
        advanceSession(ws, worker, st, now);
        if (st.session == PalfrenierState.Session.ON_BREAK) {
            ambience.tickBreak(ws, worker, zone, st, now);
            return;
        }

        // ---- WORKING ----
        switch (st.phase) {
            case DECIDE:
                decide(ws, worker, zone, data, st, now);
                break;
            case FETCH_HEAL_FEED:
                chores.fetchHealFeed(ws, worker, zone, data, st, now);
                break;
            case HEAL_HORSE:
                chores.healHorse(ws, worker, zone, data, st, now);
                break;
            case FETCH_FEED:
                chores.fetchFeed(ws, worker, zone, data, st, now);
                break;
            case MOVE_TO_HORSE:
                taming.moveToHorse(ws, worker, zone, data, st, now);
                break;
            case FEED_HORSE:
                taming.feedHorse(ws, worker, zone, data, st, now);
                break;
            case MOUNT_HORSE:
                taming.mountHorse(ws, worker, zone, data, st, now);
                break;
            case RIDE_HORSE:
                taming.rideHorse(ws, worker, zone, data, st, now);
                break;
            case DISMOUNT_PAUSE:
                taming.dismountPause(st, now);
                break;
            case MOVE_TO_CHEST_FOR_SADDLE:
                chores.moveToChestForSaddle(ws, worker, zone, data, st, now);
                break;
            case OPEN_CHEST_TAKE_SADDLE:
                chores.openChestTakeSaddle(ws, worker, zone, st, now);
                break;
            case CARRY_SADDLE_TO_HORSE:
                chores.carrySaddleToHorse(ws, worker, data, st, now);
                break;
            case APPLY_SADDLE:
                chores.applySaddle(ws, worker, zone, data, st, now);
                break;
            case FETCH_CARROTS:
                chores.fetchCarrots(ws, worker, zone, data, st, now);
                break;
            case MOVE_TO_PAIR:
                chores.moveToPair(ws, worker, data, st, now);
                break;
            case FEED_PAIR:
                chores.feedPair(ws, worker, zone, data, st, now);
                break;
            case AMBIENT_PET_HORSE:
                ambience.ambientPetHorse(worker, st, now);
                break;
            case AMBIENT_IDLE_PAUSE:
                ambience.ambientIdlePause(worker, st, now);
                break;
            case GO_TO_BED:
            case SLEEP:
                // Only ever driven from tickNight; reaching here means it's daytime now.
                support.reset(st);
                break;
        }
    }

    private void advanceSession(WorldServer world, EntityTravellerWorker worker, PalfrenierState st, long now) {
        if (st.sessionUntilTick == 0L) {
            st.session = PalfrenierState.Session.WORKING;
            st.sessionUntilTick = now + rollSession(worker, TravellersConfig.STABLES.workSessionTicks);
            support.logHand(worker, "on station");
            return;
        }
        if (now < st.sessionUntilTick) return;

        if (st.session == PalfrenierState.Session.WORKING) {
            support.abandonChore(world, worker, st); // drop whatever was mid-flight, cleanly
            st.session = PalfrenierState.Session.ON_BREAK;
            st.sessionUntilTick = now + rollSession(worker, TravellersConfig.STABLES.breakDurationTicks);
            st.breakNextMoveTick = now;
            support.logHand(worker, "taking a break (" + ((st.sessionUntilTick - now) / 20L) + "s)");
        } else {
            st.session = PalfrenierState.Session.WORKING;
            st.sessionUntilTick = now + rollSession(worker, TravellersConfig.STABLES.workSessionTicks);
            support.reset(st);
            worker.getNavigator().clearPath();
            support.logHand(worker, "back to work");
        }
    }

    /** {@code base} randomised by +/-25% so palfreniers desync. */
    private long rollSession(EntityTravellerWorker worker, int base) {
        int span = Math.max(1, base / 2);
        return base + worker.getRNG().nextInt(span) - span / 2;
    }

    private void decide(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                        StableZoneData data, PalfrenierState st, long now) {
        if (now < st.nextDecideTick) return;

        Set<UUID> claimed = claimedHorsesByOthers(world, worker);
        List<BlockPos> chests = zone.getRolePositions(ZoneRoles.CHEST);

        // Healing comes first.
        RealChestSource chestStock = new RealChestSource(world, zone);
        if (!chests.isEmpty() && StableWork.healFeed(chestStock, Float.MAX_VALUE) != null) {
            EntityHorse hurt = support.findNearest(world, zone, worker,
                    h -> h.isTame() && StableWork.isHurt(h.getHealth(), h.getMaxHealth())
                            && poolEntry(data, h) != null
                            && !claimed.contains(h.getUniqueID())
                            && !support.isAvoided(st, h.getUniqueID(), now));
            if (hurt != null) {
                st.targetChest = support.nearestChest(worker, chests);
                support.startTarget(st, hurt.getUniqueID(), PalfrenierState.Phase.FETCH_HEAL_FEED, now);
                support.logHand(worker, "healing a horse");
                return;
            }
        }

        EntityHorse untamed = support.findNearest(world, zone, worker,
                h -> !h.isTame() && poolEntry(data, h) != null
                        && !claimed.contains(h.getUniqueID())
                        && !support.isAvoided(st, h.getUniqueID(), now));
        if (untamed != null) {
            boolean fetch = !chests.isEmpty() && StableWork.tamingFeed(chestStock) != null;
            support.startTarget(st, untamed.getUniqueID(),
                    fetch ? PalfrenierState.Phase.FETCH_FEED : PalfrenierState.Phase.MOVE_TO_HORSE, now);
            if (fetch) st.targetChest = support.nearestChest(worker, chests);
            return;
        }

        // Only walk to the chest when a saddle is actually there right now.
        if (!chests.isEmpty() && support.countItem(world, zone, Items.SADDLE) > 0) {
            EntityHorse unsaddled = support.findNearest(world, zone, worker,
                    h -> h.isTame() && !h.isChild() && !h.isHorseSaddled()
                            && poolEntry(data, h) != null
                            && !claimed.contains(h.getUniqueID())
                            && !support.isAvoided(st, h.getUniqueID(), now));
            if (unsaddled != null) {
                st.targetChest = support.nearestChest(worker, chests);
                support.startTarget(st, unsaddled.getUniqueID(), PalfrenierState.Phase.MOVE_TO_CHEST_FOR_SADDLE, now);
                return;
            }
        }

        // Deliberate breeding - throttled like the offscreen timer's own check, not every decide.
        // The caps (player-wide + per-pen area) are checked HERE, before any chest trip or heart
        // animation, so a palfrenier at cap never mimes a breeding StableWork.breed would refuse.
        if (now >= st.nextBreedCheckTick) {
            st.nextBreedCheckTick = now + TravellersConfig.STABLES.breedingCheckIntervalTicks;
            if (StableWork.canBreedNow(world, zone, data, now) && support.breedingFeedAvailable(world, zone)) {
                StableHorseEntry[] pair = StableWork.firstEligiblePair(data, now);
                if (pair != null) {
                    st.breedPoolIdA = pair[0].poolId;
                    st.breedPoolIdB = pair[1].poolId;
                    st.breedFeedBeats = 0L;
                    st.targetChest = chests.isEmpty() ? null : support.nearestChest(worker, chests);
                    st.phase = st.targetChest != null
                            ? PalfrenierState.Phase.FETCH_CARROTS : PalfrenierState.Phase.MOVE_TO_PAIR;
                    st.phaseEndTick = 0L;
                    st.chestOpen = false;
                    st.targetSince = now;
                    st.approachStartTick = -1L;
                    support.logHand(worker, "breeding a pair");
                    return;
                }
            } else if (StableWork.eligiblePairExists(data, now)) {
                // A pair is ready but a cap blocks it - note it once per check interval.
                StableWatchLog.record(world, zone, StableWatchLog.Kind.HELD, "breeding: cap reached", true);
            }
        }

        // Nothing pressing: stay a palfrenier without inventing busywork.
        EntityHorse anyHorse = support.findNearest(world, zone, worker, h -> true);
        if (anyHorse != null && worker.getRNG().nextBoolean()) {
            st.targetHorseUUID = anyHorse.getUniqueID();
            st.phase = PalfrenierState.Phase.AMBIENT_PET_HORSE;
        } else {
            st.phase = PalfrenierState.Phase.AMBIENT_IDLE_PAUSE;
        }
        st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
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

    private void surrenderOwnMount(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                                   StableZoneData data) {
        StableMountRelease.surrenderLoadedMount(world, worker, zone, data);
    }
}
