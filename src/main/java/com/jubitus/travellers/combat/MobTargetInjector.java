package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.combat.lists.TravellerBlacklist;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class MobTargetInjector {

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent e) {
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
                                        ? TravellersDerivedConfig.RAIDER_AGGRO_BLACKLIST
                                        : TravellersDerivedConfig.TRAVELLER_AGGRO_BLACKLIST;

                                // Only allow targeting if THIS mob type is NOT blacklisted for that Traveller kind.
                                return !list.isBlacklisted(mob);
                            }
                        }
                )
        );

    }

    private static boolean hasTravellerTargetTask(EntityCreature mob) {
        for (Object o : mob.targetTasks.taskEntries) {
            // Task list entries are TaskEntry with 'action' field, but we can just check class names:
            try {
                net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry entry =
                        (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry) o;
                if (entry.action instanceof EntityAINearestAttackableTarget) {
                    // Rough check: read the generic target class from toString() or keep a mark
                    // Simpler: keep a capability/nbt mark; or just allow duplicates (harmless but messy).
                    // Here we do a conservative check by class name:
                    if (entry.action.toString().contains("EntityTraveller")) return true;
                }
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

}
