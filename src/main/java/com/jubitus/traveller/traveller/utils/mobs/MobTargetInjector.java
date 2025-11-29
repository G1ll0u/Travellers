package com.jubitus.traveller.traveller.utils.mobs;

import com.jubitus.traveller.TravellersModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class MobTargetInjector {


    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent e) {
        if (e.getWorld().isRemote) return;

        Entity ent = e.getEntity();
        if (!(ent instanceof net.minecraft.entity.EntityCreature)) return;
        if (!(ent instanceof net.minecraft.entity.monster.IMob)) return;


        EntityCreature mob = (EntityCreature) ent;
        if (hasTravellerTargetTask(mob)) return;

        mob.targetTasks.addTask(3,
                new EntityAINearestAttackableTarget<>(
                        mob, com.jubitus.traveller.traveller.entityAI.EntityTraveller.class,
                        10, true, false,
                        new com.google.common.base.Predicate<com.jubitus.traveller.traveller.entityAI.EntityTraveller>() {
                            @Override
                            public boolean apply(@javax.annotation.Nullable com.jubitus.traveller.traveller.entityAI.EntityTraveller t) {
                                if (t == null || !t.isEntityAlive() || t.isInvisible()) return false;

                                TravellerBlacklist list = (t instanceof com.jubitus.traveller.traveller.entityAI.EntityTravellerRaider)
                                        ? TravellersModConfig.RAIDER_AGGRO_BLACKLIST
                                        : TravellersModConfig.TRAVELLER_AGGRO_BLACKLIST;

                                // Only allow targeting if THIS mob type is NOT blacklisted for that Traveller kind.
                                return !list.isBlacklisted(mob);
                            }
                        }
                )
        );

    }

    private boolean hasTravellerTargetTask(EntityCreature mob) {
        for (Object o : mob.targetTasks.taskEntries) {
            // Task list entries are TaskEntry with 'action' field, but we can just check class names:
            try {
                net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry entry =
                        (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry) o;
                if (entry.action instanceof EntityAINearestAttackableTarget) {
                    // Rough check: read the generic target class from toString() or keep a marker
                    // Simpler: keep a capability/nbt marker; or just allow duplicates (harmless but messy).
                    // Here we do a conservative check by class name:
                    if (entry.action.toString().contains("EntityTraveller")) return true;
                }
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

}
