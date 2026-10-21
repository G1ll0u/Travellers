package com.jubitus.travellers.combat;

import com.jubitus.travellers.debug.profile.MovementProfiler;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Iterator;
import java.util.Set;

/**
 * Some third-party mods (e.g. The Hordes) reach directly into an entity's targetTasks and append
 * their own vanilla target-selector tasks (e.g. a player-only EntityAINearestAttackableTarget) on
 * top of ours, without going through initEntityAI(). Those foreign tasks share the vanilla
 * targeting mutex bit and are unthrottled, so they can win the race to claim attackTarget before
 * our own budgeted scan (PathBudget COMBAT_SCAN) gets a turn.
 * <p>
 * Strips any targetTasks entry that isn't one of our own classes, restoring our own targeting as
 * the sole authority over attackTarget. Movement AI (entity.tasks) and attribute modifiers (e.g. a
 * FOLLOW_RANGE boost) from other mods are left untouched.
 * <p>
 * Runs every tick, before the entity's AI (LivingUpdateEvent fires first), and removes silently: a
 * running vanilla target task clears the attack target in resetTask(), which vanilla calls both on
 * removeTask() and whenever one of our higher-priority target tasks starts.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class ForeignTargetTaskScrubber {

    private static final String OWN_PACKAGE_PREFIX = "com.jubitus.travellers.";
    // EntityAITasks.executingTaskEntries
    private static final String EXECUTING_FIELD = "field_75780_b";

    @SubscribeEvent
    public static void onLivingUpdate(LivingEvent.LivingUpdateEvent event) {
        if (!(event.getEntityLiving() instanceof EntityTraveller)) return;
        EntityTraveller traveller = (EntityTraveller) event.getEntityLiving();
        if (traveller.world.isRemote) return;

        EntityAITasks targetTasks = traveller.targetTasks;
        Set<EntityAITasks.EntityAITaskEntry> executing = null;
        Iterator<EntityAITasks.EntityAITaskEntry> it = targetTasks.taskEntries.iterator();
        while (it.hasNext()) {
            EntityAITasks.EntityAITaskEntry entry = it.next();
            String cls = entry.action.getClass().getName();
            if (cls.startsWith(OWN_PACKAGE_PREFIX)) continue;
            if (entry.using) {
                if (executing == null) {
                    executing = ObfuscationReflectionHelper.getPrivateValue(EntityAITasks.class, targetTasks, EXECUTING_FIELD);
                }
                executing.remove(entry);
                entry.using = false;
            }
            it.remove();
            MovementProfiler.foreignTargetTask(cls);
        }
    }
}
