package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.client.sound.ModSounds;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.VoiceSet;
import com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon.CampfireBlock;
import com.jubitus.travellers.entity.entityAI.travel.EntityAITravel;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.mill.MillenaireVillageDirectory;
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

    // Find a dry standable Y near XZ (no chunk loads)
    @Nullable
    public BlockPos topStandable(BlockPos guess) {
        World w = owner.getEntityWorld();
        if (!(w instanceof WorldServer)) return w.getTopSolidOrLiquidBlock(guess); // fallback if needed
        WorldServer ws = (WorldServer) w;
        if (!ws.getChunkProvider().chunkExists(guess.getX() >> 4, guess.getZ() >> 4)) return null;

        BlockPos p = w.getTopSolidOrLiquidBlock(guess);
        for (int i = 0; i < 3; i++) {               // probe up
            if (isDryStandable(p)) break;
            p = p.up();
            if (!ws.getChunkProvider().chunkExists(p.getX() >> 4, p.getZ() >> 4)) return null;
        }
        for (int i = 0; i < 6 && p.getY() > 1; i++) { // then probe down
            if (isDryStandable(p)) break;
            p = p.down();
            if (!ws.getChunkProvider().chunkExists(p.getX() >> 4, p.getZ() >> 4)) return null;
        }
        return isDryStandable(p) ? p : null;
    }

    // “Dry standable” check (single authoritative version)
    public boolean isDryStandable(BlockPos feet) {
        World w = owner.getEntityWorld();
        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down())) return false;

        // never stand on Millénaire fire pit itself
        Block campBlock = CampfireBlock.get();
        if (campBlock != null && w.getBlockState(feet).getBlock() == campBlock) return false;

        IBlockState below = w.getBlockState(feet.down());
        if (below.getMaterial().isLiquid()) return false;

        // forbid non-water liquids intersecting our AABB
        AxisAlignedBB aabb = owner.getEntityBoundingBox().offset(
                (feet.getX() + 0.5) - owner.posX,
                (feet.getY()) - owner.posY,
                (feet.getZ() + 0.5) - owner.posZ
        );
        if (EntityAITravel.intersectsForbiddenLiquid(w, aabb)) return false;

        IBlockState atFeet = w.getBlockState(feet);
        IBlockState atHead = w.getBlockState(feet.up());
        boolean free = (atFeet.getMaterial().isReplaceable() || atFeet.getCollisionBoundingBox(w, feet) == null)
                && (atHead.getMaterial().isReplaceable() || atHead.getCollisionBoundingBox(w, feet.up()) == null);

        BlockFaceShape shape = below.getBlockFaceShape(w, feet.down(), EnumFacing.UP);
        boolean solidBelow = shape == BlockFaceShape.SOLID
                || below.getBlock() == Blocks.SNOW_LAYER
                || below.getBlock() == Blocks.GRASS_PATH
                || below.getBlock() == Blocks.ICE
                || below.getBlock() == Blocks.PACKED_ICE;

        return free && solidBelow;
    }

    public BlockPos getTargetVillage() {
        return this.targetVillage;
    }

    public void setTargetVillage(@Nullable BlockPos pos) {
        this.targetVillage = pos;
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
        return MillVillageIndex.nameForApprox(owner.getEntityWorld(), near);
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

        if (isMerchant()) {
            // Merchants: make sure nearestVillage is populated at least once
            if (nearest == null) {
                updateNearestVillage();
                nearest = getNearestVillage();
            }
            if (nearest != null) {
                origin = nearest;  // always route FROM the village center
            }
        }

        // --- 2) Pick a destination village ---
// --- 2) Pick a destination ---
// Default: another village
        BlockPos dest = pickDiverseDestination(
                origin,
                TravellersModConfig.VILLAGE_PICK_RADIUS * 4
        );

        if (dest == null) {
            return;
        }


        // --- 3) Collect intermediate village stops along the corridor ---

        List<BlockPos> mids = collectMidsOnCorridor(
                origin,
                dest,
                TravellersModConfig.CORRIDOR_WIDTH
        );

        this.route.clear();

        if (isMerchant()) {
            // ====== STRICT VILLAGE-TO-VILLAGE ROUTING FOR MERCHANTS ======
            // Merchants are considered to already be *at* their current village;
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
        owner.setTargetVillage(firstStop);
        owner.setCurrentWaypoint(null);
        this.readyToDepart = true;
        this.departCooldownTicks = 0;

    }

    private boolean isMerchant() {
        // Merchants AND workers use strict village-to-village routes
        return owner instanceof EntityTravellerMerchant
                || owner instanceof EntityTravellerWorker;
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

    @Nullable
    public BlockPos pickDiverseDestination(BlockPos origin, int maxRadius) {
        List<BlockPos> all = getVillagesCached();
        if (all.isEmpty()) return null;

        // --- NEW: clamp selection radius to a hard max (2000 blocks) ---
        int effectiveRadius;
        if (maxRadius <= 0) {
            effectiveRadius = TravellersModConfig.VILLAGE_PICK_RADIUS;
        } else {
            effectiveRadius = Math.min(maxRadius, TravellersModConfig.VILLAGE_PICK_RADIUS);
        }

        long r2 = (long) effectiveRadius * (long) effectiveRadius;

        List<BlockPos> candidates = new ArrayList<>(all.size());
        for (BlockPos v : all) {
            if (v.equals(origin)) continue;
            if (effectiveRadius > 0 && origin.distanceSq(v) > r2) continue; // <- uses clamped radius
            candidates.add(v);
        }
        if (candidates.isEmpty()) return null;

        // If MPB is installed, prefer villages reachable via the road network
        World world = owner.getEntityWorld();
        if (com.jubitus.travellers.utils.world.MPBIntegration.PRESENT
                && world instanceof WorldServer) {
            List<BlockPos> roadVillages = com.jubitus.travellers.utils.world.MPBIntegration
                    .getConnectedVillages((WorldServer) world);
            if (!roadVillages.isEmpty()) {
                List<BlockPos> roadCandidates = new ArrayList<>();
                for (BlockPos c : candidates) {
                    if (isNearRoadVillage(c, roadVillages, 64)) roadCandidates.add(c);
                }
                if (!roadCandidates.isEmpty()) {
                    candidates = roadCandidates;
                }
            }
        }

        // Sort by distance ascending
        candidates.sort(Comparator.comparingDouble(origin::distanceSq));

        int n = candidates.size();
        if (n == 1) return candidates.get(0);

        // Chance split driven by wanderlust:
        //   low wanderlust -> more short trips; high -> more long trips
        float shortP = 0.40f * (1.0f - this.wanderlust) + 0.10f; // 0.10–0.50
        float longP = 0.50f * (this.wanderlust) + 0.20f;        // 0.20–0.70
        float midP = 1.0f - (shortP + longP);
        if (midP < 0.10f) midP = 0.10f; // keep some mids
        float rest = 1.0f - (shortP + longP + midP);
        if (rest != 0) {
            shortP += rest * 0.5f;
        } // normalize

        // Exploration override: sometimes pick across all uniformly
        if (owner.getRNG().nextFloat() < this.exploreChance) {
            return candidates.get(owner.getRNG().nextInt(n));
        }

        // Define bands by quantiles
        int nearEnd = Math.max(1, n / 3);             // first ~third
        int midStart = nearEnd;
        int midEnd = Math.max(midStart + 1, (2 * n) / 3); // second ~third
        int farStart = midEnd;

        // Decide which band to pick from
        float roll = owner.getRNG().nextFloat();
        int from, to;
        if (roll < shortP) {
            from = 0;
            to = nearEnd;
        } else if (roll < shortP + midP) {
            from = midStart;
            to = midEnd;
        } else {
            from = farStart;
            to = n;
        }

        // Pick inside the band with a mild weight:
        // - near band: prefer slightly farther within near (avoid 1-block hops)
        // - mid band: flat
        // - far band: prefer the very far end
        BlockPos pick = owner.weightedPickInRange(candidates, origin, from, to,
                (from == 0) ? +0.5 : (from == farStart ? -0.5 : 0.0));
        return pick != null ? pick : candidates.get(owner.getRNG().nextInt(n));
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
            List<BlockPos> raw = MillVillageIndex.getAllSettlementCenters(owner.getEntityWorld());

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

    /**
     * Align torso (renderYawOffset) to travel direction while pathing.
     */
    public void syncBodyYawWhileTravelling() {
        if (owner.isInCombat() || owner.getAttackTarget() != null) return;
        if (owner.getNavigator().noPath() && owner.getCurrentWaypoint() == null) return;

        double dx = owner.motionX, dz = owner.motionZ;

        // In water, trust the waypoint over noisy motion
        boolean water = owner.isInWater();
        if (water || (dx * dx + dz * dz) < 0.0016D) { // speed < 0.04
            BlockPos wp = owner.getCurrentWaypoint();
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

        float bodyTurn = water ? 24.0F : 45.0F;
        float headTurn = water ? 30.0F : 60.0F;
        float headLimit = 75.0F;

        owner.rotationYaw = EntityTraveller.rotlerp(owner.rotationYaw, lastDesiredTravelYaw, bodyTurn);
        owner.renderYawOffset = EntityTraveller.rotlerp(owner.renderYawOffset, owner.rotationYaw, bodyTurn);
        owner.rotationYawHead = EntityTraveller.rotlerp(owner.rotationYawHead, owner.renderYawOffset, headTurn);

        float diff = MathHelper.wrapDegrees(owner.rotationYawHead - owner.renderYawOffset);
        if (diff < -headLimit) owner.rotationYawHead = owner.renderYawOffset - headLimit;
        if (diff > headLimit) owner.rotationYawHead = owner.renderYawOffset + headLimit;
    }

    public boolean isAtTargetStop() {
        BlockPos tv = owner.getTargetVillage();
        if (tv == null) return false;
        int r = arrivalRadiusBlocks(tv);
        // center-based distance is the least confusing
        return owner.getDistanceSqToCenter(tv) <= (double) (r * r);
    }

    public int arrivalRadiusBlocks(BlockPos target) {

        World w = owner.getEntityWorld();

        // --- Shared helper: is this exactly a Millénaire village center? ---
        boolean isVillageCenter = false;
        if (w != null) {
            for (BlockPos c : MillVillageIndex.getAllVillageCenters(w)) {
                if (c.equals(target)) {
                    isVillageCenter = true;
                    break;
                }
            }
        }

        // --- Workers ---
        if (owner instanceof EntityTravellerWorker) {
            // Village centers → slightly tighter ring than VillageNearDistance
            if (isVillageCenter) {
                int base = MillenaireVillageDirectory.nearRadiusFor(target);
                if (base <= 0) base = TravellersModConfig.VILLAGE_NEAR;
                // e.g. ¾ of Millénaire radius, but never more than 24
                int r = Math.min(24, Math.max(8, (int) (base * 0.75)));
                return r;
            }
            // Non-village targets (home chests, employer positions) → small radius
            return 8;
        }

        // --- Merchants & normal travellers ---
        if (owner instanceof EntityTravellerMerchant || owner instanceof EntityTraveller) {
            if (isVillageCenter) {
                int base = MillenaireVillageDirectory.nearRadiusFor(target);
                if (base <= 0) base = TravellersModConfig.VILLAGE_NEAR;
                int r = Math.min(12, Math.max(8, (int) (base * 0.75)));
                return r;
            }
        }

        // Fallback: default behaviour
        int r = MillenaireVillageDirectory.nearRadiusFor(target);
        if (r <= 0) r = TravellersModConfig.VILLAGE_NEAR;
        return r;
    }

    public void maybePlayTravelAmbient() {
        // these three are simple getters on EntityTraveller (see section 2)
        if (owner.getSpawnAmbientCooldownTicks() > 0) return;
        if (owner.getTravelRecoverTicks() > 0) return;
        if (!TravellersModConfig.travellerAmbient) return;
        if (owner.isChoirSinging()) return;
        if (owner instanceof EntityTravellerWorker) {
            EntityTravellerWorker worker = (EntityTravellerWorker) owner;
            if (worker.isHiredFollower()) {
                return;
            }
        }

        if (owner.isInCombat() || owner.isInPostCombatSilence() || owner.isAutoEating() || owner.isHandActive()) return;
        if (owner.getTargetVillage() == null) return;
        if (owner.getNavigator().noPath() && owner.getCurrentWaypoint() == null) return;
        if (owner.motionX * owner.motionX + owner.motionZ * owner.motionZ < 0.005) return;

        if (!owner.world.isRemote) {
            // Only block within a small radius to avoid everyone suppressing each other
            final double radius = 24.0;
            boolean someoneSinging = !owner.world.getEntitiesWithinAABB(
                    EntityTraveller.class,
                    owner.getEntityBoundingBox().grow(radius, 4.0, radius),
                    e -> e != null && e != owner && e.isEntityAlive() && e.isAmbientPlayingNow()
            ).isEmpty();

            if (someoneSinging) {
                owner.setNextTravelAmbientTick(
                        owner.ticksExisted + 40 + owner.getRNG().nextInt(20)
                );
                return;
            }
        }

        if (owner.ticksExisted < owner.getVoiceLockUntilTick()) return;

        VoiceSet v = CultureRegistry.resolveTravellerVoiceSet(owner.getCultureId(), owner.getVoicePersonalityId());

        SoundEvent evt = ModSounds.AMBIENT;
        if (v != null && v.ambient != null && v.ambient.length > 0) {
            evt = v.ambient[owner.getRNG().nextInt(v.ambient.length)];
        }


        float vol = TravellersModConfig.travellerAmbientVolume;
        float pitch = 0.95F + (owner.getRNG().nextFloat() - 0.5F) * 0.06F;
        int durTicks = 25 * 20 + owner.getRNG().nextInt(6 * 20);

        if (owner.playVoiceMoving(evt, vol, pitch, durTicks, false, EntityTraveller.VoiceKind.AMBIENT)) {
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

    public void markVillageBad(BlockPos center) {
        if (center != null) {
            badVillages.add(center);
        }
    }

    public boolean isVillageBad(BlockPos center) {
        return center != null && badVillages.contains(center);
    }

    private static boolean isNearRoadVillage(BlockPos candidate, List<BlockPos> roadVillages, int radius) {
        long r2 = (long) radius * radius;
        for (BlockPos rv : roadVillages) {
            int dx = rv.getX() - candidate.getX();
            int dz = rv.getZ() - candidate.getZ();
            if ((long) dx * dx + (long) dz * dz <= r2) return true;
        }
        return false;
    }

}