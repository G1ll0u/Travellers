package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumDifficulty;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Centralises all combat logic for traveller entities.
 * Each EntityTraveller holds one instance as {@code public final TravellerCombatLogic combat}.
 * <p>
 * To change how travellers fight, edit this class.
 * To change per-type behaviour, override the relevant hooks in the entity subclass.
 */
public class TravellerCombatLogic {

    public static final int THREAT_FOR_HIT = 8;

    private final EntityTraveller entity;

    // --- State (moved from EntityTraveller) ---
    private boolean inCombat = false;
    private int lastThreatDecayTick = 0;
    private final Map<UUID, Threat> threatMap = new HashMap<>();
    private final Map<UUID, HitGrace> playerGrace = new HashMap<>();
    private final Map<UUID, Integer> playerLastAggroTick = new HashMap<>();

    // Package-visible so EntityAIAttackMeleeCustom can read without going through EntityTraveller
    public int lastLOSorCloseTick = 0;
    @Nullable public UUID chasingPlayerId = null;

    @Nullable private UUID watchFightTargetId = null;
    private int watchFightUntilTick = 0;

    public TravellerCombatLogic(EntityTraveller entity) {
        this.entity = entity;
    }

    // -------------------------------------------------------------------------
    // Combat state
    // -------------------------------------------------------------------------

    public boolean isInCombat() {
        boolean base = isInCombatRaw();
        if (!base) return false;
        if (!TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen) return base;
        return isAnyPlayerWithinViewRadius();
    }

    public boolean isInCombatRaw() {
        return entity.getAttackTarget() != null
                || entity.getRevengeTarget() != null
                || entity.hurtResistantTime > 0;
    }

    /** Raw inCombat flag (not affected by off-screen ghost rules). */
    public boolean getInCombatFlag() {
        return inCombat;
    }

    public void setInCombat(boolean combat) {
        if (entity.world.isRemote) return;
        boolean prev = inCombat;
        inCombat = combat;
        if (combat && !prev) {
            entity.onCombatEntered();
        }
    }

    public void markCombatNow() {
        if (!entity.world.isRemote) {
            entity.markCombatTickNow();
        }
    }

    // -------------------------------------------------------------------------
    // Target validation
    // -------------------------------------------------------------------------

    /**
     * Returns true if this entity is allowed to engage the given target at all.
     * Does not check faction rules — use {@link #isCrossFaction(EntityTraveller)} for that.
     */
    public boolean canEngageTarget(@Nullable EntityLivingBase target) {
        if (target == null || !target.isEntityAlive()) return false;
        if (entity.world.getDifficulty() == EnumDifficulty.PEACEFUL) return false;

        TravellerBlacklist blacklist = entity.getAggroBlacklist();
        if (blacklist != null && blacklist.isBlacklisted(target)) return false;

        if (TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen
                && !entity.isInPlayerViewDistance()
                && !(target instanceof EntityPlayer)) {
            return false;
        }
        return true;
    }

    /** True only when the two entities belong to opposing factions. */
    public boolean isCrossFaction(EntityTraveller other) {
        return entity.isAggressiveToTravellers() ^ other.isAggressiveToTravellers();
    }

    /**
     * True when the attack-target limiter should be bypassed for this target.
     * Archers always bypass — they don't occupy melee slots.
     * Also handles close-range emergency, active retaliation and revenge.
     */
    public boolean shouldBypassAttackTargetLimiter(@Nullable EntityLivingBase e) {
        if (e == null || !e.isEntityAlive()) return false;

        // Archers bypass entirely — ranged attacks don't clog melee slots
        if (entity.hasBowAnywherePublic() || entity.isAimingBow()) return true;

        final double emergSq = EntityTraveller.EMERGENCY_ENGAGE_RANGE * EntityTraveller.EMERGENCY_ENGAGE_RANGE;
        if (entity.getAttackTarget() == null && entity.getDistanceSq(e) <= emergSq) return true;
        if (e instanceof EntityLiving && ((EntityLiving) e).getAttackTarget() == entity) return true;
        if (e.getRevengeTarget() == entity) return true;
        if (entity.getRevengeTarget() == e) return true;
        DamageSource ds = entity.getLastDamageSource();
        if (ds != null && ds.getTrueSource() == e) return true;

        // Owner-assist: as long as the owner is still actively fighting this same target,
        // don't let the worker get evicted from its slot just because the target never
        // personally engaged the worker (e.g. an unusual/passive entity type the worker
        // wouldn't normally target on its own).
        return entity.isOwnerStillFightingAssistTarget(e);
    }

    // -------------------------------------------------------------------------
    // Ranged crowd avoidance
    // -------------------------------------------------------------------------

    /**
     * Weighted "don't shoot into a crowd" score around the target.
     * Friendly travellers near target add +1, nearby players add +2.
     * Returns 0 when target is a player (never avoid shooting at players).
     */
    public int rangedCrowdScoreAround(@Nullable EntityLivingBase target, double radius) {
        if (target == null || entity.world == null) return 0;
        if (target instanceof EntityPlayer) return 0;

        AxisAlignedBB box = target.getEntityBoundingBox().grow(radius, 2.0D, radius);
        int score = 0;

        List<EntityTraveller> travs = entity.world.getEntitiesWithinAABB(EntityTraveller.class, box,
                t -> t != null && t.isEntityAlive() && t != entity);
        for (EntityTraveller t : travs) {
            if (entity.isAggressiveToTravellers() == t.isAggressiveToTravellers()) score++;
        }

        List<EntityPlayer> players = entity.world.getEntitiesWithinAABB(EntityPlayer.class, box,
                p -> p != null && p.isEntityAlive() && !p.isSpectator());
        score += players.size() * 2;

        return score;
    }

    // -------------------------------------------------------------------------
    // Melee attack execution
    // -------------------------------------------------------------------------

    /**
     * Executes a melee attack including enchantment bonuses, knockback and fire aspect.
     * Replaces the {@code attackEntityAsMob} body so it is defined in one place.
     */
    /**
     * Melee damage formula shared by loaded combat and the virtual battle simulation.
     * creatureAttribute may be null (treated as UNDEFINED — generic enchant bonus only).
     */
    public static float computeMeleeDamage(float baseAttackDamage, ItemStack weapon,
                                           @Nullable EnumCreatureAttribute creatureAttribute) {
        float damage = baseAttackDamage;
        if (!weapon.isEmpty()) {
            damage += EnchantmentHelper.getModifierForCreature(weapon,
                    creatureAttribute != null ? creatureAttribute : EnumCreatureAttribute.UNDEFINED);
        }
        return damage;
    }

    public boolean performMeleeAttack(Entity target) {
        float damage = computeMeleeDamage(
                (float) entity.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue(),
                entity.getHeldItemMainhand(),
                target instanceof EntityLivingBase
                        ? ((EntityLivingBase) target).getCreatureAttribute() : null);

        if (target instanceof EntityPlayer) {
            damage *= TravellersConfig.ATTRIBUTES.travellerPlayerDamageMultiplier;
        }
        boolean hit = target.attackEntityFrom(DamageSource.causeMobDamage(entity), damage);
        if (!hit) return false;

        TravellerItemDamage.damageWeaponOnHit(entity);

        if (!entity.world.isRemote && target instanceof EntityLivingBase) {
            rememberThreat((EntityLivingBase) target, THREAT_FOR_HIT / 2);
        }

        if (!entity.world.isRemote) {
            entity.world.setEntityState(entity, (byte) 4);
        }

        int kb = EnchantmentHelper.getKnockbackModifier(entity);
        if (kb > 0 && target instanceof EntityLivingBase) {
            EntityLivingBase victim = (EntityLivingBase) target;
            double x = MathHelper.sin(entity.rotationYaw * 0.017453292F);
            double z = -MathHelper.cos(entity.rotationYaw * 0.017453292F);
            victim.knockBack(entity, kb * 0.5F, x, z);
            entity.motionX *= 0.6D;
            entity.motionZ *= 0.6D;
        }

        int fire = EnchantmentHelper.getFireAspectModifier(entity);
        if (fire > 0) target.setFire(fire * 4);

        entity.swingArm(EnumHand.MAIN_HAND);
        if (target instanceof EntityLivingBase) {
            EnchantmentHelper.applyThornEnchantments((EntityLivingBase) target, entity);
        }
        EnchantmentHelper.applyArthropodEnchantments(entity, target);

        return true;
    }

    // -------------------------------------------------------------------------
    // Move speed cap
    // -------------------------------------------------------------------------

    /**
     * Returns the appropriate AI movement speed cap for the current combat state.
     * Override  in entity subclasses to customise.
     */
    public float computeMoveSpeedCap(double base) {
        if (entity.isAimingBow()) return (float) Math.min(base * 0.98D, 0.30D);
        if (entity.getAttackTarget() != null) return (float) Math.min(base * 1.22D, 0.38D);
        return (float) Math.min(base * 1.02D, 0.32D);
    }

    // -------------------------------------------------------------------------
    // Threat memory
    // -------------------------------------------------------------------------

    public void rememberThreat(EntityLivingBase e, int addScore) {
        if (e == null || !e.isEntityAlive()) return;
        TravellerBlacklist bl = entity.getAggroBlacklist();
        if (bl != null && bl.isBlacklisted(e)) return;
        if (!entity.shouldAttackExtraEntity(e) && !(e instanceof IMob)) return;

        if (e instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) e;
            if (entity instanceof EntityTravellerWorker && t instanceof EntityTravellerWorker) {
                EntityTravellerWorker me = (EntityTravellerWorker) entity;
                if (!me.shouldAttackExtraEntity(e)
                        && entity.isAggressiveToTravellers() == t.isAggressiveToTravellers()) return;
            } else {
                if (entity.isAggressiveToTravellers() == t.isAggressiveToTravellers()) return;
            }
        }

        if (e instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) e;
            if (p.isCreative() || p.isSpectator()) return;
        }

        UUID id = e.getUniqueID();
        Threat th = threatMap.get(id);
        int eid = e.getEntityId();
        if (th == null) {
            threatMap.put(id, new Threat(Math.max(1, addScore), entity.ticksExisted, eid));
        } else {
            th.score = Math.min(1000, th.score + addScore);
            th.lastSeenTick = entity.ticksExisted;
            th.entityId = eid;
        }
    }

    public void decayAndPruneThreats() {
        if (threatMap.isEmpty()) return;
        int now = entity.ticksExisted;
        if (now - lastThreatDecayTick < TravellersConfig.COMBAT.threatDecayIntervalTicks) return;
        lastThreatDecayTick = now;

        Iterator<Map.Entry<UUID, Threat>> it = threatMap.entrySet().iterator();
        while (it.hasNext()) {
            Threat t = it.next().getValue();
            int age = now - t.lastSeenTick;
            if (age > TravellersConfig.COMBAT.threatTtlTicks) { it.remove(); continue; }
            t.score = Math.max(0, t.score - TravellersConfig.COMBAT.threatDecayPerSec);
            if (t.score == 0) it.remove();
        }
    }

    @Nullable
    public Threat getThreat(UUID id) {
        return threatMap.get(id);
    }

    // -------------------------------------------------------------------------
    // Player hit grace
    // -------------------------------------------------------------------------

    /** Returns true while the player is still within the free-hit grace window. */
    public boolean notePlayerHitAndIsStillGrace(EntityPlayer p) {
        final int now = entity.ticksExisted;
        final UUID id = p.getUniqueID();
        HitGrace g = playerGrace.get(id);
        if (g == null || now > g.expiresAt) {
            g = new HitGrace();
            g.count = 0;
        }
        g.count++;
        g.expiresAt = now + TravellersConfig.PLAYER_AGGRO.playerForgiveWindowTicks;
        playerGrace.put(id, g);
        return g.count <= TravellersConfig.PLAYER_AGGRO.playerFreeHits;
    }

    public void pruneGraceHits() {
        final int now = entity.ticksExisted;
        playerGrace.entrySet().removeIf(e -> now > e.getValue().expiresAt);
    }

    public void markPlayerRecentAggro(EntityPlayer p) {
        playerLastAggroTick.put(p.getUniqueID(), entity.ticksExisted);
    }

    public int lastPlayerAggroAgeTicks(EntityPlayer p) {
        Integer t = playerLastAggroTick.get(p.getUniqueID());
        return (t == null) ? Integer.MAX_VALUE : (entity.ticksExisted - t);
    }

    // -------------------------------------------------------------------------
    // Chase / watch-fight memory
    // -------------------------------------------------------------------------

    public void resetChaseMemory() {
        chasingPlayerId = null;
        lastLOSorCloseTick = 0;
    }

    public void startWatchingFight(EntityLivingBase target, int ticks) {
        if (target == null) return;
        watchFightTargetId = target.getUniqueID();
        watchFightUntilTick = entity.ticksExisted + ticks;
    }

    public boolean hasWatchFightTarget() { return watchFightTargetId != null; }
    public boolean isWatchFightExpired() { return entity.ticksExisted >= watchFightUntilTick; }
    public void clearWatchFight() { watchFightTargetId = null; }

    @Nullable
    public UUID getWatchFightTargetId() { return watchFightTargetId; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean isAnyPlayerWithinViewRadius() {
        if (entity.world == null) return true;
        if (entity.world.isRemote) return true;
        double radius = TravellersConfig.COMBAT.travellerOffscreenViewRadius;
        if (radius <= 0.0D) return true;
        return entity.world.getClosestPlayer(entity.posX, entity.posY, entity.posZ, radius, false) != null;
    }

    // -------------------------------------------------------------------------
    // Inner classes (moved from EntityTraveller)
    // -------------------------------------------------------------------------

    public static final class HitGrace {
        public int count;
        public int expiresAt;
    }

    public static final class Threat {
        public int score;
        public int lastSeenTick;
        public int entityId;

        Threat(int s, int t, int eid) {
            score = s;
            lastSeenTick = t;
            entityId = eid;
        }
    }
}
