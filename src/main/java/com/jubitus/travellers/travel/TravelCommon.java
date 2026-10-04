package com.jubitus.travellers.travel;

import com.jubitus.travellers.unit.TravellerVoice;
import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.sound.ModSounds;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.unit.culture.VoiceSet;
import com.jubitus.travellers.camp.CampfireBlock;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillFootprints;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;
import com.jubitus.travellers.travel.destination.TravelDestinationChooser;
import com.jubitus.travellers.travel.destination.RecentDestinationRing;

public class TravelCommon {
    public final List<BlockPos> route = new ArrayList<BlockPos>();
    private final int lastTravelTick = 0;
    private final EntityTraveller owner;
    private final Set<BlockPos> badVillages = new HashSet<>();
    public List<BlockPos> cachedVillages = Collections.emptyList();
    public int villageCacheTicker = 0;
    // --- AI State Fields (saved to disk)---
    public BlockPos targetVillage;
    public BlockPos currentWaypoint;
    public BlockPos roamTarget;
    public int pauseTicks;
    public int villageIdleTicks;
    public boolean villageIdling;
    public BlockPos nearestVillage;   // updated every tick
    // --- Path personality (persistent), so they don't always follow exact same path ---
    public float pathAngleBiasDeg;      // small left/right heading bias
    public double pathLaneOffset;       // lateral offset from the beeline
    public int pathStepJitter;          // jitter step size a little
    public int recalcPhase;             // desync repath ticks
    // Personality knobs
    public float wanderlust;          // 0..1 (0 = likes short trips, 1 = likes long trips)
    public float exploreChance;       // small epsilon to allow any village sometimes
    // Anti-ping-pong ring of recently chosen destinations (packed XZ, newest first). Shared shape
    // with VirtualTravellerRecord.recentDestinations so loaded and virtual behave identically.
    public long[] recentDestinations = new long[0];
    public int routeIndex = -1; // -1 = no active route
    // --- Village dwell/route handoff ---
    public boolean readyToDepart = false;
    public int departCooldownTicks = 0;
    // Personality: probability to insert an intermediate stop before the far destination
    public float hopPreference; // 0..1, e.g., 0.0 = never stop at B first, 1.0 = always
    public float lastDesiredTravelYaw;

    public TravelCommon(EntityTraveller owner) {
        this.owner = owner;
        this.lastDesiredTravelYaw = Float.NaN;
    }

    public BlockPos getTargetVillage() {
        return this.targetVillage;
    }

    public void setTargetVillage(@Nullable BlockPos pos) {
        this.targetVillage = pos;
    }

    /** Drops the route and target; the travel AI picks a fresh one once ready to depart. */
    public void clearRoute(boolean readyToDepart) {
        route.clear();
        routeIndex = -1;
        targetVillage = null;
        setCurrentWaypoint(null);
        setReadyToDepart(readyToDepart);
        setDepartCooldownTicks(0);
    }

    /** Replaces the route with a single stop and lets the travel AI leave right away. */
    public void routeTo(BlockPos target) {
        route.clear();
        route.add(target);
        routeIndex = 0;
        targetVillage = target;
        setCurrentWaypoint(null);
        setReadyToDepart(true);
    }// --- Arrival policy (ONE source of truth) ---

    public int getTravelRecoverTicks() {
        return owner.getTravelRecoverTicks();
    }

    public void setTravelRecoverTicks(int t) {
        owner.setTravelRecoverTicks(t);
    }

    public boolean isReadyToDepart() {
        return readyToDepart;
    }

    public void setReadyToDepart(boolean v) {
        this.readyToDepart = v;
    }

    public int getDepartCooldownTicks() {
        return departCooldownTicks;
    }

    public void setDepartCooldownTicks(int t) {
        departCooldownTicks = t;
    }// was: lookupNearestVillageNameOrCoords()

    public String lookupNearestVillageNameOrCoords() {
        BlockPos near = getNearestVillage();
        if (near == null) return "this area";
        return MillSettlements.nameOf(owner.getEntityWorld(), near);
    }

    @Nullable
    public BlockPos getNearestVillage() {
        return this.nearestVillage; // may be null if none found yet
    }// was: updateTargetVillage()

    public BlockPos getRoamTarget() {
        return roamTarget;
    }

    public void setRoamTarget(BlockPos roamTarget) {
        this.roamTarget = roamTarget;
    }

    public int getPauseTicks() {
        return pauseTicks;
    }

    public void setPauseTicks(int pauseTicks) {
        this.pauseTicks = pauseTicks;
    }

    public int getVillageIdleTicks() {
        return villageIdleTicks;
    }

    public void setVillageIdleTicks(int villageIdleTicks) {
        this.villageIdleTicks = villageIdleTicks;
    }

    public float getPathAngleBiasDeg() {
        return pathAngleBiasDeg;
    }

    public double getPathLaneOffset() {
        return pathLaneOffset;
    }

    public int getPathStepJitter() {
        return pathStepJitter;
    }

    public int getRecalcPhase() {
        return recalcPhase;
    }

    public void ensureRoutePlanned() {
        if (routeIndex >= 0 && routeIndex < route.size()) {
            return; // already have an active route
        }

        // --- 1) Choose origin anchor ---
        BlockPos origin = owner.getPosition();
        BlockPos nearest = getNearestVillage();

        if (isWorker()) {
            // Workers: make sure nearestVillage is populated at least once
            if (nearest == null) {
                updateNearestVillage();
                nearest = getNearestVillage();
            }
            if (nearest != null) {
                origin = nearest;  // always route FROM the village center
            }
        } else {
            // Still at the settlement it last went to (or any it stands in): leave from there, never re-pick it.
            BlockPos last = recentDestinations.length > 0
                    ? new BlockPos((int) (recentDestinations[0] >> 32), 0, (int) recentDestinations[0]) : null;
            if (last != null && isAtStop(last, ARRIVAL_HYSTERESIS_BLOCKS)) {
                origin = last;
            } else if (nearest != null && isAtStop(nearest, 0)) {
                origin = nearest;
            }
        }

        // --- 2) Pick a destination village ---
// --- 2) Pick a destination ---
// Default: another village
        BlockPos dest = pickDiverseDestination(
                origin,
                TravellersConfig.DESTINATIONS.villagePickRadius * 4
        );

        if (dest == null) {
            return;
        }


        // --- 3) Collect intermediate village stops along the corridor ---

        List<BlockPos> mids = collectMidsOnCorridor(
                origin,
                dest,
                TravellersConfig.TRAVEL.corridorWidth
        );
        // Don't route a non-raider through a village its home village is at open war with (§4.6).
        mids = TravelDestinationChooser.filterOpenConflictMids(
                owner.getEntityWorld(), owner.getHomeVillage(),
                owner instanceof EntityTravellerRaider, mids);

        this.route.clear();

        if (isWorker()) {
            // Strict village-to-village routing for workers:
            // a worker is considered to already be *at* its current village;
            // first travel target should be some *other* village.

            // (a) Add all corridor mill in order (between origin and dest)
            for (BlockPos mid : mids) {
                if (mid.equals(origin)) continue; // just in case
                if (route.isEmpty() || !route.get(route.size() - 1).equals(mid)) {
                    route.add(mid);
                }
            }

            // (silva) Add final destination (if it’s not already last)
            if (!dest.equals(origin)
                    && (route.isEmpty() || !route.get(route.size() - 1).equals(dest))) {
                route.add(dest);
            }

        } else {
            // ====== EXISTING BEHAVIOUR FOR NORMAL TRAVELLERS ======
            boolean takeMidFirst = !mids.isEmpty() && (owner.getRNG().nextFloat() < this.hopPreference);
            if (takeMidFirst) {
                int idx = 0;
                if (mids.size() >= 3 && owner.getRNG().nextFloat() < 0.25f) {
                    idx = 1 + owner.getRNG().nextInt(Math.min(2, mids.size() - 1));
                }
                this.route.add(mids.get(idx));
            } else if (!mids.isEmpty() && owner.getRNG().nextFloat() < 0.20f) {
                this.route.addAll(mids);
            }

            this.route.add(dest);
        }


        if (route.isEmpty()) {
            return;
        }

        // --- 4) Start route at first stop ---

        this.routeIndex = 0;

        BlockPos firstStop = this.route.get(0);

// IMPORTANT: keep TravelCommon and the entity in sync:
        this.targetVillage = firstStop;           // <--- ADD THIS
        owner.getTravelCommon().setTargetVillage(firstStop);
        owner.getTravelCommon().setCurrentWaypoint(null);
        this.readyToDepart = true;
        this.departCooldownTicks = 0;

    }

    private boolean isWorker() {
        // Workers use strict village-to-village routes
        return owner instanceof EntityTravellerWorker;
    }

    public void updateNearestVillage() {
        List<BlockPos> villages = getVillagesCached();
        if (villages.isEmpty()) {
            this.nearestVillage = null;
            return;
        }

        BlockPos me = owner.getPosition();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (BlockPos v : villages) {
            double d2 = me.distanceSq(v);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = v;
            }
        }

        if (this.nearestVillage == null) {
            this.nearestVillage = best;
        } else {
            double curD2 = me.distanceSq(this.nearestVillage);
            if (bestD2 + EntityTraveller.NEAR_HYS_DELTA_SQ < curD2) {
                this.nearestVillage = best;
            }
        }

        if (!owner.getEntityWorld().isRemote) syncBodyYawWhileTravelling();
    }

    /**
     * Regionalized destination choice (Plan B). Delegates to the single
     * {@link TravelDestinationChooser} authority shared with the virtual side; keeps the historic
     * MPB road-connected candidate restriction (applied inside the chooser).
     */
    @Nullable
    public BlockPos pickDiverseDestination(BlockPos origin, int maxRadius) {
        World world = owner.getEntityWorld();
        if (!(world instanceof WorldServer)) return null;

        TravelDestinationChooser.DestinationContext ctx = new TravelDestinationChooser.DestinationContext();
        ctx.originCentre = origin;
        ctx.homeVillage = owner.getHomeVillage();
        ctx.cultureId = owner.getCultureId();
        ctx.kind = owner instanceof EntityTravellerRaider ? TravelDestinationChooser.EntityKind.RAIDER
                : owner instanceof EntityTravellerWorker ? TravelDestinationChooser.EntityKind.WORKER
                : TravelDestinationChooser.EntityKind.TRAVELLER;
        ctx.wanderlust = this.wanderlust;
        ctx.exploreChance = this.exploreChance;
        ctx.recentDestinations = this.recentDestinations;
        ctx.rng = owner.getRNG();
        ctx.applyRoadRestriction = com.jubitus.travellers.pathing.MPBIntegration.PRESENT;
        ctx.badVillages = this::isVillageBad;
        ctx.debugId = owner.getUniqueID();
        ctx.loaded = true;

        TravelDestinationChooser.ChosenDestination chosen =
                TravelDestinationChooser.choose(world, ctx);
        if (chosen == null) return null;
        noteChosenDestination(chosen.dest);
        return chosen.dest;
    }


    /**
     * Returns [v1, v2, ..., dest], where each v is a village near the straight line
     */
    public List<BlockPos> collectMidsOnCorridor(BlockPos start, BlockPos dest, double corridorWidth) {
        List<BlockPos> all = getVillagesCached();
        List<BlockPos> mids = new ArrayList<BlockPos>();
        if (all.isEmpty()) return mids;

        Vec3d A = new Vec3d(start.getX() + 0.5, 0, start.getZ() + 0.5);
        Vec3d B = new Vec3d(dest.getX() + 0.5, 0, dest.getZ() + 0.5);
        Vec3d AB = B.subtract(A);
        double ab2 = AB.lengthSquared();
        if (ab2 < 1e-6) return mids;

        final class Mid implements Comparable<Mid> {
            final double t;
            final BlockPos p;

            Mid(double t, BlockPos p) {
                this.t = t;
                this.p = p;
            }

            @Override
            public int compareTo(Mid o) {
                return Double.compare(this.t, o.t);
            }
        }

        List<Mid> tmp = new ArrayList<Mid>();

        for (BlockPos v : all) {
            if (v.equals(start) || v.equals(dest)) continue;
            Vec3d P = new Vec3d(v.getX() + 0.5, 0, v.getZ() + 0.5);
            double t = AB.dotProduct(P.subtract(A)) / ab2;     // along-track fraction
            if (t <= 0.02 || t >= 0.98) continue;              // strictly between A and C
            double cw2 = corridorWidth * corridorWidth;
            if (EntityTraveller.pointToSegment2DSq(P, A, B) <= cw2) {
                tmp.add(new Mid(t, v));
            }
        }
        Collections.sort(tmp);
        for (Mid m : tmp) mids.add(m.p);
        return mids;
    }

    public List<BlockPos> getVillagesCached() {
        if ((villageCacheTicker++ & 15) == 0) { // refresh every 16 ticks
            List<BlockPos> raw = MillSettlements.snapshot(owner.getEntityWorld()).centers;

            if (badVillages.isEmpty()) {
                 cachedVillages = raw;
            } else {
                List<BlockPos> filtered = new ArrayList<>(raw.size());
                for (BlockPos v : raw) {
                    if (!badVillages.contains(v)) {
                        filtered.add(v);
                    }
                }
                cachedVillages = filtered;
            }
        }
        return cachedVillages;
    }

    // Smooths the heading toward the travel direction while pathing.
    public void syncBodyYawWhileTravelling() {
        if (owner.isInCombat() || owner.getAttackTarget() != null) return;
        // A village visit caps its own body turns (TravellerMoveHelper).
        if (owner.isVisitingVillage()) return;
        if (owner.getNavigator().noPath() && owner.getTravelCommon().getCurrentWaypoint() == null) return;

        double dx = owner.motionX, dz = owner.motionZ;

        // In water, trust the waypoint over noisy motion
        boolean water = owner.isInWater();
        if (water || (dx * dx + dz * dz) < 0.0016D) { // speed < 0.04
            BlockPos wp = owner.getTravelCommon().getCurrentWaypoint();
            if (wp == null) return;
            dx = (wp.getX() + 0.5D) - owner.posX;
            dz = (wp.getZ() + 0.5D) - owner.posZ;
            if (dx * dx + dz * dz < 1e-6) return;
        }

        float desired = (float) (Math.atan2(dz, dx) * 180D / Math.PI) - 90.0F;

        // Low-pass desired yaw to avoid oscillation
        if (Float.isNaN(lastDesiredTravelYaw)) lastDesiredTravelYaw = desired;
        lastDesiredTravelYaw = EntityTraveller.rotlerp(
                lastDesiredTravelYaw,
                desired,
                (water ? 6.0F : 10.0F)
        );

        // Heading only: the body follows it (TravellerBodyHelper) and the head is the gaze's.
        owner.setBodyYaw(EntityTraveller.rotlerp(owner.rotationYaw, lastDesiredTravelYaw, water ? 24.0F : 45.0F));
    }

    public boolean isAtTargetStop() {
        return isAtStop(owner.getTravelCommon().getTargetVillage(), 0);
    }

    /** Arrival with hysteresis: once inside, the stop only counts as left a few blocks further out. */
    public boolean isAtTargetStopHys(boolean lastWasInside) {
        return isAtStop(owner.getTravelCommon().getTargetVillage(), lastWasInside ? ARRIVAL_HYSTERESIS_BLOCKS : 0);
    }

    private static final int ARRIVAL_HYSTERESIS_BLOCKS = 8;

    // A settlement stop counts as reached within its arrival radius (XZ), or as soon as the unit
    // stands in the town hall's own footprint - a big town hall must never be walked into first.
    private boolean isAtStop(@Nullable BlockPos stop, int extra) {
        if (stop == null) return false;
        int r = arrivalRadiusBlocks(stop) + extra;
        if (stopKindFor(stop) == StopKind.OTHER) {
            return owner.getDistanceSqToCenter(stop) <= (double) r * r;
        }
        double dx = owner.posX - (stop.getX() + 0.5D);
        double dz = owner.posZ - (stop.getZ() + 0.5D);
        if (dx * dx + dz * dz <= (double) r * r) return true;
        MillFootprints.Footprint f = MillFootprints.atPlanar(owner.world, owner.posX, owner.posZ, 2 + extra);
        return f != null && f.building.getPos() != null
                && f.building.getPos().getiX() == stop.getX() && f.building.getPos().getiZ() == stop.getZ();
    }

    private enum StopKind {VILLAGE, LONE_BUILDING, OTHER}

    @Nullable
    private BlockPos stopKindTarget;
    private StopKind stopKind = StopKind.OTHER;

    private StopKind stopKindFor(BlockPos target) {
        if (!target.equals(stopKindTarget)) {
            if (owner.world == null || MillSettlements.wd(owner.world) == null) return StopKind.OTHER;
            stopKindTarget = target;
            MillSettlements.Settlement s = MillSettlements.at(owner.world, target);
            stopKind = s == null ? StopKind.OTHER : s.isVillage ? StopKind.VILLAGE : StopKind.LONE_BUILDING;
        }
        return stopKind;
    }

    public int arrivalRadiusBlocks(BlockPos target) {
        switch (stopKindFor(target)) {
            case VILLAGE:
                return TravellersConfig.VILLAGE_VISIT.arrivalRadius;
            case LONE_BUILDING:
                return TravellersConfig.VILLAGE_VISIT.loneBuildingArrivalRadius;
            default:
                // Workers' non-settlement targets (home chests, employer positions).
                if (owner instanceof EntityTravellerWorker) return 8;
                return TravellersConfig.DESTINATIONS.villageNear;
        }
    }

    // --- Village visit hand-off: the stop whose visit is finished, so the trip may move on ---

    @Nullable
    private BlockPos visitedStop;

    @Nullable
    public BlockPos getVisitedStop() {
        return visitedStop;
    }

    public void setVisitedStop(@Nullable BlockPos stop) {
        this.visitedStop = stop;
    }

    public void maybePlayTravelAmbient() {
        // these three are simple getters on EntityTraveller (see section 2)
        if (owner.getSpawnAmbientCooldownTicks() > 0) return;
        if (owner.getTravelRecoverTicks() > 0) return;
        if (!TravellersConfig.SOUND.travellerAmbient) return;
        if (owner.isChoirSinging()) return;
        if (owner instanceof EntityTravellerWorker) {
            EntityTravellerWorker worker = (EntityTravellerWorker) owner;
            if (worker.isHiredFollower()) {
                return;
            }
        }

        if (owner.isInCombat() || owner.isInPostCombatSilence() || owner.isAutoEating() || owner.isHandActive()) return;
        if (owner.getTravelCommon().getTargetVillage() == null) return;
        if (owner.getTravelAI() == null) return;
        if (owner.getNavigator().noPath() && owner.getTravelCommon().getCurrentWaypoint() == null) return;
        if (owner.motionX * owner.motionX + owner.motionZ * owner.motionZ < 0.005) return;

        if (!owner.world.isRemote) {
            // Only block within a small radius to avoid everyone suppressing each other
            final double radius = 24.0;
            boolean someoneSinging = !owner.world.getEntitiesWithinAABB(
                    EntityTraveller.class,
                    owner.getEntityBoundingBox().grow(radius, 4.0, radius),
                    e -> e != null && e != owner && e.isEntityAlive() && e.voice.isAmbientPlaying()
            ).isEmpty();

            if (someoneSinging) {
                owner.setNextTravelAmbientTick(
                        owner.ticksExisted + 40 + owner.getRNG().nextInt(20)
                );
                return;
            }
        }

        if (owner.ticksExisted < owner.voice.getLockUntilTick()) return;

        VoiceSet v = CultureRegistry.resolveTravellerVoiceSet(owner.getCultureId(), owner.voice.getPersonalityId());

        SoundEvent evt = ModSounds.AMBIENT;
        if (v != null && v.ambient != null && v.ambient.length > 0) {
            evt = v.ambient[owner.getRNG().nextInt(v.ambient.length)];
        }


        float vol = TravellersConfig.SOUND.travellerAmbientVolume;
        float pitch = 0.95F + (owner.getRNG().nextFloat() - 0.5F) * 0.06F;
        int durTicks = 25 * 20 + owner.getRNG().nextInt(6 * 20);

        if (owner.voice.play(evt, vol, pitch, durTicks, false, TravellerVoice.Kind.AMBIENT)) {
            owner.scheduleNextTravelAmbient();
        }

    }

    public BlockPos getCurrentWaypoint() {
        return currentWaypoint;
    }

    public void setCurrentWaypoint(BlockPos currentWaypoint) {
        this.currentWaypoint = currentWaypoint;
    }

    public boolean isVillageIdling() {  // you already store this
        return this.villageIdling;
    }

    public void setVillageIdling(boolean villageIdling) {
        this.villageIdling = villageIdling;
    }

    public void noteChosenDestination(BlockPos dest) {
        recentDestinations = RecentDestinationRing.push(recentDestinations, dest,
                TravellersConfig.DESTINATIONS.recentDestMemory);
    }

    public void markVillageBad(BlockPos center) {
        if (center != null) {
            badVillages.add(center);
        }
    }

    public boolean isVillageBad(BlockPos center) {
        return center != null && badVillages.contains(center);
    }

    // ---- persistence (the unit's own NBT) ----

    public void writeNbt(net.minecraft.nbt.NBTTagCompound compound) {
        if (getTargetVillage() != null) compound.setTag("TargetVillage", net.minecraft.nbt.NBTUtil.createPosTag(getTargetVillage()));
        if (getCurrentWaypoint() != null)
            compound.setIntArray("CurrentWaypoint", new int[]{getCurrentWaypoint().getX(), getCurrentWaypoint().getY(), getCurrentWaypoint().getZ()});
        if (getRoamTarget() != null)
            compound.setIntArray("RoamTarget", new int[]{getRoamTarget().getX(), getRoamTarget().getY(), getRoamTarget().getZ()});
        compound.setInteger("PauseTicks", getPauseTicks());
        compound.setInteger("VillageIdleTicks", getVillageIdleTicks());
        compound.setBoolean("VillageIdling", isVillageIdling());
        compound.setInteger("RouteIndex", routeIndex);
        compound.setFloat("Wanderlust", wanderlust);
        compound.setFloat("ExploreChance", exploreChance);
        if (recentDestinations.length > 0) {
            compound.setIntArray("RecentDests", RecentDestinationRing.toIntPairs(recentDestinations));
        }
        compound.setFloat("HopPreference", hopPreference);
        compound.setFloat("PathAngleBiasDeg", getPathAngleBiasDeg());
        compound.setDouble("PathLaneOffset", getPathLaneOffset());
        compound.setInteger("PathStepJitter", getPathStepJitter());
        compound.setInteger("RecalcPhase", getRecalcPhase());
        compound.setBoolean("ReadyToDepart", isReadyToDepart());
        compound.setInteger("DepartCooldown", getDepartCooldownTicks());
        if (getVisitedStop() != null) compound.setLong("VisitedStop", getVisitedStop().toLong());
        net.minecraft.nbt.NBTTagList lst = new net.minecraft.nbt.NBTTagList();
        for (BlockPos p : route) {
            lst.appendTag(new net.minecraft.nbt.NBTTagIntArray(new int[]{p.getX(), p.getY(), p.getZ()}));
        }
        compound.setTag("RouteVillages", lst);
    }

    public void readNbt(net.minecraft.nbt.NBTTagCompound compound) {
        setTargetVillage(compound.hasKey("TargetVillage", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)
                ? net.minecraft.nbt.NBTUtil.getPosFromTag(compound.getCompoundTag("TargetVillage")) : null);
        if (compound.hasKey("CurrentWaypoint")) {
            int[] a = compound.getIntArray("CurrentWaypoint");
            if (a.length == 3) setCurrentWaypoint(new BlockPos(a[0], a[1], a[2]));
        } else {
            setCurrentWaypoint(null);
        }
        if (compound.hasKey("RoamTarget")) {
            int[] arr = compound.getIntArray("RoamTarget");
            setRoamTarget(new BlockPos(arr[0], arr[1], arr[2]));
        } else {
            setRoamTarget(null);
        }
        if (compound.hasKey("Wanderlust")) wanderlust = compound.getFloat("Wanderlust");
        if (compound.hasKey("ExploreChance")) exploreChance = compound.getFloat("ExploreChance");
        if (compound.hasKey("RecentDests", 11)) {
            recentDestinations = RecentDestinationRing.fromIntPairs(compound.getIntArray("RecentDests"));
        }
        if (compound.hasKey("HopPreference")) hopPreference = compound.getFloat("HopPreference");
        if (compound.hasKey("PathAngleBiasDeg")) pathAngleBiasDeg = compound.getFloat("PathAngleBiasDeg");
        if (compound.hasKey("PathLaneOffset")) pathLaneOffset = compound.getDouble("PathLaneOffset");
        if (compound.hasKey("PathStepJitter")) pathStepJitter = compound.getInteger("PathStepJitter");
        if (compound.hasKey("RecalcPhase")) recalcPhase = compound.getInteger("RecalcPhase");
        setPauseTicks(compound.getInteger("PauseTicks"));
        setVillageIdleTicks(compound.getInteger("VillageIdleTicks"));
        setVillageIdling(compound.getBoolean("VillageIdling"));
        route.clear();
        if (compound.hasKey("RouteVillages", net.minecraftforge.common.util.Constants.NBT.TAG_LIST)) {
            net.minecraft.nbt.NBTTagList lst = compound.getTagList("RouteVillages", net.minecraftforge.common.util.Constants.NBT.TAG_INT_ARRAY);
            for (int i = 0; i < lst.tagCount(); i++) {
                int[] a = lst.getIntArrayAt(i);
                if (a.length == 3) route.add(new BlockPos(a[0], a[1], a[2]));
            }
        }
        routeIndex = compound.getInteger("RouteIndex");
        setReadyToDepart(compound.getBoolean("ReadyToDepart"));
        setDepartCooldownTicks(compound.getInteger("DepartCooldown"));
        setVisitedStop(compound.hasKey("VisitedStop") ? BlockPos.fromLong(compound.getLong("VisitedStop")) : null);
    }
}
