package com.jubitus.travellers.combat;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
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
     * May this hired worker pick {@code e} as a target on its own: enemy players and workers
     * (declared or engaged owners), raiders, monsters, and its extra categories (attack list,
     * enemy-village villagers, Ancient Warfare 2). Never its owner or a blacklisted entity.
     */
    public static boolean isWorkerEnemy(EntityTravellerWorker worker, @Nullable Entity e) {
        if (!(e instanceof EntityLivingBase) || e == worker || !e.isEntityAlive()) return false;
        if (worker.isOwner((EntityLivingBase) e)) return false;
        if (worker.getAggroBlacklist().matches(e)) return false;
        if (e instanceof EntityTravellerWorker) return worker.playerHostility.isEnemyHiredWorker((EntityTravellerWorker) e);
        if (e instanceof EntityPlayer) return worker.playerHostility.isEnemyPlayer((EntityPlayer) e);
        if (e instanceof EntityTraveller) return isCrossFaction(worker, (EntityTraveller) e);
        if (e instanceof IMob) return true;
        return worker.shouldAttackExtraEntity(e);
    }
}
