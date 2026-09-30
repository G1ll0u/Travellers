package com.jubitus.travellers.entity.entityai.combat;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.world.data.TravellerVillageRelations;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;

import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class VillageRelationsCombatHooks {

    // called once per world tick; we stagger inside
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent e) {
        if (!(e.world instanceof WorldServer)) return;
        if (e.phase != TickEvent.Phase.END) return;

        WorldServer ws = (WorldServer) e.world;
        long t = ws.getTotalWorldTime();

        // every 20 ticks (1 sec), do a light scan
        if ((t % 20L) != 0L) return;

        TravellerVillageRelations rel = TravellerVillageRelations.get(ws);
        int dim = ws.provider.getDimension();

        // You can tighten this by scanning loaded MillVillagers only if you have a list.
        // We do a safe "entities in world" scan by class.
        List<org.millenaire.common.entity.MillVillager> villagers =
                ws.getEntities(org.millenaire.common.entity.MillVillager.class, v -> v != null && v.isEntityAlive());

        for (org.millenaire.common.entity.MillVillager v : villagers) {
            // stagger per entity id to reduce spikes
            if (((v.getEntityId() + t) % 20L) != 0L) continue;

            Building th = v.getTownHall();
            if (th == null) continue;

            Point p = th.getPos();
            if (p == null) continue;

            BlockPos villageCenter = new BlockPos(p.getiX(), p.getiY(), p.getiZ());

            // If current target is a worker but not enemy anymore -> clear
            EntityLivingBase cur = v.getAttackTarget();
            if (cur instanceof EntityTravellerWorker) {
                EntityTravellerWorker w = (EntityTravellerWorker) cur;
                UUID owner = w.getFollowOwnerId();
                if (owner == null || !rel.isEnemy(owner, dim, villageCenter)) {
                    v.setAttackTarget(null);
                } else {
                    continue; // keep fighting
                }
            } else if (cur != null && cur.isEntityAlive()) {
                continue; // villager already fighting something else
            }

            AxisAlignedBB box = v.getEntityBoundingBox().grow(16.0D, 6.0D, 16.0D);
            List<EntityTravellerWorker> workers = ws.getEntitiesWithinAABB(EntityTravellerWorker.class, box,
                    w -> w != null && w.isEntityAlive() && w.isHiredFollower() && w.getFollowOwnerId() != null
            );

            EntityTravellerWorker best = null;
            double bestD2 = Double.MAX_VALUE;

            for (EntityTravellerWorker w : workers) {
                UUID owner = w.getFollowOwnerId();
                if (owner == null) continue;
                if (!rel.isEnemy(owner, dim, villageCenter)) continue;

                double d2 = v.getDistanceSq(w);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = w;
                }
            }

            if (best != null) {
                v.setAttackTarget(best);
                v.setRevengeTarget(best);
            }
        }
    }
}