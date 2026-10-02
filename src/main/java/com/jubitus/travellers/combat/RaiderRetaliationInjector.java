package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTravellerRaider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;


// If you have a MODID constant, use it. Otherwise replace "travellers" with your mod id string.
@Mod.EventBusSubscriber(modid = "travellers")
public final class RaiderRetaliationInjector {

    private static final String TAG = "TravAddedRaiderTarget";

    @SubscribeEvent
    public static void onJoinWorld(EntityJoinWorldEvent e) {
        if (e.getWorld().isRemote) return;

        Entity ent = e.getEntity();
        if (!(ent instanceof EntityLiving)) return;

        EntityLiving living = (EntityLiving) ent;

        // Don't add anything to raiders themselves
        if (living instanceof EntityTravellerRaider) return;

        // Only touch entities that are in your RAIDER_ATTACK_LIST (id, namespace, or class match)
        if (!TravellersDerivedConfig.RAIDER_ATTACK_LIST.isListed(living)) return;

        // Avoid double-injecting on chunk reloads
        NBTTagCompound tag = living.getEntityData();
        if (tag.getBoolean(TAG)) return;

        // Only creatures can own target AIs like NearestAttackableTarget
        if (living instanceof EntityCreature) {
            EntityCreature creature = (EntityCreature) living;

            // Optional: skip truly pacifist things (no attack goals)
            if (!hasAnyAttackGoal(creature)) {
                // If you prefer to force them anyway, remove this block.
                tag.setBoolean(TAG, true);
                return;
            }

            // Priority 5 is usually below "attack player/villager" but high enough to matter
            creature.targetTasks.addTask(5, new TargetRaidersGoal(creature));
            tag.setBoolean(TAG, true);
        }
    }

    /**
     * Heuristic: see if the mob already has some attack AI so we don’t add a target to pacifists.
     */
    private static boolean hasAnyAttackGoal(EntityCreature creature) {
        for (EntityAITasks.EntityAITaskEntry en : creature.tasks.taskEntries) {
            Object ai = en.action;
            // Common vanilla attack goals. Add others your ecosystem uses.
            if (ai instanceof net.minecraft.entity.ai.EntityAIAttackMelee) return true;
            if (ai instanceof net.minecraft.entity.ai.EntityAIAttackRanged) return true;       // skeleton-style
            if (ai instanceof net.minecraft.entity.ai.EntityAIAttackRangedBow) return true;  // bow-style
        }
        return false;
    }

    /**
     * A tiny wrapper just so we can recognize the task type if you ever need to remove it.
     */
    public static final class TargetRaidersGoal extends EntityAINearestAttackableTarget<EntityTravellerRaider> {
        public TargetRaidersGoal(EntityCreature owner) {
            super(owner,
                    EntityTravellerRaider.class,
                    10,        // check interval
                    true,      // must see target
                    false,     // ignore invisible
                    raider -> true); // predicate (keep all raiders)
        }
    }
}
