package com.jubitus.travellers.horse;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIFollowParent;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAIMate;
import net.minecraft.entity.ai.EntityAIPanic;
import net.minecraft.entity.ai.EntityAIRunAroundLikeCrazy;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.ai.EntityAIWanderAvoidWater;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;

/**
 * Gives a horse that leaves the mount or Stable system its ordinary vanilla behaviour back. The
 * stripped AI those systems use (mount bond invariants, Stable containment) never survives a reload
 * on its own, so without this a released horse would stand frozen until its chunk reloads.
 */
public final class HorseAI {

    /** {@code EntityLiving}'s own FOLLOW_RANGE base; the mount system raises it for route planning. */
    private static final double VANILLA_FOLLOW_RANGE = 16.0D;

    private HorseAI() {
    }

    /** Mirrors {@code AbstractHorse.initEntityAI} exactly, plus vanilla's follow range. */
    public static void restoreVanilla(EntityHorse horse) {
        for (EntityAITasks.EntityAITaskEntry entry : new ArrayList<>(horse.tasks.taskEntries)) {
            horse.tasks.taskEntries.remove(entry);
        }
        horse.tasks.addTask(0, new EntityAISwimming(horse));
        horse.tasks.addTask(1, new EntityAIPanic(horse, 1.2D));
        horse.tasks.addTask(1, new EntityAIRunAroundLikeCrazy(horse, 1.2D));
        horse.tasks.addTask(2, new EntityAIMate(horse, 1.0D, AbstractHorse.class));
        horse.tasks.addTask(4, new EntityAIFollowParent(horse, 1.0D));
        horse.tasks.addTask(6, new EntityAIWanderAvoidWater(horse, 0.7D));
        horse.tasks.addTask(7, new EntityAIWatchClosest(horse, EntityPlayer.class, 6.0F));
        horse.tasks.addTask(8, new EntityAILookIdle(horse));
        horse.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(VANILLA_FOLLOW_RANGE);
    }
}
