package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * When inside a village's arrival radius:
 * - choose an allowed building (by planKey / isTownhall),
 * - walk inside,
 * - wander a bit and look at people,
 * - run for a fixed duration (default 2400 ticks ~= 2 minutes),
 * - then mark the traveller ready to depart.
 */
public class EntityAIVisitVillageBuildings extends EntityAIBase {

    // ---------- Tunables ----------
    private static final int DEFAULT_VISIT_TICKS = 1200; // 1 minutes @20tps
    private static final int REPATH_TICKS = 12;
    private static final int RESELECT_BUILDING_COOLDOWN = 80;
    private static final double INSIDE_REACH_SQ = 2.25;     // ~1.5 blocks
    private static final int INSIDE_WANDER_STEP = 20;    // blocks max between inside waypoints
    private static final int INSIDE_WANDER_COOLDOWN = 40; // ~2s between picks
    private static final int LOOK_TICKS = 25;            // hold a look target ~1.25s
    private static final double LOOK_RANGE = 12.0D;
    private static final int CANDIDATE_SCAN_Y = 6;       // scan ±6Y to find standable
    private static final int BUILDING_CENTER_NUDGE = 3;  // how far around center to probe

    // ---------- State ----------
    private final EntityTraveller mob;
    private final int totalVisitTicks;
    private Set<String> wordlist;                 // lowercase keys to allow (inn, tavern, townhall, farm, ...)
    private @Nullable Building targetBuilding;
    private @Nullable BlockPos targetInside;     // concrete spot to path to (inside/near the building)
    private @Nullable BlockPos currentInsideWaypoint;

    private int ticksLeft;
    private int repathTicker;
    private int reselectCooldown;
    private int insideWanderCooldown;
    private int lookHold;
    private @Nullable EntityLivingBase lookTarget;

    // Optional: export “what I’m visiting” so other AIs (e.g., drink beer) can react
    private @Nullable String currentVisitKey;     // e.g., "inn", "tavern", "townhall", "farm", or planKey

    /**
     * Convenience ctor: 2 minutes and a default wordlist.
     */
    public EntityAIVisitVillageBuildings(EntityTraveller mob) {
        this(mob, DEFAULT_VISIT_TICKS, new HashSet<>(Arrays.asList(
                "inn", "tavern", "townhall", "temple", "church", "market",
                "farm", "bakery", "forge", "smith", "warehouse", "guard", "barracks"
        )));
    }

    public EntityAIVisitVillageBuildings(EntityTraveller mob, int visitTicks, Set<String> allowedKeysLowercase) {
        this.mob = mob;
        this.totalVisitTicks = Math.max(60, visitTicks);
        this.wordlist = new HashSet<>(allowedKeysLowercase);
        this.setMutexBits(1 | 2); // MOVE + LOOK
    }

    /**
     * Replace the allowed wordlist at runtime (e.g., from config). All keys must be lowercase.
     */
    public EntityAIVisitVillageBuildings setWordlist(Set<String> allowedLowercase) {
        this.wordlist = new HashSet<>(allowedLowercase);
        return this;
    }

    // ------- Lifecycle -------
    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

        BlockPos stop = mob.getTargetVillage();
        if (stop == null) return false;
        if (!mob.isAtTargetStop()) return false;

        // >>> NEW: if current stop is a LONE BUILDING, do NOT start visiting; auto-continue the trip
        if (MillVillageIndex.isLoneBuildingStop(mob.world, stop)) {
            // we’re inside the stop radius already; handoff to route manager
            mob.setVillageIdling(false);
            mob.setReadyToDepart(true);
            return false;
        }

        // Only proceed for real villages
        Building th = resolveTownHallFor(stop);
        if (th == null) return false;

        Building pick = pickAllowedBuilding(th);
        if (pick == null) return false;

        BlockPos inside = findInsideSpotNear(pick.getPos());
        return inside != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

        // Stop if we left the arrival radius (e.g., target changed)
        BlockPos stop = mob.getTargetVillage();
        if (stop == null) return false;
        if (!mob.isAtTargetStopHys(true)) return false;

        // Stop when timer expires
        return ticksLeft > 0;
    }

    @Override
    public void startExecuting() {
        ticksLeft = totalVisitTicks;
        repathTicker = 0;
        reselectCooldown = 0;
        insideWanderCooldown = 0;
        lookHold = 0;
        lookTarget = null;
        currentInsideWaypoint = null;
        currentVisitKey = null;
        mob.setVillageIdling(true);    // tells the rest of your system “we’re dwelling now”

        // Pick building & first inside spot
        BlockPos stop = mob.getTargetVillage();
        Building th = (stop != null) ? resolveTownHallFor(stop) : null;
        targetBuilding = (th != null) ? pickAllowedBuilding(th) : null;

        if (targetBuilding != null) {
            targetInside = findInsideSpotNear(targetBuilding.getPos());
            currentVisitKey = classifyBuildingKey(targetBuilding); // exportable tag
        } else {
            targetInside = null;
        }

        // If we failed to pick, end fast; route manager will handle departure later
        if (targetInside == null) {
            ticksLeft = 0;
            mob.setVillageIdling(false);
            mob.setReadyToDepart(true);
            return;
        }
        // head to the inside spot
        tryMoveTo(targetInside);
    }

    @Override
    public void resetTask() {
        // Clear path/targets & export that we’re no longer visiting anything
        mob.getNavigator().clearPath();
        targetBuilding = null;
        targetInside = null;
        currentInsideWaypoint = null;
        lookTarget = null;
        currentVisitKey = null;
        // Don't toggle villageIdling/readyToDepart here; let stop/finish control it
    }

    @Override
    public void updateTask() {
        if (ticksLeft > 0) ticksLeft--;

        // Occasionally re-path to the same inside anchor (keeps path fresh), or pick a new inside step
        if ((++repathTicker % REPATH_TICKS) == 0) {
            if (targetInside != null && mob.getDistanceSqToCenter(targetInside) > INSIDE_REACH_SQ) {
                tryMoveTo(targetInside);
            }
        }

        // If we reached the building's inside anchor, wander around a bit
        if (targetInside != null && mob.getDistanceSqToCenter(targetInside) <= INSIDE_REACH_SQ) {
            // Pick small internal waypoints every few seconds
            if (insideWanderCooldown-- <= 0) {
                insideWanderCooldown = INSIDE_WANDER_COOLDOWN;
                BlockPos wobble = pickSmallWander(targetInside, INSIDE_WANDER_STEP);
                if (wobble != null) {
                    currentInsideWaypoint = wobble;
                    tryMoveTo(wobble);
                }
            }
            // Idle looking: pick someone to look at
            tickCasualLooks();
        }

        // If the building was bad (blocked) for too long, reselect another allowed one
        if (reselectCooldown-- <= 0) {
            reselectCooldown = RESELECT_BUILDING_COOLDOWN;
            maybeReselectIfStale();
        }

        // Done? handoff to travel system
        if (ticksLeft <= 0) {
            mob.setVillageIdling(false);
            mob.setReadyToDepart(true); // your route manager will advance on next update
        }
    }

    // ------- Core helpers -------

    /**
     * Resolve Town Hall for a stop center (BlockPos).
     */
    @Nullable
    private Building resolveTownHallFor(BlockPos stop) {
        World w = mob.world;
        MillWorldData wd = safeWD(w);
        if (wd == null) return null;

        // First: exact TH match
        Building b = wd.getBuilding(new Point(stop.getX(), stop.getY(), stop.getZ()));
        if (b != null && b.isTownhall) return b;

        // Otherwise: use your MillVillageIndex nearest resolution to find a TH center,
        // then resolve it to a Building.
        MillVillageIndex.VillageInfo vi =
                MillVillageIndex.findNearest2D(w, stop, 48); // small radius; we should be inside already
        if (vi == null) return null;
        return wd.getBuilding(new Point(vi.center.getX(), vi.center.getY(), vi.center.getZ()));
    }

    /**
     * Choose a random allowed building belonging to TH (including TH if allowed).
     */
    @Nullable
    private Building pickAllowedBuilding(Building townHall) {
        MillWorldData wd = safeWD(mob.world);
        if (wd == null) return null;

        List<Building> candidates = new ArrayList<>();
        for (Building b : wd.allBuildings()) {
            Building th = b.isTownhall ? b : b.getTownHall();
            if (th == null || th != townHall) continue;

            String key = classifyBuildingKey(b);
            if (key == null) continue;
            if (!wordlist.isEmpty() && !wordlist.contains(key)) continue;

            candidates.add(b);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(mob.getRNG().nextInt(candidates.size()));
    }

    /**
     * Find a standable/swimmable spot near the building center, without loading chunks.
     */
    @Nullable
    private BlockPos findInsideSpotNear(Point center) {
        BlockPos c = new BlockPos(center.getiX(), center.getiY(), center.getiZ());
        // Probe center and a few nudges around it
        int[] offs = new int[]{0, +2, -2, +BUILDING_CENTER_NUDGE, -BUILDING_CENTER_NUDGE};
        for (int dx : offs)
            for (int dz : offs) {
                BlockPos probe = new BlockPos(c.getX() + dx, c.getY(), c.getZ() + dz);
                BlockPos ok = findStandableOrWaterColumn(probe);
                if (ok != null) return ok;
            }
        return null;
    }

    @Nullable
    private MillWorldData safeWD(World w) {
        try {
            return Mill.getMillWorld(w);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Map a Millénaire Building to a coarse key (“inn”, “farm”, …) using planKey/name.
     * Falls back to returning the raw planKey to keep it usable by your behavior switch.
     */
    @Nullable
    private String classifyBuildingKey(Building b) {
        if (b == null) return null;
        if (b.isTownhall) return "townhall";
        String plan = (b.location != null && b.location.planKey != null) ? b.location.planKey.toLowerCase(Locale.ROOT) : "";
        if (plan.isEmpty()) return null;

        // cheap keyword map — extend as you discover more plan keys
        if (containsAny(plan, "inn", "tavern")) return "inn";
        if (containsAny(plan, "forge", "smith")) return "forge";
        if (containsAny(plan, "farm", "field", "barn", "stable")) return "farm";
        if (containsAny(plan, "church", "temple", "shrine")) return "temple";
        if (containsAny(plan, "market", "shop")) return "market";
        if (containsAny(plan, "warehouse", "depot", "storehouse")) return "warehouse";
        if (containsAny(plan, "guard", "barrack")) return "guard";

        // default: let caller decide using the planKey itself (you can add it to the wordlist)
        return plan;
    }

    @Nullable
    private BlockPos findStandableOrWaterColumn(BlockPos guess) {
        if (!isAreaLoaded(guess, 1)) return null;
        World w = mob.world;

        BlockPos top = w.getTopSolidOrLiquidBlock(guess);
        // Scan downward a bit to find feet spot (accept water columns too)
        BlockPos p = top;
        for (int dy = 0; dy <= CANDIDATE_SCAN_Y && p.getY() > 1; dy++, p = p.down()) {
            if (isSwimmable(p) || isStandable(p)) return p;
        }
        // also scan a bit upward (in case of low ceilings at top)
        p = top.up();
        for (int dy = 0; dy < 3 && p.getY() < 255; dy++, p = p.up()) {
            if (isStandable(p)) return p;
        }
        return null;
    }

    private boolean containsAny(String hay, String... needles) {
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }

    private boolean isAreaLoaded(BlockPos pos, int radius) {
        if (!(mob.world instanceof WorldServer)) return mob.world.isAreaLoaded(pos, radius);
        WorldServer ws = (WorldServer) mob.world;

        int minCX = (pos.getX() - radius) >> 4;
        int maxCX = (pos.getX() + radius) >> 4;
        int minCZ = (pos.getZ() - radius) >> 4;
        int maxCZ = (pos.getZ() + radius) >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!ws.getChunkProvider().chunkExists(cx, cz)) return false;
            }
        }
        return true;
    }

    private boolean isSwimmable(BlockPos feet) {
        World w = mob.world;
        IBlockState feetS = w.getBlockState(feet);
        IBlockState headS = w.getBlockState(feet.up());
        IBlockState belowS = w.getBlockState(feet.down());
        boolean feetWater = (feetS.getMaterial() == Material.WATER);
        boolean headOK = (headS.getMaterial() == Material.WATER) || headS.getMaterial().isReplaceable();
        return feetWater && headOK && belowS.getMaterial() != Material.LAVA;
    }

    private boolean isStandable(BlockPos feet) {
        World w = mob.world;
        IBlockState below = w.getBlockState(feet.down());
        IBlockState atFeet = w.getBlockState(feet);
        IBlockState atHead = w.getBlockState(feet.up());

        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) return false;

        // surface solid-ish
        if (!supportsStandingSurface(w, feet.down(), below)) return false;

        // feet & head replaceable
        return atFeet.getMaterial().isReplaceable() && atHead.getMaterial().isReplaceable();
    }

    // ------- Navigation helpers (standable / water) -------

    private static boolean supportsStandingSurface(World w, BlockPos pos, IBlockState st) {
        if (st.getMaterial().isLiquid()) return false;
        if (st.getBlockFaceShape(w, pos, net.minecraft.util.EnumFacing.UP) == net.minecraft.block.state.BlockFaceShape.SOLID)
            return true;
        AxisAlignedBB bb = st.getBoundingBox(w, pos);
        if (bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001) return true;
        return st.getBlock() == Blocks.ICE || st.getBlock() == Blocks.PACKED_ICE || st.getBlock() == Blocks.FROSTED_ICE
                || st.getBlock() == Blocks.GLASS || st.getBlock() == Blocks.STAINED_GLASS;
    }

    /**
     * Like your travel AI: pick small inside wander steps around anchor, staying navigable.
     */
    @Nullable
    private BlockPos pickSmallWander(BlockPos anchor, int step) {
        Random r = mob.getRNG();
        for (int tries = 0; tries < 10; tries++) {
            int nx = anchor.getX() + r.nextInt(step * 2 + 1) - step;
            int nz = anchor.getZ() + r.nextInt(step * 2 + 1) - step;
            BlockPos guess = new BlockPos(nx, anchor.getY(), nz);
            BlockPos ok = findStandableOrWaterColumn(guess);
            if (ok != null) {
                // keep it within already loaded chunks
                if (!isAreaLoaded(ok, 1)) continue;
                return ok;
            }
        }
        return null;
    }

    private void tryMoveTo(BlockPos p) {
        if (p == null) return;
        double speed = mob.isInWater() ? 0.8 * TravellersModConfig.movementSpeedWhileTravel : TravellersModConfig.movementSpeedWhileTravel;
        mob.getNavigator().tryMoveToXYZ(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, speed);
    }

    private void maybeReselectIfStale() {
        if (targetBuilding == null) return;
        // If we’ve failed to reach the anchor for a while, try another allowed building
        if (targetInside != null && mob.getDistanceSqToCenter(targetInside) > 64.0) {
            Building th = targetBuilding.getTownHall();
            if (th == null) return;
            Building alt = pickAllowedBuilding(th);
            if (alt != null && alt != targetBuilding) {
                targetBuilding = alt;
                targetInside = findInsideSpotNear(alt.getPos());
                currentVisitKey = classifyBuildingKey(alt);
                if (targetInside != null) tryMoveTo(targetInside);
            }
        }
    }

    private void tickCasualLooks() {
        if (lookHold-- > 0 && lookTarget != null && lookTarget.isEntityAlive()) {
            mob.getLookHelper().setLookPositionWithEntity(lookTarget, 30f, 30f);
            return;
        }
        // Pick a fresh target
        lookTarget = pickNearbyEntityToLookAt(LOOK_RANGE);
        lookHold = LOOK_TICKS;
        if (lookTarget != null) {
            mob.getLookHelper().setLookPositionWithEntity(lookTarget, 30f, 30f);
        }
    }

    @Nullable
    private EntityLivingBase pickNearbyEntityToLookAt(double range) {
        AxisAlignedBB box = mob.getEntityBoundingBox().grow(range, 3.0, range);
        List<EntityLivingBase> list = mob.world.getEntitiesWithinAABB(EntityLivingBase.class, box, e ->
                e != null && e.isEntityAlive() && e != mob);
        if (list.isEmpty()) return null;
        return list.get(mob.getRNG().nextInt(list.size()));
    }

    // ------- Public hook so other systems can know “what am I visiting?” -------

    /**
     * Returns a coarse key like "inn", "townhall", "farm" (or raw planKey fallback) while active.
     */
    @Nullable
    public String getCurrentVisitKey() {
        return currentVisitKey;
    }


}
