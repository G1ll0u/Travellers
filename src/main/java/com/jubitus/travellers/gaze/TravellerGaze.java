package com.jubitus.travellers.gaze;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.millenaire.common.entity.MillVillager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Decides what a traveller looks at when no AI task asks for something specific: a focus a task
 * holds (talk partner, a player it stopped for, a building), a short glance at someone passing, the
 * way ahead while walking, or looking around while standing. {@link TravellerLookHelper} turns the head.
 */
public final class TravellerGaze {

    public enum Kind {
        IDLE, AHEAD, GLANCE, INTEREST, POINT, TALK, TASK
    }

    private static final int SCAN_PERIOD = 30;
    private static final int NOD_TICKS = 10;
    private static final double FOCUS_RANGE = 20.0D;
    private static final double GLANCE_RANGE = 16.0D;

    private final EntityTraveller mob;

    @Nullable
    private Kind focusKind;
    @Nullable
    private Entity focusEntity;
    private double focusX, focusY, focusZ;

    @Nullable
    private Entity glanceEntity;
    private boolean hasGlancePos;
    private double glanceX, glanceY, glanceZ;
    private int glanceTicks;
    private int glanceCooldown;

    @Nullable
    private Entity idleEntity;
    private boolean hasIdlePos;
    private double idleX, idleY, idleZ;
    private int holdTicks;

    private int awayTicks;
    private int nodTicks;

    private List<BlockPos> places = Collections.emptyList();
    private List<EntityLivingBase> nearby = Collections.emptyList();
    private int scanTicker;
    private boolean watched;

    private Kind kind = Kind.IDLE;
    double tx, ty, tz;
    private boolean hasTarget;

    public TravellerGaze(EntityTraveller mob) {
        this.mob = mob;
        this.scanTicker = Math.floorMod(mob.getEntityId(), SCAN_PERIOD);
    }

    // ---------------------------------------------------------------- what tasks can ask

    public void focus(Kind kind, Entity e) {
        focusKind = kind;
        focusEntity = e;
    }

    public void focus(Kind kind, BlockPos p) {
        focusKind = kind;
        focusEntity = null;
        focusX = p.getX() + 0.5D;
        focusY = p.getY() + 0.5D;
        focusZ = p.getZ() + 0.5D;
    }

    public void clearFocus() {
        focusKind = null;
        focusEntity = null;
        awayTicks = 0;
    }

    /** Clears the focus only if it is of this kind, so one owner can't drop another's focus. */
    public void clearFocus(Kind kind) {
        if (focusKind == kind) clearFocus();
    }

    @Nullable
    public Kind getFocusKind() {
        return focusKind;
    }

    public void glance(Entity e, int ticks) {
        glanceEntity = e;
        hasGlancePos = false;
        glanceTicks = ticks;
    }

    public boolean isGlancing() {
        return glanceTicks > 0 && (glanceEntity != null || hasGlancePos);
    }

    public void nod() {
        if (nodTicks <= 0) nodTicks = NOD_TICKS;
    }

    /** Spots worth a look while standing around here (a village's buildings). */
    public void setPlaces(List<BlockPos> places) {
        this.places = places;
    }

    public void reset() {
        clearFocus();
        glanceEntity = null;
        hasGlancePos = false;
        glanceTicks = 0;
        idleEntity = null;
        hasIdlePos = false;
        holdTicks = 0;
        nodTicks = 0;
        places = Collections.emptyList();
    }

    void noteTask() {
        kind = Kind.TASK;
    }

    Kind kind() {
        return kind;
    }

    public String describe() {
        if (!hasTarget) return kind.name();
        return String.format(Locale.ROOT, "%s@%.1f,%.1f,%.1f", kind.name(), tx, ty, tz);
    }

    // ---------------------------------------------------------------- deciding

    /** Once per tick when no task asked for a look. False = nothing in particular, relax the head. */
    boolean resolve() {
        if (glanceCooldown > 0) glanceCooldown--;
        hasTarget = pick();
        if (hasTarget && nodTicks > 0) {
            // A small dip of the head: the look point sinks and comes back.
            double progress = (NOD_TICKS - nodTicks) / (double) NOD_TICKS;
            double dist = Math.max(1.0D, Math.sqrt(sq(tx - mob.posX) + sq(tz - mob.posZ)));
            ty -= Math.sin(progress * Math.PI) * 0.3D * dist;
            nodTicks--;
        }
        return hasTarget;
    }

    private boolean pick() {
        if (focusKind != null && focusEntity != null && !isValid(focusEntity, FOCUS_RANGE)) clearFocus();

        if (glanceTicks > 0 && (hasGlancePos || isValid(glanceEntity, GLANCE_RANGE))) {
            glanceTicks--;
            kind = Kind.GLANCE;
            return hasGlancePos ? set(glanceX, glanceY, glanceZ) : eyes(glanceEntity);
        }
        glanceTicks = 0;
        glanceEntity = null;
        hasGlancePos = false;

        if (focusKind != null) {
            // Someone it stopped for still gets the odd look away, the way people do.
            if (focusKind == Kind.INTEREST && watched && --awayTicks <= 0) {
                awayTicks = 80 + mob.getRNG().nextInt(120);
                if (lookAway()) return pick();
            }
            kind = focusKind;
            if (focusEntity != null) return eyes(focusEntity);
            return set(focusX, focusY, focusZ);
        }

        boolean walking = isWalking();
        if (watched && glanceCooldown <= 0 && --holdTicks <= 0) {
            holdTicks = holdDuration();
            if (mob.getRNG().nextDouble() < TravellersConfig.GAZE.glanceChance && tryGlance(walking)) return pick();
            if (!walking) pickIdle();
        }
        if (walking) {
            kind = Kind.AHEAD;
            idleEntity = null;
            hasIdlePos = false;
            // Unwatched, the head simply relaxes onto the body, which already faces the way.
            return watched && aheadPoint();
        }
        kind = Kind.IDLE;
        if (!watched) return false;
        if (idleEntity != null) {
            if (isValid(idleEntity, GLANCE_RANGE)) return eyes(idleEntity);
            idleEntity = null;
        }
        return hasIdlePos && set(idleX, idleY, idleZ);
    }

    /** Every server tick: refreshes what is around now and then, and lets the greeter react to it. */
    public void tickAwareness() {
        if (--scanTicker > 0) return;
        scanTicker = SCAN_PERIOD;
        refreshAwareness();
        if (watched) mob.getGreeter().scan(nearby);
    }

    /** What the last awareness scan saw (empty while no player is near). */
    public List<EntityLivingBase> getNearby() {
        return nearby;
    }

    /** Only scans its surroundings while a player is close enough to see the head move. */
    private void refreshAwareness() {
        double watch = TravellersConfig.GAZE.watchRange;
        watched = mob.world.isAnyPlayerWithinRangeAt(mob.posX, mob.posY, mob.posZ, watch);
        if (!watched) {
            nearby = Collections.emptyList();
            return;
        }
        double r = TravellersConfig.GAZE.awarenessRadius;
        nearby = mob.world.getEntitiesWithinAABB(EntityLivingBase.class,
                mob.getEntityBoundingBox().grow(r, 4.0D, r), this::isNoticeable);
    }

    private boolean isNoticeable(@Nullable EntityLivingBase e) {
        if (e == null || e == mob || !e.isEntityAlive() || e.isInvisible()) return false;
        if (e.isRidingOrBeingRiddenBy(mob)) return false;
        if (e instanceof EntityPlayer) return !((EntityPlayer) e).isSpectator();
        return e instanceof EntityTraveller || e instanceof MillVillager || e instanceof IMob || e instanceof EntityAnimal;
    }

    /** A short look at whoever stands out most around it: a player, someone coming its way, a threat. */
    private boolean tryGlance(boolean walking) {
        Entity best = null;
        double bestScore = 0.0D;
        Random rng = mob.getRNG();
        for (EntityLivingBase e : nearby) {
            if (!isValid(e, GLANCE_RANGE)) continue;
            double score = salience(e, walking);
            if (score <= 0.0D) continue;
            score *= 0.5D + rng.nextDouble();
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best == null || !mob.getEntitySenses().canSee(best)) return false;
        glance(best, 25 + rng.nextInt(45));
        glanceCooldown = 20;
        return true;
    }

    private double salience(EntityLivingBase e, boolean walking) {
        double dx = e.posX - mob.posX;
        double dz = e.posZ - mob.posZ;
        double d2 = dx * dx + dz * dz;
        if (walking) {
            // Walking, it only notices what is ahead or beside it.
            float bearing = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            if (Math.abs(MathHelper.wrapDegrees(bearing - mob.rotationYaw)) > 110.0F) return 0.0D;
        }
        double score;
        if (e instanceof EntityPlayer) score = 4.0D;
        else if (e instanceof IMob) score = 3.0D;
        else if (e instanceof EntityTraveller || e instanceof MillVillager) score = 2.0D;
        else score = 0.8D;
        // Someone coming closer draws the eye.
        double closing = -(dx * (com.jubitus.travellers.unit.EntityMotion.velX(e) - mob.motionX) + dz * (com.jubitus.travellers.unit.EntityMotion.velZ(e) - mob.motionZ));
        if (closing > 0.02D && d2 < 100.0D) score += 2.0D;
        return score / (1.0D + d2 / 64.0D);
    }

    /** Briefly looks at something else than the focus; false when there is nothing to look at. */
    private boolean lookAway() {
        Random rng = mob.getRNG();
        List<EntityLivingBase> others = new ArrayList<>();
        for (EntityLivingBase e : nearby) {
            if (e != focusEntity && isValid(e, GLANCE_RANGE)) others.add(e);
        }
        if (!others.isEmpty() && rng.nextBoolean()) {
            glance(others.get(rng.nextInt(others.size())), 20 + rng.nextInt(25));
            return true;
        }
        if (focusEntity == null) return false;
        // Off into the distance, roughly to one side of the person it is facing.
        double base = Math.atan2(focusEntity.posZ - mob.posZ, focusEntity.posX - mob.posX);
        double a = base + (rng.nextBoolean() ? 1 : -1) * (0.6D + rng.nextDouble() * 0.8D);
        glanceEntity = null;
        hasGlancePos = true;
        glanceX = mob.posX + Math.cos(a) * 12.0D;
        glanceY = mob.posY + mob.getEyeHeight() + (rng.nextDouble() - 0.5D);
        glanceZ = mob.posZ + Math.sin(a) * 12.0D;
        glanceTicks = 20 + rng.nextInt(25);
        return true;
    }

    private void pickIdle() {
        Random rng = mob.getRNG();
        idleEntity = null;
        hasIdlePos = false;

        double roll = rng.nextDouble();
        if (roll < 0.40D) {
            List<EntityLivingBase> close = new ArrayList<>();
            for (EntityLivingBase e : nearby) {
                if (isValid(e, 12.0D)) close.add(e);
            }
            if (!close.isEmpty()) {
                idleEntity = close.get(rng.nextInt(close.size()));
                return;
            }
        }
        if (roll < 0.65D && !places.isEmpty()) {
            List<BlockPos> close = new ArrayList<>();
            for (BlockPos p : places) {
                if (p.distanceSq(mob.posX, mob.posY, mob.posZ) <= 24.0D * 24.0D) close.add(p);
            }
            if (!close.isEmpty()) {
                BlockPos p = close.get(rng.nextInt(close.size()));
                hasIdlePos = true;
                idleX = p.getX() + 0.5D;
                idleY = p.getY() + 1.5D;
                idleZ = p.getZ() + 0.5D;
                return;
            }
        }
        // Somewhere around, mostly in front: a person looking about, not spinning on the spot.
        double yaw = Math.toRadians(mob.renderYawOffset + (rng.nextDouble() * 2.0D - 1.0D) * 100.0D);
        double dist = 4.0D + rng.nextDouble() * 10.0D;
        hasIdlePos = true;
        idleX = mob.posX - Math.sin(yaw) * dist;
        idleY = mob.posY + mob.getEyeHeight() + (rng.nextDouble() * 1.5D - 1.0D);
        idleZ = mob.posZ + Math.cos(yaw) * dist;
    }

    private int holdDuration() {
        TravellersConfig.Gaze cfg = TravellersConfig.GAZE;
        double min = Math.min(cfg.holdMinSeconds, cfg.holdMaxSeconds);
        double max = Math.max(cfg.holdMinSeconds, cfg.holdMaxSeconds);
        return (int) Math.round(20.0D * (min + mob.getRNG().nextDouble() * (max - min)));
    }

    private boolean isWalking() {
        if (!mob.getNavigator().noPath()) return true;
        return mob.motionX * mob.motionX + mob.motionZ * mob.motionZ > 0.0004D;
    }

    /** A few path nodes ahead, so the head leads a turn instead of lagging behind it. */
    private boolean aheadPoint() {
        Path path = mob.getNavigator().getPath();
        if (path == null || path.isFinished()) return false;
        int idx = Math.min(path.getCurrentPathIndex() + 3, path.getCurrentPathLength() - 1);
        if (idx < 0) return false;
        PathPoint p = path.getPathPointFromIndex(idx);
        double dx = p.x + 0.5D - mob.posX;
        double dz = p.z + 0.5D - mob.posZ;
        if (dx * dx + dz * dz < 1.0D) return false;
        return set(p.x + 0.5D, mob.posY + mob.getEyeHeight() - 0.2D, p.z + 0.5D);
    }

    private boolean isValid(@Nullable Entity e, double range) {
        return e != null && e.isEntityAlive() && e != mob && mob.getDistanceSq(e) <= range * range;
    }

    private boolean eyes(Entity e) {
        return set(e.posX, e.posY + e.getEyeHeight(), e.posZ);
    }

    private boolean set(double x, double y, double z) {
        tx = x;
        ty = y;
        tz = z;
        return true;
    }

    private static double sq(double v) {
        return v * v;
    }
}
