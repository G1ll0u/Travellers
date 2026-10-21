package com.jubitus.travellers.combat.ai;

import com.jubitus.travellers.worldmap.stream.MapStrikeLog;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;


/**
 * Safety-first ranged AI for 1.12.2 travellers, with vertical aim, enforced facing, and strafing.
 * <p>
 * Behaviour, in order of priority each tick:
 * - The bow is drawn (real vanilla setActiveHand state, via {@link EntityTraveller#setAimingBow}) for
 * the entire time this AI is running, LOS or not - so a shot can go out the instant LOS returns and
 * so third-party mods that react to "using an item" (ambient bow-pull sounds, the bow's own string
 * texture) see the traveller as actually drawing, not just posing.
 * - With LOS: kite to hold a preferred distance band between min/max range. A friendly blocking the
 * shot line is first handled "for free" by lofting a high arc over them ({@link BowShotLine#canLoftOverBlocker}
 * picks the other root of the same ballistic equation used to aim); only when no arc can clear it
 * (e.g. a low ceiling) does the archer physically sidestep to open the angle.
 * - Without LOS: reposition toward the target faster than normal (a short grace period first, to
 * avoid twitchy repathing on brief flicker), still fully drawn and ready.
 * - HOLD-attitude and escort workers ({@link ArcherMovement#standsGround}) skip kiting/backstep/distance-drift entirely and
 * hold their ground - the lofted shot covers most blocked-line cases without moving at all, and the
 * rare last-resort sidestep is small and tethered to the worker's own formation slot. This is what
 * lets a player park an archer group on a tower without them wandering off the edge.
 * <p>
 * Min/max range gates hand-off to melee (too close) or target drop (too far) - both stay outside
 * this class, driven by {@link EntityTraveller#getAttackTarget()} range checks elsewhere.
 */
public class EntityAIRangedBowTravellerSafe extends EntityAIBase {

    // --- accuracy tuning ---
    private static final float ARROW_VEL = BowBallistics.ARROW_VEL;
    private static final float INACC_NEAR = 1.5F;    // close-range spread
    private static final float INACC_FAR = 3.1F;    // long-range spread
    private static final int LOS_REACQUIRE_GRACE_TICKS = 6; // brief hold-still before committing to reposition
    private static final int AIM_SETTLE_MIN_TICKS = 3;
    private static final int AIM_SETTLE_MAX_TICKS = 6;
    private static final float LOB_INACCURACY_BONUS = 1.4F;  // a lofted shot is harder to land than a flat one
    // Brief lowered-bow window right after firing, so the string/nock visibly resets and redraws per
    // shot (like a skeleton) instead of staying frozen at full draw for the whole engagement. Short
    // enough that it's always well clear of shootIntervalTicks (min 10) before the next shot is due.
    private static final int BOW_RELAX_TICKS = 8;
    // An archer that cannot hit its target for this long drops it and lets the normal scan pick a visible one.
    private static final int NO_SHOT_SWITCH_TICKS = 3 * 20;
    private static final int UNHITTABLE_IGNORE_TICKS = 4 * 20;
    private final EntityTraveller mob;
    private final double minDist;
    private final double maxDist;
    private final double minDistSq;
    private final double maxDistSq;
    private final double meleeHandOffSq; // slightly > minDistSq recommended
    private final int shootIntervalTicks; // cadence
    private final boolean ghostEquipBow;
    private final BowShotLine shotLine;
    private final ArcherMovement movement;
    private final BowBallistics.VelocityTracker velocity = new BowBallistics.VelocityTracker();
    private int coolDown;
    private int aimWarmup;
    private int steadyTicksLeft = 0;
    private int noLOSTicks = 0;
    private int unableToHitTicks = 0; // consecutive ticks without LOS or with a shot line that cannot be cleared
    private boolean wasSeeing = false;
    private int bowRelaxTicksLeft = 0; // >0 while the bow is deliberately let down after a shot

    public EntityAIRangedBowTravellerSafe(EntityTraveller owner,
                                          double minDist,
                                          double maxDist,
                                          int shootIntervalTicks,
                                          double meleeHandOff,
                                          boolean ghostEquipBow) {
        this.mob = owner;
        this.minDist = minDist;
        this.maxDist = maxDist;
        this.minDistSq = minDist * minDist;
        this.maxDistSq = maxDist * maxDist;
        this.meleeHandOffSq = meleeHandOff * meleeHandOff;
        this.shootIntervalTicks = Math.max(10, shootIntervalTicks);
        this.ghostEquipBow = ghostEquipBow;
        this.shotLine = new BowShotLine(owner, owner.getAggroBlacklist());
        this.movement = new ArcherMovement(owner, shotLine, minDist, maxDist);
        this.setMutexBits(1 | 2); // move + look
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (TravellersDerivedConfig.isPeacefulBlocked(mob.world)) return false;
        if (mob.isAutoEating()) return false;

        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;
        if (!mob.hasBowAnywherePublic()) return false;

        double d2 = mob.getDistanceSq(tgt);
        if (d2 > maxDistSq) {
            // Just stop this AI; don't clear target here or the horde tracker will grab control.
            return false;
        }
        return !(d2 < meleeHandOffSq); // melee owns close range
        // <-- do NOT require LOS here: an archer should keep closing/repositioning toward a target
        // it can't currently see, not give up the moment LOS blips.
    }

    @Override
    public boolean shouldContinueExecuting() {
        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;
        double d2 = mob.getDistanceSq(tgt);
        if (d2 > maxDistSq) return false;
        return !(d2 < meleeHandOffSq); // likewise, don't require LOS
    }

    @Override
    public void startExecuting() {
        steadyTicksLeft = 0;
        aimWarmup = ArcherMovement.randBetween(mob, AIM_SETTLE_MIN_TICKS, AIM_SETTLE_MAX_TICKS);
        movement.start();
        noLOSTicks = 0;
        unableToHitTicks = 0;
        shotLine.reset();
        bowRelaxTicksLeft = 0;
        wasSeeing = false;

        mob.getNavigator().clearPath(); // we'll handle micro-movement
        ensureBowInHand();
        mob.setAimingBow(true);
        mob.setSprinting(false);
        mob.delaySwordHideVisual();
    }

    @Override
    public void resetTask() {
        mob.setAimingBow(false);
        mob.setSprinting(false);
        // put away ghost bow if needed
        ItemStack hand = mob.getHeldItemMainhand();
        if (!hand.isEmpty() && hand.getItem() instanceof ItemBow && isGhostBow(hand)) {
            mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        mob.getMoveHelper().strafe(0.0F, 0.0F);
    }


    @Override
    public void updateTask() {
        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null) return;

        // Bow stays drawn for the whole time this AI runs, LOS or not - see class javadoc - except for
        // a brief relax right after a shot (see #performShot), so the string/nock visibly cycles per
        // shot like a skeleton instead of staying permanently at full draw.
        ensureBowInHand();
        if (bowRelaxTicksLeft > 0) {
            bowRelaxTicksLeft--;
        } else if (!mob.isAimingBow()) {
            mob.setAimingBow(true);
        }

        // Readiness (cooldown) isn't gated on LOS: an archer that's been blind for a while but had
        // its cadence timer run out can fire the instant it sees the target again, no extra wait.
        if (coolDown > 0) coolDown--;

        boolean see = mob.getEntitySenses().canSee(tgt);
        double d2 = mob.getDistanceSq(tgt);
        double d = Math.sqrt(d2);

        if (see) {
            noLOSTicks = 0;
            if (!wasSeeing) {
                // Just regained LOS: give the aim a short beat to settle before firing.
                aimWarmup = Math.max(aimWarmup, ArcherMovement.randBetween(mob, AIM_SETTLE_MIN_TICKS, AIM_SETTLE_MAX_TICKS));
            }
            wasSeeing = true;

            mob.setSprinting(false);

            boolean blocked = shotLine.tickShotBlocked(tgt);
            // A blocked shot isn't necessarily a dead end: try lofting an arc over the blocker first
            // (free - no movement needed, so it's also what keeps a HOLD archer from having to break
            // rank at all). Only fall back to physically sidestepping when no arc can clear it.
            boolean lofted = blocked && shotLine.canLoftOverBlocker(tgt);
            boolean needsClearing = blocked && !lofted;
            if (needsClearing) {
                if (++unableToHitTicks >= NO_SHOT_SWITCH_TICKS && giveUpUnhittableTarget(tgt)) return;
            } else {
                unableToHitTicks = 0;
            }

            // Face the actual angle we're about to shoot at - flat at the target normally, tilted up
            // to match the arc when lofting, so the pose never disagrees with the arrow's path.
            movement.hardFaceTarget(tgt, 48.0F, 36.0F, 75.0F, lofted ? shotLine.lobPitchDeg() : null);

            if (movement.standsGround()) {
                movement.applyHoldMovement(needsClearing);
            } else {
                movement.applyKitingMovement(d, needsClearing);
            }

            if (needsClearing) {
                return; // movement above is already biased toward clearing the shot line
            }
            if (aimWarmup > 0) {
                aimWarmup--;
                return;
            }
            if (coolDown > 0) {
                return;
            }
            if (d2 < minDistSq || d2 > maxDistSq) return;

            boolean targetMoving = Math.abs(tgt.posX - tgt.prevPosX) + Math.abs(tgt.posZ - tgt.prevPosZ) > 0.02;

            // Only steady briefly if stationary AND already in the preferred band.
            // Outside the band, keep moving; inside, 1-tick micro-settle for a cleaner shot.
            double preferred = movement.preferredDistance();
            double band = (maxDist - minDist) * 0.15;
            boolean inBand = d >= preferred - band && d <= preferred + band;

            if (!targetMoving && inBand) {
                if (steadyTicksLeft <= 0) {
                    steadyTicksLeft = 1;
                    mob.getMoveHelper().strafe(0f, 0f);
                    return;
                } else {
                    steadyTicksLeft--;
                }
            } else {
                steadyTicksLeft = 0; // no steady while closing or target moving
            }

            performShot(tgt, d, lofted);
            coolDown = shootIntervalTicks;
            mob.setAimingBow(false);
            bowRelaxTicksLeft = BOW_RELAX_TICKS;
            return;
        }

        // --- NO LOS ---
        wasSeeing = false;
        noLOSTicks++;
        if (++unableToHitTicks >= NO_SHOT_SWITCH_TICKS && giveUpUnhittableTarget(tgt)) return;
        movement.hardFaceTarget(tgt, 32.0F, 22.0F, 75.0F, null);

        // HOLD attitude: never actually walk to chase LOS back (see class javadoc for what does
        // stay active - kiting/sidestep while visible are untouched, only this blind pursuit stops).
        if (noLOSTicks < LOS_REACQUIRE_GRACE_TICKS || movement.standsGround()) {
            // Short grace: hold still briefly (foliage flicker, a door swinging shut, etc.) rather
            // than immediately committing to a reposition.
            mob.getNavigator().clearPath();
            mob.getMoveHelper().strafe(0, 0);
            return;
        }

        movement.repositionForLOS(tgt, d);
    }

    /** Drops a target that cannot be hit; the boosted scan then prefers one that can (LOS is part of its filter). */
    private boolean giveUpUnhittableTarget(EntityLivingBase tgt) {
        mob.ignoreTarget(tgt, UNHITTABLE_IGNORE_TICKS);
        mob.combat.noteDrop("archer: no shot");
        mob.setAttackTarget(null);
        mob.requestImmediateRetarget();
        unableToHitTicks = 0;
        return true;
    }

    private void performShot(EntityLivingBase target, double distance, boolean lofted) {
        World w = mob.world;
        EntityTippedArrow arrow = new EntityTippedArrow(w, mob);
        arrow.setEnchantmentEffectsFromEntity(mob, 1.0F);
        arrow.pickupStatus = EntityTippedArrow.PickupStatus.DISALLOWED;

        // Shooter eye
        final double sx = mob.posX;
        final double sy = mob.posY + mob.getEyeHeight();
        final double sz = mob.posZ;

        // Base aim (target mid)
        double bx = target.posX;
        double by = target.posY + target.getEyeHeight() * 0.5;
        double bz = target.posZ;

        // Smoothed target velocity (blocks/tick)
        Vec3d vT = velocity.sample(target, mob.ticksExisted);
        BowBallistics.Shot shot = BowBallistics.solveShot(sx, sy, sz, bx, by, bz, vT, lofted);

        // Calm just before release (1 tick)
        mob.getMoveHelper().strafe(0.0F, 0.0F);
        double base = mob.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        mob.setAIMoveSpeed((float) Math.min(base * 0.60D, 0.22D));

        // Distance-scaled inaccuracy (keeps some misses)
        double dNorm = Math.min(1.0, shot.horizontalRange / 24.0);
        float inaccuracy = (float) (INACC_NEAR + (INACC_FAR - INACC_NEAR) * dNorm);
        if (lofted) inaccuracy += LOB_INACCURACY_BONUS; // an arcing shot is harder to land cleanly

        // Fire
        arrow.shoot(shot.dx, shot.dy, shot.dz, ARROW_VEL, inaccuracy);

        // Player-damage scaling (including the owner-specific friendly-fire rate) is applied
        // uniformly in TravellerFriendlyFireHandler based on whoever the arrow actually hits —
        // "target" here is only the intended target, which can differ from the real victim
        // (e.g. the owner standing in the shot line), so scaling it here would silently miss that case.
        int powerLevel = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                net.minecraft.init.Enchantments.POWER, resolveActualBow());
        double desiredFinal = (TravellersConfig.COMBAT.rangedBaseDamage
                + powerLevel * TravellersConfig.COMBAT.rangedPowerDamagePerLevel)
                * TravellersConfig.COMBAT.rangedDamageMultiplier;

        double speed = Math.sqrt(arrow.motionX * arrow.motionX
                + arrow.motionY * arrow.motionY
                + arrow.motionZ * arrow.motionZ);

        if (speed < 1.0e-6) speed = 1.0; // safety

        arrow.setDamage(desiredFinal / speed);

        // Also kill crit randomness (optional but recommended for "consistent")
        arrow.setIsCritical(false);


        w.playSound(null, mob.posX, mob.posY, mob.posZ, SoundEvents.ENTITY_SKELETON_SHOOT,
                SoundCategory.HOSTILE, 1.0F, 1.0F / (mob.getRNG().nextFloat() * 0.4F + 0.8F));
        w.spawnEntity(arrow);
        MapStrikeLog.record(MapStrikeLog.ARROW, mob.dimension, w.getTotalWorldTime(), mob.getUniqueID(),
                mob.posX, mob.posZ, target.posX, target.posZ);
        mob.swingArm(EnumHand.MAIN_HAND);
    }


    private ItemStack resolveActualBow() {
        ItemStack hand = mob.getHeldItemMainhand();
        if (!hand.isEmpty() && hand.getItem() instanceof ItemBow && !isGhostBow(hand)) return hand;
        return mob.findBowInBackpackPublic();
    }

    private boolean isGhostBow(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemBow && s.hasTagCompound() && s.getTagCompound().getBoolean("TravellerGhostBow");
    }

    private void ensureBowInHand() {
        ItemStack hand = mob.getHeldItemMainhand();
        boolean holdingBow = !hand.isEmpty() && hand.getItem() instanceof ItemBow;
        if (holdingBow) return;
        if (!ghostEquipBow) return;
        ItemStack bow = mob.findBowInBackpackPublic();
        if (!bow.isEmpty()) mob.setHeldItem(EnumHand.MAIN_HAND, mob.makeGhostBow(bow));
    }
}
