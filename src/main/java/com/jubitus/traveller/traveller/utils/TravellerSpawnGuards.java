package com.jubitus.traveller.traveller.utils;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class TravellerSpawnGuards {
    @SubscribeEvent
    public static void onPotentialSpawns(WorldEvent.PotentialSpawns e) {
        if (e.getType() != EnumCreatureType.CREATURE) return;

        final int cap = TravellersModConfig.loadedTravellerCap;
        final int loaded = e.getWorld()
                .getEntities(EntityTraveller.class, net.minecraft.entity.Entity::isEntityAlive)
                .size();

        if (loaded >= cap * 0.7) {
            for (int i = 0; i < e.getList().size(); i++) {
                Biome.SpawnListEntry s = e.getList().get(i);
                if (s.entityClass == EntityTraveller.class) {
                    e.getList().set(i, new Biome.SpawnListEntry(EntityTraveller.class,
                            Math.max(1, s.itemWeight / 3), 1, 1));
                }
            }
        }
    }

    // NATURAL spawns go through CheckSpawn in 1.12
    @SubscribeEvent
    public static void onCheckSpawn(LivingSpawnEvent.CheckSpawn e) {
        if (!(e.getEntity() instanceof EntityTraveller)) return;
        if (e.getWorld().isRemote) return;

        // Optional: only allow natural spawns in Overworld
        if (e.getWorld().provider.getDimension() != 0) {
            e.setResult(net.minecraftforge.fml.common.eventhandler.Event.Result.DENY);
            return;
        }

        final int cap = TravellersModConfig.loadedTravellerCap;
        final int loaded = e.getWorld()
                .getEntities(EntityTraveller.class, net.minecraft.entity.Entity::isEntityAlive)
                .size();
        if (loaded >= cap) {
            e.setResult(net.minecraftforge.fml.common.eventhandler.Event.Result.DENY);
            return;
        }

        // Require being near a village. If there are no villages, this returns false -> DENY.
        final int radius = TravellersModConfig.villagePickRadius; // e.g. 160–256
        final BlockPos pos = new BlockPos(e.getX(), e.getY(), e.getZ());
        if (!MillVillageIndex.isNearVillage(e.getWorld(), pos, radius)) {
            e.setResult(net.minecraftforge.fml.common.eventhandler.Event.Result.DENY);
        }

        // leave DEFAULT to let vanilla proceed
    }

    // Non-natural spawns (eggs, /summon, spawners, structures, etc.) fire SpecialSpawn in 1.12.
    // Do NOT cancel here — that keeps player-spawned entities working even when over cap.
    @SubscribeEvent
    public static void onSpecialSpawn(LivingSpawnEvent.SpecialSpawn e) {
        if (!(e.getEntity() instanceof EntityTraveller)) {
        }
        // Intentionally empty: allow eggs and /summon (and other special spawns) to go through.
        // (Forge 1.12 does not expose a spawn reason to distinguish egg vs spawner.)
    }
}

