package com.jubitus.travellers.camp;

import com.jubitus.travellers.unit.Relocate;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.camp.build.BuildStep;
import com.jubitus.travellers.camp.build.IncrementalStructureBuilder;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.combat.TravellerHostilityHelper;
import com.jubitus.travellers.virtual.MaterializePositionResolver;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.block.BlockBed;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives a materialized traveller/raider through its camp lifecycle:
 *  - Walks to the footprint edge, then builds its shelter block by block (bottom-up, structural
 *    blocks first, doors/beds/torches/etc last), pacing around the footprint as needed to reach/see
 *    each next block, swinging its arm as it places.
 *  - Once built: at night navigates to the bed and stays put; by day wanders within the camp radius.
 *  - On departure: tears its shelter down block by block, top-down, with the same vanilla
 *    block-break particles/sound a player breaking blocks would trigger. The last occupant to finish
 *    also walks over and douses the firepit before actually leaving.
 *
 * Also handles the loaded-claim path:
 *  - Lone travellers/raiders: claim via normal chance roll near any free campsite.
 *  - Group followers: claim directly in the leader's campsite with no chance roll (mirrors virtual).
 *    If the leader's camp is full, followers loiter near the firepit until a slot opens or the group leaves.
 *
 * Combat (higher priority) preempts this task; departure resumes cleanly on the next execution since
 * all build/teardown progress lives on the persisted CampOccupant, not on this AI instance.
 * EntityAIFollowLeader yields to this task when the leader is camping.
 */
public class EntityAICampStay extends EntityAIBase {

    private static final double SPEED = 0.5D;
    private static final double WORK_POS_ARRIVE_SQ = 2.25; // 1.5 blocks
    private static final int STEERING_TIMEOUT_TICKS = 20; // ~2s of light steering before falling back to real pathfinding
    private static final int WANDER_INTERVAL_MIN = 160;
    private static final int WANDER_INTERVAL_RANGE = 120;
    private static final int WANDER_PAUSE_MIN = 80;
    private static final int WANDER_PAUSE_RANGE = 240;
    private static final int FIREPIT_CHANCE = 3; // 1-in-N wander targets go near the firepit

    private final EntityTraveller mob;

    // --- shouldExecute throttle ---
    private long lastCheckTick = -100L;
    private boolean lastResult = false;

    // --- occupant state ---
    private boolean wasOccupant = false;
    private long claimCooldownUntil = 0L;
    private long nextDaytimeStopRollTick = 0L;

    // --- cached per-execution ---
    @Nullable private CampsiteRecord currentCamp;
    private int currentSlotIndex = -1;
    @Nullable private BlockPos standPos;    // slot center, used as fallback
    @Nullable private BlockPos approachPos; // 1 block outside footprint entrance
    @Nullable private BlockPos bedPos;      // bed block position (set once the shelter has a floor/frame up)

    // --- near-fire mode (follower with no free slot) ---
    private boolean nearFireMode = false;

    // --- daytime wander ---
    private long nextWanderTick = 0L;
    @Nullable private BlockPos wanderTarget;

    // --- sleep (night + daytime nap) ---
    private static final int THREAT_CHECK_INTERVAL_TICKS = 10;
    private static final int THREAT_SUPPRESS_TICKS = 200; // ~10s of "allowed to fight" once a threat is seen
    private static final double SLEEP_ARRIVE_SQ = 4.0; // ~2 blocks: beds have partial collision and
            // are usually boxed in by walls, so pathfinding often can't center exactly on them
    private static final int SLEEP_APPROACH_TIMEOUT_TICKS = 40; // ~2s before accepting "as close as it got"
    // How far the "give up on pathfinding, lie down anyway" fallback is still allowed to snap the
    // last bit of distance. Above this, a failed/empty path (noPath() true from tick 1, e.g. bed
    // behind an unfinished wall) must never be treated as "arrived" — that read as a teleport into
    // bed from across the camp. Beyond this radius we keep steering by hand instead.
    private static final double SLEEP_TELEPORT_MAX_SQ = 9.0; // ~3 blocks
    private boolean sleeping = false;
    private long wakeCooldownUntil = 0L;
    private long nextNapRollTick = 0L;
    private long pendingNapDurationTicks = -1L; // >=0 while walking to bed to start a rolled nap
    private long napEndTick = -1L;              // >=0 while actually napping (unset for indefinite night sleep)
    private long nextThreatCheckTick = 0L;
    private long threatSuppressUntil = 0L; // while now < this, camping does NOT count as "passive"
    private long sleepApproachStartTick = -1L;

    // --- block-by-block build/teardown ---
    private static final int OVERLAP_YIELD_TICKS = 30; // ~1.5s of stepping aside before placing anyway
    private long nextBlockTick = 0L;
    private long positioningStartTick = -1L;
    private long workLastProgressTick = -1L; // last tick moveToward() saw real distance improvement
    private double workBestDistSq = Double.MAX_VALUE;
    private long overlapYieldStartTick = -1L;
    private boolean firepitDoused = false;

    // --- manual terrain restore after teardown (diff vs the occupant's TerrainSnapshot) ---
    @Nullable private java.util.List<BlockPos> restoreQueue;
    private long restoreDeadlineTick = -1L;

    // --- far approach (claimed a camp but still a long walk away, possibly across a ravine) ---
    private static final double FAR_APPROACH_DIST_SQ = 20.0 * 20.0;
    private static final int APPROACH_REPATH_TICKS = 40;      // re-issue pathfinding every ~2s
    private static final int APPROACH_STUCK_TICKS = 100;      // ~5s without distance progress -> travel-style hop
    private static final int APPROACH_ABANDON_TICKS = 1200;   // ~60s total without arriving -> give up the claim
    private static final int APPROACH_HOP_STEP_BLOCKS = 12;
    private long approachStartTick = -1L;
    private long approachLastProgressTick = -1L;
    private long nextApproachRepathTick = 0L;
    private double approachBestDistSq = Double.MAX_VALUE;

    // --- work-spot anti-stuck: failing to reach a spot a few blocks away must never soft-lock ---
    private static final int WORK_STUCK_TELEPORT_TICKS = 100; // ~5s of not arriving -> snap to the spot

    public EntityAICampStay(EntityTraveller mob) {
        this.mob = mob;
        this.setMutexBits(1); // movement — overrides travel/roam/wander
    }

    @Override
    public boolean shouldExecute() {
        if (!TravellersConfig.CAMP_EVENTS.enabled) return false;
        if (com.jubitus.travellers.raid.RaidWorldData.isRaider(mob.world, mob.getUniqueID())) return false;
        if (mob instanceof EntityTravellerWorker) {
            if (!TravellersConfig.CAMP_EVENTS.workersCanCamp) return false;
            EntityTravellerWorker w = (EntityTravellerWorker) mob;
            // Hired workers are following orders/escorting and never stop to camp; only an idle,
            // unhired worker (population-spawned) may join/found a camp like a traveller.
            if (w.isHiredFollower() || w.getMode() != EntityTravellerWorker.WorkerMode.IDLE) return false;
        }
        if (!mob.isEntityAlive() || mob.getAttackTarget() != null) return false;
        if (!(mob.world instanceof WorldServer)) return false;

        long now = mob.world.getTotalWorldTime();
        if (now - lastCheckTick < 10L) return lastResult;
        lastCheckTick = now;

        WorldServer ws = (WorldServer) mob.world;
        CampsiteManager cm = CampsiteManager.get(ws);

        CampsiteRecord camp = cm.campOf(mob.getUniqueID());
        if (camp == null) {
            if (wasOccupant) {
                // The manager removed our occupant (departure / eviction): leave, don't re-claim.
                wasOccupant = false;
                claimCooldownUntil = now + 1200L;
                clearCachedState();
                nearFireMode = false;
                return lastResult = false;
            }
            camp = tryClaimLoaded(ws, cm, now);
            if (camp == null) {
                // Group follower: join the leader's camp directly (no chance roll) or stay near fire.
                if (mob.getGroupLeaderUUID() != null) {
                    return lastResult = handleFollowerWithoutClaim(ws, cm, now);
                }
                nearFireMode = false;
                return lastResult = false;
            }
        }

        // Successfully have a camp claim (own or just obtained).
        Integer slot = slotOf(camp);
        if (slot == null) return lastResult = false;
        BlockPos sp = cm.slotStandPos(camp.id, slot);
        if (sp == null) return lastResult = false;

        this.standPos = sp;
        this.currentCamp = camp;
        this.currentSlotIndex = slot;
        this.nearFireMode = false;
        if (approachPos == null) approachPos = cm.slotApproachPos(camp.id, slot);
        if (bedPos == null) {
            CampOccupant occ = camp.occupants.get(slot);
            if (occ != null && occ.bedPos != null) bedPos = occ.bedPos;
        }
        this.wasOccupant = true;
        return lastResult = true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void startExecuting() {
        long now = mob.world.getTotalWorldTime();
        // Nobody camps, builds a shelter and sleeps in it without getting off their horse first.
        TravellerMountSupport.setMountMode(mob, MountMode.HITCHED);
        if (nearFireMode) {
            nextWanderTick = now;
            return;
        }
        CampOccupant occ = getOccupant();
        if (occ != null && occ.buildState != CampOccupant.BuildState.BUILT && approachPos != null) {
            mob.getNavigator().tryMoveToXYZ(approachPos.getX() + 0.5, approachPos.getY(), approachPos.getZ() + 0.5, SPEED);
        } else if (standPos != null) {
            mob.getNavigator().tryMoveToXYZ(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, SPEED);
        }
        nextWanderTick = now + 40L;
        nextBlockTick = now;
        positioningStartTick = -1L;
    }

    @Override
    public void updateTask() {
        long now = mob.world.getTotalWorldTime();

        // Peacefully occupying the camp in some form this tick: don't proactively pick fights.
        // Retaliation (TravellerHurtByTarget) is unaffected by this flag. Suspended for a while
        // whenever a nearby threat was just detected (see tickApproachAndSleep) so a sleeping
        // traveller actually gets up and fights off a pile of hostiles instead of just enduring it.
        mob.setCampingPassive(now >= threatSuppressUntil);

        if (nearFireMode) {
            if (sleeping) wakeUp(now); // safety net: sleeping never starts in this mode
            tickNearFire(now);
            return;
        }

        CampOccupant occ = getOccupant();
        if (occ == null || standPos == null) return;

        // Any state other than BUILT requires walking/placing/breaking — never move while asleep.
        if (sleeping && occ.buildState != CampOccupant.BuildState.BUILT) {
            wakeUp(now);
        }

        // Still a long walk from the claimed slot (fresh claim within campSeekRadius, or dragged away
        // by combat): dedicated approach phase with real pathfinding and the travel AI's own
        // stuck-rescue, instead of letting the short-range work machinery flail at long range.
        if (isFarFromSlot()) {
            tickFarApproach(now);
            return;
        }
        approachLastProgressTick = -1L; // arrived: reset so a later far excursion restarts cleanly

        if (occ.buildState == CampOccupant.BuildState.BUILDING) {
            tickBuilding(occ, now);
            return;
        }
        if (occ.buildState == CampOccupant.BuildState.TEARING_DOWN) {
            tickTearingDown(occ, now);
            return;
        }

        if (!currentCamp.firepitBuilt || !currentCamp.firepitLit) {
            // Also covers relighting an extinguished pit left by a wiped-out previous group — the
            // actual place/relight fires in CampsiteManager.reconcilePhysical once we're near it.
            wanderTarget = null;
            tickWalkToFirepit(now);
            return;
        }

        if (CampsiteManager.isNight(mob.world)) {
            wanderTarget = null;
            tickNight(occ, now);
            return;
        }

        tickDay(occ, now);
    }

    // --- far approach ---

    /** XZ-only, like every other camp distance: far means the short-range work machinery shouldn't run yet. */
    private boolean isFarFromSlot() {
        if (standPos == null) return false;
        double dx = standPos.getX() + 0.5 - mob.posX;
        double dz = standPos.getZ() + 0.5 - mob.posZ;
        return dx * dx + dz * dz > FAR_APPROACH_DIST_SQ;
    }

    /**
     * Walks the whole distance to the claimed slot with real pathfinding (periodically reissued), a
     * no-progress watchdog that escalates to the travel AI's terrain-aware step-teleport
     * ({@code offscreenHopTowardPos} — the exact same rescue travellers already use to cross ravines
     * and cliffs mid-route), and finally abandons the claim if the slot genuinely can't be reached —
     * the traveller resumes its route instead of soft-locking at the edge of a hole forever.
     */
    private void tickFarApproach(long now) {
        BlockPos target = approachPos != null ? approachPos : standPos;
        if (target == null) return;
        double dx = target.getX() + 0.5 - mob.posX;
        double dz = target.getZ() + 0.5 - mob.posZ;
        double dsq = dx * dx + dz * dz;

        if (approachLastProgressTick < 0) {
            approachStartTick = now;
            approachLastProgressTick = now;
            approachBestDistSq = dsq;
            nextApproachRepathTick = now;
        }
        if (dsq < approachBestDistSq - 1.0) {
            approachBestDistSq = dsq;
            approachLastProgressTick = now;
        }

        if (now - approachLastProgressTick > APPROACH_STUCK_TICKS) {
            if (mob.offscreenHopTowardPos(target, APPROACH_HOP_STEP_BLOCKS)) {
                approachLastProgressTick = now;
                approachBestDistSq = Double.MAX_VALUE; // re-baseline from the new position
                nextApproachRepathTick = now;          // repath immediately from where we landed
                return;
            }
            if (now - approachStartTick > APPROACH_ABANDON_TICKS) {
                abandonClaim(now);
                return;
            }
        }

        if (mob.getNavigator().noPath() || now >= nextApproachRepathTick) {
            mob.getNavigator().tryMoveToXYZ(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, SPEED);
            nextApproachRepathTick = now + APPROACH_REPATH_TICKS;
        }
    }

    /** The slot is unreachable: release the claim (ruin semantics if anything was built) and walk away. */
    private void abandonClaim(long now) {
        if (mob.world instanceof WorldServer) {
            CampsiteManager.get((WorldServer) mob.world).releaseOccupant(mob.getUniqueID());
        }
        wasOccupant = false;
        claimCooldownUntil = now + 1200L;
        lastResult = false;
        standPos = null;
        mob.getNavigator().clearPath();
        clearGhostBlockIfHeld();
        clearCachedState();
    }

    // --- block-by-block building ---

    /** Places the shelter's next unbuilt block once in reach/LOS of it, pacing around the footprint as needed. */
    private void tickBuilding(CampOccupant occ, long now) {
        ShelterSlot slot = currentCamp.slots.get(currentSlotIndex);
        List<BuildStep> plan = CampBuildCoordinator.ensurePlan(occ, slot, mob.world.rand);
        if (plan == null) return; // no shelter template installed for this culture; idle at standPos

        if (occ.buildProgress >= plan.size()) {
            occ.buildState = CampOccupant.BuildState.BUILT;
            clearGhostBlockIfHeld();
            return;
        }

        BuildStep next = plan.get(occ.buildProgress);
        ensureGhostBlockInHand(next);
        if (!inReachAndLOS(next.pos)) {
            moveToward(next.pos, slot, now);
            return;
        }
        if (overlapsStep(next)) {
            // Body still straddling the cell(s) about to be filled: step aside for a moment. This
            // must NEVER block the build outright — any "refuse to place until clear" rule here has
            // proven deadlock-prone (guard refuses / movement thinks it's arrived / repath spins).
            // After the grace period the block is placed regardless: suffocation is disabled while
            // camping and unstickIfEmbedded frees a genuinely embedded builder right after.
            if (overlapYieldStartTick < 0) overlapYieldStartTick = now;
            if (now - overlapYieldStartTick < OVERLAP_YIELD_TICKS) {
                stepAsideFrom(next.pos);
                return;
            }
        } else {
            overlapYieldStartTick = -1L;
        }
        mob.getNavigator().clearPath();
        faceTarget(next.pos);
        positioningStartTick = -1L;
        if (now < nextBlockTick) return;

        BuildStep placed = CampBuildCoordinator.placeNext(mob.world, currentCamp, occ, slot, mob.world.rand, now);
        if (placed != null) {
            mob.doInteractSwing(EnumHand.MAIN_HAND);
            nextBlockTick = now + TravellersConfig.CAMP_EVENTS.buildTicksPerBlock;
            overlapYieldStartTick = -1L;
            unstickIfEmbedded();
        }
    }

    /** Gentle steer directly away from the given block cell — no navigator, no pathfinding. */
    private void stepAsideFrom(BlockPos pos) {
        mob.getNavigator().clearPath();
        double dx = mob.posX - (pos.getX() + 0.5);
        double dz = mob.posZ - (pos.getZ() + 0.5);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) { dx = 1.0; dz = 0.0; len = 1.0; }
        mob.getMoveHelper().setMoveTo(mob.posX + dx / len, mob.posY, mob.posZ + dz / len, SPEED);
    }

    /** Holds a visual-only copy of the next block to place, swapping it out only when the block changes. */
    private void ensureGhostBlockInHand(BuildStep next) {
        ItemStack cur = mob.getHeldItemMainhand();
        if (mob.isGhostBlock(cur)
                && cur.getItem() == Item.getItemFromBlock(next.state.getBlock())
                && cur.getMetadata() == next.state.getBlock().damageDropped(next.state)) {
            return;
        }
        ItemStack ghost = mob.makeGhostBlock(next.state);
        if (!ghost.isEmpty()) mob.setHeldItem(EnumHand.MAIN_HAND, ghost);
    }

    private void clearGhostBlockIfHeld() {
        ItemStack cur = mob.getHeldItemMainhand();
        if (!cur.isEmpty() && mob.isGhostBlock(cur)) {
            mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /**
     * Breaks the shelter's topmost remaining block once in reach/LOS of it; once every structure block
     * is gone, hand-restores the snapshotted terrain the same way ({@link #tickRestoringTerrain})
     * before the departure is finalized.
     */
    private void tickTearingDown(CampOccupant occ, long now) {
        ShelterSlot slot = currentCamp.slots.get(currentSlotIndex);
        List<BuildStep> plan = CampBuildCoordinator.ensurePlan(occ, slot, mob.world.rand);
        if (plan == null) {
            // Locked-in template unresolvable (deleted/renamed): no way to compute which blocks to
            // break one by one — finish instantly via the full snapshot restore, same as offscreen.
            finishOwnTeardown(now);
            return;
        }
        if (occ.buildProgress <= 0) {
            tickRestoringTerrain(occ, slot, now);
            return;
        }

        BuildStep next = plan.get(occ.buildProgress - 1);
        if (!inReachAndLOS(next.pos)) {
            moveToward(next.pos, slot, now);
            return;
        }
        mob.getNavigator().clearPath();
        faceTarget(next.pos);
        positioningStartTick = -1L;
        if (now < nextBlockTick) return;

        BuildStep broken = CampBuildCoordinator.breakNext(mob.world, currentCamp, occ, slot, mob.world.rand, now);
        if (broken != null) {
            mob.doInteractSwing(EnumHand.MAIN_HAND);
            nextBlockTick = now + TravellersConfig.CAMP_EVENTS.buildTicksPerBlock;
            // buildProgress hitting 0 flows into tickRestoringTerrain on the next tick.
        }
    }

    /**
     * All structure blocks are gone: hand-restore the terrain the shelter displaced, block by block,
     * working a diff between the world and the occupant's {@code TerrainSnapshot} (leftover graded
     * fill broken top-down first, then the original ground rebuilt bottom-up) with the same
     * pacing/reach machinery as building. The diff is recomputed from the world on interruption, so
     * this resumes for free; a generous deadline plus the idempotent full {@code snapshot.restore()}
     * in {@code finalizeDeparture} guarantee it can never stall the departure — worst case the last
     * few blocks snap into place when the deadline finishes it.
     */
    private void tickRestoringTerrain(CampOccupant occ, ShelterSlot slot, long now) {
        if (occ.snapshot == null) {
            finishOwnTeardown(now);
            return;
        }
        if (restoreQueue == null) {
            restoreQueue = occ.snapshot.computeRestoreOrder(mob.world);
            restoreDeadlineTick = now + 600L
                    + (long) restoreQueue.size() * TravellersConfig.CAMP_EVENTS.buildTicksPerBlock * 4L;
        }
        if (restoreQueue.isEmpty() || now > restoreDeadlineTick) {
            finishOwnTeardown(now);
            return;
        }

        BlockPos next = restoreQueue.get(0);
        if (!inReachAndLOS(next)) {
            moveToward(next, slot, now);
            return;
        }
        mob.getNavigator().clearPath();
        faceTarget(next);
        positioningStartTick = -1L;
        if (now < nextBlockTick) return;

        occ.snapshot.restoreCell(mob.world, next);
        restoreQueue.remove(0);
        mob.doInteractSwing(EnumHand.MAIN_HAND);
        nextBlockTick = now + TravellersConfig.CAMP_EVENTS.buildTicksPerBlock;
        unstickIfEmbedded(); // restored ground can rise back up under the worker
        if (restoreQueue.isEmpty()) finishOwnTeardown(now);
    }

    /**
     * Own shelter is fully torn down. If this entity is the last occupant left, it also walks over and
     * douses the firepit (with the same break particles) before the departure is finalized and the
     * entity is released back to travel/roam AI.
     */
    private void finishOwnTeardown(long now) {
        if (currentCamp == null || !(mob.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) mob.world;

        boolean isLast = currentCamp.occupants.size() == 1;
        if (isLast && currentCamp.firepitBuilt && !firepitDoused) {
            BlockPos fire = currentCamp.firepitPos;
            if (!inReachAndLOS(fire)) {
                // Walk straight to the firepit — moveToward() targets the shelter's own perimeter,
                // which can sit farther from the fire than the reach distance and stall forever.
                if (positioningStartTick < 0) positioningStartTick = now;
                if (now - positioningStartTick < STEERING_TIMEOUT_TICKS) {
                    mob.getMoveHelper().setMoveTo(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5, SPEED);
                } else if (mob.getNavigator().noPath()) {
                    mob.getNavigator().tryMoveToXYZ(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5, SPEED);
                }
                if (now - positioningStartTick > WORK_STUCK_TELEPORT_TICKS) {
                    snapTo(fire); // never let the douse walk stall the whole departure
                    positioningStartTick = -1L;
                }
                return;
            }
            positioningStartTick = -1L;
            mob.getNavigator().clearPath();
            mob.doInteractSwing(EnumHand.MAIN_HAND);
            IncrementalStructureBuilder.breakStep(ws, fire);
            firepitDoused = true;
        }

        CampsiteManager.get(ws).finalizeDeparture(ws, currentCamp, currentSlotIndex);
        wasOccupant = false;
        claimCooldownUntil = now + 1200L;
        nearFireMode = false;
        clearCachedState();
    }

    /**
     * XZ-only reach check — deliberately height-independent so the traveller can place/break every
     * block of the structure (including the roof) while staying on the ground. A real line-of-sight
     * raytrace was tried here but is unusable: traced at a fixed (ground-level) eye height, it gets
     * blocked by the structure's own already-built walls as soon as it's a few blocks tall, so any
     * block behind them (most of the roof/interior) could never be reached. Footprints are graded
     * perfectly flat and the traveller always paces to the nearest perimeter point of the target
     * ({@link #moveToward}), so a bare XZ-distance check is sufficient here.
     */
    private boolean inReachAndLOS(BlockPos target) {
        double reach = TravellersConfig.CAMP_EVENTS.buildReachDistance;
        double dx = target.getX() + 0.5 - mob.posX;
        double dz = target.getZ() + 0.5 - mob.posZ;
        return dx * dx + dz * dz <= reach * reach;
    }

    private void faceTarget(BlockPos target) {
        mob.getLookHelper().setLookPosition(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30F, 30F);
    }

    /**
     * Safety net: if the block just placed left the mob partially embedded, pop it up onto solid
     * ground. Gated on an actual bounding-box collision — the old unconditional Y-snap re-teleported
     * the mob to an integer Y after every placement, causing a visible little hop per block.
     */
    private void unstickIfEmbedded() {
        if (!(mob.world instanceof WorldServer)) return;
        if (!mob.world.collidesWithAnyBlock(mob.getEntityBoundingBox().shrink(0.001))) return;
        int bx = MathHelper.floor(mob.posX);
        int bz = MathHelper.floor(mob.posZ);
        double safeY = MaterializePositionResolver.ensureNotInSolid((WorldServer) mob.world, bx, mob.posY, bz);
        if (safeY != mob.posY) {
            Relocate.teleport(mob, mob.posX, safeY, mob.posZ);
        }
    }

    /**
     * Light steering toward the nearest footprint-perimeter point to {@code blockTarget}; escalates to
     * real pathfinding if steering doesn't get there in time.
     *
     * <p>While the mob is still standing inside the footprint, the "arrived, stop" shortcut is disabled
     * and pathfinding is used immediately instead of waiting out the steering timeout — otherwise a mob
     * that happens to already be within {@link #WORK_POS_ARRIVE_SQ} of its own perimeter target (common
     * right at the entrance) would stop and do nothing without ever actually leaving the block it's
     * blocking, permanently stalling the build/teardown.
     */
    /** True if the mob's bounding box overlaps the step's cell — or its companion's (bed/door second half). */
    private boolean overlapsStep(BuildStep step) {
        AxisAlignedBB bb = mob.getEntityBoundingBox();
        if (bb.intersects(new AxisAlignedBB(step.pos))) return true;
        return step.companion != null && bb.intersects(new AxisAlignedBB(step.companion.pos));
    }

    private void moveToward(BlockPos blockTarget, ShelterSlot slot, long now) {
        BlockPos work = slot.perimeterPosNear(blockTarget);
        // XZ-only, like inReachAndLOS: the perimeter outside the footprint is NOT graded, so a work
        // spot 1-2 blocks below/above the mob must still count as arrivable — a Y-inclusive distance
        // here makes the mob steer forever at a point it's already standing on (frantic spinning).
        double dxw = work.getX() + 0.5 - mob.posX;
        double dzw = work.getZ() + 0.5 - mob.posZ;
        double dsq = dxw * dxw + dzw * dzw;
        if (dsq <= WORK_POS_ARRIVE_SQ) {
            mob.getNavigator().clearPath();
            positioningStartTick = -1L;
            workLastProgressTick = -1L;
            workBestDistSq = Double.MAX_VALUE;
            return;
        }
        if (positioningStartTick < 0) {
            positioningStartTick = now;
            workLastProgressTick = now;
            workBestDistSq = dsq;
        }
        // Progress tracking, mirrors tickFarApproach(): a mob that is steadily closing the distance
        // (just slowly, e.g. skirting an obstacle) must never be teleported out from under itself —
        // only a genuine stall (no improvement for the whole watchdog window) counts as "stuck".
        if (dsq < workBestDistSq - 1.0) {
            workBestDistSq = dsq;
            workLastProgressTick = now;
        }
        if (now - positioningStartTick < STEERING_TIMEOUT_TICKS) {
            mob.getMoveHelper().setMoveTo(work.getX() + 0.5, work.getY(), work.getZ() + 0.5, SPEED);
        } else if (mob.getNavigator().noPath()) {
            // Steering didn't get there: escalate to real pathfinding (reissued whenever the path
            // dies, incl. immediately when computation failed outright).
            mob.getNavigator().tryMoveToXYZ(work.getX() + 0.5, work.getY(), work.getZ() + 0.5, SPEED);
        }
        // Hard watchdog: steering AND pathfinding both failed to make any real progress for ~5s (work
        // spot walled off by the build itself, pinned in a hole, path spam failing...). Snap to the
        // spot — it's right at the footprint edge, so this is imperceptible, and it's the only
        // guarantee construction/teardown can never soft-lock on movement. Last resort only: if the
        // mob is still closing the distance, however slowly, the watchdog keeps resetting instead.
        if (now - workLastProgressTick > WORK_STUCK_TELEPORT_TICKS) {
            snapTo(work);
            positioningStartTick = -1L;
            workLastProgressTick = -1L;
            workBestDistSq = Double.MAX_VALUE;
        }
    }

    /** Teleports to {@code spot} at a safe (non-embedded) Y. Last-resort anti-stuck, short range only. */
    private void snapTo(BlockPos spot) {
        if (!(mob.world instanceof WorldServer)) return;
        double safeY = MaterializePositionResolver.ensureNotInSolid(
                (WorldServer) mob.world, spot.getX(), spot.getY(), spot.getZ());
        Relocate.teleport(mob, spot.getX() + 0.5, safeY, spot.getZ() + 0.5);
    }

    // --- built: night/day behaviour ---

    /** Navigate to the bed at night and lie down; without a bed, just stand near the slot as before. */
    private void tickNight(CampOccupant occ, long now) {
        if (occ.bedPos == null) {
            BlockPos target = standPos;
            if (target == null) return;
            double dsq = mob.getDistanceSq(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (dsq > 4.0) {
                if (mob.getNavigator().noPath()) {
                    mob.getNavigator().tryMoveToXYZ(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, SPEED);
                }
            } else {
                mob.getNavigator().clearPath();
            }
            return;
        }
        tickApproachAndSleep(occ.bedPos, now);
    }

    /**
     * Daytime: wander as before, with a periodic rolled chance (only if a bed is available) to walk
     * over and take a timed nap. Also wakes an entity that carried {@link #sleeping} over from the
     * night cycle (day always ends night sleep, regardless of the roll timers below).
     */
    private void tickDay(CampOccupant occ, long now) {
        if (sleeping) {
            boolean napExpired = napEndTick >= 0 && now >= napEndTick;
            boolean nightSleepCarryover = napEndTick < 0; // night sleep never sets napEndTick
            if (napExpired || nightSleepCarryover) {
                wakeUp(now);
            } else {
                return; // still napping
            }
        }

        if (pendingNapDurationTicks < 0
                && occ.bedPos != null
                && now >= wakeCooldownUntil
                && now >= nextNapRollTick) {
            nextNapRollTick = now + TravellersConfig.CAMP_EVENTS.daytimeNapRollIntervalTicks;
            if (mob.world.rand.nextDouble() < TravellersConfig.CAMP_EVENTS.daytimeNapChance) {
                int min = TravellersConfig.CAMP_EVENTS.daytimeNapMinTicks;
                int range = Math.max(1, TravellersConfig.CAMP_EVENTS.daytimeNapMaxTicks - min);
                pendingNapDurationTicks = min + mob.world.rand.nextInt(range);
            }
        }

        if (pendingNapDurationTicks >= 0) {
            wanderTarget = null;
            tickApproachAndSleep(occ.bedPos, now);
            if (sleeping) {
                napEndTick = now + pendingNapDurationTicks;
                pendingNapDurationTicks = -1L;
            }
            return;
        }

        tickDaytimeWander(now);
    }

    /** Walks to the bed, then lies down once close enough. No-op once already sleeping. */
    private void tickApproachAndSleep(BlockPos bed, long now) {
        if (now >= nextThreatCheckTick) {
            nextThreatCheckTick = now + THREAT_CHECK_INTERVAL_TICKS;
            if (hasNearbyThreat(TravellersConfig.CAMP_EVENTS.sleepThreatWakeRadius)) {
                threatSuppressUntil = now + THREAT_SUPPRESS_TICKS;
                if (sleeping) wakeUp(now);
                mob.setCampingPassive(false); // let EntityAIDefendIfClose/RaiderAttackTargetAI react this tick
                sleepApproachStartTick = -1L;
                return;
            }
        }
        if (sleeping) return;
        if (now < wakeCooldownUntil) {
            // Recently woken (e.g. by combat): stay put near the bed but don't lie back down yet.
            if (mob.getDistanceSq(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5) > 4.0) {
                if (mob.getNavigator().noPath()) {
                    mob.getNavigator().tryMoveToXYZ(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, SPEED);
                }
            } else {
                mob.getNavigator().clearPath();
            }
            sleepApproachStartTick = -1L;
            return;
        }

        double dsq = mob.getDistanceSq(bed.getX() + 0.5, bed.getY() + 0.5, bed.getZ() + 0.5);
        if (dsq <= SLEEP_ARRIVE_SQ) {
            if (mob.sleep.trySleepIn(bed)) {
                sleeping = true;
                mob.getNavigator().clearPath();
            }
            sleepApproachStartTick = -1L;
            return;
        }

        // A bed's partial-height collision (and usually being boxed in against a wall) often means
        // pathfinding's own "close enough, stop" point falls just short of SLEEP_ARRIVE_SQ, stalling
        // forever. After a short timeout, accept whatever the pathfinder got to and lie down anyway —
        // trySleepInBed already snaps to the exact bed position, so this is purely a gating decision.
        // Only within SLEEP_TELEPORT_MAX_SQ though: a noPath() that fails instantly (e.g. bed behind
        // an unbuilt wall) must not be read as "arrived" from arbitrary range, or the entity visibly
        // teleports across the camp into bed instead of walking there.
        if (sleepApproachStartTick < 0) sleepApproachStartTick = now;
        if (mob.getNavigator().noPath() && now - sleepApproachStartTick > SLEEP_APPROACH_TIMEOUT_TICKS) {
            if (dsq <= SLEEP_TELEPORT_MAX_SQ) {
                if (mob.sleep.trySleepIn(bed)) {
                    sleeping = true;
                }
                sleepApproachStartTick = -1L;
                return;
            }
            // Still far and pathfinding gave up outright: steer straight at the bed by hand instead
            // of stalling or teleporting, same escalation used while pacing a build footprint.
            mob.getMoveHelper().setMoveTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, SPEED);
            return;
        }

        if (mob.getNavigator().noPath()) {
            mob.getNavigator().tryMoveToXYZ(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, SPEED);
        }
    }

    /**
     * True if a hostile (or cross-faction traveller) is within range — mirrors
     * {@link com.jubitus.travellers.combat.ai.EntityAIDefendIfClose}'s candidate
     * filter, scoped small and local since it's only used to interrupt sleep, not to pick a target.
     */
    private boolean hasNearbyThreat(double radius) {
        double r2 = radius * radius;
        AxisAlignedBB bb = mob.getEntityBoundingBox().grow(radius, 2.0, radius);
        List<EntityLivingBase> list = mob.world.getEntitiesWithinAABB(EntityLivingBase.class, bb,
                e -> e != null && e.isEntityAlive() && !e.isInvisible()
                        && mob.getDistanceSq(e) <= r2
                        && (e instanceof IMob
                                || TravellerHostilityHelper.isLavaCowAggressive(e)
                                || (e instanceof EntityTraveller && e != mob
                                        && com.jubitus.travellers.combat.Hostility.isCrossFaction((EntityTraveller) e, mob))
                                || mob.shouldAttackExtraEntity(e)));
        return !list.isEmpty();
    }

    /** Wakes the entity if currently sleeping, starting the re-sleep cooldown. No-op otherwise. */
    private void wakeUp(long now) {
        if (!sleeping) return;
        BlockPos bed = mob.sleep.getBedPos();
        mob.sleep.wakeUp();
        sleeping = false;
        napEndTick = -1L;
        wakeCooldownUntil = now + TravellersConfig.CAMP_EVENTS.wakeCooldownTicks;
        repositionBesideBed(bed);
    }

    /**
     * Places the entity on standable ground right beside the bed, mirroring
     * {@code EntityPlayer#wakeUpPlayer}: {@link BlockBed#getSafeExitLocation} first, and on top of
     * the bed itself as the last resort. Never scans for a floor (an upward scan inside a roofed
     * shelter finds the roof and used to drop the entity on top of the structure).
     */
    private void repositionBesideBed(@Nullable BlockPos bed) {
        if (bed == null || !(mob.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) mob.world;

        if (ws.getBlockState(bed).getBlock() instanceof BlockBed) {
            BlockPos exit = BlockBed.getSafeExitLocation(ws, bed, 0);
            if (exit != null) {
                Relocate.teleport(mob, exit.getX() + 0.5, exit.getY(), exit.getZ() + 0.5);
                return;
            }
        }
        // Boxed in: stand on the bed itself, exactly like a waking player.
        Relocate.teleport(mob, bed.getX() + 0.5, bed.getY() + 0.5625, bed.getZ() + 0.5);
    }

    /** Wander within camp radius; 1-in-N chance the target is near the firepit. */
    private void tickDaytimeWander(long now) {
        if (now >= nextWanderTick) {
            pickWanderTarget(now);
            return;
        }
        if (wanderTarget != null) {
            double dsq = mob.getDistanceSq(wanderTarget.getX() + 0.5, mob.posY, wanderTarget.getZ() + 0.5);
            if (dsq <= 4.0) {
                if (mob.getNavigator().noPath()) {
                    nextWanderTick = now + WANDER_PAUSE_MIN + mob.world.rand.nextInt(WANDER_PAUSE_RANGE);
                    wanderTarget = null;
                }
            } else if (mob.getNavigator().noPath()) {
                mob.getNavigator().tryMoveToXYZ(wanderTarget.getX() + 0.5, wanderTarget.getY(), wanderTarget.getZ() + 0.5, SPEED);
            }
        } else if (standPos != null && mob.getDistanceSq(standPos) > 256.0 && mob.getNavigator().noPath()) {
            mob.getNavigator().tryMoveToXYZ(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, SPEED);
        }
    }

    private void pickWanderTarget(long now) {
        nextWanderTick = now + WANDER_INTERVAL_MIN + mob.world.rand.nextInt(WANDER_INTERVAL_RANGE);
        if (currentCamp == null) return;
        BlockPos fire = currentCamp.firepitPos;
        int r = currentCamp.radius;
        if (mob.world.rand.nextInt(FIREPIT_CHANCE) == 0) {
            int dx = mob.world.rand.nextInt(5) - 2;
            int dz = mob.world.rand.nextInt(5) - 2;
            if (dx == 0 && dz == 0) dx = 1;
            wanderTarget = new BlockPos(fire.getX() + dx, fire.getY(), fire.getZ() + dz);
        } else {
            for (int attempt = 0; attempt < 10; attempt++) {
                int dx = mob.world.rand.nextInt(r * 2 + 1) - r;
                int dz = mob.world.rand.nextInt(r * 2 + 1) - r;
                if ((long) dx * dx + (long) dz * dz <= (long) r * r) {
                    wanderTarget = new BlockPos(fire.getX() + dx, fire.getY(), fire.getZ() + dz);
                    return;
                }
            }
            wanderTarget = standPos;
        }
    }

    /** After building the shelter, entity walks to firepitPos to "light" the fire (triggers reconcile). */
    private void tickWalkToFirepit(long now) {
        if (currentCamp == null) return;
        BlockPos fire = currentCamp.firepitPos;
        double dsq = mob.getDistanceSq(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5);
        if (dsq > 4.0) {
            if (positioningStartTick < 0) positioningStartTick = now;
            if (mob.getNavigator().noPath()) {
                mob.getNavigator().tryMoveToXYZ(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5, SPEED);
            }
            if (now - positioningStartTick > WORK_STUCK_TELEPORT_TICKS) {
                snapTo(fire); // the whole camp waits on this walk to get its fire — never stall it
                positioningStartTick = -1L;
            }
        } else {
            mob.getNavigator().clearPath();
            positioningStartTick = -1L;
        }
    }

    /** No slot available: loiter near the firepit until a slot opens or the leader leaves camp. */
    private void tickNearFire(long now) {
        if (currentCamp == null) return;
        BlockPos fire = currentCamp.firepitPos;
        if (now >= nextWanderTick) {
            int dx = mob.world.rand.nextInt(5) - 2;
            int dz = mob.world.rand.nextInt(5) - 2;
            if (dx == 0 && dz == 0) dx = 1;
            wanderTarget = new BlockPos(fire.getX() + dx, fire.getY(), fire.getZ() + dz);
            nextWanderTick = now + 80 + mob.world.rand.nextInt(120);
        }
        if (wanderTarget != null) {
            double dsq = mob.getDistanceSq(wanderTarget.getX() + 0.5, mob.posY, wanderTarget.getZ() + 0.5);
            if (dsq <= 4.0) {
                if (mob.getNavigator().noPath()) wanderTarget = null;
            } else if (mob.getNavigator().noPath()) {
                mob.getNavigator().tryMoveToXYZ(wanderTarget.getX() + 0.5, wanderTarget.getY(), wanderTarget.getZ() + 0.5, SPEED);
            }
        }
    }

    /**
     * Called when the normal claim path fails for a group follower. Tries to join the leader's camp
     * directly with no day/night chance roll (mirrors VirtualTravellerManager.tryCampClaim). Falls
     * back to near-fire loitering when the camp is full.
     */
    private boolean handleFollowerWithoutClaim(WorldServer ws, CampsiteManager cm, long now) {
        EntityTraveller leaderEnt = resolveLeaderEntity(ws);
        if (leaderEnt == null) { nearFireMode = false; return false; }

        CampsiteRecord leaderCamp = cm.campOf(leaderEnt.getUniqueID());
        if (leaderCamp == null) { nearFireMode = false; return false; } // leader not camping

        if (now >= claimCooldownUntil) {
            String ec = mob instanceof EntityTravellerRaider ? "RAIDER" : "TRAVELLER";
            // Use the leader's departTick so the whole group leaves together.
            long depart = CampsiteManager.computeDepartTick(ws, now);
            for (CampOccupant o : leaderCamp.occupants.values()) {
                if (o.travellerUUID.equals(leaderEnt.getUniqueID())) { depart = o.departTick; break; }
            }
            int slot = cm.claim(leaderCamp, mob.getUniqueID(), ec, mob.getCultureId(), now, depart);
            if (slot >= 0) {
                BlockPos sp = cm.slotStandPos(leaderCamp.id, slot);
                if (sp == null) { nearFireMode = false; return false; }
                currentCamp = leaderCamp;
                currentSlotIndex = slot;
                standPos = sp;
                approachPos = cm.slotApproachPos(leaderCamp.id, slot);
                CampOccupant occ = leaderCamp.occupants.get(slot);
                if (occ != null && occ.bedPos != null) bedPos = occ.bedPos;
                wasOccupant = true;
                nearFireMode = false;
                return true;
            }
        }

        // Camp full or on cooldown: stay near the fire until a slot opens or the leader leaves.
        currentCamp = leaderCamp;
        nearFireMode = true;
        return true;
    }

    @Override
    public void resetTask() {
        // Anything that stops this task (combat, eviction, departure...) must wake a sleeping
        // entity — never leave it lying down while some other AI takes over movement.
        if (sleeping) {
            wakeUp(mob.world.getTotalWorldTime());
        }
        mob.setCampingPassive(false);
        // Camping (for any reason: departure, combat, eviction - resetTask is the single exit point)
        // is over, so the rider wants its horse back under it.
        TravellerMountSupport.setMountMode(mob, MountMode.MOUNTED);
        standPos = null;
        lastResult = false;
        wanderTarget = null;
        nextWanderTick = 0L;
        nearFireMode = false;
        mob.getNavigator().clearPath();
        clearGhostBlockIfHeld();
        clearCachedState();
    }

    private void clearCachedState() {
        currentCamp = null;
        currentSlotIndex = -1;
        approachPos = null;
        bedPos = null;
        positioningStartTick = -1L;
        workLastProgressTick = -1L;
        workBestDistSq = Double.MAX_VALUE;
        overlapYieldStartTick = -1L;
        firepitDoused = false;
        pendingNapDurationTicks = -1L;
        napEndTick = -1L;
        sleepApproachStartTick = -1L;
        restoreQueue = null;
        restoreDeadlineTick = -1L;
        approachStartTick = -1L;
        approachLastProgressTick = -1L;
        nextApproachRepathTick = 0L;
        approachBestDistSq = Double.MAX_VALUE;
    }

    @Nullable
    private CampOccupant getOccupant() {
        if (currentCamp == null || currentSlotIndex < 0) return null;
        return currentCamp.occupants.get(currentSlotIndex);
    }

    private Integer slotOf(CampsiteRecord camp) {
        for (Map.Entry<Integer, CampOccupant> e : camp.occupants.entrySet()) {
            if (e.getValue().travellerUUID.equals(mob.getUniqueID())) return e.getKey();
        }
        return null;
    }

    @Nullable
    private EntityTraveller resolveLeaderEntity(WorldServer ws) {
        UUID leaderUUID = mob.getGroupLeaderUUID();
        if (leaderUUID == null) return null;
        net.minecraft.entity.Entity e = ws.getEntityFromUuid(leaderUUID);
        return e instanceof EntityTraveller ? (EntityTraveller) e : null;
    }

    private static String entityClassOf(EntityTraveller mob) {
        if (mob instanceof EntityTravellerRaider) return "RAIDER";
        if (mob instanceof EntityTravellerWorker) return "WORKER";
        return "TRAVELLER";
    }

    private CampsiteRecord tryClaimLoaded(WorldServer ws, CampsiteManager cm, long now) {
        if (now < claimCooldownUntil) return null;
        String ec = entityClassOf(mob);
        CampsiteRecord camp = cm.findClaimable(ws.provider.getDimension(), mob.posX, mob.posZ, ec,
                mob.getCultureId(), TravellersConfig.CAMP_EVENTS.campSeekRadius);
        if (camp == null) return null;
        if (!CampsiteManager.isNight(ws)) {
            if (now < nextDaytimeStopRollTick) return null;
            nextDaytimeStopRollTick = now + TravellersConfig.CAMP_EVENTS.daytimeStopRollIntervalTicks;
            if (ws.rand.nextDouble() >= TravellersConfig.CAMP_EVENTS.daytimeStopChance) return null;
        }
        int slot = cm.claim(camp, mob.getUniqueID(), ec, mob.getCultureId(), now,
                CampsiteManager.computeDepartTick(ws, now));
        return slot < 0 ? null : camp;
    }
}
