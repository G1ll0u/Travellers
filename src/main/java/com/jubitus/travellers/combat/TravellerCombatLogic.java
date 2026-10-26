package com.jubitus.travellers.combat;

import com.jubitus.travellers.worldmap.stream.MapStrikeLog;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.combat.lists.EntityRuleList;
import com.jubitus.travellers.combat.compat.AncientWarfare2Compat;
import com.jubitus.travellers.choir.net.MsgChoirSingerMute;
import com.jubitus.travellers.net.TravellersNetwork;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

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

    // --- Per-tick upkeep state ---
    private boolean wasInCombatPrev = false;
    private int silenceUntilTick = 0;
    private static final int RECENT_HIT_TICKS = 40;
    // A melee escort takes no target farther than this from its leader, and drops one beyond ESCORT_DROP.
    private static final double ESCORT_LEASH = 10.0D;
    private static final double ESCORT_DROP = 12.0D;
    private int targetAdoptedTick = 0;
    // Human hesitation before the first blow or arrow at a newly adopted target (8..16 ticks).
    private static final int REACTION_MIN_TICKS = 8;
    private static final int REACTION_SPREAD_TICKS = 9;
    private int reactionUntilTick = 0;
    private int stuckTargetTicks = 0;
    private double lastTargetDistSq = -1.0D;
    // Last dead attack target a retarget boost was armed for: cleanup is deferred while boosted, so the
    // dead target stays assigned and would otherwise re-arm the boost every tick.
    @Nullable private EntityLivingBase lastHandledDeadTarget = null;
    // Last melee blow landed, for "in contact" (TargetLockPolicy).
    @Nullable private Entity lastStrikeTarget = null;
    private int lastStrikeTick = -1000;
    // Instrumentation: the target seen last tick and why it is being dropped (see observeTargetDrop).
    @Nullable private EntityLivingBase observedTarget = null;
    @Nullable private String dropReason = null;

    // -------------------------------------------------------------------------
    // Per-tick upkeep (server only; EntityTraveller.onUpdate calls these in this order)
    // -------------------------------------------------------------------------

    /** 5 s of voice silence after a fight ends. */
    /** Still hesitating after switching to a new target: no strike or shot at it yet. */
    public boolean isReacting() {
        return entity.ticksExisted < reactionUntilTick;
    }

    public boolean isInPostCombatSilence() {
        return entity.ticksExisted < silenceUntilTick;
    }

    /** Attacker-slot upkeep, combat-end reset, disengage when too far, stuck target, revenge re-engage. */
    public void tickEngagement() {
        observeTargetDrop();
        EntityLivingBase at = entity.getAttackTarget();
        // Archers bypass the limiter - no slot to maintain
        if (at != null && at.isEntityAlive() && !entity.shouldBypassAttackTargetLimiter(at)) {
            // Same effective cap as the initial join in setAttackTarget(): the plain default cap is
            // smaller than the group-scaled cap workers actually joined under.
            boolean ok = AttackTargetLimiter.tryJoin(entity, at, AttackTargetLimiter.priorityFor(entity),
                    entity.computeEffectiveAttackerCap(at));
            if (!ok) {
                // Evicted from the slot: back off briefly so we don't frantic-retry every tick
                noteDrop("evicted from attacker cap");
                entity.ignoreTarget(at, 30);
                entity.setAttackTarget(null);
                entity.setInCombat(false);
                entity.getNavigator().clearPath();
                entity.setSprinting(false);
            }
        }

        boolean inCombatNow = isInCombat();
        if (wasInCombatPrev && !inCombatNow) {
            entity.getNavigator().clearPath();
            entity.setSprinting(false);
            entity.setAIMoveSpeed((float) entity.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue());
            silenceUntilTick = entity.ticksExisted + 100;
        }
        wasInCombatPrev = inCombatNow;

        disengageIfTooFar();
        abandonUnreachableTarget();
        reengageRevengeTarget();
        adoptRevengeTargetAfterLock();
        reassessTarget();
    }

    /** Every second while closing on a target: a visible enemy that comes close may take its place (TargetLockPolicy decides). */
    private void reassessTarget() {
        EntityLivingBase cur = entity.getAttackTarget();
        if (cur == null || !cur.isEntityAlive()) return;
        if ((entity.ticksExisted + entity.getEntityId()) % TargetLockPolicy.REASSESS_TICKS != 0) return;
        if (isInContact(cur)) return;
        double r = TargetLockPolicy.closeThreatDist(entity.hasBowAnywherePublic());
        List<EntityLivingBase> near = SharedThreatScan.around(entity, r, 0);
        if (near == null || near.isEmpty()) return;
        near.sort(Comparator.comparingDouble(entity::getDistanceSq));
        int rays = 0;
        for (EntityLivingBase e : near) {
            if (e == cur || e == entity || e.isInvisible() || entity.isIgnoring(e)) continue;
            if (!SharedThreatScan.heightAllows(entity, e) || !Hostility.isUnitEnemy(entity, e)) continue;
            if (!mayReplaceTarget(e)) continue;
            // Nearest first: two sight rays at most per look-around.
            if (rays++ >= 2) return;
            if (!entity.getEntitySenses().canSee(e)) continue;
            entity.trySetAttackTargetLimited(e, 0);
            return;
        }
    }

    /** Trading blows with its target: close, and a blow exchanged either way within the last 3 s. */
    private boolean isInContact(EntityLivingBase cur) {
        if (entity.getDistanceSq(cur) > TargetLockPolicy.CONTACT_DIST_SQ) return false;
        int now = entity.ticksExisted;
        boolean struck = lastStrikeTarget == cur && now - lastStrikeTick <= TargetLockPolicy.CONTACT_BLOW_TICKS;
        boolean hitBy = entity.getRevengeTarget() == cur && now - entity.getRevengeTimer() <= TargetLockPolicy.CONTACT_BLOW_TICKS;
        return struck || hitBy;
    }

    /** Too far, or a player who turned creative/spectator: dropped (target tasks no longer re-check every tick). */
    private void disengageIfTooFar() {
        EntityLivingBase tgt = entity.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return;
        if (tgt instanceof EntityPlayer && (((EntityPlayer) tgt).isCreative() || ((EntityPlayer) tgt).isSpectator())) {
            noteDrop("creative player");
            entity.setAttackTarget(null);
            entity.setInCombat(false);
            return;
        }
        if (beyondEscortLeash(tgt, ESCORT_DROP)) {
            noteDrop("escort leash");
            entity.setAttackTarget(null);
            entity.setInCombat(false);
            entity.getNavigator().clearPath();
            return;
        }
        double maxEngage = EngageRange.dropLimit(entity, tgt);
        if (entity.getDistanceSq(tgt) > maxEngage * maxEngage) {
            noteDrop("too far");
            entity.setAttackTarget(null);
            entity.setInCombat(false);
            if (entity.isAimingBow()) {
                entity.setAimingBow(false);
                entity.resetActiveHand();
            }
        }
    }

    /** A target that does not get closer for 80 ticks (other level, out of sight) is dropped for a while. */
    private void abandonUnreachableTarget() {
        EntityLivingBase tgt = entity.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) {
            stuckTargetTicks = 0;
            lastTargetDistSq = -1.0D;
            return;
        }
        double d2 = entity.getDistanceSq(tgt);
        // In melee range the distance barely changes while trading blows: not stuck. An archer holds its
        // distance on purpose (from a cliff too): its own no-shot rule drops what it cannot hit.
        final double meleeRangeSq = 3.5 * 3.5;
        if (d2 > meleeRangeSq && !entity.isAimingBow() && lastTargetDistSq >= 0 && d2 >= lastTargetDistSq - 2.0D) {
            stuckTargetTicks++;
        } else {
            stuckTargetTicks = 0;
        }
        lastTargetDistSq = d2;
        if (stuckTargetTicks <= 80) return;

        boolean abandon = false;
        if (Math.abs(tgt.posY - entity.posY) > 4.0D) {
            abandon = true;
        } else if (((entity.ticksExisted + entity.getEntityId()) % 20) == 0) {
            abandon = !entity.getEntitySenses().canSee(tgt);
        }
        if (abandon) {
            noteDrop("unreachable");
            // Shorter ignore for revenge targets (they attacked us): re-engage sooner
            entity.ignoreTarget(tgt, entity.getRevengeTarget() == tgt ? 40 : 200);
            entity.setAttackTarget(null);
            entity.setInCombat(false);
            entity.getNavigator().clearPath();
            stuckTargetTicks = 0;
            lastTargetDistSq = -1.0D;
        }
    }

    /** Skips the 0-8 tick scan delay of EntityAIDefendIfClose after the attack target was cleared. */
    private void reengageRevengeTarget() {
        if (entity.getAttackTarget() != null) return;
        EntityLivingBase rev = entity.getRevengeTarget();
        if (rev != null && rev.isEntityAlive() && !entity.isIgnoring(rev)) {
            double r = EngageRange.limit(entity, rev);
            if (entity.getDistanceSq(rev) <= r * r) {
                entity.trySetAttackTargetLimited(rev);
            }
        }
    }

    /** A hit never forces a switch; once the lock has expired, a nearer aggressor that hit us recently is taken over. */
    private void adoptRevengeTargetAfterLock() {
        EntityLivingBase cur = entity.getAttackTarget();
        if (cur == null || !cur.isEntityAlive()) return;
        if (((entity.ticksExisted + entity.getEntityId()) % 20) != 0) return;
        EntityLivingBase rev = entity.getRevengeTarget();
        if (rev == null || rev == cur || !rev.isEntityAlive() || entity.isIgnoring(rev)) return;
        if (rev instanceof EntityTraveller && !entity.isHostileUnit((EntityTraveller) rev)) return;
        if (entity.getDistanceSq(rev) >= entity.getDistanceSq(cur)) return;
        if (mayReplaceTarget(rev)) entity.trySetAttackTargetLimited(rev, 0);
    }

    /** Dead or vanished target: arm the retarget boost once, then clean up unless a revenge target or the boost holds. */
    public void tickTargetLoss() {
        EntityLivingBase tgt = entity.getAttackTarget();
        if (tgt != null && !tgt.isEntityAlive() && lastHandledDeadTarget != tgt) {
            // Bypass target-scan throttling so the next enemy is picked up within a tick or two
            lastHandledDeadTarget = tgt;
            entity.requestImmediateRetarget();
        }
        if (tgt != null && tgt.isEntityAlive()) return;

        EntityLivingBase rev = entity.getRevengeTarget();
        boolean revAlive = rev != null && rev.isEntityAlive();
        // While boosted, hold off so target-scan tasks can pick up a nearby threat before control goes back to travel/formation AI.
        // A living revenge target is kept: vanilla's revengeTimer decays it (~5 s) and reengageRevengeTarget() re-locks on range.
        if (revAlive || entity.isRetargetBoosted()) return;
        if (isInCombat()) {
            entity.setInCombat(false);
            entity.setAttackTarget(null);
            entity.setRevengeTarget(null);
            entity.getNavigator().clearPath();
            entity.getTravelCommon().setTravelRecoverTicks(60);
        } else if (tgt != null) {
            entity.setAttackTarget(null);
            entity.setRevengeTarget(null);
            entity.getNavigator().clearPath();
        }
    }

    /** Bow pose reset, player-chase forgiveness and watch-fight upkeep. Server only: the client must only mirror synced state. */
    public void tickChaseMemory() {
        if (!isInCombat() && entity.isAimingBow()) {
            // Only cancel if the thing in use is a bow, so eating stays intact
            ItemStack hand = entity.getHeldItemMainhand();
            if (!hand.isEmpty() && hand.getItem() instanceof ItemBow) {
                entity.resetActiveHand();
            }
            entity.setAimingBow(false);
        }

        EntityLivingBase tgt = entity.getAttackTarget();
        if (tgt instanceof EntityPlayer) {
            forgivePlayerIfCalm((EntityPlayer) tgt);
        } else if (tgt == null) {
            resetChaseMemory();
        }

        if (entity.isAimingBow()) {
            entity.setSprinting(false);
            if ((entity.ticksExisted & 0x3F) == 0) {
                pruneGraceHits();
            }
        }

        if (entity.getAttackTarget() == null && hasWatchFightTarget()) {
            if (isWatchFightExpired()) {
                clearWatchFight();
            } else if (entity.world instanceof WorldServer) {
                Entity e = ((WorldServer) entity.world).getEntityFromUuid(getWatchFightTargetId());
                if (e instanceof EntityLivingBase && e.isEntityAlive()) {
                    entity.getNavigator().clearPath();
                    entity.getLookHelper().setLookPositionWithEntity(e, 30.0F, (float) entity.getVerticalFaceSpeed());
                } else {
                    clearWatchFight();
                }
            }
        }
    }

    private void forgivePlayerIfCalm(EntityPlayer p) {
        // Still engaging: line of sight or being near keeps the chase fresh
        double keepDist = TravellersConfig.PLAYER_AGGRO.playerFleeKeepDistance;
        if (entity.getEntitySenses().canSee(p) || entity.getDistanceSq(p) <= keepDist * keepDist) {
            lastLOSorCloseTick = entity.ticksExisted;
        }
        boolean playerCalm = lastPlayerAggroAgeTicks(p) > TravellersConfig.PLAYER_AGGRO.playerCalmForgiveTicks;
        Threat th = getThreat(p.getUniqueID());
        boolean wasRecentlyThreat = th != null && entity.ticksExisted - th.lastSeenTick <= 40;
        boolean fleeingLongEnough =
                entity.ticksExisted - lastLOSorCloseTick > TravellersConfig.PLAYER_AGGRO.playerFleeForgiveTicks;
        // An assist chase (worker joining its owner against a player that never attacked it) lives as long as the owner fights
        boolean assistStillDriven = entity.isOwnerStillFightingAssistTarget(p);

        if (playerCalm && fleeingLongEnough && !wasRecentlyThreat && !assistStillDriven) {
            noteDrop("player forgiven");
            entity.setAttackTarget(null);
            entity.setInCombat(false);
            entity.getNavigator().clearPath();
            if (entity.isAimingBow()) {
                entity.setAimingBow(false);
                entity.resetActiveHand();
            }
            resetChaseMemory();
            entity.setRevengeTarget(null);
        }
    }

    // -------------------------------------------------------------------------
    // Combat state
    // -------------------------------------------------------------------------

    public boolean isInCombat() {
        boolean base = isInCombatRaw();
        if (!base) return false;
        if (!TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen) return base;
        // Shared chunk-based predicate, never an independent distance circle - see
        // EntityTraveller.isInPlayerViewDistanceApprox().
        return entity.isInPlayerViewDistance();
    }

    // Grace window after being hit by a living entity, separate from vanilla's hurtResistantTime
    // (which is also refreshed by environmental damage like fire/lava and would otherwise freeze
    // travel/follow AI - both gate on isInCombat() - for as long as the entity keeps burning/drowning).
    private static final int ENTITY_HIT_GRACE_TICKS = 20;
    private int lastEntityHitTick = Integer.MIN_VALUE / 2;

    public boolean isInCombatRaw() {
        return entity.getAttackTarget() != null
                || entity.getRevengeTarget() != null
                || (entity.ticksExisted - lastEntityHitTick) <= ENTITY_HIT_GRACE_TICKS;
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
            if (!Hostility.isCrossFaction(entity, t)) score++;
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

        // Damage scaling keys off the entity really hit (UnitDamageScaling, TravellerFriendlyFireHandler).
        boolean hit = target.attackEntityFrom(DamageSource.causeMobDamage(entity), damage);
        if (hit) {
            lastStrikeTarget = target;
            lastStrikeTick = entity.ticksExisted;
        }
        if (!hit) return false;

        // A counter-effect on the target (e.g. Thorns) can synchronously kill the attacker inside
        // attackEntityFrom above and drop its weapon as a world item sharing this exact ItemStack
        // reference (vanilla never copies on drop) — damaging it after that would over-wear the
        // already-dropped item instead of anything this entity still holds. See EntityTraveller's
        // matching guard around damageArmorOnHit for the same root cause.
        // INVARIANT: no wear after a possibly lethal hit - the drop shares this ItemStack.
        if (entity.getHealth() > 0.0F) {
            TravellerItemDamage.damageWeaponOnHit(entity);
        }

        if (!entity.world.isRemote && target instanceof EntityLivingBase) {
            rememberThreat((EntityLivingBase) target, THREAT_FOR_HIT / 2);
        }

        if (!entity.world.isRemote) {
            MapStrikeLog.record(MapStrikeLog.MELEE, entity.dimension, entity.world.getTotalWorldTime(),
                    entity.getUniqueID(), entity.posX, entity.posZ, target.posX, target.posZ);
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
    // Threat memory
    // -------------------------------------------------------------------------

    public void rememberThreat(EntityLivingBase e, int addScore) {
        if (e == null || !e.isEntityAlive()) return;
        EntityRuleList bl = entity.getAggroBlacklist();
        if (bl != null && bl.matches(e)) return;
        if (!entity.shouldAttackExtraEntity(e) && !(e instanceof IMob)) return;

        if (e instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) e;
            if (!entity.isHostileUnit(t)) return;
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

    /**
     * The one gate every attack target goes through (the entity's setAttackTarget delegates here):
     * range, peaceful, creative players, blacklist, friendly mounts, offscreen, factions, attacker cap.
     */
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        EntityLivingBase prev = entity.getAttackTarget();
        // Re-asserting the current target changes nothing; dropping it is tickEngagement's job.
        if (target != null && target == prev && !TravellersDerivedConfig.isPeacefulBlocked(entity.world)) return;
        if (target == null && dropReason == null && prev != null && prev.isEntityAlive()
                && com.jubitus.travellers.debug.profile.MovementProfiler.isActive()) {
            noteDrop("untagged: " + dropCaller());
        }
        // A live, still-useful target is kept against any switch request (see TargetLockPolicy).
        TargetLockPolicy.Verdict verdict = null;
        if (!entity.world.isRemote && target != null && prev != null && prev != target) {
            verdict = replaceVerdict(target);
            if (verdict == TargetLockPolicy.Verdict.LOCKED) {
                recordTargetChange("refused", prev, target);
                return;
            }
        }
        if (!entity.world.isRemote && target != null) {
            double r = EngageRange.limit(entity, target);
            double d2 = entity.getDistanceSq(target);

            if (d2 > r * r) {
                if (refusalKeepsCurrent(target)) return;
                // hard reject
                noteDrop("refused: out of range");
                leaveSlot(prev);
                entity.vanillaSetAttackTarget(null);
                entity.setInCombat(false);
                entity.setRevengeTarget(null);
                entity.getNavigator().clearPath();
                entity.setSprinting(false);
                if (entity.isAimingBow()) {
                    entity.setAimingBow(false);
                    entity.resetActiveHand();
                }
                return;
            }
        }
        if (entity.world != null && TravellersDerivedConfig.isPeacefulBlocked(entity.world)) {
            noteDrop("peaceful");
            leaveSlot(prev);
            entity.vanillaSetAttackTarget(null);
            entity.setInCombat(false);
            return;
        }
        // A creative/spectator player is never a valid target, regardless of what triggered this
        // call (revenge, call-for-help, proactive scan...) - this is the single choke point every
        // setAttackTarget caller goes through via virtual dispatch, so checking it here once covers
        // all of them instead of every individual target-selector task re-deriving it.
        if (target instanceof EntityPlayer) {
            EntityPlayer tp = (EntityPlayer) target;
            if (tp.isCreative() || tp.isSpectator()) {
                if (refusalKeepsCurrent(target)) return;
                noteDrop("refused: creative player");
                leaveSlot(prev);
                entity.vanillaSetAttackTarget(null);
                entity.setInCombat(false);
                return;
            }
        }
        if (target != null) {
            EntityRuleList blacklist = entity.getAggroBlacklist();
            if (blacklist.matches(target)) {
                entity.ignoreTarget(target, 20 * 60);
                if (refusalKeepsCurrent(target)) return;
                noteDrop("refused: blacklisted");
                rejectTarget();
                return;
            }
        }

        // Never target a friendly unit's bonded horse (see isFriendlyBondedMount): stops owner-assist
        // / defend AIs from mobbing a mount they can't damage.
        if (target != null && !entity.world.isRemote && entity.isFriendlyBondedMount(target)) {
            entity.ignoreTarget(target, 20 * 30);
            if (refusalKeepsCurrent(target)) return;
            noteDrop("refused: friendly mount");
            rejectTarget();
            return;
        }

        // Off-screen: no combat targets at all (a player target is only kept on the client side, which never decides)
        if (TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen
                && !entity.isInPlayerViewDistance()
                && (!entity.world.isRemote || !(target instanceof EntityPlayer))) {
            noteDrop("offscreen");
            rejectTarget();
            return;
        }
        if (!entity.isAggressiveToTravellers() && target instanceof net.minecraft.entity.IEntityOwnable) {
            net.minecraft.entity.IEntityOwnable ownable = (net.minecraft.entity.IEntityOwnable) target;
            if (ownable.getOwner() instanceof EntityPlayer) {
                if (refusalKeepsCurrent(target)) return;
                noteDrop("refused: player's pet");
                rejectTarget();
                return;
            }
        }
        if (target instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) target;

            // Same-faction fights are blocked, except hired workers of hostile owners
            if (!entity.isHostileUnit(other)) {
                if (refusalKeepsCurrent(target)) return;
                noteDrop("refused: same side");
                rejectTarget();
                return;
            }
        }
        if (target != null && !entity.world.isRemote && beyondEscortLeash(target, ESCORT_LEASH)) {
            if (refusalKeepsCurrent(target)) return;
            noteDrop("refused: escort leash");
            rejectTarget();
            return;
        }

        // Hard cap of attackers per target (close-range bypass).
        if (!entity.world.isRemote && target != null) {
            // target is never a creative/spectator player here - rejected earlier above.
            {
                // If target is close (<=8 blocks) AND we have no current target,
                // ignore the limiter and allow engagement.
                boolean bypass = entity.shouldBypassAttackTargetLimiter(target);

                int prio = AttackTargetLimiter.priorityFor(entity);
                if (!bypass) {
                    int effectiveMax = entity.computeEffectiveAttackerCap(target);

                    if (!AttackTargetLimiter.tryJoin(entity, target, prio, effectiveMax)) {
                        if (TravellersConfig.COMPAT.ancientWarfare2DebugLog
                                && AncientWarfare2Compat.isFactionNpc(target)) {
                            net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                                    "[Travellers/AW2] %s FAILED to join attacker slot on %s#%s (cap=%d, current=%d)",
                                    entity.getName(), target.getName(), target.getUniqueID(),
                                    effectiveMax, AttackTargetLimiter.getAttackerCount(target));
                        }
                        entity.ignoreTarget(target, 30);
                        if (refusalKeepsCurrent(target)) return;
                        startWatchingFight(target, 60);
                        if (entity.getRevengeTarget() == target) entity.setRevengeTarget(null);
                        entity.setInCombat(false);
                        entity.getNavigator().clearPath();
                        return;
                    } else if (TravellersConfig.COMPAT.ancientWarfare2DebugLog
                            && AncientWarfare2Compat.isFactionNpc(target)) {
                        net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                                "[Travellers/AW2] %s joined attacker slot on %s#%s (cap=%d, now=%d)",
                                entity.getName(), target.getName(), target.getUniqueID(),
                                effectiveMax, AttackTargetLimiter.getAttackerCount(target));
                    }
                } else {
                    // Bypass (revenge / emergency): register an unlimited slot so keepAlive
                    // won't evict this entity on the next tick.
                    // Archers skip keepAlive entirely, so no slot needed for them.
                    boolean isArcher = entity.hasBowAnywherePublic() || entity.isAimingBow();
                    if (!isArcher) {
                        AttackTargetLimiter.tryJoin(entity, target, prio, Integer.MAX_VALUE);
                    }
                }
            }
        }

        if (!entity.world.isRemote && prev != null && prev != target) {
            AttackTargetLimiter.leave(entity, prev);
        }
        entity.vanillaSetAttackTarget(target);

        if (!entity.world.isRemote) {
            if (target != prev) {
                targetAdoptedTick = entity.ticksExisted;
                if (target != null) {
                    reactionUntilTick = entity.ticksExisted + REACTION_MIN_TICKS + entity.getRNG().nextInt(REACTION_SPREAD_TICKS);
                    recordTargetChange(verdict == null ? "adopted" : verdict.name().toLowerCase(java.util.Locale.ROOT), prev, target);
                }
            }
            if (target != null) entity.setInCombat(true);
            if (target instanceof EntityPlayer) {
                chasingPlayerId = target.getUniqueID();
                lastLOSorCloseTick = entity.ticksExisted; // we just “saw” them
            } else if (target == null) {
                resetChaseMemory();
            }

            // Play attack shout when freshly engaging a target
            if (target != null && prev == null) {
                entity.voice.tryPlayAttack();
            }

            // Loaded-vs-loaded fight tracking: the offscreen scanner never sees entities that were
            // already loaded when the fight started, so notification/journal coverage needs this hook too.
            if (target instanceof EntityTraveller && prev != target) {
                com.jubitus.travellers.battle.VirtualBattleManager.get((net.minecraft.world.WorldServer) entity.world)
                        .onLoadedEngagement((net.minecraft.world.WorldServer) entity.world, entity, (EntityTraveller) target);
            }
        }
    }

    /** False when the current target is locked and {@code candidate} has no right to replace it. */
    public boolean mayReplaceTarget(EntityLivingBase candidate) {
        EntityLivingBase cur = entity.getAttackTarget();
        return cur == null || cur == candidate || replaceVerdict(candidate) != TargetLockPolicy.Verdict.LOCKED;
    }

    private TargetLockPolicy.Verdict replaceVerdict(EntityLivingBase candidate) {
        EntityLivingBase cur = entity.getAttackTarget();

        TargetLockPolicy.Situation s = new TargetLockPolicy.Situation();
        s.currentUseless = !cur.isEntityAlive() || entity.isIgnoring(cur);
        s.currentAgeTicks = entity.ticksExisted - targetAdoptedTick;
        s.recentThreats = countRecentThreats();

        EntityLivingBase leader = entity.getEffectiveFollowTarget();
        if (leader != null && candidate instanceof EntityLiving) {
            s.candidateAttacksLeader = ((EntityLiving) candidate).getAttackTarget() == leader
                    || candidate.getRevengeTarget() == leader;
        }
        s.candidateHitsMeClose = entity.getRevengeTarget() == candidate
                && entity.ticksExisted - entity.getRevengeTimer() <= RECENT_HIT_TICKS
                && entity.getDistanceSq(candidate) <= TargetLockPolicy.CLOSE_HIT_DIST_SQ;
        s.inContact = cur.isEntityAlive() && isInContact(cur);
        s.currentDistSq = entity.getDistanceSq(cur);
        s.candidateDistSq = entity.getDistanceSq(candidate);
        s.candidateAttacksMe = candidate instanceof EntityLiving && ((EntityLiving) candidate).getAttackTarget() == entity;
        s.archer = entity.hasBowAnywherePublic();
        return TargetLockPolicy.decide(s);
    }

    /** Names why the live target is about to be dropped; the first reason wins until the next observation. */
    public void noteDrop(String reason) {
        EntityLivingBase cur = entity.getAttackTarget();
        if (dropReason == null && cur != null && cur.isEntityAlive()) dropReason = reason;
    }

    /** First caller outside the unit classes and vanilla's setter chain: names an untagged drop (profiling only). */
    private static String dropCaller() {
        for (StackTraceElement f : new Throwable().getStackTrace()) {
            String c = f.getClassName();
            if (c.equals(TravellerCombatLogic.class.getName()) || c.startsWith("com.jubitus.travellers.unit.EntityTraveller")
                    || c.equals("net.minecraft.entity.EntityLiving") || c.equals("net.minecraft.entity.EntityCreature")) continue;
            return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber();
        }
        return "?";
    }

    /** A live target that disappeared since last tick is a drop: tallied with the reason noted for it. */
    private void observeTargetDrop() {
        EntityLivingBase seen = observedTarget;
        EntityLivingBase cur = entity.getAttackTarget();
        String reason = dropReason;
        observedTarget = cur;
        dropReason = null;
        if (seen == null || cur != null || !seen.isEntityAlive()) return;
        if (reason == null) reason = "untagged";
        com.jubitus.travellers.debug.profile.MovementProfiler.targetDrop(reason);
        if (com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(entity.getUniqueID())) {
            com.jubitus.travellers.debug.trace.TravellerTracer.event(entity, "combat", "target dropped (" + reason + "): "
                    + seen.getName() + String.format(java.util.Locale.ROOT, " d=%.1f", entity.getDistance(seen)));
        }
    }

    /** Profiler tally and trace line for one target decision; {@code kind} is "adopted", "refused" or the switch verdict. */
    private void recordTargetChange(String kind, @Nullable EntityLivingBase prev, EntityLivingBase target) {
        com.jubitus.travellers.debug.profile.MovementProfiler.targetChange(kind);
        if (!com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(entity.getUniqueID())) return;
        String from = prev == null || !prev.isEntityAlive() ? "-"
                : prev.getName() + String.format(java.util.Locale.ROOT, " d=%.1f", entity.getDistance(prev));
        com.jubitus.travellers.debug.trace.TravellerTracer.event(entity, "combat", "target " + kind + ": " + from
                + " -> " + target.getName() + String.format(java.util.Locale.ROOT, " d=%.1f", entity.getDistance(target)));
    }

    private int countRecentThreats() {
        int n = 0;
        for (Threat t : threatMap.values()) {
            if (entity.ticksExisted - t.lastSeenTick <= RECENT_HIT_TICKS * 2) n++;
        }
        return n;
    }

    /** The entity's attackEntityFrom: vanilla damage, then armor wear, choir mute and retaliation. */
    public boolean onAttacked(DamageSource source, float amount) {

        if (source == DamageSource.CACTUS || "cactus".equals(source.getDamageType())) {
            return false;
        }

        entity.shield.consumeBlocked(); // clear a stale flag before this hit
        if (source.isProjectile() && source.getTrueSource() instanceof EntityLivingBase && !entity.world.isRemote) {
            entity.shield.onShotAt((EntityLivingBase) source.getTrueSource());
        }
        boolean tookDamage = entity.vanillaAttackEntityFrom(source, amount);
        if (!tookDamage || entity.world.isRemote) return tookDamage;

        // A lethal hit already ran onDeath() synchronously inside super.attackEntityFrom above,
        // which may have dropped this entity's armor as world EntityItems — vanilla's dropEquipment
        // hands the drop the exact same ItemStack reference still sitting in the equipment slot,
        // it never copies it. Damaging armor after that point would silently over-wear the item
        // that's already on the ground instead of anything still worn by this entity.
        // INVARIANT: no wear after a possibly lethal hit - the drop shares this ItemStack.
        if (entity.getHealth() > 0.0F && !entity.shield.consumeBlocked()) {
            TravellerItemDamage.damageArmorOnHit(entity, amount);
        }

        if (entity.isChoirSinging()) {
            // Keep the OGG stream alive; just mute the sound on clients for 8 seconds so
            // when unmuted the singer is back at the correct position in the song.
            TravellersNetwork.CHANNEL.sendToAllAround(
                    new MsgChoirSingerMute(entity.getEntityId(), 8000),
                    new NetworkRegistry.TargetPoint(entity.dimension, entity.posX, entity.posY, entity.posZ, 64.0));
            // Don't touch voiceLockUntilTick so the coordinator keeps the session alive.
        }
        // Non-choir case: no explicit stop here anymore. playHurtSound() (called by
        // super.attackEntityFrom above) already crossfades out whatever voice line was
        // playing when it plays the hurt bark. If the hurt bark itself was throttled by
        // TravellerCrowdSoundLimiter (dense battle) there's nothing to interrupt with,
        // so the prior sound is deliberately left alone rather than cut to silence.

        Entity src = source.getTrueSource();

        if (src instanceof EntityPlayer && ((EntityPlayer) src).isCreative()) {
            // Vanilla's super.attackEntityFrom above already set the revenge target to this
            // creative player unconditionally - clear it so nothing downstream (TravellerHurtByTarget,
            // isInCombatRaw, threat memory...) treats a harmless creative hit as an ongoing threat.
            entity.setInCombat(false);
            entity.setRevengeTarget(null);
            return true;
        }

        // A worker hit by its owner never aggroes back
        if (src instanceof EntityPlayer && entity.isOwner((EntityPlayer) src)) {
            return true;
        }

        // Free hits: a non-raider takes a few hits from a player before striking back, unless already at war with them.
        if (src instanceof EntityPlayer && !entity.isAggressiveToTravellers()
                && !(entity instanceof EntityTravellerWorker
                        && ((EntityTravellerWorker) entity).playerHostility.isEnemyPlayer((EntityPlayer) src))
                && notePlayerHitAndIsStillGrace((EntityPlayer) src)) {
            entity.setRevengeTarget(null);
            return true;
        }

        if (src instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) src;
            if (!com.jubitus.travellers.combat.Hostility.isAtWar(entity, other)) {
                // same faction -> no revenge target, no aggro
                entity.setRevengeTarget(null);
                return true;
            }
        }

        if (src instanceof org.millenaire.common.entity.MillVillager) {
            org.millenaire.common.entity.MillVillager mv = (org.millenaire.common.entity.MillVillager) src;
            if (!com.jubitus.travellers.village.MillVillagerTravellerGuard.shouldFightMillVillager(entity, mv)) {
                // Absorb hit from a non-enemy millager without retaliating
                return true;
            }
        }

        if (src instanceof EntityLivingBase) {
            entity.setRevengeTarget((EntityLivingBase) src);
        }

        if (!TravellersDerivedConfig.isPeacefulBlocked(entity.world) && src instanceof EntityLivingBase) {
            EntityLivingBase le = (EntityLivingBase) src;

            // Always remember threats – we saw them / they hit us
            rememberThreat(le, TravellerCombatLogic.THREAT_FOR_HIT);

            // Already fighting it, or locked on another target: nothing to decide, so no path or LOS work
            // per hit (the aggressor stays the revenge target; adoptRevengeTargetAfterLock may take it later).
            if (entity.getAttackTarget() == le || !mayReplaceTarget(le)) return true;

            boolean canSee = entity.getEntitySenses().canSee(le);
            boolean near = entity.getDistanceSq(le) <= 9.0D;

            // Only an actual switch pays for a path check: the target must be reachable
            boolean hasPath = entity.hasPathToThrottled(le, 2); // urgent

            // Players are only taken on when seen or nearby
            boolean aggro = hasPath && (!(le instanceof EntityPlayer) || canSee || near);
            if (aggro) {
                entity.setInCombat(true);
                entity.setAttackTarget(le);
            } else if (entity.getAttackTarget() == null) {
                // Take the hit and remember the threat, but don't start active combat
                entity.setInCombat(false);
            }
        }

        return true;
    }

    /** A melee escort fights only near the leader it escorts (horizontal distance); archers shoot from their place. */
    private boolean beyondEscortLeash(EntityLivingBase target, double leash) {
        if (!(entity instanceof EntityTravellerWorker) || entity.hasBowAnywherePublic()) return false;
        EntityLivingBase leader = ((EntityTravellerWorker) entity).getEscortedLeader();
        if (leader == null) return false;
        double dx = target.posX - leader.posX;
        double dz = target.posZ - leader.posZ;
        return dx * dx + dz * dz > leash * leash;
    }

    /** A refused new candidate never costs the unit the live target it already has. */
    public boolean refusalKeepsCurrent(@Nullable EntityLivingBase candidate) {
        EntityLivingBase cur = entity.getAttackTarget();
        return !entity.world.isRemote && candidate != null && cur != null && cur != candidate && cur.isEntityAlive();
    }

    private void leaveSlot(@Nullable EntityLivingBase prev) {
        if (!entity.world.isRemote && prev != null) AttackTargetLimiter.leave(entity, prev);
    }

    private void rejectTarget() {
        EntityLivingBase cur = entity.getAttackTarget();
        if (cur != null) {
            AttackTargetLimiter.leave(entity, cur);
            entity.ignoreTarget(cur, 20 * 60);
        }

        entity.vanillaSetAttackTarget(null);
        if (!entity.world.isRemote) {
            entity.setInCombat(false);
            resetChaseMemory();
            entity.setRevengeTarget(null);
            entity.getNavigator().clearPath();
        }
    }
}
