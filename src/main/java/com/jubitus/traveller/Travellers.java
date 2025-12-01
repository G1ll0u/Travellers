package com.jubitus.traveller;

import com.jubitus.traveller.traveller.camp.CampfireManager;
import com.jubitus.traveller.traveller.culture.CultureRegistry;
import com.jubitus.traveller.traveller.entityAI.*;
import com.jubitus.traveller.traveller.pathing.TravellerChunkLoadingCallback;
import com.jubitus.traveller.traveller.pathing.TravellerChunkManager;
import com.jubitus.traveller.traveller.render.ClientProxy;
import com.jubitus.traveller.traveller.utils.TravellerSpawnGuards;
import com.jubitus.traveller.traveller.utils.commands.*;
import com.jubitus.traveller.traveller.utils.debug.FollowTravellerClient;
import com.jubitus.traveller.traveller.utils.debug.TravellerFollowNet;
import com.jubitus.traveller.traveller.utils.mobs.MobTargetInjector;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
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
    @Mod.Instance(Reference.MOD_ID)
    public static Travellers INSTANCE;

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
        event.registerServerCommand(new CommandMountTraveller());
        event.registerServerCommand(new CommandTravellerDebug());
        event.registerServerCommand(new CommandDumpCultureItems());
        event.registerServerCommand(new CommandMillDump());
        event.registerServerCommand(new CommandMillNearestVillageDump());
        event.registerServerCommand(new CommandTravellerMap());
        event.registerServerCommand(new CommandTravellerInfo());
        event.registerServerCommand(new CommandTravellerMerchantDebug());

    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File millenaireFolder = new File("millenaire");
        VILLAGE_CENTERS = loadVillageCenters(millenaireFolder);
        TravellerFollowNet.init();               // <-- runs on both sides


        // Load config
        File configDir = new File(event.getModConfigurationDirectory(), "travellers");
        File configFile = new File(configDir, "travellers.cfg");
        TravellersModConfig.init(configFile);

        TravellerChunkManager.registerCallback();                 // ForgeChunkManager callback
        MinecraftForge.EVENT_BUS.register(TravellerChunkManager.INSTANCE); // tick/unload events
        // Client-only stuff
        if (event.getSide().isClient()) {
            ClientProxy.registerRenderers();
        }

        // --- Register traveller entities if enabled in config ---
        if (TravellersModConfig.enableTravellerEntity) {

            // Normal traveller
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Reference.MOD_ID, "traveller"),
                    EntityTraveller.class,
                    Reference.MOD_ID + ".traveller",
                    1,              // ID already used by you
                    this,
                    128, 1, true,
                    0xaca288, 0x7D4D33
            );

            // *** NEW: merchant traveller (non-despawning + chunk-loader entity class) ***
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Reference.MOD_ID, "traveller_merchant"),
                    EntityTravellerMerchant.class,              // <-- you'll create this class
                    Reference.MOD_ID + ".traveller_merchant",   // old name, no colon
                    5,                                          // <-- NEW unique numeric ID
                    this,
                    32,                                        // tracking range (match normal traveller)
                    1,                                          // update frequency
                    true,                                       // send velocity updates
                    0xC0A060,                                   // egg primary colour (optional)
                    0x302010                                    // egg secondary colour (optional)
            );

            // Dog
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Reference.MOD_ID, "traveller_dog"),
                    EntityTravellerDog.class,
                    Reference.MOD_ID + ".traveller_dog",
                    3,
                    this,
                    128,
                    1,
                    true,
                    0x6A4E3B, 0xC7A67A
            );

            // Boat
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Reference.MOD_ID, "traveller_boat"),
                    EntityTravellerBoat.class,
                    "traveller_boat",
                    4,
                    this,
                    128,
                    3,
                    true
            );

            // Raider
            if (TravellersModConfig.enableTravellerRaider) {
                EntityRegistry.registerModEntity(
                        new ResourceLocation(Reference.MOD_ID, "traveller_raider"),
                        EntityTravellerRaider.class,
                        Reference.MOD_ID + ".traveller_raider",
                        2,
                        this,
                        128, 1, true,
                        0x3d2c1e, 0x8b2e1a
                );

                if (TravellersModConfig.RaiderWeight > 0) {
                    for (Biome biome : ForgeRegistries.BIOMES) {
                        EntityRegistry.addSpawn(
                                EntityTravellerRaider.class,
                                TravellersModConfig.RaiderWeight,
                                TravellersModConfig.RaiderMinGroup,
                                TravellersModConfig.RaiderMaxGroup,
                                EnumCreatureType.CREATURE,
                                biome
                        );
                    }
                }
            }

            // Spawns for normal travellers only (merchant is special, usually no random spawns)
            if (TravellersModConfig.TravellerWeight > 0) {
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
            }

        } else {
            System.out.println("[Travellers] Traveller entityAI disabled in config.");
        }

        // Other event bus registrations
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(TravellerSpawnGuards.class);
        MinecraftForge.EVENT_BUS.register(new MobTargetInjector());
    }

    @Mod.EventHandler
    public void onServerStarting(net.minecraftforge.fml.common.event.FMLServerStartingEvent event) {

        CultureRegistry.initMillenaireData();

    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        // 1.12.2-safe: use FMLCommonHandler, same as your CampfireTicker
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler
                .instance()
                .getMinecraftServerInstance();
        if (server == null) return;

        for (WorldServer ws : server.worlds) {
            CampfireManager.get(ws).extinguishAllForWorld(ws);
        }
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        CultureRegistry.initMillenaireData();
    }

}
