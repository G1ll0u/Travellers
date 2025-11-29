package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
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

    // Tunables
    private final int waterScanRadius;
    private final int minFishTime;
    private final int maxFishTime;
    private final int reelEvery;
    private final int castWindupTicks;
    private final int cooldownTicks;
    private final float startChancePerTick;

    private final EntityTraveller mob;
    private final double approachSpeed;

    private BlockPos shoreSpot;     // where we stand to fish
    private int castTimer;
    private int fishTimer;
    private int reelTimer;
    private int cooldown;
    private Phase phase = Phase.IDLE;

    public EntityAIFishWhileTravel(EntityTraveller mob) {
        this(
                mob,
                TravellersModConfig.fishApproachSpeed,
                TravellersModConfig.fishWaterScanRadius,
                TravellersModConfig.fishMinTimeTicks,
                TravellersModConfig.fishMaxTimeTicks,
                TravellersModConfig.fishReelEveryTicks,
                TravellersModConfig.fishCastWindupTicks,
                TravellersModConfig.fishCooldownTicks,
                TravellersModConfig.fishStartChancePerTick
        );
    }

    public EntityAIFishWhileTravel(EntityTraveller mob,
                                   double approachSpeed,
                                   int waterScanRadius,
                                   int minFishTime,
                                   int maxFishTime,
                                   int reelEvery,
                                   int castWindupTicks,
                                   int cooldownTicks,
                                   float startChancePerTick) {
        this.mob = mob;
        this.approachSpeed = approachSpeed;
        this.waterScanRadius = waterScanRadius;
        this.minFishTime = minFishTime;
        this.maxFishTime = maxFishTime;
        this.reelEvery = reelEvery;
        this.castWindupTicks = castWindupTicks;
        this.cooldownTicks = cooldownTicks;
        this.startChancePerTick = startChancePerTick;

        this.setMutexBits(1 | 2);
    }

    private static boolean isLeafBlock(World w, BlockPos pos, IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof net.minecraft.block.BlockLeaves) return true;
        try {
            return b.isLeaves(s, w, pos);
        } catch (Throwable ignore) {
            return false;
        }
    }

    // reuse your rotlerp (static) from EntityTraveller
    private static float rotlerp(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    // Dry standable = feet cell is standable AND not liquid.
    private boolean isDryStandable(BlockPos feet) {
        if (!isStandable(feet)) return false;
        return !mob.world.getBlockState(feet).getMaterial().isLiquid();
    }

    @Override
    public boolean shouldExecute() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (mob.world.isRemote) return false;

        // NEW: never start fishing logic while in water
        if (mob.isInWater()) return false;

        // Do NOT fish if busy
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;
        if (mob.getTravelRecoverTicks() > 0) return false;
        if (mob.hasPlayerPassenger()) return false;

        // Only while travelling to somewhere (not idling in village)
        if (mob.isVillageIdling()) return false;
        if (mob.getTargetVillage() == null) return false;

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
        // small travel ambient desync so music doesn’t collide
        mob.scheduleNextTravelAmbient();

        // Clear path/waypoint while we control movement
        mob.getNavigator().clearPath();
        mob.setCurrentWaypoint(null);
        castTimer = 0;
        fishTimer = 0;
        reelTimer = 0;
    }

    @Override
    public void resetTask() {
        // Put ghost rod away when stopping
        ItemStack hand = mob.getHeldItemMainhand();
        if (!hand.isEmpty() && mob.isGhostRod(hand)) {
            mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        mob.getNavigator().clearPath();
        mob.setCurrentWaypoint(null);
        phase = Phase.IDLE;
        // Cooldown before next fishing session
        cooldown = cooldownTicks;
    }

    // --- phases ---

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

    private void approachShore() {
        if (shoreSpot == null) {
            phase = Phase.DONE;
            return;
        }

        // Ensure the spot still looks valid; bail if chunks gone or water disappeared
        if (!isAreaLoaded(shoreSpot, 1) || !isNavigable(shoreSpot) || !isWaterAheadOf(shoreSpot)) {
            phase = Phase.DONE;
            return;
        }

        double d2 = mob.getDistanceSqToCenter(shoreSpot);
        if (d2 > 2.0) {
            mob.getNavigator().tryMoveToXYZ(
                    shoreSpot.getX() + 0.5,
                    shoreSpot.getY(),
                    shoreSpot.getZ() + 0.5,
                    approachSpeed
            );
        } else {
            mob.getNavigator().clearPath();
            // Face the water
            faceNearestWater();
            // Equip the ghost rod (visual only)
            ensureGhostRodInHand();
            castTimer = castWindupTicks;
            phase = Phase.CAST_WINDUP;
        }
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
        faceNearestWater();
        if (mob.isInWater()) {
            phase = Phase.REEL_OUT; // bail and stow rod if we somehow ended up in water
            return;
        }

        // safety: if the water went away, or we slipped too far, end early
        if (shoreSpot == null || !isAreaLoaded(shoreSpot, 1) || mob.getDistanceSqToCenter(shoreSpot) > 9.0 || !isWaterAheadOf(shoreSpot)) {
            phase = Phase.REEL_OUT;
            return;
        }

        if (fishTimer <= 0) {
            phase = Phase.REEL_OUT;
        }
    }

    // --- helpers ---

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
        if (best != null) {
            double dx = best.getX() + 0.5 - mob.posX;
            double dz = best.getZ() + 0.5 - mob.posZ;
            float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            mob.rotationYaw = rotlerp(mob.rotationYaw, yaw, 8.0F);
            mob.renderYawOffset = rotlerp(mob.renderYawOffset, mob.rotationYaw, 8.0F);
            mob.rotationYawHead = rotlerp(mob.rotationYawHead, mob.renderYawOffset, 12.0F);
        }
    }

    private void playCastSound() {
        mob.playSound(SoundEvents.ENTITY_BOBBER_THROW, 0.6F, 0.95F + mob.getRNG().nextFloat() * 0.1F);
    }

    private void playReelSound() {
        mob.playSound(SoundEvents.ENTITY_BOBBER_RETRIEVE, 0.6F, 0.95F + mob.getRNG().nextFloat() * 0.1F);
    }

    // --- environment checks (vanilla water only; no chunk loads) ---

    private void maybePlayWaterPlop() {
        if (mob.getRNG().nextFloat() < 0.25f) {
            mob.playSound(SoundEvents.ENTITY_BOBBER_SPLASH, 0.5F, 0.9F + mob.getRNG().nextFloat() * 0.2F);
        }
    }

    private boolean isAreaLoaded(BlockPos pos, int r) {
        if (!(mob.world instanceof WorldServer)) return mob.world.isAreaLoaded(pos, r);
        WorldServer ws = (WorldServer) mob.world;
        int minCX = (pos.getX() - r) >> 4, maxCX = (pos.getX() + r) >> 4;
        int minCZ = (pos.getZ() - r) >> 4, maxCZ = (pos.getZ() + r) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!ws.getChunkProvider().chunkExists(cx, cz)) return false;
            }
        }
        return true;
    }

    private boolean isNavigable(BlockPos feet) {
        return isStandable(feet) || isSwimmable(feet);
    }

    private boolean isStandable(BlockPos feet) {
        World w = mob.world;
        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down())) return false;
        IBlockState below = w.getBlockState(feet.down());
        if (isLeafBlock(w, feet.down(), below)) return false;
        if (below.getMaterial().isLiquid()) return false;
        BlockFaceShape shape = below.getBlockFaceShape(w, feet.down(), EnumFacing.UP);
        boolean solidBelow = shape == BlockFaceShape.SOLID || below.getBlock() == Blocks.SNOW_LAYER
                || below.getBlock() == Blocks.ICE || below.getBlock() == Blocks.PACKED_ICE || below.getBlock() == Blocks.FROSTED_ICE
                || below.getBlock() == Blocks.GLASS || below.getBlock() == Blocks.STAINED_GLASS;
        IBlockState atFeet = w.getBlockState(feet);
        IBlockState atHead = w.getBlockState(feet.up());
        boolean free = (atFeet.getMaterial().isReplaceable() || atFeet.getCollisionBoundingBox(w, feet) == null)
                && (atHead.getMaterial().isReplaceable() || atHead.getCollisionBoundingBox(w, feet.up()) == null);
        return solidBelow && free;
    }

    private boolean isSwimmable(BlockPos feet) {
        World w = mob.world;
        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down())) return false;
        IBlockState f = w.getBlockState(feet);
        IBlockState h = w.getBlockState(feet.up());
        IBlockState b = w.getBlockState(feet.down());
        return isVanillaWater(f) && (isVanillaWater(h) || h.getMaterial().isReplaceable())
                && !isForbiddenLiquid(f) && !isForbiddenLiquid(h) && !isForbiddenLiquid(b);
    }

    private boolean isVanillaWater(IBlockState s) {
        Block b = s.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER;
    }

    private boolean isForbiddenLiquid(IBlockState s) {
        if (!s.getMaterial().isLiquid()) return false;
        return !isVanillaWater(s); // forbid all non-vanilla liquids for safety
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
                if (!isAreaLoaded(guessXZ, 1)) continue;

                // Resolve Y to a *dry standable* spot only (don’t accept swimmable here)
                BlockPos y = findSurfaceY(guessXZ);
                if (y == null) continue;
                if (!isDryStandable(y)) continue;

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
                if (!isAreaLoaded(col, 1)) continue;

                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos probe = col.down(dy);
                    IBlockState s = w.getBlockState(probe);
                    IBlockState above = w.getBlockState(probe.up());
                    if (isVanillaWater(s) && (isVanillaWater(above) || above.getMaterial().isReplaceable())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    private BlockPos findSurfaceY(BlockPos xzGuess) {
        World w = mob.world;
        if (!isAreaLoaded(xzGuess, 1)) return null;
        BlockPos top = w.getTopSolidOrLiquidBlock(xzGuess);

        // walk up a couple, then down a bit to settle on a navigable cell
        BlockPos p = top;
        for (int i = 0; i < 3; i++) {
            if (isStandable(p) || isSwimmable(p)) break;
            p = p.up();
            if (!isAreaLoaded(p, 1)) return null;
        }
        for (int i = 0; i < 8 && p.getY() > 1; i++) {
            if (isStandable(p) || isSwimmable(p)) break;
            p = p.down();
            if (!isAreaLoaded(p, 1)) return null;
        }
        return (isStandable(p) || isSwimmable(p)) ? p : null;
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
                if (!isAreaLoaded(col, 1)) continue;

                // check down up to 3 for a water surface
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos probe = col.down(dy);
                    IBlockState s = w.getBlockState(probe);
                    IBlockState above = w.getBlockState(probe.up());
                    if (isVanillaWater(s) && (isVanillaWater(above) || above.getMaterial().isReplaceable())) {
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

    private enum Phase {IDLE, APPROACH, CAST_WINDUP, FISHING, REEL_OUT, DONE}
}

