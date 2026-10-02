package com.jubitus.travellers.entity.entityai.combat;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Some third-party mods (e.g. The Hordes) reach directly into an entity's targetTasks and append
 * their own vanilla target-selector tasks (e.g. a player-only EntityAINearestAttackableTarget) on
 * top of ours, without going through initEntityAI(). Those foreign tasks share the vanilla
 * targeting mutex bit and are unthrottled, so they can win the race to claim attackTarget before
 * our own budgeted scan (CombatTargetScanScheduler) gets a turn — once that happens our own tasks
 * correctly defer to the existing target and never reconsider other candidates, which looked like
 * "raiders spawned by a horde event only ever attack the player".
 * <p>
 * Strips any targetTasks entry that isn't one of our own classes, restoring our own targeting as
 * the sole authority over attackTarget. Movement AI (entity.tasks) and attribute modifiers (e.g. a
 * FOLLOW_RANGE boost) from other mods are left untouched — only a competing target-selector is a
 * problem, and those are the only kind of entry this removes.
 * <p>
 * Runs on a periodic check rather than reacting within the same tick a foreign task gets added —
 * this Forge build's EventPriority has no MONITOR tier to guarantee running strictly after every
 * other mod's LOWEST listener, but it doesn't need one: a foreign task left over from one tick is
 * still there on the next check a few ticks later, so same-tick ordering is irrelevant here.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class ForeignTargetTaskScrubber {

    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final String OWN_PACKAGE_PREFIX = "com.jubitus.travellers.";

    @SubscribeEvent
    public static void onLivingUpdate(LivingEvent.LivingUpdateEvent event) {
        if (!(event.getEntityLiving() instanceof EntityTraveller)) return;
        EntityTraveller traveller = (EntityTraveller) event.getEntityLiving();
        if (traveller.world.isRemote) return;
        if (traveller.ticksExisted % CHECK_INTERVAL_TICKS != 0) return;

        EntityAITasks targetTasks = traveller.targetTasks;
        List<EntityAIBase> foreign = null;
        for (EntityAITasks.EntityAITaskEntry entry : targetTasks.taskEntries) {
            if (!entry.action.getClass().getName().startsWith(OWN_PACKAGE_PREFIX)) {
                if (foreign == null) foreign = new ArrayList<>();
                foreign.add(entry.action);
            }
        }
        if (foreign == null) return;
        for (EntityAIBase task : foreign) targetTasks.removeTask(task);
    }
}
