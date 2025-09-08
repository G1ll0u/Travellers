package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIAttackMelee;

public class EntityAIAttackMeleeCustom extends EntityAIAttackMelee {

    public EntityAIAttackMeleeCustom(EntityCreature creature, double speedIn, boolean useLongMemory) {
        super(creature, speedIn, useLongMemory); // ensures attacker is properly initialized
        this.setMutexBits(1 | 2);
    }

    @Override
    protected double getAttackReachSqr(EntityLivingBase target) {
        // Use attacker.width from the parent class
        return this.attacker.width * this.attacker.width + target.width * 6.0F;
    }
}

