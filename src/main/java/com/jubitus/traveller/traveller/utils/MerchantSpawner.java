package com.jubitus.traveller.traveller.utils;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

public class MerchantSpawner {

    public static void spawnMerchant(World world, List<BlockPos> villages) {
        if (villages.isEmpty()) return;
        BlockPos spawnPos = villages.get(0); // spawn at first village

        EntityTraveller merchant = new EntityTraveller(world);
        merchant.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        world.spawnEntity(merchant);
    }
}


