package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

public class EntityAICombatDrawSword extends EntityAIBase {
    private final EntityTraveller entity;

    public EntityAICombatDrawSword(EntityTraveller entity) {
        this.entity = entity;
        this.setMutexBits(1); // non-conflicting with movement
    }

    @Override
    public boolean shouldExecute() {
        return entity.isInCombat();
    }

    @Override
    public void startExecuting() {
        entity.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
    }

    @Override
    public void resetTask() {
        entity.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
    }
}

