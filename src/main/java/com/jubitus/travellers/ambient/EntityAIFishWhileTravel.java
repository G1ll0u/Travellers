package com.jubitus.travellers.ambient;

import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.Footing;
import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.gaze.TravellerGaze;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;

public class EntityAIFishWhileTravel extends EntityAIBase {

    // Path / validation throttles
    private static final int REPATH_INTERVAL = 20;      // 1s
    private static final int VALIDATE_INTERVAL = 10;    // 0.5s
    private static final int LOOK_INTERVAL = 5;         // 0.25s
    // Tunables
    private final int waterScanRadius;
    private final int minFishTime;
    private final int maxFishTime;
    private final int reelEvery;
    private final int castWindupTicks;
    private final int cooldownTicks;
    private final float startChancePerTick;
    private final EntityTraveller mob;
    private BlockPos shoreSpot;     // where we stand to fish
    private int castTimer;
    private int fishTimer;
    private int reelTimer;
    private int cooldown;
    private Phase phase = Phase.IDLE;
    private int repathDelay;
    private int validateDelay;
    private int lookDelay;

    private BlockPos lastPathTarget;      // prevent pointless re-paths
    private BlockPos cachedAimWater;      // don't rescan water every tick


    public EntityAIFishWhileTravel(EntityTraveller mob) {
        this(
                mob,
                TravellersConfig.FISHING.fishWaterScanRadius,
                TravellersConfig.FISHING.fishMinTimeTicks,
                TravellersConfig.FISHING.fishMaxTimeTicks,
                TravellersConfig.FISHING.fishReelEveryTicks,
                TravellersConfig.FISHING.fishCastWindupTicks,
                TravellersConfig.FISHING.fishCooldownTicks,
                TravellersConfig.FISHING.fishStartChancePerTick
        );
    }

    public EntityAIFishWhileTravel(EntityTraveller mob,
                                   int waterScanRadius,
                                   int minFishTime,
                                   int maxFishTime,
                                   int reelEvery,
                                   int castWindupTicks,
                                   int cooldownTicks,
                                   float startChancePerTick) {
        this.mob = mob;
        this.waterScanRadius = waterScanRadius;
        this.minFishTime = minFishTime;
        this.maxFishTime = maxFishTime;
        this.reelEvery = reelEvery;
        this.castWindupTicks = castWindupTicks;
        this.cooldownTicks = cooldownTicks;
        this.startChancePerTick = startChancePerTick;

        this.setMutexBits(1 | 2);
    }

    @Override
    public boolean shouldExecute() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        float p = startChancePerTick;
        float pPerSecond = 1.0f - (float) Math.pow(1.0f - p, 20.0);
        if (mob.getRNG().nextFloat() > pPerSecond) return false;


        if (mob.world.isRemote) return false;

        // NEW: never start fishing logic while in water
        if (mob.isInWater()) return false;

        // Do NOT fish if busy
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;
        if (mob.getTravelRecoverTicks() > 0) return false;
        if (mob.hasPlayerPassenger()) return false;

        // Only while travelling to somewhere (not idling in village)
        if (mob.isIdlingAtStop()) return false;
        if (mob.getTravelCommon().getTargetVillage() == null) return false;

        // Random gate so it’s occasional
        if (mob.getRNG().nextFloat() > startChancePerTick) return false;

        // Find a nearby “shore” spot next to vanilla water
        BlockPos here = mob.getPosition();
        BlockPos shore = findShore(here, waterScanRadius);
        if (shore == null) return false;

        this.shoreSpot = shore;
        this.phase = Phase.APPROACH;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

        switch (phase) {
            case APPROACH:
            case CAST_WINDUP:
            case FISHING:
            case REEL_OUT:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void startExecuting() {
        mob.scheduleNextTravelAmbient();
        mob.getNavigator().clearPath();
        mob.getTravelCommon().setCurrentWaypoint(null);

        castTimer = fishTimer = reelTimer = 0;

        repathDelay = 0;
        validateDelay = 0;
        lookDelay = 0;

        lastPathTarget = null;
        cachedAimWater = null;
    }

    @Override
    public void resetTask() {
        // Put ghost rod away when stopping
        ItemStack hand = mob.getHeldItemMainhand();
        if (!hand.isEmpty() && mob.isGhostRod(hand)) {
            mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        mob.getNavigator().clearPath();
        mob.getTravelCommon().setCurrentWaypoint(null);
        phase = Phase.IDLE;
        mob.getGaze().clearFocus(TravellerGaze.Kind.POINT);
        // Cooldown before next fishing session
        cooldown = cooldownTicks;
    }

    @Override
    public void updateTask() {
        switch (phase) {
            case APPROACH:
                approachShore();
                break;

            case CAST_WINDUP:
                doCastWindup();
                break;

            case FISHING:
                doFishingLoop();
                break;

            case REEL_OUT:
                doReelOut();
                break;

            default:
                break;
        }
    }

    // --- phases ---

    private void approachShore() {
        if (shoreSpot == null) {
            phase = Phase.DONE;
            return;
        }

        // Only re-validate expensive environment checks occasionally
        if (validateDelay-- <= 0) {
            validateDelay = VALIDATE_INTERVAL;

            // Bail if chunks gone or water disappeared (same logic, just not per-tick)
            if (!mob.world.isAreaLoaded(shoreSpot, 1) || !isNavigable(shoreSpot) || !isWaterAheadOf(shoreSpot)) {
                phase = Phase.DONE;
                return;
            }
        }

        double d2 = mob.getDistanceSqToCenter(shoreSpot);

        if (d2 > 2.0) {
            // Let the navigator keep following its current path.
            // Only compute a new path if we have none OR on a timer OR target changed.
            boolean needsPath = mob.getNavigator().noPath();

            if (!needsPath && repathDelay > 0) {
                repathDelay--;
                return;
            }

            // throttle repath
            repathDelay = REPATH_INTERVAL;

            // avoid repathing to the same block unless path is gone
            if (!needsPath && shoreSpot.equals(lastPathTarget)) {
                return;
            }

            lastPathTarget = shoreSpot;

            boolean ok = mob.getNavigator().tryMoveToXYZ(
                    shoreSpot.getX() + 0.5,
                    shoreSpot.getY(),
                    shoreSpot.getZ() + 0.5,
                    com.jubitus.travellers.unit.UnitSpeed.nav(TravellersConfig.SPEED.fishApproachPercent)
            );

            // If no path found, don't keep hammering pathfinder
            if (!ok) {
                phase = Phase.DONE;
            }

            return;
        }

        // We arrived
        mob.getNavigator().clearPath();

        // Cache the aim target once on arrival
        cachedAimWater = findNearestWaterColumn(shoreSpot, 4);

        faceNearestWaterCached();
        ensureGhostRodInHand();
        castTimer = castWindupTicks;
        phase = Phase.CAST_WINDUP;
    }

    private void faceNearestWaterCached() {
        // Only rotate often; only rescan water sometimes
        if (lookDelay-- > 0) {
            if (cachedAimWater != null) {
                facePos(cachedAimWater);
            }
            return;
        }
        lookDelay = LOOK_INTERVAL;

        // rescan only occasionally (or if we lost the cache)
        if (cachedAimWater == null || !mob.world.isBlockLoaded(cachedAimWater)) {
            cachedAimWater = findNearestWaterColumn(mob.getPosition(), 4);
        }
        if (cachedAimWater != null) {
            facePos(cachedAimWater);
        }
    }

    // The gaze turns head and body; passers-by still get a glance.
    private void facePos(BlockPos best) {
        mob.getGaze().focus(TravellerGaze.Kind.POINT, best);
    }

    private void doCastWindup() {
        if (castTimer-- == castWindupTicks) {
            // little “ready” swing
            mob.world.setEntityState(mob, (byte) 4);
            mob.swingArm(EnumHand.MAIN_HAND);
        }
        if (castTimer <= 0) {
            // “cast” swing + tiny bob sound
            mob.world.setEntityState(mob, (byte) 4);
            mob.swingArm(EnumHand.MAIN_HAND);
            playCastSound();
            // choose a random fish duration
            fishTimer = minFishTime + mob.getRNG().nextInt(Math.max(1, (maxFishTime - minFishTime + 1)));
            reelTimer = reelEvery;
            phase = Phase.FISHING;
        }
    }

    private void doFishingLoop() {
        fishTimer--;
        reelTimer--;

        // occasional micro “tug” / reel motion
        if (reelTimer <= 0) {
            mob.world.setEntityState(mob, (byte) 4);
            mob.swingArm(EnumHand.MAIN_HAND);
            maybePlayWaterPlop();
            reelTimer = reelEvery + mob.getRNG().nextInt(20);
        }

        // hold still and keep looking toward water
        mob.getNavigator().clearPath();
        faceNearestWaterCached();
        if (mob.isInWater()) {
            phase = Phase.REEL_OUT; // bail and stow rod if we somehow ended up in water
            return;
        }

        // safety: if the water went away, or we slipped too far, end early
        if (shoreSpot == null || !LoadedChunks.isAreaLoaded(mob.world, shoreSpot, 1) || mob.getDistanceSqToCenter(shoreSpot) > 9.0 || !isWaterAheadOf(shoreSpot)) {
            phase = Phase.REEL_OUT;
            return;
        }

        if (fishTimer <= 0) {
            phase = Phase.REEL_OUT;
        }
    }

    private void doReelOut() {
        // final reel/swing
        mob.world.setEntityState(mob, (byte) 4);
        mob.swingArm(EnumHand.MAIN_HAND);
        playReelSound();

        // stow the ghost rod; back to travel
        ItemStack hand = mob.getHeldItemMainhand();
        if (!hand.isEmpty() && mob.isGhostRod(hand)) {
            mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        phase = Phase.DONE;
    }

    // --- helpers ---

    private void ensureGhostRodInHand() {
        ItemStack hand = mob.getHeldItemMainhand();
        if (mob.isFishingRodLike(hand)) {
            // real rod already held? turn it into backpack visual and use ghost
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(mob.inventory, hand.copy(), false);
            if (remainder.isEmpty()) {
                mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        mob.setHeldItem(EnumHand.MAIN_HAND, mob.makeGhostRod(rod));
    }

    private void faceNearestWater() {
        BlockPos best = findNearestWaterColumn(mob.getPosition(), 4);
        if (best != null) facePos(best);
    }

    /**
     * Aim at the best nearby water (including up to 3 blocks below the stand).
     */
    @Nullable
    private BlockPos findNearestWaterColumn(BlockPos around, int r) {
        World w = mob.world;
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos col = new BlockPos(around.getX() + dx, around.getY(), around.getZ() + dz);
                if (!LoadedChunks.isAreaLoaded(mob.world, col, 1)) continue;

                // check down up to 3 for a water surface
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos probe = col.down(dy);
                    IBlockState s = w.getBlockState(probe);
                    IBlockState above = w.getBlockState(probe.up());
                    if (BlockRules.isWater(s) && (BlockRules.isWater(above) || above.getMaterial().isReplaceable())) {
                        double d2 = around.distanceSq(probe);
                        if (d2 < bestD2) {
                            bestD2 = d2;
                            best = probe;
                        }
                        break; // take the highest water in this column
                    }
                }
            }
        }
        return best;
    }


    // --- environment checks (vanilla water only; no chunk loads) ---

    private void playCastSound() {
        mob.playSound(SoundEvents.ENTITY_BOBBER_THROW, 0.6F, 0.95F + mob.getRNG().nextFloat() * 0.1F);
    }

    private void playReelSound() {
        mob.playSound(SoundEvents.ENTITY_BOBBER_RETRIEVE, 0.6F, 0.95F + mob.getRNG().nextFloat() * 0.1F);
    }

    private void maybePlayWaterPlop() {
        if (mob.getRNG().nextFloat() < 0.25f) {
            mob.playSound(SoundEvents.ENTITY_BOBBER_SPLASH, 0.5F, 0.9F + mob.getRNG().nextFloat() * 0.2F);
        }
    }

    private boolean isNavigable(BlockPos feet) {
        return isStandable(feet) || isSwimmable(feet);
    }

    private boolean isStandable(BlockPos feet) {
        return Footing.canStandAt(mob.world, feet);
    }

    private boolean isSwimmable(BlockPos feet) {
        return Footing.canSwimAt(mob.world, feet);
    }

    /**
     * Old “isWaterAheadOf(shoreSpot)” → use the ledge-aware check now.
     */
    private boolean isWaterAheadOf(BlockPos stand) {
        return hasFishableWaterFrom(stand);
    }

    /**
     * Pick a dry “shore” cell near 'around' that has fishable water within reach/down 3.
     */
    @Nullable
    private BlockPos findShore(BlockPos around, int radius) {
        World w = mob.world;
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos guessXZ = new BlockPos(around.getX() + dx, around.getY(), around.getZ() + dz);
                if (!LoadedChunks.isAreaLoaded(mob.world, guessXZ, 1)) continue;

                // Resolve Y to a *dry standable* spot only (don’t accept swimmable here)
                BlockPos y = findSurfaceY(guessXZ);
                if (y == null) continue;
                if (!isStandable(y)) continue;

                // Must be next to fishable water (can be below ledge)
                if (!hasFishableWaterFrom(y)) continue;

                double d2 = around.distanceSq(y);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = y;
                }
            }
        }
        return best;
    }

    /**
     * True if, from 'stand', there is fishable vanilla water within 1–3 blocks horizontally
     * AND the water surface can be up to 3 blocks *below* the stand Y (ledge fishing).
     */
    private boolean hasFishableWaterFrom(BlockPos stand) {
        World w = mob.world;
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            // Only allow water exactly 1 block away
            for (int step = 1; step <= 1; step++) {
                BlockPos col = stand.offset(f, step);
                if (!mob.world.isBlockLoaded(col)) continue;

                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos probe = col.down(dy);
                    IBlockState s = w.getBlockState(probe);
                    IBlockState above = w.getBlockState(probe.up());
                    if (BlockRules.isWater(s) && (BlockRules.isWater(above) || above.getMaterial().isReplaceable())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    private BlockPos findSurfaceY(BlockPos xzGuess) {
        int y = ColumnGround.topLayer(mob.world, xzGuess.getX(), xzGuess.getZ(), true);
        return y == ColumnGround.NONE ? null : new BlockPos(xzGuess.getX(), y, xzGuess.getZ());
    }

    private enum Phase {IDLE, APPROACH, CAST_WINDUP, FISHING, REEL_OUT, DONE}
}

