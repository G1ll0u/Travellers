package com.jubitus.travellers.utils.mobs;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class MobTargetInjector {

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent e) {
        if (e.getWorld().isRemote) return;

        Entity ent = e.getEntity();
        if (!(ent instanceof EntityCreature)) return;
        if (!(ent instanceof net.minecraft.entity.monster.IMob)) return;


        EntityCreature mob = (EntityCreature) ent;
        if (hasTravellerTargetTask(mob)) return;

        mob.targetTasks.addTask(3,
                new EntityAINearestAttackableTarget<>(
                        mob, EntityTraveller.class,
                        10, true, false,
                        new com.google.common.base.Predicate<EntityTraveller>() {
                            @Override
                            public boolean apply(@javax.annotation.Nullable EntityTraveller t) {
                                if (t == null || !t.isEntityAlive() || t.isInvisible()) return false;

                                TravellerBlacklist list = (t instanceof EntityTravellerRaider)
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
