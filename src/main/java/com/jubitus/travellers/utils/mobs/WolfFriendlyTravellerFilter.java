package com.jubitus.travellers.utils.mobs;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;

public final class WolfFriendlyTravellerFilter {

    private WolfFriendlyTravellerFilter() {}

    /**
     * Returns true if the wolf should be prevented from targeting this entity.
     * Only applies to player-owned wolves vs. non-hostile travellers/workers.
     */
    public static boolean isProtected(EntityLivingBase target, EntityWolf wolf) {
        if (!(target instanceof EntityTraveller)) return false;
        EntityTraveller traveller = (EntityTraveller) target;

        // Raiders and any aggressive traveller are valid targets
        if (traveller.isAggressiveToTravellers()) return false;

        // For hired workers: check if their owner is an enemy of the wolf's player owner
        if (traveller instanceof EntityTravellerWorker) {
            EntityTravellerWorker worker = (EntityTravellerWorker) traveller;
            if (worker.isHiredFollower()) {
                EntityLivingBase wolfOwner = wolf.getOwner();
                if (wolfOwner instanceof EntityPlayer) {
                    // Enemy worker → valid target
                    if (worker.isEnemyPlayer((EntityPlayer) wolfOwner)) return false;
                }
            }
        }

        return true;
    }
}
