package com.jubitus.travellers.combat.ai;

import com.jubitus.travellers.combat.Hostility;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

public class TravellerHurtByTarget extends EntityAITarget {
    private final EntityCreature owner;
    private final boolean callForHelp;
    private final double assistRadius;
    private int revengeTimerOld;

    public TravellerHurtByTarget(EntityCreature owner, boolean callForHelp, double assistRadius) {
        super(owner, false); // don't require LOS for the trigger
        this.owner = owner;
        this.callForHelp = callForHelp;
        this.assistRadius = assistRadius;
        this.setMutexBits(1); // vanilla convention for target AIs
    }

    @Override
    public boolean shouldExecute() {
        if (owner.world.isRemote) return false;
        if (owner.getLastDamageSource() == null
                && !(owner instanceof EntityTraveller && ((EntityTraveller) owner).wasMountRecentlyAttacked())) return false;

        final int revengeTimer = owner.getRevengeTimer();
        EntityLivingBase attacker = owner.getRevengeTarget();
        if (attacker == null || !attacker.isEntityAlive()) return false;

        // Blacklisted attacker: clear revenge target immediately and ignore
        if (owner instanceof EntityTraveller) {
            com.jubitus.travellers.combat.lists.EntityRuleList bl = ((EntityTraveller) owner).getAggroBlacklist();
            if (bl != null && bl.matches(attacker)) {
                this.revengeTimerOld = revengeTimer;
                owner.setRevengeTarget(null);
                return false;
            }
        }

        // Too far: consume the event so we don't retrigger forever
        if (owner.getDistanceSq(attacker) > assistRadius * assistRadius) {
            this.revengeTimerOld = revengeTimer;
            owner.setRevengeTarget(null);
            return false;
        }

        if (attacker instanceof EntityTraveller && owner instanceof EntityTraveller) {
            boolean crossFaction = Hostility.isAtWar((EntityTraveller) owner, (EntityTraveller) attacker);
            if (!crossFaction) {
                this.revengeTimerOld = revengeTimer;
                owner.setRevengeTarget(null);
                return false;
            }
        }

        return revengeTimer != this.revengeTimerOld;
    }


    @Override
    public void startExecuting() {
        EntityLivingBase attacker = this.owner.getRevengeTarget();
        this.revengeTimerOld = this.owner.getRevengeTimer(); // consume by default
        if (attacker == null || !attacker.isEntityAlive()) {
            owner.setRevengeTarget(null);
            return;
        }
        if (owner.getDistanceSq(attacker) > assistRadius * assistRadius) {
            owner.setRevengeTarget(null); // optional but helps prevent re-triggers
            return;
        }
        if (attacker instanceof EntityTraveller && owner instanceof EntityTraveller) {
            EntityTraveller me = (EntityTraveller) owner;
            EntityTraveller other = (EntityTraveller) attacker;

            boolean crossFaction = Hostility.isAtWar(me, other);
            if (!crossFaction) {
                this.revengeTimerOld = this.owner.getRevengeTimer();
                owner.setRevengeTarget(null);
                return;
            }
        }

        if (this.taskOwner instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) this.taskOwner;
            t.trySetAttackTargetLimited(attacker, 2); // emergency priority
        } else {
            this.taskOwner.setAttackTarget(attacker);
        }
        this.revengeTimerOld = this.owner.getRevengeTimer();

        // Never against a unit (allies notice it through their own reassessment).
        if (this.callForHelp && !(attacker instanceof EntityTraveller) && owner instanceof EntityTraveller) {
            callAllies((EntityTraveller) owner, attacker, this.assistRadius);
        }

        super.startExecuting();
    }

    /**
     * Pulls idle same-side allies around {@code caller} into the fight against {@code attacker}, once per attacker
     * per group (HelpCallGate). Against a player, a hired worker answers only a call from its own owner's side.
     */
    public static void callAllies(EntityTraveller caller, EntityLivingBase attacker, double radius) {
        if (attacker == null || !attacker.isEntityAlive()) return;
        // Skip recruiting more allies once enough are already engaged with this attacker.
        if (com.jubitus.travellers.combat.AttackTargetLimiter.getAttackerCount(attacker)
                >= com.jubitus.travellers.config.TravellersConfig.COMBAT.callForHelpSkipIfAttackersAtLeast) {
            com.jubitus.travellers.debug.profile.MovementProfiler.helpCall("call for help, skipped", 0);
            return;
        }
        if (!com.jubitus.travellers.combat.HelpCallGate.tryCall(caller, attacker)) return;
        int recruited = 0;
        int meleeSlots = com.jubitus.travellers.combat.HelpCallGate.freeMeleeSlots(caller, attacker);
        AxisAlignedBB box = new AxisAlignedBB(
                caller.posX - radius, caller.posY - 8, caller.posZ - radius,
                caller.posX + radius, caller.posY + 8, caller.posZ + radius);

        List<EntityTraveller> allies = caller.world.getEntitiesWithinAABB(EntityTraveller.class, box);

        // Cap before sorting to avoid O(n log n) on hundreds of allies
        final int MAX_HELP_ALLIES = 12;
        if (allies.size() > MAX_HELP_ALLIES) {
            allies.sort((a, b) -> Double.compare(a.getDistanceSq(attacker), b.getDistanceSq(attacker)));
            allies = allies.subList(0, MAX_HELP_ALLIES);
        } else {
            // Sort melee first, then by distance within each group
            allies.sort((a, b) -> {
                boolean aBow = a.hasBowAnywherePublic();
                boolean bBow = b.hasBowAnywherePublic();
                if (aBow != bBow) return aBow ? 1 : -1;
                return Double.compare(a.getDistanceSq(attacker), b.getDistanceSq(attacker));
            });
        }

        for (EntityTraveller ally : allies) {
            if (ally == caller) continue;
            if (!ally.isEntityAlive()) continue;
            if (TravellersDerivedConfig.isPeacefulBlocked(ally.world)) continue;

            // Only same-faction allies respond to calls for help.
            if (Hostility.isCrossFaction(caller, ally)) continue;
            if (attacker instanceof EntityPlayer && !sameHiredSide(caller, ally)) continue;

            // Only assist if attacker is within help range of THIS ally
            if (ally.getDistanceSq(attacker) > (radius * radius)) continue;

            // Don't steal targets: only pull in allies that currently have no target.
            EntityLivingBase cur = ally.getAttackTarget();
            if (cur != null && cur.isEntityAlive()) continue;
            boolean melee = !ally.hasBowAnywherePublic();
            if (melee && meleeSlots <= 0) continue;

            if (ally.trySetAttackTargetLimited(attacker, 0)) {
                ally.getNavigator().clearPath();
                recruited++;
                if (melee) meleeSlots--;
            }
        }
        com.jubitus.travellers.debug.profile.MovementProfiler.helpCall("call for help", recruited);
    }

    // Hired workers take on a player only for their own owner's side; units for hire answer only units for hire.
    private static boolean sameHiredSide(EntityTraveller caller, EntityTraveller ally) {
        java.util.UUID a = hiredOwnerOf(caller);
        java.util.UUID b = hiredOwnerOf(ally);
        return a == null ? b == null : a.equals(b);
    }

    @javax.annotation.Nullable
    private static java.util.UUID hiredOwnerOf(EntityTraveller unit) {
        if (!(unit instanceof com.jubitus.travellers.unit.EntityTravellerWorker)) return null;
        com.jubitus.travellers.unit.EntityTravellerWorker w = (com.jubitus.travellers.unit.EntityTravellerWorker) unit;
        return w.isHiredFollower() ? w.getFollowOwnerId() : null;
    }

    // Hands the target off once: vanilla's continue/reset would drop it beyond FOLLOW_RANGE or after 3 s unseen.
    @Override
    public boolean shouldContinueExecuting() {
        return false;
    }

    @Override
    public void resetTask() {
    }
}
