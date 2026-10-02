package com.jubitus.travellers.gaze;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.village.visit.EntityAIVillageVisit;
import com.jubitus.travellers.follow.EntityAIFollowLeader;
import com.jubitus.travellers.travel.EntityAITravel;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Says hello to a party it meets on the way, once per {@link Greetings} cooldown: the first member of
 * a party to notice the other speaks, the rest of its party nods along, and one member of the greeted
 * party answers a moment later. Hired workers only ever answer; raiders take no part. During a village
 * visit only players are greeted (the visit has its own conversations).
 */
public final class TravellerGreeter {

    private static final double REPLY_RANGE = 20.0D;

    private final EntityTraveller mob;

    @Nullable
    private Entity replyTo;
    private long replyAt;

    public TravellerGreeter(EntityTraveller mob) {
        this.mob = mob;
    }

    /** After each awareness scan of a watched traveller: greets the closest party not greeted yet. */
    void scan(List<EntityLivingBase> nearby) {
        if (!TravellersConfig.GAZE.greetingsEnabled || !canStartGreeting()) return;
        UUID me = Greetings.partyOf(mob);
        if (me == null) return;
        boolean visiting = mob.isVisitingVillage();
        double range = TravellersConfig.GAZE.greetRange;
        EntityLivingBase best = null;
        double bestScore = Double.MAX_VALUE;
        for (EntityLivingBase e : nearby) {
            UUID them = Greetings.partyOf(e);
            if (them == null || them.equals(me) || !e.isEntityAlive()) continue;
            boolean player = e instanceof EntityPlayer;
            if (!player && (visiting || isVisiting(e))) continue;
            double d2 = mob.getDistanceSq(e);
            if (d2 > range * range || Greetings.hasGreeted(mob.world, me, them)) continue;
            // A player's party is greeted through the player whenever they are there.
            double score = player ? d2 - 10000.0D : d2;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best != null && mob.getEntitySenses().canSee(best)) greet(best, nearby);
    }

    /** Greets this entity's party unless already done; also called when stopping for a player. */
    public void greet(EntityLivingBase target) {
        greet(target, mob.getGaze().getNearby());
    }

    private void greet(EntityLivingBase target, List<EntityLivingBase> nearby) {
        if (!TravellersConfig.GAZE.greetingsEnabled) return;
        UUID me = Greetings.partyOf(mob);
        UUID them = Greetings.partyOf(target);
        if (me == null || them == null || me.equals(them) || Greetings.hasGreeted(mob.world, me, them)) return;

        say(target);
        Greetings.record(mob.world, me, them);
        for (EntityLivingBase e : nearby) {
            if (e != mob && e instanceof EntityTraveller && me.equals(Greetings.partyOf(e))) {
                nodAt((EntityTraveller) e, target);
            }
        }

        if (Greetings.hasGreeted(mob.world, them, me)) return;
        Greetings.record(mob.world, them, me);
        EntityTraveller responder = pickResponder(target, them, nearby);
        if (responder != null) responder.getGreeter().scheduleReply(mob, 15 + mob.getRNG().nextInt(20));
        for (EntityLivingBase e : nearby) {
            if (e != responder && e instanceof EntityTraveller && them.equals(Greetings.partyOf(e))) {
                nodAt((EntityTraveller) e, mob);
            }
        }
    }

    /** The greeted one itself if it can answer, else the member of its party nearest to us. */
    @Nullable
    private EntityTraveller pickResponder(EntityLivingBase target, UUID them, List<EntityLivingBase> nearby) {
        if (target instanceof EntityTraveller && canAnswer((EntityTraveller) target)) return (EntityTraveller) target;
        EntityTraveller best = null;
        double bestD2 = REPLY_RANGE * REPLY_RANGE;
        for (EntityLivingBase e : nearby) {
            if (!(e instanceof EntityTraveller) || !them.equals(Greetings.partyOf(e))) continue;
            EntityTraveller t = (EntityTraveller) e;
            double d2 = mob.getDistanceSq(t);
            if (d2 < bestD2 && canAnswer(t)) {
                bestD2 = d2;
                best = t;
            }
        }
        return best;
    }

    void scheduleReply(Entity to, int delayTicks) {
        replyTo = to;
        replyAt = mob.world.getTotalWorldTime() + delayTicks;
    }

    /** Every server tick: says a scheduled answer once its moment comes. */
    public void tick() {
        if (replyTo == null || mob.world.getTotalWorldTime() < replyAt) return;
        Entity to = replyTo;
        replyTo = null;
        if (to.isEntityAlive() && canAnswer(mob) && mob.getDistanceSq(to) <= REPLY_RANGE * REPLY_RANGE) say(to);
    }

    private void say(Entity to) {
        mob.playHelloSound();
        nodAt(mob, to);
    }

    private static void nodAt(EntityTraveller t, Entity to) {
        t.getGaze().glance(to, 40);
        t.getGaze().nod();
    }

    /** Only while travelling, following, idling or stopped for someone - never busy or fighting. */
    private boolean canStartGreeting() {
        if (mob.getFollowOwnerId() != null) return false;
        if (!canAnswer(mob)) return false;
        EntityAIBase moving = mob.getRunningMovementTask();
        return moving == null || moving instanceof EntityAITravel || moving instanceof EntityAIFollowLeader
                || moving instanceof EntityAIStopAndLookAtPlayer || moving instanceof EntityAIVillageVisit;
    }

    private static boolean canAnswer(EntityTraveller t) {
        if (!t.isEntityAlive() || t.isInCombat() || t.getAttackTarget() != null) return false;
        if (t.isSleepingInBed() || t.isAutoEating()) return false;
        return !(t instanceof EntityTravellerWorker) || !((EntityTravellerWorker) t).isChoirFreezeActive();
    }

    private static boolean isVisiting(EntityLivingBase e) {
        return e instanceof EntityTraveller && ((EntityTraveller) e).isVisitingVillage();
    }
}
