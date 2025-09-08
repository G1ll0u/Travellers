package com.jubitus.traveller;

import com.jubitus.traveller.traveller.render.ClientProxy;
import com.jubitus.traveller.traveller.commands.CommandSpawnTraveller;
import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.utils.MobTargetInjector;
import com.jubitus.traveller.traveller.utils.VillageIndex;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.monster.IMob;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.jubitus.traveller.traveller.utils.VillageDataLoader.loadVillageCenters;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION, dependencies = "required-after:millenaire")

public class Travellers {
    public static List<BlockPos> VILLAGE_CENTERS = new ArrayList<>();
    @Mod.EventHandler
    public void serverLoad(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSpawnTraveller());
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File millenaireFolder = new File("millenaire");
        VILLAGE_CENTERS = loadVillageCenters(millenaireFolder);

        // Load config
        File configFile = event.getSuggestedConfigurationFile();
        TravellersModConfig.init(configFile);

        // Client-only stuff
        if (event.getSide().isClient()) {
            ClientProxy.registerRenderers();
        }

        // --- Register traveller entityAI if enabled in config ---
        if (TravellersModConfig.enableTravellerEntity && TravellersModConfig.TravellerWeight > 0) {
            int entityId = 1; // unique for this mod
            EntityRegistry.registerModEntity(
                    new ResourceLocation("travellers", "traveller"),
                    EntityTraveller.class,
                    "Traveller",
                    entityId,
                    this,
                    128, 1, true,
                    0x996600, 0x00ff00 // spawn egg colors
            );

            // Add spawn to all biomes
            for (Biome biome : ForgeRegistries.BIOMES) {
                EntityRegistry.addSpawn(
                        EntityTraveller.class,
                        TravellersModConfig.TravellerWeight, // spawn weight
                        1,  // min group size
                        3,  // max group size
                        EnumCreatureType.CREATURE,
                        biome
                );
            }
        } else {
            System.out.println("[Travellers] Traveller entityAI disabled in config.");
        }

        // Other event bus registrations
        MinecraftForge.EVENT_BUS.register(new MobTargetInjector());
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event) {
        if (!event.getWorld().isRemote) {
            VillageIndex.forceRefresh(event.getWorld());
        }
    }
    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            VillageIndex.clear(event.getWorld());
        }
    }
    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        VillageIndex.clearAll();
    }
    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getEntity() instanceof EntityCreature && event.getEntity() instanceof IMob) {
            EntityCreature mob = (EntityCreature) event.getEntity();

            // Add targeting for your traveller
            mob.targetTasks.addTask(3,
                    new EntityAINearestAttackableTarget<>(mob, EntityTraveller.class, true));
        }
    }
}
