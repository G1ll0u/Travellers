package com.jubitus.travellers.entity.entityai.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityMoveHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Unified door / fence-gate handling for every EntityTraveller subclass. Replaces the old
 * EntityAIOpenFenceGate + vanilla EntityAIOpenDoor pair.
 *
 * <ul>
 *   <li>Opens a closed doorway the tick the entity reaches one it is heading through - detected
 *       from where the entity is actually trying to move (move helper target, motion, or a
 *       horizontal collision), so it works the same under plain pathing, travel AI, follow
 *       steering and the stable job.</li>
 *   <li>Registers the open doorway with {@link TravellerDoorways}; the central per-world tick
 *       there is what closes it again once the entity is through and nobody else needs it. This
 *       task never closes anything itself, so a follower that wobbles or gets its AI starved can
 *       no longer leave a door hanging open.</li>
 *   <li>mutex 0 - never blocks travel, combat or look AI. Inert offscreen; never loads a chunk.</li>
 * </ul>
 */
public class EntityAIPassDoor extends EntityAIBase {

    private static final double OPEN_REACH = 1.9D;          // how close before we open a doorway ahead
    private static final double OPEN_REACH_GATE = 2.75D;    // ... a fence gate: earlier, its 1.5-tall
    //                                                        collision box makes a mob hop if it
    //                                                        touches the gate before it swings open
    private static final double OPEN_REACH_STUCK = 2.6D;    // ... when shoved against it
    private static final double CLEAR_DIST = 1.4D;          // how far past before we let go of it
    private static final int RELEASE_LOITER_TICKS = 200;    // give up holding if we never cross
    private static final int REOPEN_COOLDOWN = 40;

    private final EntityTraveller entity;

    private BlockPos target;   // scan result carried from shouldExecute() to startExecuting()
    private BlockPos held;     // doorway currently open in our name
    private double fromX;
    private double fromZ;
    private int heldTicks;
    private BlockPos lastReleased;
    private int reopenCooldown;

    public EntityAIPassDoor(EntityTraveller entity) {
        this.entity = entity;
        this.setMutexBits(0);
    }

    private static boolean enabled() {
        return TravellersConfig.GENERAL.enableTravellerOpenDoors
                || TravellersConfig.GENERAL.enableTravellerOpenFenceGates;
    }

    @Override
    public boolean shouldExecute() {
        if (entity.world == null || entity.world.isRemote || !entity.isEntityAlive()) return false;
        if (!enabled() || !entity.isInPlayerViewDistanceApprox()) return false;
        if (reopenCooldown > 0) reopenCooldown--;
        if (held != null) return true;
        // Scan every tick while stuck or actively walking a route (open the doorway before we reach
        // it); only idle-throttle an entity that is neither.
        boolean eager = entity.collidedHorizontally
                || (entity.getNavigator() != null && !entity.getNavigator().noPath());
        if (!eager && (entity.ticksExisted + entity.getEntityId()) % 3 != 0) return false;
        target = scan();
        return target != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return held != null && entity.isEntityAlive() && !entity.world.isRemote;
    }

    @Override
    public void startExecuting() {
        if (target == null) target = scan();
        if (target == null) return;
        TravellerDoorways.setOpen(entity.world, target, true);
        TravellerDoorways.acquire(entity.world, target, entity.getEntityId());
        held = target;
        target = null;
        fromX = entity.posX;
        fromZ = entity.posZ;
        heldTicks = 0;
    }

    @Override
    public void updateTask() {
        if (reopenCooldown > 0) reopenCooldown--;
        if (held == null) return;
        heldTicks++;

        if (!entity.world.isBlockLoaded(held, false)
                || !TravellerDoorways.isDoorway(entity.world.getBlockState(held))) {
            letGo(false);
            return;
        }

        TravellerDoorways.touch(entity.world, held, entity.getEntityId());

        double dcx = entity.posX - (held.getX() + 0.5D);
        double dcz = entity.posZ - (held.getZ() + 0.5D);
        double horiz = Math.sqrt(dcx * dcx + dcz * dcz);
        double approachX = (held.getX() + 0.5D) - fromX;
        double approachZ = (held.getZ() + 0.5D) - fromZ;
        boolean farSide = (dcx * approachX + dcz * approachZ) < 0.0D;

        boolean passed = horiz > CLEAR_DIST && (farSide || horiz > 3.0D);
        boolean gaveUp = heldTicks > RELEASE_LOITER_TICKS && horiz > CLEAR_DIST && !occupiesDoor();

        if (passed || gaveUp) letGo(true);
    }

    @Override
    public void resetTask() {
        if (held != null) letGo(false);
        target = null;
    }

    private void letGo(boolean cooldown) {
        if (held != null) {
            TravellerDoorways.release(entity.world, held, entity.getEntityId());
            if (cooldown) {
                lastReleased = held;
                reopenCooldown = REOPEN_COOLDOWN;
            }
        }
        held = null;
        heldTicks = 0;
    }

    private boolean occupiesDoor() {
        return Math.abs(entity.posX - (held.getX() + 0.5D)) < 0.6D
                && Math.abs(entity.posZ - (held.getZ() + 0.5D)) < 0.6D;
    }

    /** Direction the entity is actually trying to go, or null when it is not trying to move. */
    private boolean intent(double[] out) {
        EntityMoveHelper mh = entity.getMoveHelper();
        if (mh != null && mh.isUpdating()) {
            double dx = mh.getX() - entity.posX;
            double dz = mh.getZ() - entity.posZ;
            if (dx * dx + dz * dz > 1.0E-4D) {
                normalize(out, dx, dz);
                return true;
            }
        }
        if (entity.motionX * entity.motionX + entity.motionZ * entity.motionZ > 0.0025D) {
            normalize(out, entity.motionX, entity.motionZ);
            return true;
        }
        if (entity.collidedHorizontally) {
            double yaw = Math.toRadians(entity.rotationYaw);
            normalize(out, -Math.sin(yaw), Math.cos(yaw));
            return true;
        }
        return false;
    }

    private static void normalize(double[] out, double x, double z) {
        double len = Math.sqrt(x * x + z * z);
        out[0] = x / len;
        out[1] = z / len;
    }

    /** Nearest closed doorway the entity is heading into (or a co-held open one), or null. */
    private BlockPos scan() {
        double[] dir = new double[2];
        if (!intent(dir)) return null;

        double baseReach = entity.collidedHorizontally ? OPEN_REACH_STUCK : OPEN_REACH;
        double maxReach = Math.max(baseReach, OPEN_REACH_GATE);

        int ex = MathHelper.floor(entity.posX);
        int ey = MathHelper.floor(entity.posY);
        int ez = MathHelper.floor(entity.posZ);
        int fx = ex + (int) Math.round(dir[0] * 2.0D);
        int fz = ez + (int) Math.round(dir[1] * 2.0D);

        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;

        for (int cx = Math.min(ex, fx) - 1; cx <= Math.max(ex, fx) + 1; cx++) {
            for (int cz = Math.min(ez, fz) - 1; cz <= Math.max(ez, fz) + 1; cz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = new BlockPos(cx, ey + dy, cz);
                    if (!entity.world.isBlockLoaded(p, false)) continue;
                    IBlockState st = entity.world.getBlockState(p);
                    if (!TravellerDoorways.isDoorway(st)) continue;
                    if (!typeEnabled(st)) continue;

                    BlockPos key = TravellerDoorways.normalize(entity.world, p);
                    if (key == null) continue;

                    double ddx = key.getX() + 0.5D - entity.posX;
                    double ddz = key.getZ() + 0.5D - entity.posZ;
                    double d2 = ddx * ddx + ddz * ddz;
                    if (d2 >= bestSq) continue;
                    double reach = TravellerDoorways.isGate(st) ? maxReach : baseReach;
                    if (d2 > reach * reach) continue;

                    boolean atDoor = d2 < 0.6D * 0.6D;
                    if (!atDoor) {
                        double dot = (ddx * dir[0] + ddz * dir[1]) / Math.max(1.0E-4D, Math.sqrt(d2));
                        if (dot < -0.2D) continue;              // clearly behind us
                    }

                    boolean open = TravellerDoorways.isOpen(entity.world, key);
                    if (open && !TravellerDoorways.isHeld(entity.world, key)) continue; // player's, leave it
                    if (!open && key.equals(lastReleased) && reopenCooldown > 0
                            && !entity.collidedHorizontally) continue;                  // anti open/close jitter

                    best = key;
                    bestSq = d2;
                }
            }
        }
        return best;
    }

    private boolean typeEnabled(IBlockState st) {
        return TravellerDoorways.isGate(st)
                ? TravellersConfig.GENERAL.enableTravellerOpenFenceGates
                : TravellersConfig.GENERAL.enableTravellerOpenDoors;
    }
}
