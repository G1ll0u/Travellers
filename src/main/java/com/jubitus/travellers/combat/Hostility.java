package com.jubitus.travellers.combat;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import com.jubitus.travellers.horse.HorseMarkers;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;

/**
 * The one answer to "are these two on opposite sides". Retaliation keeps its own wider rule
 * ({@code PlayerHostility.canRetaliateAgainst}); owner-assist adds "whatever the owner hits"
 * on top of {@link #isWorkerEnemy}.
 */
public final class Hostility {

    private Hostility() {
    }

    /** Raiders on one side, every other unit (travellers, workers) on the other. */
    public static boolean isCrossFaction(EntityTraveller a, EntityTraveller b) {
        return a.isAggressiveToTravellers() != b.isAggressiveToTravellers();
    }

    /**
     * {@link #isCrossFaction}, plus the fight between a hired worker and a non-worker unit that is
     * actually under way (either one is attacking the other's side), so both sides strike back.
     */
    public static boolean isAtWar(EntityTraveller a, EntityTraveller b) {
        if (isCrossFaction(a, b)) return true;
        return fightsAgainst(a, b) || fightsAgainst(b, a);
    }

    private static boolean fightsAgainst(EntityTraveller maybeWorker, EntityTraveller other) {
        if (!(maybeWorker instanceof EntityTravellerWorker) || other instanceof EntityTravellerWorker) return false;
        EntityTravellerWorker w = (EntityTravellerWorker) maybeWorker;
        if (!w.isHiredFollower()) return false;
        return w.getAttackTarget() == other || isAggressingOurSide(w, other);
    }

    /** Whatever e is currently attacking is the worker, its owner, or a hired mate of the same owner. */
    public static boolean isAggressingOurSide(EntityTravellerWorker worker, @Nullable Entity e) {
        if (!(e instanceof EntityLiving) || !e.isEntityAlive()) return false;
        EntityLivingBase t = ((EntityLiving) e).getAttackTarget();
        if (t == null || !t.isEntityAlive()) return false;
        if (t == worker || worker.isOwner(t)) return true;
        if (!(t instanceof EntityTravellerWorker)) return false;
        EntityTravellerWorker mate = (EntityTravellerWorker) t;
        return mate.isHiredFollower() && worker.getFollowOwnerId() != null
                && worker.getFollowOwnerId().equals(mate.getFollowOwnerId());
    }

    /** The owner's own side: bonded mount, tamed animal, blacklisted entity. Never a defence target. */
    public static boolean isOwnerSide(EntityTravellerWorker worker, @Nullable Entity e) {
        if (e == null) return false;
        if (worker.getAggroBlacklist().matches(e)) return true;
        if (e instanceof EntityHorse && HorseMarkers.isBondedMount((EntityHorse) e)) return true;
        if (e instanceof EntityTameable) {
            java.util.UUID tamer = ((EntityTameable) e).getOwnerId();
            return tamer != null && tamer.equals(worker.getFollowOwnerId());
        }
        return false;
    }

    /**
     * May this hired worker pick {@code e} as a target on its own: enemy players and workers
     * (declared or engaged owners), raiders, monsters, and its extra categories (attack list,
     * enemy-village villagers, Ancient Warfare 2), and anything actively attacking its side. Never its owner or its owner's side.
     */
    public static boolean isWorkerEnemy(EntityTravellerWorker worker, @Nullable Entity e) {
        if (!(e instanceof EntityLivingBase) || e == worker || !e.isEntityAlive()) return false;
        if (worker.isOwner((EntityLivingBase) e)) return false;
        if (isOwnerSide(worker, e)) return false;
        if (e instanceof EntityTravellerWorker) return worker.playerHostility.isEnemyHiredWorker((EntityTravellerWorker) e);
        if (e instanceof EntityPlayer) return worker.playerHostility.isEnemyPlayer((EntityPlayer) e);
        if (isAggressingOurSide(worker, e)) return true;
        if (e instanceof EntityTraveller) return isCrossFaction(worker, (EntityTraveller) e);
        if (e instanceof IMob) return true;
        return worker.shouldAttackExtraEntity(e);
    }
}
