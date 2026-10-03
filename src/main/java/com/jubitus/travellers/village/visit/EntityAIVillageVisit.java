package com.jubitus.travellers.village.visit;

import com.jubitus.travellers.unit.Relocate;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.gaze.TravellerGaze;
import com.jubitus.travellers.follow.FollowRecoveryHelper;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.mixins.MixinResManagerPointsAccessor;
import com.jubitus.travellers.village.MillFootprints;
import com.jubitus.travellers.village.MillGoods;
import com.jubitus.travellers.village.VillageVisitIndex;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.millenaire.common.entity.MillVillager;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.buildingmanagers.ResManager;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A traveller's, raider's or wandering worker's stay in a village of its trip: strolls the streets,
 * visits buildings (the exact spot a Millénaire villager would go to, entered and left through the
 * building's entrance), reads the information panels and glances at / talks with the people around.
 * When the stay is over it steps back outside if needed, a worker leaves its gift to the village, and
 * the stop is marked visited so the trip moves on ({@code TravelCommon.visitedStop}).
 *
 * <p>Never picks a spot on a roof: stroll spots are outside every building footprint, and indoor
 * spots are Millénaire's own points, reached only when a full path from the entrance truly arrives.
 * Outdoor walks accept a partial path and re-path as they go. Stuck inside a building, it walks back
 * out through the entrance; a hop to the nearest outdoor spot is the last resort.
 *
 * <p>The head is the traveller's {@link TravellerGaze}, given foci here; conversations are
 * {@link VillageConversation}s; {@link VisitOccupancy} keeps visitors spread over the village.
 */
public class EntityAIVillageVisit extends EntityAIBase {

    private static final int RESUME_WINDOW_TICKS = 20 * 60 * 2;
    private static final int PROGRESS_CHECK_TICKS = 20;
    private static final int OUTDOOR_SEARCH_RADIUS = 12;
    private static final double TARGET_CROWD_RADIUS = 4.0D;
    private static final double SPOT_SPACING = 1.3D;
    private static final double NEARBY_SCAN_RADIUS = 8.0D;
    private static final int PRE_TURN_TICKS = 12;
    private static final int PLAYER_CHECK_TICKS = 5;
    private static final int TALK_WALK_TICKS = 100;

    private enum Activity {
        NONE, STROLL, BUILDING, PANEL, TALK,
        // Leaving: out of any building, back to the horse, then (a group leader) wait for the group.
        EXIT, FETCH_HORSE, WAIT_GROUP;

        boolean departing() {
            return this == EXIT || this == FETCH_HORSE || this == WAIT_GROUP;
        }
    }

    private static final double HORSE_REACH_BLOCKS = 4.0D;
    private static final double GROUP_SCAN_BLOCKS = 96.0D;

    private static final class Leg {
        final BlockPos pos;
        final int lingerTicks;
        /** Inside a building footprint: only walked to when a full path arrives; else linger outside. */
        final boolean indoor;
        /** Walking back out: tried whatever the path search says, and a stuck here means a hop. */
        final boolean exit;
        final boolean panel;
        /** What to face while lingering (a panel's leg faces the panel itself). */
        @Nullable
        final BlockPos look;

        Leg(BlockPos pos, int lingerTicks, boolean indoor, boolean exit, boolean panel) {
            this(pos, lingerTicks, indoor, exit, panel, null);
        }

        Leg(BlockPos pos, int lingerTicks, boolean indoor, boolean exit, boolean panel, @Nullable BlockPos look) {
            this.pos = pos;
            this.lingerTicks = lingerTicks;
            this.indoor = indoor;
            this.exit = exit;
            this.panel = panel;
            this.look = look;
        }
    }

    private final EntityTraveller mob;

    private boolean running;
    private boolean finished;

    private long lastActiveTick = Long.MIN_VALUE / 2;
    @Nullable
    private VillageVisitIndex.Settlement settlement;
    @Nullable
    private VillageVisitIndex.Target lastTarget;
    private int waitTicks;
    private boolean loneHaltDone;
    private int failuresInARow;

    private Activity activity = Activity.NONE;
    private final ArrayDeque<Leg> legs = new ArrayDeque<>();
    @Nullable
    private Leg leg;
    @Nullable
    private BlockPos legTarget;
    @Nullable
    private BlockPos lookAt;
    private boolean moving;
    private int lingerLeft;
    private double bestDist;
    private int noProgressTicks;
    private int progressTicker;
    @Nullable
    private BlockPos exitEntrance;
    private int preTurnLeft;
    /** The previous leg was only walked through (no linger): don't stop to turn before the next one. */
    private boolean justWalked;

    private List<EntityLivingBase> nearby = Collections.emptyList();
    private int socialTicker;

    @Nullable
    private VillageConversation conversation;
    private int seenSpeakerChanges;
    private int talkWalkTicks;

    @Nullable
    private EntityPlayer facingPlayer;
    private int playerCloseTicks;

    private int avoidTicks;
    private int avoidCooldown;
    @Nullable
    private Vec3d sidestep;

    public EntityAIVillageVisit(EntityTraveller mob) {
        this.mob = mob;
        this.setMutexBits(3);
    }

    public boolean isRunning() {
        return running && !finished;
    }

    /** Still enjoying the village (not leaving yet) - what keeps a group's followers dispersed. A
     * short interruption (a fight, a meal) doesn't end it: the stay resumes afterwards. */
    public boolean isStaying() {
        VillageVisitState st = mob.getVillageVisitState();
        if (finished || st == null || st.isLeaving() || activity.departing()) return false;
        if (!st.asFollower && !st.stop.equals(mob.getTargetVillage())) return false;
        return running || mob.world.getTotalWorldTime() - lastActiveTick <= RESUME_WINDOW_TICKS;
    }

    @Nullable
    public BlockPos getVisitStop() {
        VillageVisitState st = mob.getVillageVisitState();
        return st == null ? null : st.stop;
    }

    private int remaining() {
        VillageVisitState st = mob.getVillageVisitState();
        return st == null ? 0 : st.remainingTicks;
    }

    private boolean asFollower() {
        VillageVisitState st = mob.getVillageVisitState();
        return st != null && st.asFollower;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote || !mob.usesVillageVisit() || !canVisitNow()) return false;
        if (mob.getGroupLeaderUUID() != null) return mob.canDisperseWithLeader();
        if (mob.getDepartCooldownTicks() > 0) return false;
        BlockPos stop = mob.getTargetVillage();
        if (stop == null || stop.equals(mob.getTravelCommon().getVisitedStop())) return false;
        // An interrupted stay resumes wherever the unit stands in the village (it may be out at its horse).
        if (isResumable(stop, false)) return true;
        return mob.isAtTargetStop();
    }

    /** A stay already under way for this stop (interrupted by a fight, a meal, going offscreen or a
     * restart) picks up where it was instead of starting over. */
    private boolean isResumable(BlockPos stop, boolean follower) {
        VillageVisitState st = mob.getVillageVisitState();
        return st != null && stop.equals(st.stop) && follower == st.asFollower;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (finished || !mob.usesVillageVisit() || !canVisitNow()) return false;
        VillageVisitState st = mob.getVillageVisitState();
        if (st == null) return false;
        if (st.asFollower) return mob.getGroupLeaderUUID() != null;
        return Objects.equals(mob.getTargetVillage(), st.stop);
    }

    private boolean canVisitNow() {
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive() || mob.getAttackTarget() != null) return false;
        if (mob.isCatchupTravel()) return false;
        return !(mob instanceof EntityTravellerWorker) || !((EntityTravellerWorker) mob).isHiredFollower();
    }

    @Override
    public void startExecuting() {
        boolean follower = mob.getGroupLeaderUUID() != null;
        BlockPos stop = follower ? mob.getLeaderVisitStop() : mob.getTargetVillage();
        // An interrupted stay picks up where it was - including one already on its way out
        // (remainingTicks 0), which goes straight back to leaving.
        boolean resume = stop != null && isResumable(stop, follower);
        settlement = stop == null ? null : VillageVisitIndex.get(mob.world, stop);
        if (!resume) {
            // A follower's stay has no timer of its own: it lasts as long as its leader's.
            mob.setVillageVisitState(stop == null ? null
                    : new VillageVisitState(stop, follower ? Integer.MAX_VALUE / 2 : rollStayTicks(), follower));
            lastTarget = null;
            loneHaltDone = false;
            failuresInARow = 0;
            trace("start", (follower ? "with group " : "stay=" + remaining() / 20 + "s ") + describeSettlement());
        } else {
            trace("resume", follower ? "with group" : "left=" + remaining() / 20 + "s");
        }
        running = true;
        finished = false;
        socialTicker = Math.floorMod(mob.getEntityId(), socialPeriod());
        mob.setVillageIdling(true);
        if (!follower) mob.setReadyToDepart(false);
        clearActivity();
        mob.getGaze().reset();
        mob.getGaze().setPlaces(placesOfInterest(settlement));

        // Back from offscreen: first stand where it reappeared, facing the building it was visiting.
        VillageVisitState st = mob.getVillageVisitState();
        if (st != null && st.facing != null && !st.isLeaving()) {
            activity = Activity.STROLL;
            legs.add(new Leg(mob.getPosition(), 60 + mob.getRNG().nextInt(80), false, false, false, st.facing));
        }
        if (st != null) {
            st.spot = null;
            st.facing = null;
        }
    }

    @Override
    public void resetTask() {
        running = false;
        lastActiveTick = mob.world.getTotalWorldTime();
        clearActivity();
        mob.getNavigator().clearPath();
        mob.getGaze().reset();
        facingPlayer = null;
        playerCloseTicks = 0;
        nearby = Collections.emptyList();
        VisitOccupancy.release(mob.world, mob.getUniqueID());
        // Interrupted (a fight, a meal): the stay resumes on the next start, the trip stays put, and
        // a horse tied at the village edge stays tied.
        if (!finished) mob.setVillageIdling(false);
    }

    @Override
    public void updateTask() {
        lastActiveTick = mob.world.getTotalWorldTime();
        if (settlement == null) {
            finishVisit();
            return;
        }
        VillageVisitState st = mob.getVillageVisitState();
        if (st == null) {
            finishVisit();
            return;
        }
        if (st.asFollower) {
            if (st.remainingTicks > 0 && !mob.canDisperseWithLeader()) st.remainingTicks = 0;
        } else if (st.remainingTicks > 0) {
            st.remainingTicks--;
        }

        tickPlayer();
        tickSocial();
        updateActivity();
    }

    private void updateActivity() {
        if (activity == Activity.TALK) {
            tickTalk();
            return;
        }
        if (activity == Activity.WAIT_GROUP) {
            tickWaitGroup();
            return;
        }
        if (remaining() <= 0 && !activity.departing()) {
            beginExit();
            return;
        }
        if (activity == Activity.NONE) {
            startNextActivity();
            if (activity == Activity.NONE) return;
        }
        tickLegs();
    }

    // ---------------------------------------------------------------- activities

    private void startNextActivity() {
        VillageVisitIndex.Settlement s = settlement;
        if (s == null) return;
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;

        if (s.lone) {
            planLoneHalt(s);
            return;
        }

        boolean streetsOnly = mob instanceof EntityTravellerRaider && !cfg.raidersVisitBuildings;
        int wStroll = cfg.weightStroll;
        int wBuilding = streetsOnly || s.buildings.isEmpty() ? 0 : cfg.weightBuilding;
        int wPanel = streetsOnly || !cfg.visitPanels || s.panelGroups.isEmpty() ? 0 : cfg.weightPanel;
        int total = wStroll + wBuilding + wPanel;
        if (total <= 0) {
            wStroll = 1;
            total = 1;
        }
        int roll = mob.getRNG().nextInt(total);
        boolean planned;
        if (roll < wBuilding) planned = planTarget(drawBuilding(s), Activity.BUILDING);
        else if (roll < wBuilding + wPanel) planned = planTarget(drawPanel(s), Activity.PANEL);
        else planned = false;
        if (!planned && !planStroll(s)) planPause();
    }

    /** A building to visit, any of them, quieter ones preferred; never the one just visited. */
    @Nullable
    private VillageVisitIndex.Target drawBuilding(VillageVisitIndex.Settlement s) {
        List<VillageVisitIndex.Target> options = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (VillageVisitIndex.Target t : s.buildings) {
            if (t == lastTarget && s.buildings.size() > 1) continue;
            options.add(t);
            weights.add(crowdWeight(t));
        }
        return pickWeighted(options, weights);
    }

    /** A building first (so one with many panels doesn't win every draw), then one of its panels. */
    @Nullable
    private VillageVisitIndex.Target drawPanel(VillageVisitIndex.Settlement s) {
        List<List<VillageVisitIndex.Target>> options = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (List<VillageVisitIndex.Target> group : s.panelGroups) {
            if (lastTarget != null && group.contains(lastTarget) && s.panelGroups.size() > 1) continue;
            options.add(group);
            weights.add(crowdWeight(group.get(0)));
        }
        List<VillageVisitIndex.Target> group = pickWeighted(options, weights);
        if (group == null) return null;
        return group.get(mob.getRNG().nextInt(group.size()));
    }

    /** Lower the more visitors are already at this building, at its entrance or inside at its point. */
    private double crowdWeight(VillageVisitIndex.Target t) {
        java.util.UUID me = mob.getUniqueID();
        int n = VisitOccupancy.countNear(mob.world, t.point, TARGET_CROWD_RADIUS, me);
        if (t.entrance != null && t.entrance.distanceSq(t.point) > TARGET_CROWD_RADIUS * TARGET_CROWD_RADIUS) {
            n += VisitOccupancy.countNear(mob.world, t.entrance, TARGET_CROWD_RADIUS, me);
        }
        return 1.0D / (1.0D + TravellersConfig.VILLAGE_VISIT.crowdPenalty * n);
    }

    @Nullable
    private <T> T pickWeighted(List<T> options, List<Double> weights) {
        if (options.isEmpty()) return null;
        double total = 0.0D;
        for (double w : weights) total += w;
        double r = mob.getRNG().nextDouble() * total;
        for (int i = 0; i < options.size(); i++) {
            r -= weights.get(i);
            if (r <= 0.0D) return options.get(i);
        }
        return options.get(options.size() - 1);
    }

    private boolean planStroll(VillageVisitIndex.Settlement s) {
        int spread = TravellersConfig.VILLAGE_VISIT.strollSpreadBlocks;
        List<Double> weights = new ArrayList<>(s.entrances.size());
        for (BlockPos e : s.entrances) {
            weights.add(VisitOccupancy.crowdFactor(mob.world, e, TARGET_CROWD_RADIUS + 1.0D, mob.getUniqueID(),
                    TravellersConfig.VILLAGE_VISIT.crowdPenalty));
        }
        for (int attempt = 0; attempt < 6; attempt++) {
            BlockPos anchor = s.entrances.isEmpty() ? s.center : pickWeighted(s.entrances, weights);
            if (anchor == null) anchor = s.center;
            BlockPos seed = anchor.add(mob.getRNG().nextInt(spread * 2 + 1) - spread, 0,
                    mob.getRNG().nextInt(spread * 2 + 1) - spread);
            if (!mob.world.isBlockLoaded(seed)) continue;
            BlockPos spot = VillageGround.outdoorInColumn(mob.world, seed, 4);
            if (spot == null) continue;
            if (spot.distanceSq(mob.getPosition()) < 9.0D) continue;
            activity = Activity.STROLL;
            legs.add(new Leg(spot, rollLingerTicks(), false, false, false));
            exitEntrance = null;
            VisitOccupancy.claim(mob.world, mob.getUniqueID(), spot, 20 * 60);
            return true;
        }
        return false;
    }

    private boolean planTarget(@Nullable VillageVisitIndex.Target t, Activity kind) {
        if (t == null || !mob.world.isBlockLoaded(t.point)) return false;
        boolean inside = MillFootprints.at(mob.world, t.point.getX() + 0.5D, t.point.getY(), t.point.getZ() + 0.5D, 0) != null;
        BlockPos approach = t.entrance;
        if ((approach == null || approach.equals(t.point)) && inside) {
            // No entrance on record: walk to the closest outdoor ground first, go in from there.
            approach = VillageGround.nearestOutdoor(mob.world, t.point, 8);
        }
        lastTarget = t;
        activity = kind;
        exitEntrance = approach;
        boolean viaApproach = approach != null && !approach.equals(t.point);
        if (viaApproach) legs.add(new Leg(approach, 0, false, false, false));
        legs.add(new Leg(t.point, rollLingerTicks(), inside, false, t.panel));
        if (viaApproach) legs.add(new Leg(approach, 0, false, true, false));
        VisitOccupancy.claim(mob.world, mob.getUniqueID(), approach != null ? approach : t.point, 20 * 60);
        trace("activity", kind + " " + t.label + " at " + fmt(t.point) + (inside ? " inside" : ""));
        return true;
    }

    private void planLoneHalt(VillageVisitIndex.Settlement s) {
        if (loneHaltDone) {
            planPause();
            return;
        }
        loneHaltDone = true;
        VillageVisitIndex.Target t = null;
        if (TravellersConfig.VILLAGE_VISIT.visitPanels && !s.panels.isEmpty()) t = s.panels.get(0);
        else if (!s.buildings.isEmpty()) t = s.buildings.get(0);
        BlockPos entrance = t != null ? t.entrance : (s.entrances.isEmpty() ? null : s.entrances.get(0));
        if (entrance == null && t == null) {
            planPause();
            return;
        }
        activity = Activity.BUILDING;
        exitEntrance = entrance;
        // A short halt: stand in front of it rather than going in.
        BlockPos spot = t != null && t.panel ? t.point : entrance != null ? entrance : t.point;
        legs.add(new Leg(spot, remaining(), false, false, t != null && t.panel));
        trace("activity", "HALT at " + fmt(spot));
    }

    private void planPause() {
        activity = Activity.STROLL;
        legs.add(new Leg(mob.getPosition(), 40 + mob.getRNG().nextInt(60), false, false, false));
    }

    private void beginExit() {
        clearActivity();
        MillFootprints.Footprint f = MillFootprints.at(mob.world, mob.posX, mob.posY, mob.posZ, 0);
        BlockPos out = null;
        if (f != null) {
            out = entranceOf(f.building);
            if (out == null) out = VillageGround.nearestOutdoor(mob.world, mob.getPosition(), OUTDOOR_SEARCH_RADIUS);
        }
        if (out == null) {
            beginFetchHorse();
            return;
        }
        activity = Activity.EXIT;
        exitEntrance = out;
        legs.add(new Leg(out, 0, false, true, false));
        trace("exit", "leaving " + f.planKey() + " by " + fmt(out));
    }

    /** Walks back to the horse tied at the village edge; only once beside it does the rider ask to
     * ride again (from afar, the mount reconciler would teleport the horse to it instead). */
    private void beginFetchHorse() {
        clearActivity();
        if (mob.getVillageHitchStop() == null || mob.getMountMode() != MountMode.HITCHED) {
            beginWaitGroup();
            return;
        }
        net.minecraft.entity.Entity horse = mob.getMountUUID() == null ? null
                : ((net.minecraft.world.WorldServer) mob.world).getEntityFromUuid(mob.getMountUUID());
        if ((horse == null || !horse.isEntityAlive()) && mob.getPendingMountNbt() != null && mob.getPendingMountPos() != null) {
            // The horse is held as data at a post nobody has loaded: it reappears there as soon as
            // that ground ticks. If it doesn't, move its post to the loaded edge closest to it.
            BlockPos post = mob.getPendingMountPos();
            if (!isTicking(post)) {
                BlockPos alt = nearestTickingEntrance(post);
                if (alt == null) {
                    untieHorse();
                    beginWaitGroup();
                    return;
                }
                mob.setParkedMount(mob.getPendingMountNbt(), alt);
                post = alt;
            }
            activity = Activity.FETCH_HORSE;
            legs.add(new Leg(post, 0, false, true, false));
            trace("fetch horse", "at its post " + fmt(post));
            return;
        }
        if (horse == null || !horse.isEntityAlive()
                || mob.getDistanceSq(horse) <= HORSE_REACH_BLOCKS * HORSE_REACH_BLOCKS) {
            untieHorse();
            beginWaitGroup();
            return;
        }
        activity = Activity.FETCH_HORSE;
        legs.add(new Leg(horse.getPosition(), 0, false, true, false));
        trace("fetch horse", "at " + fmt(horse.getPosition()));
    }

    private boolean isTicking(BlockPos p) {
        return mob.world.isBlockLoaded(p) && !com.jubitus.travellers.virtual.VirtualTravellerManager
                .isFrozenByVanillaAt((net.minecraft.world.WorldServer) mob.world, p.getX() + 0.5D, p.getZ() + 0.5D);
    }

    @Nullable
    private BlockPos nearestTickingEntrance(BlockPos near) {
        VillageVisitIndex.Settlement s = settlement;
        if (s == null) return null;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos e : s.entrances) {
            double d = e.distanceSq(near);
            if (d < bestD && isTicking(e)) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void untieHorse() {
        mob.setVillageHitchStop(null);
        if (mob.getMountMode() == MountMode.HITCHED) TravellerMountSupport.setMountMode(mob, MountMode.MOUNTED);
    }

    /** A group leader waits by its horse for its members before setting off; everyone else just goes. */
    private void beginWaitGroup() {
        clearActivity();
        if (asFollower() || TravellersConfig.VILLAGE_VISIT.regroupTimeoutSeconds <= 0 || loadedFollowers().isEmpty()) {
            finishVisit();
            return;
        }
        activity = Activity.WAIT_GROUP;
        waitTicks = TravellersConfig.VILLAGE_VISIT.regroupTimeoutSeconds * 20;
        mob.getNavigator().clearPath();
        trace("wait group", "");
    }

    private void tickWaitGroup() {
        mob.getNavigator().clearPath();
        if (--waitTicks <= 0) {
            trace("wait group", "timed out");
            finishVisit();
            return;
        }
        if (waitTicks % 20 != 0) return;
        double r = TravellersConfig.VILLAGE_VISIT.regroupRadius;
        for (EntityTraveller f : loadedFollowers()) {
            if (f.isVisitingVillage() || mob.getDistanceSq(f) > r * r) return;
        }
        finishVisit();
    }

    private List<EntityTraveller> loadedFollowers() {
        java.util.UUID me = mob.getUniqueID();
        return mob.world.getEntitiesWithinAABB(EntityTraveller.class,
                mob.getEntityBoundingBox().grow(GROUP_SCAN_BLOCKS, 16.0D, GROUP_SCAN_BLOCKS),
                t -> t != null && t != mob && t.isEntityAlive() && me.equals(t.getGroupLeaderUUID()));
    }

    private void endActivity() {
        // Waiting has no legs: running out of them is not the end of it (tickWaitGroup decides).
        if (activity == Activity.WAIT_GROUP) return;
        if (activity == Activity.EXIT) {
            beginFetchHorse();
            return;
        }
        if (activity == Activity.FETCH_HORSE) {
            untieHorse();
            beginWaitGroup();
            return;
        }
        failuresInARow = 0;
        clearActivity();
    }

    private void clearActivity() {
        if (conversation != null) {
            conversation.leave(mob);
            conversation = null;
            mob.getGaze().clearFocus(TravellerGaze.Kind.TALK);
        }
        activity = Activity.NONE;
        legs.clear();
        leg = null;
        legTarget = null;
        mob.getGaze().clearFocus(TravellerGaze.Kind.POINT);
        lookAt = null;
        moving = false;
        exitEntrance = null;
        preTurnLeft = 0;
        justWalked = false;
        avoidTicks = 0;
        sidestep = null;
    }

    private void finishVisit() {
        if (finished) return;
        finished = true;
        VillageVisitState st = mob.getVillageVisitState();
        mob.setVillageVisitState(null);
        mob.setVillageIdling(false);
        VisitOccupancy.release(mob.world, mob.getUniqueID());
        trace("finish", "");
        // A follower's own trip isn't what brought it here; it simply goes back to following.
        if (st == null || st.asFollower) return;
        if (mob instanceof EntityTravellerWorker) donate(st.stop);
        mob.getTravelCommon().setVisitedStop(st.stop);
        mob.setReadyToDepart(true);
    }

    private void donate(BlockPos stop) {
        if (!(mob.world instanceof net.minecraft.world.WorldServer)) return;
        if (!MillGoods.donateToConstruction((net.minecraft.world.WorldServer) mob.world, stop)) {
            mob.getTravelCommon().markVillageBad(stop);
        }
    }

    // ---------------------------------------------------------------- walking a leg

    private void tickLegs() {
        if (facingPlayer != null) {
            mob.getNavigator().clearPath();
            return;
        }
        if (leg == null) {
            Leg next = legs.peek();
            if (next == null) {
                endActivity();
                return;
            }
            // Look where it is going before it goes, so the body turn follows an intention.
            if (preTurnLeft > 0) {
                if (--preTurnLeft > 0) return;
            } else if (!justWalked && needsPreTurn(next)) {
                preTurnLeft = PRE_TURN_TICKS;
                mob.getGaze().focus(TravellerGaze.Kind.POINT, next.pos);
                return;
            }
            mob.getGaze().clearFocus(TravellerGaze.Kind.POINT);
            leg = legs.poll();
            if (!beginLeg(leg)) return;
        }

        if (moving) {
            if (arrived(legTarget)) {
                moving = false;
                mob.getNavigator().clearPath();
                lingerLeft = leg.lingerTicks;
                if (lookAt != null) mob.getGaze().focus(TravellerGaze.Kind.POINT, lookAt);
            } else {
                if (tickAvoidance()) return;
                trackProgress();
                return;
            }
        }

        if (--lingerLeft <= 0) {
            justWalked = leg.lingerTicks <= 0;
            leg = null;
            if (lookAt != null) mob.getGaze().clearFocus(TravellerGaze.Kind.POINT);
            lookAt = null;
        }
    }

    private boolean needsPreTurn(Leg next) {
        double dx = next.pos.getX() + 0.5D - mob.posX;
        double dz = next.pos.getZ() + 0.5D - mob.posZ;
        if (dx * dx + dz * dz < 9.0D) return false;
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        return Math.abs(MathHelper.wrapDegrees(yaw - mob.rotationYawHead)) > 60.0F;
    }

    /** Starts walking a leg; false when it was dropped (the activity may have ended with it). */
    private boolean beginLeg(Leg l) {
        BlockPos target;
        if (l.panel) {
            target = panelStandSpot(l.pos);
            lookAt = l.pos;
        } else {
            target = l.pos.equals(mob.getPosition()) ? l.pos : VillageGround.standAt(mob.world, l.pos);
            lookAt = l.look;
        }
        if (target == null) target = l.pos;
        if (l.lingerTicks > 0 && !l.exit) {
            target = spreadSpot(target, l.indoor);
            VisitOccupancy.claim(mob.world, mob.getUniqueID(), target, l.lingerTicks + 20 * 60);
        }
        legTarget = target;

        if (arrived(target)) {
            moving = true;
            return true;
        }

        Path path = mob.getNavigator().getPathToPos(target);
        boolean reaches = path != null && endsAt(path, target);
        if (!reaches && !l.exit) {
            if (l.indoor) {
                // The Millénaire spot can't be reached from the entrance: wait there, facing it.
                trace("unreachable", "lingering at the entrance of " + fmt(target));
                moving = false;
                lingerLeft = l.lingerTicks;
                legTarget = mob.getPosition();
                lookAt = l.pos;
                mob.getGaze().focus(TravellerGaze.Kind.POINT, l.pos);
                return true;
            }
            if (!gainsGround(path, target)) {
                trace("unreachable", "dropped " + activity + " " + fmt(target));
                failuresInARow++;
                clearActivity();
                return false;
            }
            // Outdoors, a partial path is fine: walk it and ask again from where it ends.
        }
        if (path != null) mob.getNavigator().setPath(path, TravellersConfig.VILLAGE_VISIT.walkSpeed);
        moving = true;
        bestDist = Math.sqrt(mob.getDistanceSqToCenter(target));
        noProgressTicks = 0;
        progressTicker = 0;
        return true;
    }

    /** A partial path is worth walking when it ends clearly closer to the target than we stand now. */
    private boolean gainsGround(@Nullable Path path, BlockPos target) {
        if (path == null) return false;
        PathPoint end = path.getFinalPathPoint();
        if (end == null) return false;
        double now = mob.getDistanceSqToCenter(target);
        double dx = end.x - target.getX();
        double dz = end.z - target.getZ();
        double after = dx * dx + dz * dz;
        return Math.sqrt(after) < Math.sqrt(now) - 2.0D;
    }

    /** Keeps a little room from other visitors: a spot someone else holds moves to a free neighbour. */
    private BlockPos spreadSpot(BlockPos target, boolean indoor) {
        java.util.UUID me = mob.getUniqueID();
        if (VisitOccupancy.countNear(mob.world, target, SPOT_SPACING, me) == 0) return target;
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos feet = target.add(dx, dy == 2 ? -1 : dy, dz);
                        if (!mob.world.isBlockLoaded(feet) || !com.jubitus.travellers.ground.Footing.canStandAt(mob.world, feet)) continue;
                        if (!indoor && MillFootprints.at(mob.world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0) != null) continue;
                        if (VisitOccupancy.countNear(mob.world, feet, SPOT_SPACING, me) == 0) return feet;
                    }
                }
            }
        }
        return target;
    }

    private void trackProgress() {
        BlockPos target = legTarget;
        if (target == null) return;
        // The end of a partial path: ask again from here without standing there for a second.
        if (++progressTicker % 5 == 0 && mob.getNavigator().noPath()) repath();
        if (progressTicker % PROGRESS_CHECK_TICKS != 0) return;
        double d = Math.sqrt(mob.getDistanceSqToCenter(target));
        if (d < bestDist - 0.5D) {
            bestDist = d;
            noProgressTicks = 0;
        } else {
            noProgressTicks += PROGRESS_CHECK_TICKS;
        }
        if (mob.getNavigator().noPath()) repath();
        if (noProgressTicks >= TravellersConfig.VILLAGE_VISIT.stuckGiveUpSeconds * 20) onStuck();
    }

    private void repath() {
        BlockPos target = legTarget;
        if (target == null || !moving) return;
        mob.getNavigator().tryMoveToXYZ(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                TravellersConfig.VILLAGE_VISIT.walkSpeed);
    }

    private void onStuck() {
        Leg stuckLeg = leg;
        trace("stuck", activity + " toward " + fmt(legTarget));
        failuresInARow++;
        mob.getNavigator().clearPath();

        if (activity == Activity.FETCH_HORSE) {
            // Close enough for the horse to walk over on its own, or far enough to be brought here.
            untieHorse();
            beginWaitGroup();
            return;
        }
        MillFootprints.Footprint inside = MillFootprints.at(mob.world, mob.posX, mob.posY, mob.posZ, 0);
        if (inside == null) {
            if (activity == Activity.EXIT) {
                beginFetchHorse();
                return;
            }
            clearActivity();
            return;
        }
        // Inside a building: walk back out through its entrance; if even that fails, hop outside.
        BlockPos out = exitEntrance != null ? exitEntrance : entranceOf(inside.building);
        if (out == null || (stuckLeg != null && stuckLeg.exit)) {
            hopOutdoor();
            if (activity == Activity.EXIT) {
                beginFetchHorse();
            } else {
                clearActivity();
            }
            return;
        }
        Activity keep = activity == Activity.EXIT ? Activity.EXIT : Activity.BUILDING;
        clearActivity();
        activity = keep;
        exitEntrance = out;
        legs.add(new Leg(out, 0, false, true, false));
    }

    private void hopOutdoor() {
        BlockPos spot = VillageGround.nearestOutdoor(mob.world, mob.getPosition(), OUTDOOR_SEARCH_RADIUS);
        if (spot == null) {
            trace("hop", "no outdoor spot found");
            return;
        }
        trace("hop", "to " + fmt(spot));
        Relocate.teleport(mob, spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
    }

    private boolean arrived(@Nullable BlockPos target) {
        if (target == null) return true;
        double dx = mob.posX - (target.getX() + 0.5D);
        double dz = mob.posZ - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= 1.6D * 1.6D && Math.abs(mob.posY - target.getY()) <= 1.5D;
    }

    private static boolean endsAt(Path path, BlockPos target) {
        PathPoint end = path.getFinalPathPoint();
        if (end == null) return false;
        double dx = end.x - target.getX();
        double dz = end.z - target.getZ();
        return dx * dx + dz * dz <= 2.25D && Math.abs(end.y - target.getY()) <= 1;
    }

    // ---------------------------------------------------------------- avoiding people

    /** Steps aside (or waits a moment) when someone stands right in the way. True while doing so. */
    private boolean tickAvoidance() {
        if (avoidTicks > 0) {
            avoidTicks--;
            if (sidestep != null) {
                mob.getMoveHelper().setMoveTo(sidestep.x, sidestep.y, sidestep.z, TravellersConfig.VILLAGE_VISIT.walkSpeed);
            }
            if (avoidTicks == 0) {
                sidestep = null;
                avoidCooldown = 10;
                repath();
            }
            return true;
        }
        if (avoidCooldown > 0) {
            avoidCooldown--;
            return false;
        }
        if (!TravellersConfig.VILLAGE_VISIT.avoidEnabled || (mob.ticksExisted & 3) != 0) return false;
        EntityLivingBase blocker = findBlocker();
        if (blocker == null) return false;

        mob.getNavigator().clearPath();
        if (walkingToward(blocker) && mob.getEntityId() > blocker.getEntityId()) {
            // Two people meeting head on: one of them lets the other pass.
            avoidTicks = 12 + mob.getRNG().nextInt(10);
            return true;
        }
        sidestep = sidestepPoint();
        avoidTicks = sidestep != null ? 12 : 15;
        return true;
    }

    @Nullable
    private EntityLivingBase findBlocker() {
        double reach = TravellersConfig.VILLAGE_VISIT.avoidDistance;
        double yaw = Math.toRadians(mob.rotationYaw);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double cone = Math.cos(Math.toRadians(35.0D));
        EntityLivingBase best = null;
        double bestD = reach * reach;
        for (EntityLivingBase e : nearby) {
            if (!e.isEntityAlive() || Math.abs(e.posY - mob.posY) > 1.5D) continue;
            double dx = e.posX - mob.posX;
            double dz = e.posZ - mob.posZ;
            double d2 = dx * dx + dz * dz;
            if (d2 >= bestD || d2 < 1.0E-4D) continue;
            double d = Math.sqrt(d2);
            if ((dx * fx + dz * fz) / d < cone) continue;
            best = e;
            bestD = d2;
        }
        return best;
    }

    private boolean walkingToward(EntityLivingBase e) {
        double vx = e.posX - e.prevPosX;
        double vz = e.posZ - e.prevPosZ;
        double tx = mob.posX - e.posX;
        double tz = mob.posZ - e.posZ;
        double d = Math.sqrt(tx * tx + tz * tz);
        return d > 1.0E-3D && (vx * tx + vz * tz) / d > 0.02D;
    }

    /** A free spot a step to the right (or else the left) and a bit forward. */
    @Nullable
    private Vec3d sidestepPoint() {
        double yaw = Math.toRadians(mob.rotationYaw);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        for (int side = 1; side >= -1; side -= 2) {
            double px = mob.posX + (-fz * side) * 1.4D + fx * 0.8D;
            double pz = mob.posZ + (fx * side) * 1.4D + fz * 0.8D;
            BlockPos base = new BlockPos(px, mob.posY, pz);
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos feet = base.up(dy);
                if (mob.world.isBlockLoaded(feet) && com.jubitus.travellers.ground.Footing.canStandAt(mob.world, feet)) {
                    return new Vec3d(px, feet.getY(), pz);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- players

    /** A passing player only gets a glance; one that stays close makes the visitor stop and face them. */
    private void tickPlayer() {
        if (activity == Activity.TALK) {
            if (facingPlayer != null) stopFacingPlayer();
            return;
        }
        if (facingPlayer != null) {
            double r = TravellersConfig.GAZE.stopForPlayerDistance + 1.5D;
            if (!facingPlayer.isEntityAlive() || facingPlayer.isSpectator() || mob.getDistanceSq(facingPlayer) > r * r) {
                stopFacingPlayer();
            }
            return;
        }
        if (mob.ticksExisted % PLAYER_CHECK_TICKS != 0) return;
        EntityPlayer p = mob.world.getClosestPlayerToEntity(mob, TravellersConfig.GAZE.stopForPlayerDistance);
        if (p == null || p.isSpectator() || !mob.getEntitySenses().canSee(p)) {
            playerCloseTicks = 0;
            return;
        }
        playerCloseTicks += PLAYER_CHECK_TICKS;
        if (playerCloseTicks < TravellersConfig.GAZE.stopForPlayerDelaySeconds * 20.0D) return;
        facingPlayer = p;
        mob.getGaze().focus(TravellerGaze.Kind.INTEREST, p);
        mob.getGreeter().greet(p);
        mob.getNavigator().clearPath();
        trace("player", "facing " + p.getName());
    }

    private void stopFacingPlayer() {
        facingPlayer = null;
        playerCloseTicks = 0;
        mob.getGaze().clearFocus(TravellerGaze.Kind.INTEREST);
        repath();
    }

    // ---------------------------------------------------------------- social

    private int socialPeriod() {
        return Math.max(20, TravellersConfig.VILLAGE_VISIT.socialCheckSeconds * 20);
    }

    private void tickSocial() {
        if (++socialTicker < socialPeriod()) return;
        socialTicker = 0;

        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        double scan = Math.max(cfg.socialRadius, NEARBY_SCAN_RADIUS);
        nearby = mob.world.getEntitiesWithinAABB(EntityLivingBase.class,
                mob.getEntityBoundingBox().grow(scan, 3.0D, scan),
                e -> e != null && e != mob && e.isEntityAlive()
                        && !(e instanceof EntityPlayer && ((EntityPlayer) e).isSpectator()));
        if (legTarget != null && leg != null && leg.lingerTicks > 0) {
            VisitOccupancy.claim(mob.world, mob.getUniqueID(), legTarget, 20 * 60);
        }
        if (activity == Activity.TALK || activity.departing() || nearby.isEmpty()) return;

        if (isAvailableForTalk() && mob.getRNG().nextDouble() < cfg.talkChance) tryStartTalk();
    }

    /** The settlement's buildings, worth a look while standing around. */
    private static List<BlockPos> placesOfInterest(@Nullable VillageVisitIndex.Settlement s) {
        if (s == null || s.buildings.isEmpty()) return Collections.emptyList();
        List<BlockPos> out = new ArrayList<>(s.buildings.size());
        for (VillageVisitIndex.Target t : s.buildings) out.add(t.entrance != null ? t.entrance : t.point);
        return out;
    }

    /** Free to start or join a conversation: strolling or pausing, with time left, not a raider. */
    private boolean isAvailableForTalk() {
        if (!isRunning() || conversation != null || facingPlayer != null) return false;
        if (mob instanceof EntityTravellerRaider) return false;
        if (activity != Activity.NONE && activity != Activity.STROLL) return false;
        return remaining() > TravellersConfig.VILLAGE_VISIT.talkMinSeconds * 20;
    }

    private boolean tryStartTalk() {
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        VillageConversation join = VillageConversation.findJoinable(mob, cfg.socialRadius);
        if (join != null && join.join(mob)) {
            enterTalk(join);
            trace("talk", "joins a circle of " + join.size());
            return true;
        }

        List<EntityLivingBase> partners = new ArrayList<>();
        double r2 = cfg.socialRadius * cfg.socialRadius;
        for (EntityLivingBase e : nearby) {
            if (mob.getDistanceSq(e) > r2) continue;
            if (e instanceof MillVillager) {
                partners.add(e);
            } else if (e instanceof EntityTraveller) {
                EntityAIVillageVisit other = ((EntityTraveller) e).getVillageVisitAI();
                if (other != null && other.isAvailableForTalk()) partners.add(e);
            }
        }
        if (partners.isEmpty()) return false;
        EntityLivingBase partner = partners.get(mob.getRNG().nextInt(partners.size()));
        int ticks = 20 * (cfg.talkMinSeconds + mob.getRNG().nextInt(Math.max(1, cfg.talkMaxSeconds - cfg.talkMinSeconds + 1)));
        boolean villager = partner instanceof MillVillager;
        VillageConversation c = VillageConversation.start(mob, partner, villager, ticks);
        enterTalk(c);
        if (!villager) ((EntityTraveller) partner).getVillageVisitAI().enterTalk(c);
        trace("talk", "with " + partner.getName());
        return true;
    }

    private void enterTalk(VillageConversation c) {
        clearActivity();
        activity = Activity.TALK;
        conversation = c;
        seenSpeakerChanges = c.getSpeakerChanges();
        talkWalkTicks = TALK_WALK_TICKS;
        mob.getNavigator().clearPath();
    }

    private void tickTalk() {
        VillageConversation c = conversation;
        long now = mob.world.getTotalWorldTime();
        if (c == null || c.isOver(now)) {
            clearActivity();
            mob.getNavigator().clearPath();
            return;
        }
        // Take its place in the pair / circle, then stand still.
        Vec3d slot = c.slotOf(mob);
        if (slot != null && talkWalkTicks > 0) {
            double dx = slot.x - mob.posX;
            double dz = slot.z - mob.posZ;
            if (dx * dx + dz * dz > 0.5D * 0.5D) {
                talkWalkTicks--;
                if (mob.getNavigator().noPath() || talkWalkTicks % 20 == 0) {
                    mob.getNavigator().tryMoveToXYZ(slot.x, slot.y, slot.z, TravellersConfig.VILLAGE_VISIT.walkSpeed * 0.8D);
                }
            } else {
                talkWalkTicks = 0;
                mob.getNavigator().clearPath();
            }
        } else {
            mob.getNavigator().clearPath();
        }

        EntityLivingBase speaker = c.speaker(now);
        mob.getGaze().focus(TravellerGaze.Kind.TALK, speaker == mob ? c.listenerFor(mob) : speaker);
        if (TravellersConfig.VILLAGE_VISIT.talkNods && speaker != mob) {
            int changes = c.getSpeakerChanges();
            if (changes != seenSpeakerChanges) {
                seenSpeakerChanges = changes;
                if (mob.getRNG().nextDouble() < 0.6D) mob.getGaze().nod();
            } else if (mob.getRNG().nextInt(90) == 0) {
                mob.getGaze().nod();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Where to stand to read a panel: the ground in front of its face. */
    @Nullable
    private BlockPos panelStandSpot(BlockPos panel) {
        IBlockState state = mob.world.getBlockState(panel);
        EnumFacing facing = null;
        for (IProperty<?> p : state.getPropertyKeys()) {
            if (p instanceof PropertyDirection) {
                facing = state.getValue((PropertyDirection) p);
                break;
            }
        }
        if (facing == null || facing.getAxis() == EnumFacing.Axis.Y) return VillageGround.standAt(mob.world, panel);
        for (int dist = 1; dist <= 2; dist++) {
            BlockPos front = panel.offset(facing, dist);
            for (int dy = 0; dy >= -2; dy--) {
                BlockPos feet = front.up(dy);
                if (com.jubitus.travellers.ground.Footing.canStandAt(mob.world, feet)) return feet;
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos entranceOf(Building b) {
        ResManager res = b.getResManager();
        if (res == null) return null;
        Point p = ((MixinResManagerPointsAccessor) res).travellers$getPathStartPos();
        return p == null ? null : new BlockPos(p.getiX(), p.getiY(), p.getiZ());
    }

    private int rollStayTicks() {
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        boolean lone = settlement != null && settlement.lone;
        int min = lone ? cfg.loneBuildingHaltMinSeconds : cfg.stayMinSeconds;
        int max = Math.max(min, lone ? cfg.loneBuildingHaltMaxSeconds : cfg.stayMaxSeconds);
        return 20 * (min + mob.getRNG().nextInt(max - min + 1));
    }

    private int rollLingerTicks() {
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        int max = Math.max(cfg.lingerMinSeconds, cfg.lingerMaxSeconds);
        return 20 * (cfg.lingerMinSeconds + mob.getRNG().nextInt(max - cfg.lingerMinSeconds + 1));
    }

    private String describeSettlement() {
        VillageVisitIndex.Settlement s = settlement;
        if (s == null) return "no Millénaire data";
        return (s.lone ? "lone" : "village") + " entrances=" + s.entrances.size()
                + " buildings=" + s.buildings.size() + " panels=" + s.panels.size();
    }

    private void trace(String what, String detail) {
        TravellerTracer.event(mob, "visit", what + (detail.isEmpty() ? "" : " " + detail));
    }

    private static String fmt(@Nullable BlockPos p) {
        return p == null ? "-" : p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
