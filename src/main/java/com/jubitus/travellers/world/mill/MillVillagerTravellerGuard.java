package com.jubitus.travellers.world.mill;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.world.data.TravellerVillageRelations;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.millenaire.common.entity.MillVillager;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;

import java.util.UUID;

public final class MillVillagerTravellerGuard {

    private MillVillagerTravellerGuard() {}

    /**
     * Returns true if this traveller should be allowed to fight the given MillVillager.
     * Raiders always can. Friendly travellers can only fight millager-raiders (isRaider=true).
     * Hired workers can additionally fight if the owner has declared war on the village.
     */
    public static boolean shouldFightMillVillager(EntityTraveller traveller, MillVillager mv) {
        if (traveller.isAggressiveToTravellers()) return true;
        if (mv.isRaider) return true;

        if (traveller instanceof EntityTravellerWorker) {
            EntityTravellerWorker worker = (EntityTravellerWorker) traveller;
            if (!worker.isHiredFollower()) return false;
            UUID ownerId = worker.getFollowOwnerId();
            if (ownerId == null) return false;
            Building th = mv.getTownHall();
            if (th == null) return false;
            Point p = th.getPos();
            if (p == null) return false;
            BlockPos center = new BlockPos(p.getiX(), p.getiY(), p.getiZ());
            if (!(traveller.world instanceof WorldServer)) return false;
            int dim = traveller.world.provider.getDimension();
            return TravellerVillageRelations.get((WorldServer) traveller.world).isEnemy(ownerId, dim, center);
        }

        return false;
    }

    /**
     * Returns true when the MillVillager should NOT retaliate against this attacker.
     * Used by the MillVillager mixin to suppress callForHelp and setRevengeTarget.
     */
    public static boolean shouldSkipRetaliation(EntityLivingBase attacker, MillVillager defender) {
        if (!(attacker instanceof EntityTraveller)) return false;
        EntityTraveller t = (EntityTraveller) attacker;
        return !shouldFightMillVillager(t, defender);
    }
}
