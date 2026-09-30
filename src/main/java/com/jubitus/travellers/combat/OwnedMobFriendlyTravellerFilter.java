package com.jubitus.travellers.combat;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.player.EntityPlayer;

public final class OwnedMobFriendlyTravellerFilter {

    private OwnedMobFriendlyTravellerFilter() {}

    /**
     * Returns true if the owned mob (wolf or any modded tameable implementing
     * IEntityOwnable) should be prevented from targeting this entity.
     * Only applies to player-owned mobs vs. non-hostile travellers/workers.
     */
    public static boolean isProtected(EntityLivingBase target, IEntityOwnable owned) {
        if (!(target instanceof EntityTraveller)) return false;
        EntityTraveller traveller = (EntityTraveller) target;

        // Raiders and any aggressive traveller are valid targets
        if (traveller.isAggressiveToTravellers()) return false;

        Entity mobOwner = owned.getOwner();
        if (!(mobOwner instanceof EntityPlayer)) return false;
        EntityPlayer player = (EntityPlayer) mobOwner;

        // For hired workers: check if their owner is an enemy of the mob's player owner
        if (traveller instanceof EntityTravellerWorker) {
            EntityTravellerWorker worker = (EntityTravellerWorker) traveller;
            if (worker.isHiredFollower()) {
                // Enemy worker → valid target
                if (worker.isEnemyPlayer(player)) return false;
            }
        }

        return true;
    }
}
