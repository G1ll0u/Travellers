package com.jubitus.travellers;

import com.jubitus.travellers.client.input.FormationHubKeyHandler;
import com.jubitus.travellers.client.input.WorldMapKeyHandler;
import com.jubitus.travellers.client.render.ClientProxy;
import com.jubitus.travellers.client.sound.ChoirSoundWarmer;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerDog;
import com.jubitus.travellers.client.gui.ClientGuiHandler;
import com.jubitus.travellers.server.gui.CommonGuiHandler;
import com.jubitus.travellers.client.sound.ModSounds;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon.CampfireManager;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeChestHandler;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerOffscreenHandler;
import com.jubitus.travellers.entity.travellers.*;
import com.jubitus.travellers.config.TravellersConfigHistory;
import com.jubitus.travellers.config.TravellersConfigPersistence;
import com.jubitus.travellers.utils.commands.*;
import com.jubitus.travellers.utils.mobs.MobTargetInjector;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.TravellerWorkerLootHandler;
import com.jubitus.travellers.server.net.message.chunks.PacketTravellerChunksClientHandler;
import com.jubitus.travellers.server.net.message.chunks.PacketTravellerChunks;
import com.jubitus.travellers.server.net.message.chunks.PacketTravellerChunksRequest;
import com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager;
import com.jubitus.travellers.utils.world.spawn.TravellerSoftSpawner;
import com.jubitus.travellers.utils.world.spawn.TravellerSpawnGuards;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.jubitus.travellers.utils.world.mill.VillageDataLoader.loadVillageCenters;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION,
        dependencies = "required-after:millenaire",
        guiFactory = "com.jubitus.travellers.client.TravellersGuiFactory")
public class Travellers {
    public static final SimpleNetworkWrapper NETWORK =
            NetworkRegistry.INSTANCE.newSimpleChannel("travellers");
    public static List<BlockPos> VILLAGE_CENTERS = new ArrayList<>();
    @Mod.Instance(Tags.MOD_ID)
    public static Travellers INSTANCE;
    private static int nextId = 0;

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
        event.registerServerCommand(new CommandVillageNeeds());
        event.registerServerCommand(new TravellerCommandChunks());
        event.registerServerCommand(new CommandSummonWorkers());
        event.registerServerCommand(new CommandTravellersConfig());
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File millenaireFolder = new File("millenaire");
        VILLAGE_CENTERS = loadVillageCenters(millenaireFolder);
        TravellerFollowNet.init();               // <-- runs on both sides
        boolean isClient = event.getSide().isClient();

        // Load config
        File configDir = new File(event.getModConfigurationDirectory(), "travellers");
        File configFile = new File(configDir, "travellers.cfg");
        TravellersModConfig.init(configFile);
        TravellersConfigPersistence.applyOnStartup();
        TravellersConfigHistory.loadFromFile();
        TravellersModConfig.syncFromTravellersConfig();
        TravellersModConfig.applyDerivedValues();
        TravellersModConfig.syncServerCachesFromConfig();
        ConfigManager.sync(Tags.MOD_ID, Config.Type.INSTANCE);
        TravellerChunkManager.registerCallback();                 // ForgeChunkManager callback
        MinecraftForge.EVENT_BUS.register(TravellerChunkManager.INSTANCE); // tick/unload events
        MinecraftForge.EVENT_BUS.register(new TravellerHomeChestHandler());
        MinecraftForge.EVENT_BUS.register(TravellerWorkerLootHandler.class);
        MinecraftForge.EVENT_BUS.register(TravellerOffscreenHandler.class);
        MinecraftForge.EVENT_BUS.register(ModSounds.class);
        MinecraftForge.EVENT_BUS.register(MobTargetInjector.class);
        MinecraftForge.EVENT_BUS.register(TravellerSpawnGuards.class);
        MinecraftForge.EVENT_BUS.register(TravellerSoftSpawner.class);


        if (event.getSide().isClient()) {
            NetworkRegistry.INSTANCE.registerGuiHandler(Travellers.INSTANCE, new ClientGuiHandler());
        } else {
            NetworkRegistry.INSTANCE.registerGuiHandler(Travellers.INSTANCE, new CommonGuiHandler());
        }

        // Client-only stuff
        if (event.getSide().isClient()) {
            ClientProxy.registerRenderers();
            FormationHubKeyHandler formationKeyHandler = new FormationHubKeyHandler();
            FormationHubKeyHandler.register();
            MinecraftForge.EVENT_BUS.register(formationKeyHandler);
            WorldMapKeyHandler worldMapKeyHandler = new WorldMapKeyHandler();
            WorldMapKeyHandler.register();
            MinecraftForge.EVENT_BUS.register(worldMapKeyHandler);
        }
        NETWORK.registerMessage(
                isClient ? PacketTravellerChunksClientHandler.class
                        : PacketTravellerChunks.NoopHandler.class,
                PacketTravellerChunks.class,
                nextId++,
                Side.CLIENT
        );

        NETWORK.registerMessage(
                PacketTravellerChunksRequest.Handler.class,
                PacketTravellerChunksRequest.class,
                nextId++,
                Side.SERVER
        );


        // --- Register travellers entities if enabled in config ---
        if (TravellersModConfig.enableTravellerEntity) {

            // Normal travellers
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Tags.MOD_ID, "traveller"),
                    EntityTraveller.class,
                    Tags.MOD_ID + ".traveller",
                    1,              // ID already used by you
                    this,
                    256, 1, true,
                    0xaca288, 0x7D4D33
            );

            // *** NEW: merchant travellers (non-despawning + chunk-loader entity class) ***
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Tags.MOD_ID, "traveller_merchant"),
                    EntityTravellerMerchant.class,              // <-- you'll create this class
                    Tags.MOD_ID + ".traveller_merchant",   // old name, no colon
                    5,                                          // <-- NEW unique numeric ID
                    this,
                    128,                                        // tracking range (match normal travellers)
                    1,                                          // update frequency
                    true,                                       // send velocity updates
                    0xC0A060,                                   // egg primary colour (optional)
                    0x302010                                    // egg secondary colour (optional)
            );
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Tags.MOD_ID, "traveller_worker"),
                    EntityTravellerWorker.class,              // <-- you'll create this class
                    Tags.MOD_ID + ".traveller_worker",   // old name, no colon
                    6,                                          // <-- NEW unique numeric ID
                    this,
                    256,                                        // tracking range (match normal travellers)
                    1,                                          // update frequency
                    true,                                       // send velocity updates
                    0xEAE8B4,                                   // egg primary colour (optional)
                    0x480607                                    // egg secondary colour (optional)
            );
            // Dog
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Tags.MOD_ID, "traveller_dog"),
                    EntityTravellerDog.class,
                    Tags.MOD_ID + ".traveller_dog",
                    3,
                    this,
                    128,
                    1,
                    true,
                    0x6A4E3B, 0xC7A67A
            );

            // Boat
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Tags.MOD_ID, "traveller_boat"),
                    EntityTravellerBoat.class,
                    "traveller_boat",
                    4,
                    this,
                    256,
                    3,
                    true
            );

            // Raider
            if (TravellersModConfig.enableTravellerRaider) {
                EntityRegistry.registerModEntity(
                        new ResourceLocation(Tags.MOD_ID, "traveller_raider"),
                        EntityTravellerRaider.class,
                        Tags.MOD_ID + ".traveller_raider",
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
            System.out.println("[Travellers] Traveller entity disabled in config.");
        }

        // Other event bus registrations
        MinecraftForge.EVENT_BUS.register(TravellerSpawnGuards.class);
        MinecraftForge.EVENT_BUS.register(new MobTargetInjector());
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        CultureRegistry.initMillenaireData();
        ModSounds.assignVoicesToCultures(); // ✅ add this
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
    public void onLoadComplete(net.minecraftforge.fml.common.event.FMLLoadCompleteEvent event) {
        // On s'assure d'exécuter cela uniquement du côté Client (pour éviter les crashs sur un serveur dédié)
        if (event.getSide().isClient()) {
            ChoirSoundWarmer.warmUpAtLaunch();
        }
    }


    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        CultureRegistry.initMillenaireData();
        ModSounds.assignVoicesToCultures(); // ✅ add this
    }


}
