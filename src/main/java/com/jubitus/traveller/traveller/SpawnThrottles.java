package com.jubitus.traveller.traveller;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class SpawnThrottles {
    private SpawnThrottles() {}

    public static <T extends EntityLiving> boolean underLocalCap(
            World world, BlockPos pos, Class<T> cls, int cap, int radius) {
        if (cap <= 0) return true;
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - radius, pos.getY() - 32, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + 32, pos.getZ() + radius);
        int count = world.getEntitiesWithinAABB(cls, box, Entity::isEntityAlive).size();
        return count < cap;
    }
}

