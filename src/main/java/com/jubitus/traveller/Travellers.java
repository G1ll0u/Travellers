package com.jubitus.traveller;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.render.ClientProxy;
import com.jubitus.traveller.traveller.utils.TravellerSpawnGuards;
import com.jubitus.traveller.traveller.utils.commands.*;
import com.jubitus.traveller.traveller.utils.debug.FollowTravellerClient;
import com.jubitus.traveller.traveller.utils.debug.TravellerFollowNet;
import com.jubitus.traveller.traveller.utils.mobs.MobTargetInjector;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import com.jubitus.traveller.traveller.utils.villages.VillageIndex;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.monster.IMob;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
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

import static com.jubitus.traveller.traveller.utils.villages.VillageDataLoader.loadVillageCenters;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION, dependencies = "required-after:millenaire")
public class Travellers {
    public static List<BlockPos> VILLAGE_CENTERS = new ArrayList<>();



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


    @SubscribeEvent
    public static void onMouse(MouseEvent e) {
        if (!FollowTravellerClient.isActive()) return;
        int btn = e.getButton();
        if ((btn == 0 || btn == 1) && e.isButtonstate()) {
            e.setCanceled(true); // block attack/use while following
        }
    }

    @Mod.EventHandler
    public void serverLoad(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSpawnTraveller());
        event.registerServerCommand(new CommandGlowTravellers());
        event.registerServerCommand(new CommandTravellerReload());
        event.registerServerCommand(new CommandFollowTraveller());
        event.registerServerCommand(new CommandTravellerCount());
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File millenaireFolder = new File("millenaire");
        VILLAGE_CENTERS = loadVillageCenters(millenaireFolder);
        TravellerFollowNet.init();               // <-- runs on both sides
        // Load config
        File configFile = event.getSuggestedConfigurationFile();
        TravellersModConfig.init(configFile);

        // Client-only stuff
        if (event.getSide().isClient()) {
            ClientProxy.registerRenderers();
        }

        // --- Register traveller entity if enabled in config ---
        if (TravellersModConfig.enableTravellerEntity) {
            int entityId = 1; // unique for this mod
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Reference.MOD_ID, "traveller"),  // registry name
                    EntityTraveller.class,
                    Reference.MOD_ID + ".traveller",                      // <<< globally-unique old name (no colon)
                    1,                                                   // per-mod unique ID
                    this,
                    128, 1, true,
                    0xaca288, 0x7D4D33
            );

            // Add spawn to all biomes
            for (Biome biome : ForgeRegistries.BIOMES) {
                EntityRegistry.addSpawn(
                        EntityTraveller.class,
                        TravellersModConfig.TravellerWeight,
                        TravellersModConfig.MinGroupSizeAtSpawn,
                        TravellersModConfig.MaxGroupSizeAtSpawn,
                        EnumCreatureType.CREATURE,
                        biome
                );
            }
        } else {
            System.out.println("[Travellers] Traveller entityAI disabled in config.");
        }

        // Other event bus registrations
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(TravellerSpawnGuards.class);
        MinecraftForge.EVENT_BUS.register(new MobTargetInjector());
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        VillageIndex.clearAll();
    }
}
