package com.jubitus.travellers.entity.entityai.ambient;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.world.mill.VillageVisitIndex;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The only thing that turns a visitor's head during a village visit. Vanilla's look helper forgets
 * its target after one tick and relaxes back to the body, so a look has to be asked again every tick
 * or it reads as a twitch; this holds one target for a while and re-asks it every tick.
 *
 * <p>Priority: a focus the visit sets (talk partner, a player standing close, the panel or building
 * being visited) &gt; a short glance at someone passing &gt; the way ahead while walking &gt; looking
 * around calmly while standing.
 */
final class VisitGaze {

    enum Kind {
        IDLE, AHEAD, GLANCE, POINT, PLAYER, TALK
    }

    private static final double IDLE_ENTITY_RANGE = 12.0D;
    private static final double IDLE_BUILDING_RANGE = 24.0D;
    private static final int NOD_TICKS = 10;

    private final EntityTraveller mob;

    @Nullable
    private Kind focusKind;
    @Nullable
    private Entity focusEntity;
    @Nullable
    private Vec3d focusPos;

    @Nullable
    private Entity glanceEntity;
    private int glanceTicks;

    @Nullable
    private Entity idleEntity;
    @Nullable
    private Vec3d idlePos;
    private int idleHoldTicks;

    private int nodTicks;

    private Kind lastKind = Kind.IDLE;
    @Nullable
    private Vec3d lastTarget;

    VisitGaze(EntityTraveller mob) {
        this.mob = mob;
    }

    void focus(Kind kind, Entity e) {
        focusKind = kind;
        focusEntity = e;
        focusPos = null;
    }

    void focus(Kind kind, BlockPos p) {
        focusKind = kind;
        focusEntity = null;
        focusPos = new Vec3d(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
    }

    void clearFocus() {
        focusKind = null;
        focusEntity = null;
        focusPos = null;
    }

    /** Clears the focus only if it is of this kind (so one owner can't drop another's focus). */
    void clearFocus(Kind kind) {
        if (focusKind == kind) clearFocus();
    }

    @Nullable
    Kind getFocusKind() {
        return focusKind;
    }

    void glance(Entity e, int ticks) {
        glanceEntity = e;
        glanceTicks = ticks;
    }

    boolean isGlancing() {
        return glanceTicks > 0 && glanceEntity != null;
    }

    void nod() {
        if (nodTicks <= 0) nodTicks = NOD_TICKS;
    }

    void reset() {
        clearFocus();
        glanceEntity = null;
        glanceTicks = 0;
        idleEntity = null;
        idlePos = null;
        idleHoldTicks = 0;
        nodTicks = 0;
        lastTarget = null;
    }

    /** Once per tick of the visit: decides what to look at and tells the look helper. */
    void tick(List<EntityLivingBase> nearby, @Nullable VillageVisitIndex.Settlement settlement) {
        Vec3d target = null;
        Kind kind;

        if (focusKind != null && !isValid(focusEntity, 16.0D) && focusPos == null) clearFocus();
        if (focusKind != null) {
            kind = focusKind;
            target = focusEntity != null ? eyes(focusEntity) : focusPos;
        } else if (glanceTicks > 0 && isValid(glanceEntity, 12.0D)) {
            glanceTicks--;
            kind = Kind.GLANCE;
            target = eyes(glanceEntity);
        } else {
            glanceTicks = 0;
            glanceEntity = null;
            Vec3d ahead = aheadPoint();
            if (ahead != null) {
                kind = Kind.AHEAD;
                target = ahead;
                idleHoldTicks = 0;
            } else {
                kind = Kind.IDLE;
                if (--idleHoldTicks <= 0 || (idleEntity != null && !isValid(idleEntity, IDLE_ENTITY_RANGE))) {
                    pickIdle(nearby, settlement);
                }
                target = idleEntity != null ? eyes(idleEntity) : idlePos;
            }
        }
        if (target == null) return;

        double y = target.y;
        if (nodTicks > 0) {
            // A small dip of the head: the look point sinks and comes back over NOD_TICKS.
            double progress = (NOD_TICKS - nodTicks) / (double) NOD_TICKS;
            double dist = Math.max(1.0D, Math.sqrt(mob.getDistanceSq(target.x, mob.posY, target.z)));
            y -= Math.sin(progress * Math.PI) * 0.3D * dist;
            nodTicks--;
        }
        float speed = (float) TravellersConfig.VILLAGE_VISIT.gazeTurnSpeed;
        if (kind == Kind.GLANCE || kind == Kind.PLAYER) speed *= 1.5F;
        mob.getLookHelper().setLookPosition(target.x, y, target.z, speed, 30.0F);
        mob.noteHeadWriter("gaze");
        lastKind = kind;
        lastTarget = target;
    }

    String describe() {
        if (lastTarget == null) return lastKind.name();
        return String.format(Locale.ROOT, "%s@%.1f,%.1f,%.1f", lastKind.name(), lastTarget.x, lastTarget.y, lastTarget.z);
    }

    /** A few path nodes ahead while walking, so the head leads the turn instead of lagging behind it. */
    @Nullable
    private Vec3d aheadPoint() {
        Path path = mob.getNavigator().getPath();
        if (path == null || path.isFinished()) return null;
        int idx = Math.min(path.getCurrentPathIndex() + 3, path.getCurrentPathLength() - 1);
        if (idx < 0) return null;
        PathPoint p = path.getPathPointFromIndex(idx);
        double dx = p.x + 0.5D - mob.posX;
        double dz = p.z + 0.5D - mob.posZ;
        if (dx * dx + dz * dz < 1.0D) return null;
        return new Vec3d(p.x + 0.5D, mob.posY + mob.getEyeHeight() - 0.2D, p.z + 0.5D);
    }

    private void pickIdle(List<EntityLivingBase> nearby, @Nullable VillageVisitIndex.Settlement s) {
        Random rng = mob.getRNG();
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        double min = Math.min(cfg.gazeHoldMinSeconds, cfg.gazeHoldMaxSeconds);
        double max = Math.max(cfg.gazeHoldMinSeconds, cfg.gazeHoldMaxSeconds);
        idleHoldTicks = (int) Math.round(20.0D * (min + rng.nextDouble() * (max - min)));
        idleEntity = null;
        idlePos = null;

        double roll = rng.nextDouble();
        if (roll < 0.45D && !nearby.isEmpty()) {
            List<EntityLivingBase> close = new ArrayList<>();
            for (EntityLivingBase e : nearby) {
                if (isValid(e, IDLE_ENTITY_RANGE)) close.add(e);
            }
            if (!close.isEmpty()) {
                idleEntity = close.get(rng.nextInt(close.size()));
                return;
            }
        }
        if (roll < 0.70D && s != null && !s.buildings.isEmpty()) {
            List<BlockPos> close = new ArrayList<>();
            for (VillageVisitIndex.Target t : s.buildings) {
                BlockPos p = t.entrance != null ? t.entrance : t.point;
                if (p.distanceSq(mob.posX, mob.posY, mob.posZ) <= IDLE_BUILDING_RANGE * IDLE_BUILDING_RANGE) close.add(p);
            }
            if (!close.isEmpty()) {
                BlockPos p = close.get(rng.nextInt(close.size()));
                idlePos = new Vec3d(p.getX() + 0.5D, p.getY() + 1.5D, p.getZ() + 0.5D);
                return;
            }
        }
        // Somewhere around, mostly in front: a person looking about, not spinning on the spot.
        double yaw = Math.toRadians(mob.renderYawOffset + (rng.nextDouble() * 2.0D - 1.0D) * 100.0D);
        double dist = 6.0D;
        idlePos = new Vec3d(mob.posX - Math.sin(yaw) * dist,
                mob.posY + mob.getEyeHeight() + (rng.nextDouble() * 1.5D - 1.0D),
                mob.posZ + Math.cos(yaw) * dist);
    }

    private boolean isValid(@Nullable Entity e, double range) {
        return e != null && e.isEntityAlive() && e != mob && mob.getDistanceSq(e) <= range * range;
    }

    private static Vec3d eyes(Entity e) {
        return new Vec3d(e.posX, e.posY + e.getEyeHeight(), e.posZ);
    }
}
