package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.utils.mobs.TravellerBlacklist;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Safety-first ranged AI for 1.12.2 travellers, with vertical aim, enforced facing, and strafing.
 * <p>
 * Features:
 * - Runs only if the traveller has a bow (in hand or backpack; will ghost-equip for visuals).
 * - Min/max range gates; hands off to melee when too close; drops target when too far.
 * - Always faces target while aiming (overrides travel-facing while in combat).
 * - Proper vertical aim; uses target eye/mid height and vanilla bow power mapping.
 * - Strafes left/right while aiming, with occasional backstep when too close.
 * - Holds fire if a Traveller or blacklisted entity is along the shot line.
 */
public class EntityAIRangedBowTravellerSafe extends EntityAIBase {

    private static final double BLOCK_WIDTH = 0.6D; // shot safety tube
    private final EntityTraveller mob;
    private final TravellerBlacklist blacklist;
    private final double minDistSq;
    private final double maxDistSq;
    private final double meleeHandOffSq; // slightly > minDistSq recommended
    private final int shootIntervalTicks; // cadence
    // Strafing knobs (feel free to tune)
    private final float strafeSpeed = 0.32f;     // 0.28–0.40 feels good in 1.12
    private final int strafeMinTicks = 20;       // 1.0s
    private final int strafeMaxTicks = 40;       // 2.0s
    private final float strafeInput = 0.25f;      // [-1..1] input strength
    private final double strafeAbsSpeed = 0.26D; // absolute cap while strafing (≈ skeleton pace)
    private final int backstepInterval = 30;     // try at most once every 1.5s
    private final float backstepSpeed = 0.22f;   // gentle backward slide
    private final boolean ghostEquipBow;
    private int coolDown;
    private int aimWarmup;
    private int strafeDir = 1;     // +1 right, -1 left
    private int strafeTicks = 0;   // time left for current strafe direction
    private int backstepTicker = 0;

    public EntityAIRangedBowTravellerSafe(EntityTraveller owner,
                                          double minDist,
                                          double maxDist,
                                          int shootIntervalTicks,
                                          double meleeHandOff,
                                          boolean ghostEquipBow) {
        this.mob = owner;
        this.blacklist = TravellersModConfig.TRAVELLER_BLACKLIST;
        this.minDistSq = minDist * minDist;
        this.maxDistSq = maxDist * maxDist;
        this.meleeHandOffSq = meleeHandOff * meleeHandOff;
        this.shootIntervalTicks = Math.max(10, shootIntervalTicks);
        this.ghostEquipBow = ghostEquipBow;
        this.setMutexBits(1 | 2); // move + look
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.world.getDifficulty().getId() <= 0) return false; // PEACEFUL
        if (mob.isAutoEating()) return false;

        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;
        if (!mob.hasBowAnywherePublic()) return false;

        double d2 = mob.getDistanceSq(tgt);
        if (d2 > maxDistSq) { // too far → drop target and bail
            mob.setAttackTarget(null);
            mob.setInCombat(false);
            return false;
        }
        if (d2 < meleeHandOffSq) return false;   // let melee own the tick
        return mob.getEntitySenses().canSee(tgt);
    }

    @Override
    public boolean shouldContinueExecuting() {
        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;
        double d2 = mob.getDistanceSq(tgt);
        if (d2 > maxDistSq) return false;
        if (d2 < meleeHandOffSq) return false;
        return mob.getEntitySenses().canSee(tgt);
    }

    @Override
    public void startExecuting() {
        coolDown = 0;
        aimWarmup = 8 + mob.getRNG().nextInt(6);
        strafeDir = mob.getRNG().nextBoolean() ? 1 : -1;
        strafeTicks = randBetween(strafeMinTicks, strafeMaxTicks);
        backstepTicker = 0;

        mob.getNavigator().clearPath(); // we’ll handle micro-movement
        ensureBowInHand();
        mob.setAimingBow(true);
        mob.delaySwordHideVisual();
    }

    @Override
    public void resetTask() {
        mob.setAimingBow(false);
        // put away ghost bow if needed
        ItemStack hand = mob.getHeldItemMainhand();
        if (!hand.isEmpty() && hand.getItem() instanceof ItemBow && isGhostBow(hand)) {
            mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        // stop strafing
        mob.getMoveHelper().strafe(0.0F, 0.0F);
    }

    @Override
    public void updateTask() {
        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null) return;

        // --- Facing: ENFORCE yaw & pitch toward the target (hard face) ---
        hardFaceTarget(tgt, 48.0F, 36.0F, 75.0F);

        // Keep bow visible + aiming pose
        ensureBowInHand();
        if (!mob.isAimingBow()) mob.setAimingBow(true);

        // --- Micro-movement: strafe/circle the target ---
        double d2 = mob.getDistanceSq(tgt);
        double d = Math.sqrt(d2);
        applyStrafeMovement(d, tgt);

        // Safety: don’t shoot through friendlies/blacklist
        if (hasBlockingEntityAlongShot(tgt)) {
            coolDown = 0; // wait for a clean line
            return;
        }

        if (aimWarmup > 0) {
            aimWarmup--;
            return;
        }
        if (coolDown > 0) {
            coolDown--;
            return;
        }

        if (!mob.getEntitySenses().canSee(tgt)) return;
        if (d2 < minDistSq || d2 > maxDistSq) return;

        performShot(tgt, d);
        coolDown = shootIntervalTicks;
    }

    /**
     * Force the traveller to face the target smoothly but authoritatively.
     * This avoids LookHelper being overridden by other AIs in the same tick.
     *
     * @param target       the entity to face
     * @param maxYawStep   max degrees of yaw change per tick
     * @param maxPitchStep max degrees of pitch change per tick
     * @param headLimit    max head/body divergence in degrees
     */
    private void hardFaceTarget(EntityLivingBase target, float maxYawStep, float maxPitchStep, float headLimit) {
        // Vector from eyes to target mid-height
        double sx = mob.posX;
        double sy = mob.posY + mob.getEyeHeight();
        double sz = mob.posZ;
        double tx = target.posX;
        double ty = target.posY + target.getEyeHeight() * 0.5;
        double tz = target.posZ;
        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;

        double horiz = MathHelper.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-6 && Math.abs(dy) < 1.0E-6) return;

        float desiredYaw = (float) (Math.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
        float desiredPitch = (float) (-(Math.atan2(dy, horiz) * (180D / Math.PI)));

        // Smoothly step head first
        mob.rotationYawHead = rotlerp(mob.rotationYawHead, desiredYaw, maxYawStep);
        mob.rotationPitch = rotlerp(mob.rotationPitch, desiredPitch, maxPitchStep);

        // Pull body toward head so other visuals don't fight us
        mob.rotationYaw = rotlerp(mob.rotationYaw, mob.rotationYawHead, maxYawStep);
        mob.renderYawOffset = rotlerp(mob.renderYawOffset, mob.rotationYaw, maxYawStep);

        // Clamp head/body divergence
        float diff = MathHelper.wrapDegrees(mob.rotationYawHead - mob.renderYawOffset);
        if (diff < -headLimit) mob.rotationYawHead = mob.renderYawOffset - headLimit;
        if (diff > headLimit) mob.rotationYawHead = mob.renderYawOffset + headLimit;
    }

    private void applyStrafeMovement(double dist, EntityLivingBase tgt) {
        // Flip strafe direction periodically
        if (--strafeTicks <= 0) {
            strafeTicks = randBetween(strafeMinTicks, strafeMaxTicks);
            strafeDir = -strafeDir;
        }

        // Backstep gently when a bit too close (but not close enough for melee handoff)
        if (++backstepTicker >= backstepInterval) {
            backstepTicker = 0;
            if (dist * dist < (minDistSq * 1.08)) {
                // small backwards nudge
                mob.getMoveHelper().strafe(-backstepSpeed, 0.0F);
                return;
            }
        }

        // Circle around: small forward to keep moving + lateral strafe
        float forward = 0.12f; // tiny forward bias to avoid dead stops
        float sideways = strafeInput * (float) strafeDir;

        // If we’re drifting out of ideal band, bias forward/back
        double min = Math.sqrt(minDistSq) * 1.05;
        double max = Math.sqrt(maxDistSq) * 0.95;
        if (dist < min) forward = -0.18f;    // back away a bit
        else if (dist > max) forward = 0.28f; // advance to get back in range

        // Apply and clamp speed so they don’t sprint while circling
        mob.getMoveHelper().strafe(forward, sideways);
        mob.setAIMoveSpeed((float) Math.min(strafeAbsSpeed, mob.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue()));
    }

    private boolean hasBlockingEntityAlongShot(EntityLivingBase target) {
        Vec3d from = new Vec3d(mob.posX, mob.posY + mob.getEyeHeight(), mob.posZ);
        Vec3d to = new Vec3d(target.posX, target.posY + target.getEyeHeight() * 0.5, target.posZ);

        AxisAlignedBB swept = new AxisAlignedBB(
                Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z),
                Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z)
        ).grow(BLOCK_WIDTH, 0.25D, BLOCK_WIDTH);

        List<Entity> list = mob.world.getEntitiesWithinAABBExcludingEntity(mob, swept);
        if (list.isEmpty()) return false;

        Vec3d dir = to.subtract(from);
        double len2 = dir.lengthSquared();
        if (len2 < 1.0E-6) return false;

        for (Entity e : list) {
            if (!e.isEntityAlive() || e == target) continue;
            Vec3d pe = new Vec3d(e.posX, e.posY + e.height * 0.5, e.posZ).subtract(from);
            double t = pe.dotProduct(dir) / len2; // 0..1 along segment
            if (t <= 0.02D || t >= 0.98D) continue;
            Vec3d closest = from.add(dir.scale(t));
            double lateral = closest.squareDistanceTo(e.posX, e.posY + e.height * 0.5, e.posZ);
            if (lateral > (BLOCK_WIDTH * BLOCK_WIDTH)) continue;
            if (e instanceof EntityTraveller) return true;
            if (e instanceof EntityLivingBase && blacklist != null && blacklist.isBlacklisted(e)) return true;
        }
        return false;
    }

    private void performShot(EntityLivingBase target, double distance) {
        World w = mob.world;
        EntityTippedArrow arrow = new EntityTippedArrow(w, mob);
        arrow.setEnchantmentEffectsFromEntity(mob, 1.0F);
        arrow.pickupStatus = EntityTippedArrow.PickupStatus.DISALLOWED;

        // Aim from shooter eye to target mid/eye height
        double sx = mob.posX;
        double sy = mob.posY + mob.getEyeHeight();
        double sz = mob.posZ;
        double tx = target.posX;
        double ty = target.posY + target.getEyeHeight() * 0.5;
        double tz = target.posZ;

        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;
        double horiz = MathHelper.sqrt(dx * dx + dz * dz);

        // Vanilla-like arc: add slight elevation for gravity over distance
        double aimY = dy + horiz * 0.17D; // tweak 0.15–0.22 for feel

        float velocity = 2.3F;  // bow power → arrow velocity
        float inaccuracy = 5.5F; // slight spread
        arrow.shoot(dx, aimY, dz, velocity, inaccuracy);

        // scale damage a touch with distance so long shots aren’t too weak
        arrow.setDamage(arrow.getDamage() + Math.max(0.0, 0.30 * (distance / 8.0)));

        w.playSound(null, mob.posX, mob.posY, mob.posZ, SoundEvents.ENTITY_SKELETON_SHOOT,
                SoundCategory.HOSTILE, 1.0F, 1.0F / (mob.getRNG().nextFloat() * 0.4F + 0.8F));
        w.spawnEntity(arrow);
        mob.swingArm(EnumHand.MAIN_HAND);
    }

    private float rotlerp(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    private boolean isGhostBow(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemBow && s.hasTagCompound() && s.getTagCompound().getBoolean("TravellerGhostBow");
    }

    private int randBetween(int a, int b) {
        return a + mob.getRNG().nextInt(Math.max(1, b - a + 1));
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
