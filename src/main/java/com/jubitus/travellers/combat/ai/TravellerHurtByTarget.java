package com.jubitus.travellers.combat.ai;

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
            boolean crossFaction = ((EntityTraveller) owner).isAggressiveToTravellers()
                    ^ ((EntityTraveller) attacker).isAggressiveToTravellers();
            if (!crossFaction) {
                this.revengeTimerOld = revengeTimer;
                owner.setRevengeTarget(null);
                return false;
            }
        }

        // Ignore the FIRST 3 HITS from a PLAYER (per-player, with a short decay window)
        if (attacker instanceof EntityPlayer && owner instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) owner;
            EntityPlayer p = (EntityPlayer) attacker;

            if (t.notePlayerHitAndIsStillGrace(p)) {
                // swallow this retaliation tick so we don't aggro
                this.revengeTimerOld = revengeTimer;
                owner.setRevengeTarget(null); // clear so we don't re-trigger next tick
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

            boolean crossFaction = me.isAggressiveToTravellers() ^ other.isAggressiveToTravellers();
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

        // Skip recruiting more allies once enough are already engaged with this attacker —
        // a big group that's already responding doesn't need to keep pulling in reinforcements
        // (this used to make every single hit ripple out to a dozen more allies regardless of
        // how many were already fighting).
        boolean enoughAlreadyEngaged = attacker != null
                && com.jubitus.travellers.combat.AttackTargetLimiter.getAttackerCount(attacker)
                        >= com.jubitus.travellers.config.TravellersConfig.COMBAT.callForHelpSkipIfAttackersAtLeast;

        if (this.callForHelp && attacker != null && attacker.isEntityAlive() && !enoughAlreadyEngaged) {
            double r = this.assistRadius;
            AxisAlignedBB box = new AxisAlignedBB(
                    owner.posX - r, owner.posY - 8, owner.posZ - r,
                    owner.posX + r, owner.posY + 8, owner.posZ + r);

            List<EntityTraveller> allies = owner.world.getEntitiesWithinAABB(EntityTraveller.class, box);

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
                if (ally == owner) continue;
                if (!ally.isEntityAlive()) continue;
                if (attacker instanceof EntityTraveller) continue;
                if (TravellersDerivedConfig.isPeacefulBlocked(ally.world)) continue;

                // Only same-faction allies respond to calls for help.
                // Raiders recruit only raiders; friendly travellers recruit only friendly travellers/workers.
                if (owner instanceof EntityTraveller) {
                    EntityTraveller ownerT = (EntityTraveller) owner;
                    if (ownerT.isAggressiveToTravellers() != ally.isAggressiveToTravellers()) continue;
                }

                // Only assist if attacker is within help range of THIS ally
                if (ally.getDistanceSq(attacker) > (assistRadius * assistRadius)) continue;

                // IMPORTANT: don't steal targets. Only pull in allies that currently have no target.
                EntityLivingBase cur = ally.getAttackTarget();
                if (cur != null && cur.isEntityAlive()) continue;

                // Use your limiter-aware setter (it will bypass limiter automatically if <=8 blocks & no target)
                boolean ok = ally.trySetAttackTargetLimited(attacker, 0);
                if (ok) {
                    ally.getNavigator().clearPath();
                }
            }


        }

        super.startExecuting();
    }
}
