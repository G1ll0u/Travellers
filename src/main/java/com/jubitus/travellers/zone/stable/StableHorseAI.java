package com.jubitus.travellers.zone.stable;

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
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;

/**
 * Strips a stable-tracked horse's AI down to exactly what containment needs, the same philosophy
 * {@code TravellerMountSupport.applyBondInvariants} already uses for bonded mounts: no vanilla
 * wander, no panic/flee, and - deliberately - no {@code EntityAIMate}. Breeding is entirely
 * {@link StableWork}'s job now (it adds a bred foal entry directly, loaded or offscreen), so a
 * live pool horse never needs the vanilla mating task, and leaving it on would double-count: one
 * foal from the sim, another from vanilla's own {@code createChild}. Nothing left here could choose
 * to leave the zone.
 */
public final class StableHorseAI {

    private StableHorseAI() {
    }

    /** Swimming + the bounded graze task - see {@link #needsInvariants}. */
    private static final int EXPECTED_TASK_COUNT = 2;

    public static void applyContainmentInvariants(EntityHorse horse, BlockPos center, int halfWidth, int halfDepth) {
        for (EntityAITasks.EntityAITaskEntry entry : new ArrayList<>(horse.tasks.taskEntries)) {
            horse.tasks.taskEntries.remove(entry);
        }
        horse.tasks.addTask(0, new EntityAISwimming(horse));
        horse.tasks.addTask(1, new EntityAIStableGraze(horse, center, halfWidth, halfDepth));
    }

    /** Cheap self-heal check, mirroring the mount system's own - another mod re-adding AI on
     * {@code EntityJoinWorldEvent} is the same risk here as it is for a bonded mount. */
    public static boolean needsInvariants(EntityHorse horse) {
        return horse.tasks.taskEntries.size() != EXPECTED_TASK_COUNT;
    }

}
