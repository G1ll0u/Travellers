package com.jubitus.travellers.combat;

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
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;

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

        // Player-damage scaling (including the owner-specific friendly-fire rate) is applied
        // uniformly for melee and arrows in TravellerFriendlyFireHandler, keyed off the entity
        // actually hit rather than the intended target — see that class for why.
        boolean hit = target.attackEntityFrom(DamageSource.causeMobDamage(entity), damage);
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
        EntityRuleList bl = entity.getAggroBlacklist();
        if (bl != null && bl.matches(e)) return;
        if (!entity.shouldAttackExtraEntity(e) && !(e instanceof IMob)) return;

        if (e instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) e;
            if (entity instanceof EntityTravellerWorker && t instanceof EntityTravellerWorker) {
                EntityTravellerWorker me = (EntityTravellerWorker) entity;
                if (!me.shouldAttackExtraEntity(e) && !Hostility.isCrossFaction(entity, t)) return;
            } else {
                if (!Hostility.isCrossFaction(entity, t)) return;
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
        if (!entity.world.isRemote && prev != null && prev != target) {
            AttackTargetLimiter.leave(entity, prev);
        }
        if (!entity.world.isRemote && target != null) {
            double r = TravellersConfig.COMBAT.attackTriggerRange;
            double d2 = entity.getDistanceSq(target);

            if (d2 > r * r) {
                // hard reject
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
                entity.vanillaSetAttackTarget(null);
                entity.setInCombat(false);
                return;
            }
        }
        if (target != null) {
            double r = TravellersConfig.COMBAT.attackTriggerRange;
            if (entity.getDistanceSq(target) > r * r) {
                // hard reject: never keep far targets
                entity.vanillaSetAttackTarget(null);
                if (!entity.world.isRemote) {
                    entity.setInCombat(false);
                    entity.setRevengeTarget(null);
                    entity.getNavigator().clearPath();
                    entity.setSprinting(false);

                    // also cancel ranged pose if it was left on
                    if (entity.isAimingBow()) {
                        entity.setAimingBow(false);
                        entity.resetActiveHand();
                    }
                }
                return;
            }
        }

        if (target != null) {
            EntityRuleList blacklist = entity.getAggroBlacklist();
            if (blacklist.matches(target)) {
                entity.ignoreTarget(target, 20 * 60);
                rejectTarget();
                return;
            }
        }

        // Never target a friendly unit's bonded horse (see isFriendlyBondedMount): stops owner-assist
        // / defend AIs from mobbing a mount they can't damage.
        if (target != null && !entity.world.isRemote && entity.isFriendlyBondedMount(target)) {
            entity.ignoreTarget(target, 20 * 30);
            rejectTarget();
            return;
        }

        if (TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen
                && !entity.isInPlayerViewDistance()
                && !(target instanceof EntityPlayer)) {
            rejectTarget();

            return;
        }

        if (!entity.world.isRemote
                && TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen
                && !entity.isInPlayerViewDistance()) {
            // Completely ignore combat targets while off-screen
            rejectTarget();
            return;
        }
        if (!entity.isAggressiveToTravellers() && target instanceof net.minecraft.entity.IEntityOwnable) {
            net.minecraft.entity.IEntityOwnable ownable = (net.minecraft.entity.IEntityOwnable) target;
            if (ownable.getOwner() instanceof EntityPlayer) {
                rejectTarget();
                return;
            }
        }
        if (target instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) target;

            // ✅ Exception: hired worker vs hired worker can fight if owners are hostile
            if (entity instanceof EntityTravellerWorker && other instanceof EntityTravellerWorker) {
                EntityTravellerWorker me = (EntityTravellerWorker) entity;
                EntityTravellerWorker them = (EntityTravellerWorker) other;

                if (me.shouldAttackExtraEntity(them)) {
                    // allow, do NOT reject
                } else {
                    // fall through to normal faction rules
                    if (!com.jubitus.travellers.combat.Hostility.isCrossFaction(entity, other)) {
                        rejectTarget();
                        return;
                    }
                }
            } else {
                // Normal travellers: block same-faction fights
                if (!com.jubitus.travellers.combat.Hostility.isCrossFaction(entity, other)) {
                    rejectTarget();
                    return;
                }
            }
        }

// --- NEW: hard cap attackers per target ---
// --- hard cap attackers per target (with your 8-block bypass rule) ---
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


        entity.vanillaSetAttackTarget(target);

        if (!entity.world.isRemote) {
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

    /** The entity's attackEntityFrom: vanilla damage, then armor wear, choir mute and retaliation. */
    public boolean onAttacked(DamageSource source, float amount) {

        if (source == DamageSource.CACTUS || "cactus".equals(source.getDamageType())) {
            return false;
        }

        boolean tookDamage = entity.vanillaAttackEntityFrom(source, amount);
        if (!tookDamage || entity.world.isRemote) return tookDamage;

        // A lethal hit already ran onDeath() synchronously inside super.attackEntityFrom above,
        // which may have dropped this entity's armor as world EntityItems — vanilla's dropEquipment
        // hands the drop the exact same ItemStack reference still sitting in the equipment slot,
        // it never copies it. Damaging armor after that point would silently over-wear the item
        // that's already on the ground instead of anything still worn by this entity.
        // INVARIANT: no wear after a possibly lethal hit - the drop shares this ItemStack.
        if (entity.getHealth() > 0.0F) {
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

        // NEW: if worker is hit by its owner, never aggro back
        if (entity instanceof EntityTravellerWorker && src instanceof EntityPlayer) {
            EntityTravellerWorker w = (EntityTravellerWorker) entity;
            EntityPlayer owner = w.getFollowOwner();
            if (owner != null && owner == src) {
                return true;
            }
        }

        if (src instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) src;
            if (!com.jubitus.travellers.combat.Hostility.isCrossFaction(entity, other)) {
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

            boolean canSee = entity.getEntitySenses().canSee(le);
            double d2 = entity.getDistanceSq(le);
            boolean near = d2 <= 9.0D; // 3 blocks radius; tweak as you like

            // **Key: require a path before we actually mark them as attack target**
            boolean hasPath = (le != null) && entity.hasPathToThrottled(le, 2); // urgent


            // Special rules for players:
            if (le instanceof EntityPlayer) {
                EntityPlayer p = (EntityPlayer) le;

                // Only aggro on player if seen OR nearby AND pathable
                if ((canSee || near) && hasPath) {
                    entity.setInCombat(true);
                    entity.setAttackTarget(le);
                } else {
                    // Take the hit and remember threat, but don't start active combat
                    entity.setInCombat(false);
                }
            } else {
                // Non-player: must be pathable to become an active target
                if (hasPath) {
                    entity.setInCombat(true);
                    entity.setAttackTarget(le);
                } else {
                    entity.setInCombat(false);
                }
            }
        }

        return true;
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
