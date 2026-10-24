package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.equipment.TravellerEquipmentManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

import java.util.List;
import java.util.UUID;

/**
 * Shield stance of a unit holding a shield in its offhand. The block itself is vanilla's (durability,
 * attacker knockback) except its direction, see {@link #covers}; this class decides when to take the stance:
 * <ol>
 *   <li><b>guard</b> — a foe within a few blocks, or an arrow flying at the unit, after a human
 *       reaction delay. A raised shield never strikes, and the unit is slowed.</li>
 *   <li><b>exhaustion</b> — the shield drops after a random share of {@code shieldRaisedSeconds}, or
 *       after a few parries, and cannot be raised again for {@code shieldRestSeconds}. That window is
 *       when the unit strikes (and can be struck).</li>
 *   <li>a lull with no threat lowers it without any rest.</li>
 * </ol>
 */
public final class ShieldGuard {

    private static final UUID SLOW_ID = UUID.fromString("5c1f6a3e-7b0d-4e55-9a41-3d2f8e1b6c70");
    private static final double MELEE_RANGE_SQ = 4.5 * 4.5;
    private static final int REACTION_MIN = 6;
    private static final int REACTION_SPREAD = 11;
    /** Fatigue only builds while a foe is within striking distance (or arrows are flying at the unit). */
    private static final double ENGAGE_RANGE_SQ = 3.2 * 3.2;
    private static final int LULL_TICKS = 12;
    /** Longest the shield stays up waiting for a foe that never closes in. */
    private static final int APPROACH_CAP_TICKS = 60;
    /** After the last parry the shield stays up a beat: the foe is recovering, then the unit ripostes. */
    private static final int RIPOSTE_DELAY_MIN = 8;
    private static final int RIPOSTE_DELAY_SPREAD = 7;
    /** A lowered shield is followed by a hesitation before the unit may strike. */
    private static final int RECOVERY_MIN = 8;
    private static final int RECOVERY_SPREAD = 7;
    private static final int PARRIES_MIN = 2;
    private static final int PARRIES_SPREAD = 2;
    /** After being shot at, the unit watches archers drawing their bow for this long. */
    private static final int ALERT_TICKS = 100;
    private static final double ARCHER_WATCH_RANGE_SQ = 32.0 * 32.0;
    private static final int ARROW_SCAN_INTERVAL = 4;
    /** An arrow is seen from this far; a full-draw arrow then takes about 13 ticks to arrive. */
    private static final double ARROW_SCAN_RANGE = 40.0;
    private static final double ARROW_MAX_FLIGHT_TICKS = 25.0;
    /** Closest approach (blocks) still counted as aimed at the unit. */
    private static final double ARROW_MAX_MISS = 1.5;
    private static final double ARROW_MAX_Y_MISS = 1.8;
    private static final int ARROW_NOTICE_PERCENT = 70;
    /** Cosine of the half field of view (about 100 degrees each side). */
    private static final double FOV_MIN_COS = -0.17;

    private final EntityTraveller entity;
    private boolean raised;
    private int raisedTicks;
    private int raisedLimit;
    private int parries;
    private int parryLimit;
    private int restTicks;
    private int threatTicks;
    private int reactionNeeded;
    private int calmTicks;
    private int approachTicks;
    private int lowerAtTick;
    private int recoveryTicks;
    private int nextArrowScanTick;
    private Entity arrowThreat;
    private EntityLivingBase lastShooter;
    private int alertUntil;
    private boolean blockedThisHit;
    private int blockedTick = -1;
    private Entity facing;

    public ShieldGuard(EntityTraveller entity) {
        this.entity = entity;
    }

    public boolean isRaised() {
        return raised;
    }

    /** A unit strikes only with its shield down and after its recovery beat. */
    public boolean mayStrike() {
        return !raised && recoveryTicks <= 0;
    }

    /** Something shot at this unit (a hit, or an arrow seen coming): it now watches for the next draw. */
    public void onShotAt(EntityLivingBase shooter) {
        if (isFriendlyShooter(shooter)) return;
        lastShooter = shooter;
        alertUntil = entity.ticksExisted + ALERT_TICKS;
    }

    /** Called by the entity's damageShield: a hit was just absorbed. */
    public void onBlocked() {
        blockedThisHit = true;
        blockedTick = entity.ticksExisted;
        parries++;
        TravellerTracer.event(entity, "shield", "blocked a hit");
    }

    /** True during the tick a hit was absorbed: knockback and hurt sound of that hit check it. */
    public boolean blockedThisTick() {
        return blockedTick == entity.ticksExisted;
    }

    /** Yaw the body should hold while the shield is up (toward the threat), or NaN when there is none. */
    public float bodyFocusYaw() {
        if (!raised || facing == null) return Float.NaN;
        double dx = facing.posX - entity.posX;
        double dz = facing.posZ - entity.posZ;
        if (dx * dx + dz * dz < 0.0025) return Float.NaN;
        return (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
    }

    /**
     * Whether the shield stops this hit: what comes from the front half, like a player's, but of the body,
     * since vanilla's head-based test turns with a neck that twists 75 degrees and so covered the back.
     */
    public boolean covers(net.minecraft.util.DamageSource source) {
        if (source.isUnblockable() || !entity.isActiveItemStackBlocking()) return false;
        net.minecraft.util.math.Vec3d from = source.getDamageLocation();
        if (from == null) return false;
        float yaw = entity.renderYawOffset * 0.017453292F;
        return (from.x - entity.posX) * -net.minecraft.util.math.MathHelper.sin(yaw)
                + (from.z - entity.posZ) * net.minecraft.util.math.MathHelper.cos(yaw) > 0.0D;
    }

    /** True once if the last hit was absorbed by the shield (so armour is not worn for it). */
    public boolean consumeBlocked() {
        boolean b = blockedThisHit;
        blockedThisHit = false;
        return b;
    }

    /** Server side, every tick. */
    public void tick() {
        if (recoveryTicks > 0) recoveryTicks--;
        ItemStack offhand = entity.getHeldItemOffhand();
        if (!TravellerEquipmentManager.isShield(offhand) || entity.equipment.hasBowAnywhere()) {
            if (raised) lower(false);
            return;
        }
        if (restTicks > 0) restTicks--;

        Entity arrow = incomingArrow();
        EntityLivingBase foe = closeFoe();
        EntityLivingBase archer = drawingArcher();
        Entity threat = arrow != null ? arrow : archer != null ? archer : foe;
        boolean blocked = cannotGuard();

        if (raised) {
            if (!entity.isHandActive()) { // something else (a bow, a meal) took the hand
                lower(false);
                return;
            }
            boolean engaged = arrow != null || archer != null
                    || (foe != null && entity.getDistanceSq(foe) <= ENGAGE_RANGE_SQ);
            if (engaged) raisedTicks++;
            else approachTicks++;
            calmTicks = threat == null ? calmTicks + 1 : 0;
            if (lowerAtTick > 0) {
                if (entity.ticksExisted >= lowerAtTick) lower(true);
            } else if (raisedTicks >= raisedLimit) {
                lower(true); // exhausted: this is when the unit strikes, and can be struck
            } else if (parries >= parryLimit) {
                lowerAtTick = entity.ticksExisted + RIPOSTE_DELAY_MIN + entity.getRNG().nextInt(RIPOSTE_DELAY_SPREAD);
            } else if (blocked || calmTicks >= LULL_TICKS || approachTicks >= APPROACH_CAP_TICKS) {
                lower(false);
            }
            if (raised && threat != null) face(threat);
            return;
        }

        if (blocked || restTicks > 0 || threat == null) {
            threatTicks = 0;
            return;
        }
        if (threatTicks++ == 0) reactionNeeded = REACTION_MIN + entity.getRNG().nextInt(REACTION_SPREAD);
        if (threatTicks >= reactionNeeded) raise(threat);
    }

    private void raise(Entity toward) {
        entity.setActiveHand(EnumHand.OFF_HAND);
        if (!entity.isHandActive()) return;
        raised = true;
        raisedTicks = 0;
        calmTicks = 0;
        approachTicks = 0;
        lowerAtTick = 0;
        parries = 0;
        parryLimit = PARRIES_MIN + entity.getRNG().nextInt(PARRIES_SPREAD);
        raisedLimit = secondsToTicks(TravellersConfig.EQUIPMENT.shieldRaisedSeconds
                * (0.7f + 0.3f * entity.getRNG().nextFloat()));
        IAttributeInstance speed = entity.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(SLOW_ID) == null) {
            speed.applyModifier(new AttributeModifier(SLOW_ID, "travellers.shield_raised",
                    TravellersConfig.EQUIPMENT.shieldRaisedSpeedFactor - 1.0, 2).setSaved(false));
        }
        TravellerTracer.event(entity, "shield", "raised vs " + toward.getName());
        face(toward);
    }

    private void lower(boolean startRest) {
        if (raised) TravellerTracer.event(entity, "shield", startRest ? "lowered, resting" : "lowered");
        raised = false;
        facing = null;
        raisedTicks = 0;
        threatTicks = 0;
        lowerAtTick = 0;
        recoveryTicks = RECOVERY_MIN + entity.getRNG().nextInt(RECOVERY_SPREAD);
        if (startRest) restTicks = secondsToTicks(TravellersConfig.EQUIPMENT.shieldRestSeconds);
        if (entity.isHandActive() && entity.getActiveHand() == EnumHand.OFF_HAND) entity.resetActiveHand();
        IAttributeInstance speed = entity.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SLOW_ID);
    }

    /** The shield only stops what comes from the front: turn toward the threat. */
    private void face(Entity toward) {
        if (toward == null) return;
        facing = toward;
        entity.getLookHelper().setLookPosition(toward.posX, toward.posY + toward.getEyeHeight(), toward.posZ, 60.0F, 30.0F);
    }

    /** After being shot at: the attack target or last shooter, if it is drawing a bow within range. */
    private EntityLivingBase drawingArcher() {
        if (entity.ticksExisted >= alertUntil) return null;
        EntityLivingBase t = entity.getAttackTarget();
        if (isDrawingBow(t)) return t;
        return isDrawingBow(lastShooter) ? lastShooter : null;
    }

    private boolean isDrawingBow(EntityLivingBase e) {
        return e != null && e.isEntityAlive() && e.isHandActive()
                && e.getActiveItemStack().getItem() instanceof ItemBow
                && entity.getDistanceSq(e) <= ARCHER_WATCH_RANGE_SQ;
    }

    private EntityLivingBase closeFoe() {
        EntityLivingBase t = entity.getAttackTarget();
        if (t == null || !t.isEntityAlive()) return null;
        return entity.getDistanceSq(t) <= MELEE_RANGE_SQ ? t : null;
    }

    /** The nearest hostile arrow flying at the unit and about to arrive, or null. Rescanned every few ticks. */
    private Entity incomingArrow() {
        if (entity.ticksExisted < nextArrowScanTick) {
            return arrowThreat != null && arrowThreat.isEntityAlive() ? arrowThreat : null;
        }
        nextArrowScanTick = entity.ticksExisted + ARROW_SCAN_INTERVAL;
        arrowThreat = null;
        double bestTicks = ARROW_MAX_FLIGHT_TICKS;
        List<EntityArrow> arrows = entity.world.getEntitiesWithinAABB(EntityArrow.class,
                entity.getEntityBoundingBox().grow(ARROW_SCAN_RANGE, 12.0, ARROW_SCAN_RANGE));
        for (EntityArrow a : arrows) {
            double flightTicks = ticksToReach(a);
            if (flightTicks < 0 || flightTicks > bestTicks || isFriendlyShooter(a.shootingEntity)) continue;
            bestTicks = flightTicks;
            arrowThreat = a;
            if (a.shootingEntity instanceof EntityLivingBase) onShotAt((EntityLivingBase) a.shootingEntity);
        }
        return arrowThreat;
    }

    /**
     * Ticks until the arrow reaches this unit if it keeps its heading, or -1 when it will not hit it,
     * is stuck in the ground, is behind the unit, or simply goes unnoticed (a fixed share of arrows).
     */
    private double ticksToReach(EntityArrow a) {
        // A stuck arrow keeps its last motion vector but no longer moves.
        if (a.posX == a.prevPosX && a.posY == a.prevPosY && a.posZ == a.prevPosZ) return -1;
        double speedSq = a.motionX * a.motionX + a.motionZ * a.motionZ;
        if (speedSq < 0.04) return -1;
        double dx = entity.posX - a.posX;
        double dz = entity.posZ - a.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) return 0;
        double speed = Math.sqrt(speedSq);
        if ((dx * a.motionX + dz * a.motionZ) <= 0) return -1; // flying away
        double lateral = Math.abs(dx * a.motionZ - dz * a.motionX) / speed;
        if (lateral > ARROW_MAX_MISS + entity.width * 0.5) return -1; // aimed at something else
        double ticks = dist / speed;
        double yAtArrival = a.posY + a.motionY * ticks - 0.025 * ticks * ticks; // gravity drop
        if (Math.abs(yAtArrival - (entity.posY + entity.height * 0.5)) > ARROW_MAX_Y_MISS) return -1;
        if (!inFieldOfView(a)) return -1;
        // Attention: the same arrow is noticed or not by this unit, for good.
        if (Math.floorMod(a.getEntityId() * 31 + entity.getEntityId(), 100) >= ARROW_NOTICE_PERCENT) return -1;
        return ticks;
    }

    /** The unit only sees what is in front of it. */
    private boolean inFieldOfView(Entity e) {
        double dx = e.posX - entity.posX;
        double dz = e.posZ - entity.posZ;
        double n = Math.sqrt(dx * dx + dz * dz);
        if (n < 0.01) return true;
        net.minecraft.util.math.Vec3d look = entity.getLook(1.0F);
        double ln = Math.sqrt(look.x * look.x + look.z * look.z);
        if (ln < 0.01) return true;
        return (dx * look.x + dz * look.z) / (n * ln) > FOV_MIN_COS;
    }

    private boolean isFriendlyShooter(Entity shooter) {
        if (shooter == null) return false;
        if (shooter == entity) return true;
        if (shooter instanceof EntityTraveller) return !entity.isHostileUnit((EntityTraveller) shooter);
        return shooter instanceof EntityPlayer && entity instanceof EntityTravellerWorker
                && entity.isOwner((EntityPlayer) shooter);
    }

    private boolean cannotGuard() {
        if (entity.isAimingBow() || entity.isRiding() || entity.isElytraFlying() || entity.isPlayerSleeping()) return true;
        return !raised && entity.isHandActive(); // eating or fishing owns the hand
    }

    private static int secondsToTicks(float seconds) {
        return Math.max(1, Math.round(seconds * 20.0F));
    }
}
