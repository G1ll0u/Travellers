package com.jubitus.travellers.ambient;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.gaze.TravellerGaze;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.Footing;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.UnitSpeed;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Occasional fishing break while travelling: walk to a dry bank spot, cast a bobber onto open water
 * visible from the eyes, wait with a few tugs, reel in and travel on.
 */
public class EntityAIFishWhileTravel extends EntityAIBase {

    private static final int VALIDATE_INTERVAL = 10;
    // Give up walking to the bank, or turning to face the water, after this many ticks.
    private static final int APPROACH_TIMEOUT = 200;
    private static final int TURN_TIMEOUT = 60;
    // The cast waits until the head points within this many degrees of the target.
    private static final float AIM_TOLERANCE = 10.0F;
    // Vanilla EntityAITasks asks a waiting task's shouldExecute only every 3 ticks.
    private static final int TICKS_PER_CHECK = 3;
    // Horizontal cast distance in blocks, and the preferred one when several targets fit.
    private static final int CAST_MIN = 2;
    private static final int CAST_MAX = 5;
    private static final double CAST_PREFERRED = 3.5;
    // Deepest water surface accepted below the bank's feet level (fishing from a ledge).
    private static final int MAX_DROP = 4;
    // Bank spots and line-of-sight ray traces tried per search, to bound the cost of one search.
    private static final int MAX_BANK_TRIES = 8;
    private static final int MAX_SIGHT_TRIES = 12;
    // Height of the floating bobber above the water block's base (a still surface sits at ~0.89).
    private static final double BOBBER_FLOAT_Y = 0.8;
    private static final int NO_WATER = Integer.MIN_VALUE;

    private final EntityTraveller mob;
    private BlockPos bankSpot;
    private BlockPos castTarget;
    @Nullable
    private EntityTravellerBobber bobber;
    private Phase phase = Phase.IDLE;
    private int approachTicks;
    private int turnTicks;
    private int castTimer;
    private int fishTimer;
    private int reelTimer;
    private int validateDelay;
    private int nextAllowedTick;

    public EntityAIFishWhileTravel(EntityTraveller mob) {
        this.mob = mob;
        this.setMutexBits(1 | 2);
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.ticksExisted < nextAllowedTick) return false;
        double chancePerCheck = 1.0 - Math.pow(1.0 - TravellersConfig.FISHING.fishStartChancePerTick, TICKS_PER_CHECK);
        if (mob.getRNG().nextDouble() >= chancePerCheck) return false;

        if (mob.isInWater() || mob.isRiding()) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;
        if (mob.getTravelRecoverTicks() > 0) return false;
        if (mob.hasPlayerPassenger()) return false;
        // Only while travelling somewhere, not while idling in a village.
        if (mob.isIdlingAtStop()) return false;
        if (mob.getTravelCommon().getTargetVillage() == null) return false;

        if (!findBankSpot(mob.getPosition(), TravellersConfig.FISHING.fishWaterScanRadius)) return false;
        phase = Phase.APPROACH;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    @Override
    public void startExecuting() {
        mob.scheduleNextTravelAmbient();
        mob.getNavigator().clearPath();
        mob.getTravelCommon().setCurrentWaypoint(null);
        approachTicks = turnTicks = castTimer = fishTimer = reelTimer = validateDelay = 0;
    }

    @Override
    public void resetTask() {
        removeBobber();
        stowGhostRod();
        mob.getNavigator().clearPath();
        mob.getTravelCommon().setCurrentWaypoint(null);
        mob.getGaze().clearFocus(TravellerGaze.Kind.POINT);
        phase = Phase.IDLE;
        nextAllowedTick = mob.ticksExisted + TravellersConfig.FISHING.fishCooldownTicks;
    }

    @Override
    public void updateTask() {
        switch (phase) {
            case APPROACH:
                approachBank();
                break;
            case TURN:
                doTurn();
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

    private void approachBank() {
        if (validateDelay-- <= 0) {
            validateDelay = VALIDATE_INTERVAL;
            if (!LoadedChunks.isAreaLoaded(mob.world, bankSpot, CAST_MAX + 1)
                    || !Footing.canStandAt(mob.world, bankSpot) || !isOpenWater(castTarget)) {
                phase = Phase.DONE;
                return;
            }
        }
        if (++approachTicks > APPROACH_TIMEOUT) {
            phase = Phase.DONE;
            return;
        }

        if (mob.getDistanceSqToCenter(bankSpot) > 2.0) {
            if (mob.getNavigator().noPath() && !mob.getNavigator().tryMoveToXYZ(
                    bankSpot.getX() + 0.5, bankSpot.getY(), bankSpot.getZ() + 0.5,
                    UnitSpeed.nav(TravellersConfig.SPEED.fishApproachPercent))) {
                phase = Phase.DONE;
            }
            return;
        }

        mob.getNavigator().clearPath();
        // Re-aim from where he really stands: the bank search assumed the block centre.
        castTarget = findCastTarget(bankSpot, mob.getPositionEyes(1.0F));
        if (castTarget == null) {
            phase = Phase.DONE;
            return;
        }
        faceCastTarget();
        ensureGhostRodInHand();
        phase = Phase.TURN;
    }

    private void doTurn() {
        faceCastTarget();
        if (isFacingCastTarget()) {
            castTimer = TravellersConfig.FISHING.fishCastWindupTicks;
            phase = Phase.CAST_WINDUP;
        } else if (++turnTicks > TURN_TIMEOUT) {
            phase = Phase.DONE;
        }
    }

    private void doCastWindup() {
        faceCastTarget();
        if (--castTimer > 0) return;

        mob.doInteractSwing(EnumHand.MAIN_HAND);
        mob.playSound(SoundEvents.ENTITY_BOBBER_THROW, 0.6F, 0.95F + mob.getRNG().nextFloat() * 0.1F);
        bobber = new EntityTravellerBobber(mob.world, mob, new Vec3d(
                castTarget.getX() + 0.5, castTarget.getY() + BOBBER_FLOAT_Y, castTarget.getZ() + 0.5));
        mob.world.spawnEntity(bobber);

        int min = TravellersConfig.FISHING.fishMinTimeTicks;
        int max = TravellersConfig.FISHING.fishMaxTimeTicks;
        fishTimer = min + mob.getRNG().nextInt(Math.max(1, max - min + 1));
        reelTimer = TravellersConfig.FISHING.fishReelEveryTicks;
        validateDelay = VALIDATE_INTERVAL;
        phase = Phase.FISHING;
    }

    private void doFishingLoop() {
        mob.getNavigator().clearPath();
        faceCastTarget();

        if (--reelTimer <= 0) {
            tug();
            reelTimer = TravellersConfig.FISHING.fishReelEveryTicks + mob.getRNG().nextInt(20);
        }

        boolean waterGone = false;
        if (validateDelay-- <= 0) {
            validateDelay = VALIDATE_INTERVAL;
            waterGone = !isOpenWater(castTarget);
        }
        if (--fishTimer <= 0 || waterGone || mob.isInWater() || bobber == null || bobber.isDead
                || mob.getDistanceSqToCenter(bankSpot) > 9.0) {
            phase = Phase.REEL_OUT;
        }
    }

    private void doReelOut() {
        mob.doInteractSwing(EnumHand.MAIN_HAND);
        mob.playSound(SoundEvents.ENTITY_BOBBER_RETRIEVE, 0.6F, 0.95F + mob.getRNG().nextFloat() * 0.1F);
        removeBobber();
        stowGhostRod();
        phase = Phase.DONE;
    }

    // --- helpers ---

    private void tug() {
        mob.doInteractSwing(EnumHand.MAIN_HAND);
        if (bobber == null) return;
        bobber.dip();
        if (mob.getRNG().nextFloat() < 0.25F) {
            mob.world.playSound(null, bobber.posX, bobber.posY, bobber.posZ, SoundEvents.ENTITY_BOBBER_SPLASH,
                    SoundCategory.NEUTRAL, 0.5F, 0.9F + mob.getRNG().nextFloat() * 0.2F);
        }
    }

    // The gaze turns head and body; passers-by still get a glance.
    private void faceCastTarget() {
        mob.getGaze().focus(TravellerGaze.Kind.POINT, castTarget);
    }

    /** Head on the target and the body done turning, so the cast leaves straight toward the water. */
    private boolean isFacingCastTarget() {
        double dx = castTarget.getX() + 0.5 - mob.posX;
        double dz = castTarget.getZ() + 0.5 - mob.posZ;
        float bearing = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        return Math.abs(MathHelper.wrapDegrees(bearing - mob.rotationYawHead)) < AIM_TOLERANCE
                && Math.abs(MathHelper.wrapDegrees(mob.renderYawOffset - mob.prevRenderYawOffset)) < 1.0F;
    }

    private void removeBobber() {
        if (bobber != null) {
            bobber.setDead();
            bobber = null;
        }
    }

    private void stowGhostRod() {
        if (mob.isGhostRod(mob.getHeldItemMainhand())) {
            mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    private void ensureGhostRodInHand() {
        ItemStack hand = mob.getHeldItemMainhand();
        if (mob.isFishingRodLike(hand)) {
            // A real rod in hand goes back to the pack; the visible rod is always a ghost copy.
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(mob.inventory, hand.copy(), false);
            if (remainder.isEmpty()) {
                mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        mob.setHeldItem(EnumHand.MAIN_HAND, mob.makeGhostRod(new ItemStack(Items.FISHING_ROD)));
    }

    // --- water search (vanilla water only; never loads a chunk) ---

    /** Nearest dry bank spot next to open water that also has a visible cast target; sets bankSpot and castTarget. */
    private boolean findBankSpot(BlockPos around, int radius) {
        World w = mob.world;
        List<BlockPos> banks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = around.getX() + dx;
                int z = around.getZ() + dz;
                if (!LoadedChunks.isAreaLoaded(w, new BlockPos(x, around.getY(), z), 1)) continue;
                int y = ColumnGround.topLayer(w, x, z, true);
                if (y == ColumnGround.NONE) continue;
                BlockPos stand = new BlockPos(x, y, z);
                if (Footing.canStandAt(w, stand) && touchesOpenWater(stand)) banks.add(stand);
            }
        }
        banks.sort(Comparator.comparingDouble(p -> around.distanceSq(p)));

        for (int i = 0; i < banks.size() && i < MAX_BANK_TRIES; i++) {
            BlockPos stand = banks.get(i);
            Vec3d eye = new Vec3d(stand.getX() + 0.5, stand.getY() + mob.getEyeHeight(), stand.getZ() + 0.5);
            BlockPos target = findCastTarget(stand, eye);
            if (target != null) {
                bankSpot = stand;
                castTarget = target;
                return true;
            }
        }
        return false;
    }

    private boolean touchesOpenWater(BlockPos stand) {
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            BlockPos side = stand.offset(f);
            if (openWaterY(side.getX(), side.getZ(), stand) != NO_WATER) return true;
        }
        return false;
    }

    /**
     * Open water surface CAST_MIN..CAST_MAX blocks away (horizontally) from {@code stand}, seen from
     * {@code eye} without anything in between; the one closest to the preferred cast distance wins.
     */
    @Nullable
    private BlockPos findCastTarget(BlockPos stand, Vec3d eye) {
        World w = mob.world;
        if (!LoadedChunks.isAreaLoaded(w, stand, CAST_MAX + 1)) return null;

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -CAST_MAX; dx <= CAST_MAX; dx++) {
            for (int dz = -CAST_MAX; dz <= CAST_MAX; dz++) {
                int h2 = dx * dx + dz * dz;
                if (h2 < CAST_MIN * CAST_MIN || h2 > CAST_MAX * CAST_MAX) continue;
                int y = openWaterY(stand.getX() + dx, stand.getZ() + dz, stand);
                if (y != NO_WATER) candidates.add(new BlockPos(stand.getX() + dx, y, stand.getZ() + dz));
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> Math.abs(
                Math.sqrt(sq(p.getX() - stand.getX()) + sq(p.getZ() - stand.getZ())) - CAST_PREFERRED)));

        for (int i = 0; i < candidates.size() && i < MAX_SIGHT_TRIES; i++) {
            if (canSee(eye, candidates.get(i))) return candidates.get(i);
        }
        return null;
    }

    /**
     * Y of the water surface in column (x, z) when the column is open from the stand's head height
     * down to it, at most MAX_DROP below the stand's feet; NO_WATER otherwise (ground, cave water, lava…).
     */
    private int openWaterY(int x, int z, BlockPos stand) {
        World w = mob.world;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = stand.getY() + 1; y >= stand.getY() - MAX_DROP; y--) {
            p.setPos(x, y, z);
            if (BlockRules.isWater(w.getBlockState(p))) return y;
            if (!Footing.hasRoom(w, p)) return NO_WATER;
        }
        return NO_WATER;
    }

    // The ray stops on still water only, so flowing water (falls, rapids) never qualifies.
    private boolean canSee(Vec3d eye, BlockPos water) {
        World w = mob.world;
        Vec3d to = new Vec3d(water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
        RayTraceResult hit = w.rayTraceBlocks(eye, to, true, true, false);
        return hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK
                && hit.getBlockPos().getY() == water.getY()
                && BlockRules.isWater(w.getBlockState(hit.getBlockPos()));
    }

    private boolean isOpenWater(BlockPos pos) {
        World w = mob.world;
        return LoadedChunks.isLoaded(w, pos) && BlockRules.isWater(w.getBlockState(pos)) && Footing.hasRoom(w, pos.up());
    }

    private static double sq(double v) {
        return v * v;
    }

    private enum Phase {IDLE, APPROACH, TURN, CAST_WINDUP, FISHING, REEL_OUT, DONE}
}
