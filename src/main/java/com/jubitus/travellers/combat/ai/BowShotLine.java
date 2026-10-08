package com.jubitus.travellers.combat.ai;

import com.jubitus.travellers.combat.lists.EntityRuleList;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;
import java.util.List;

/** Is an ally standing in the archer's shot line, and can a lofted arc or a sidestep clear it. */
final class BowShotLine {

    private static final int BLOCK_CHECK_INTERVAL_TICKS = 3; // throttle the ally-in-the-way entity scan

    private final EntityTraveller mob;
    private final EntityRuleList blacklist;
    // Cached result of the last blocking-ally scan (see #tickShotBlocked)
    private int blockCheckCooldown = 0;
    private int blockedSidestepDir = 0; // 0 = clear, else the direction to sidestep to clear the shot
    @Nullable
    private Entity blockedByEntity = null; // the ally currently in the shot line, if any
    private float lobPitchDeg = 0f; // launch pitch (MC convention: negative = up) of the last validated lofted shot

    BowShotLine(EntityTraveller mob, EntityRuleList blacklist) {
        this.mob = mob;
        this.blacklist = blacklist;
    }

    void reset() {
        blockCheckCooldown = 0;
        blockedSidestepDir = 0;
    }

    /** The strafe direction (+1/-1) that clears the last detected blocker, 0 when clear. */
    int sidestepDir() {
        return blockedSidestepDir;
    }

    float lobPitchDeg() {
        return lobPitchDeg;
    }

    /**
     * Throttled wrapper around {@link #findBlockingSidestepDir}: the ally-in-the-way scan is an
     * entity AABB query, cheap once but not worth repeating every single tick for every archer.
     */
    boolean tickShotBlocked(EntityLivingBase target) {
        if (blockCheckCooldown > 0) {
            blockCheckCooldown--;
            return blockedSidestepDir != 0;
        }
        blockCheckCooldown = BLOCK_CHECK_INTERVAL_TICKS;
        blockedSidestepDir = findBlockingSidestepDir(target);
        return blockedSidestepDir != 0;
    }

    /**
     * Whether a high-arc trajectory can clear the current blocking ally ({@link #blockedByEntity})
     * without physically moving. Cheap on purpose - shares the same throttle as the blocker scan
     * itself ({@link #tickShotBlocked}), so this only runs a handful of times a second even with a
     * large HOLD archer group.
     */
    boolean canLoftOverBlocker(EntityLivingBase target) {
        Entity blocker = blockedByEntity;
        if (blocker == null) return false;

        double sx = mob.posX, sy = mob.posY + mob.getEyeHeight(), sz = mob.posZ;
        double bx = target.posX, by = target.posY + target.getEyeHeight() * 0.5, bz = target.posZ;
        BowBallistics.LobArc arc = BowBallistics.solveLob(sx, sy, sz, bx, by, bz,
                blocker.posX, blocker.posZ, blocker.getEntityBoundingBox().maxY);
        if (arc == null) return false;

        boolean clear = columnClear(blocker.posX, blocker.posZ, arc.arcY) && columnClear(arc.midX, arc.midZ, arc.arcY);
        if (clear) lobPitchDeg = (float) -arc.launchAngleDeg; // MC pitch: negative looks up
        return clear;
    }

    /** Short vertical scan for a solid ceiling around the arc's height at one XZ point. */
    private boolean columnClear(double x, double z, double y) {
        BlockPos base = new BlockPos(x, y, z);
        for (int dy = 0; dy <= 2; dy++) {
            if (mob.world.getBlockState(base.up(dy)).getMaterial().blocksMovement()) return false;
        }
        return true;
    }

    /**
     * Scans for a protected ally (traveller or blacklisted entity) standing in the shot line.
     *
     * @return 0 if clear, otherwise the strafe direction (+1/-1) that widens the angle away from the
     * blocker fastest.
     */
    private int findBlockingSidestepDir(EntityLivingBase target) {
        blockedByEntity = null;

        Vec3d from = new Vec3d(mob.posX, mob.posY + mob.getEyeHeight(), mob.posZ);
        Vec3d to = new Vec3d(target.posX, target.posY + target.getEyeHeight() * 0.5, target.posZ);

        if (from.squareDistanceTo(to) < 1.0E-6D) return 0;

        // Broad phase: bigger swept volume
        AxisAlignedBB swept = new AxisAlignedBB(
                Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z),
                Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z)
        ).grow(1.2D, 0.6D, 1.2D); // wider and taller than before

        List<Entity> list = mob.world.getEntitiesWithinAABBExcludingEntity(mob, swept);
        if (list.isEmpty()) return 0;

        double dx = to.x - from.x, dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double lx = (len > 1e-6) ? -dz / len : 0.0;
        double lz = (len > 1e-6) ? dx / len : 0.0;

        for (Entity e : list) {
            if (!e.isEntityAlive() || e == target) continue;

            // Only care about allies / protected entities
            boolean protectedAlly =
                    (e instanceof EntityTraveller)
                            || (e instanceof EntityLivingBase && blacklist != null && blacklist.matches(e));

            if (!protectedAlly) continue;

            // Expand their hitbox a bit to be extra safe
            AxisAlignedBB bb = e.getEntityBoundingBox().grow(0.4D, 0.5D, 0.4D);

            // Does our shot segment intersect this grown AABB?
            if (bb.calculateIntercept(from, to) != null) {
                blockedByEntity = e;
                // Which side of the shot line the blocker sits on; step to the opposite side to
                // widen the angle away from it as fast as possible.
                double ex = e.posX - from.x, ez = e.posZ - from.z;
                double lateral = ex * lx + ez * lz;
                return lateral >= 0 ? -1 : 1;
            }
        }
        return 0;
    }
}
