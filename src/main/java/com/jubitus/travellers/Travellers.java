package com.jubitus.travellers;

import com.jubitus.travellers.follow.gui.FormationHubKeyHandler;
import com.jubitus.travellers.worldmap.gui.WorldMapKeyHandler;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.gui.ClientGuiHandler;
import com.jubitus.travellers.gui.CommonGuiHandler;
import com.jubitus.travellers.sound.ModSounds;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.zone.ZoneInteractionHandler;
import com.jubitus.travellers.combat.TravellerOffscreenHandler;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.jubiconfig.JubiConfig;
import com.jubitus.jubiconfig.command.CommandModConfig;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import com.jubitus.travellers.command.CommandGlowTravellers;
import com.jubitus.travellers.command.CommandMountTraveller;
import com.jubitus.travellers.command.CommandSummonRaiders;
import com.jubitus.travellers.command.CommandSummonWorkers;
import com.jubitus.travellers.command.CommandTravellerCount;
import com.jubitus.travellers.debug.CommandTravellerDebug;
import com.jubitus.travellers.command.CommandTravellerReload;
import com.jubitus.travellers.command.CommandTravellersShelter;
import com.jubitus.travellers.command.TravellerCommandChunks;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.TravellerWorkerLootHandler;
import com.jubitus.travellers.ground.chunk.TravellerChunkManager;
import com.jubitus.travellers.spawn.TravellerSoftSpawner;
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
import net.minecraftforge.fml.common.event.FMLLoadCompleteEvent;
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


@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION,
        dependencies = "required-after:millenaire",
        guiFactory = "com.jubitus.travellers.shadow.jubiconfig.factory.ModGuiFactory")
public class Travellers {
    /**
     * Tracking range (blocks) for every entity this mod registers - and, through
     * {@code MixinEntityTracker}, for the bonded horses they ride.
     *
     * <p>One constant on purpose: vanilla tracks a horse at 80 ({@code EntityTracker}'s {@code IAnimals}
     * branch), and a rider tracked further than its own mount is a guaranteed desync, not a race -
     * {@code EntityTrackerEntry} sends a riding entity no position packets at all, so between the
     * horse's range and the rider's the client has a rider it cannot place and no vehicle to place it
     * on. The two numbers must agree, so they are the same number.
     */
    public static final int ENTITY_TRACKING_RANGE = 128;

    /** Position update frequency for the same set of entities. Vanilla animals use 3, players 2. */
    public static final int ENTITY_UPDATE_FREQUENCY = 2;
    @Mod.Instance(Tags.MOD_ID)
    public static Travellers INSTANCE;


    @Mod.EventHandler
    public void serverLoad(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandGlowTravellers());
        event.registerServerCommand(new CommandTravellerReload());
        event.registerServerCommand(new CommandTravellerCount());
        event.registerServerCommand(new CommandMountTraveller());
        event.registerServerCommand(new CommandTravellerDebug());
        event.registerServerCommand(new com.jubitus.travellers.command.CommandTravellersShelter());
        event.registerServerCommand(new TravellerCommandChunks());
        event.registerServerCommand(new CommandSummonWorkers());
        event.registerServerCommand(new CommandSummonRaiders());
        event.registerServerCommand(new CommandModConfig());
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        TravellersNetwork.init();               // <-- runs on both sides
        boolean isClient = event.getSide().isClient();

        // Load config
        File configDir = new File(event.getModConfigurationDirectory(), "travellers");
        JubiConfig.builder("travellers", "Travellers", TravellersConfig.class, TravellersNetwork.CHANNEL, configDir)
                .command("travellersconfig")
                .onSave(() -> {
                    TravellersDerivedConfig.refresh();
                    TravellersDerivedConfig.applyRuntimeEffects();
                })
                .register();
        TravellersDerivedConfig.refresh();
        ConfigManager.sync(Tags.MOD_ID, Config.Type.INSTANCE);
        TravellerChunkManager.registerCallback();                 // ForgeChunkManager callback
        MinecraftForge.EVENT_BUS.register(TravellerChunkManager.INSTANCE); // tick/unload events
        MinecraftForge.EVENT_BUS.register(new ZoneInteractionHandler());
        MinecraftForge.EVENT_BUS.register(TravellerWorkerLootHandler.class);
        MinecraftForge.EVENT_BUS.register(TravellerOffscreenHandler.class);
        MinecraftForge.EVENT_BUS.register(ModSounds.class);
        MinecraftForge.EVENT_BUS.register(TravellerSoftSpawner.class);
        MinecraftForge.EVENT_BUS.register(com.jubitus.travellers.raid.RaidScheduler.class);
        MinecraftForge.EVENT_BUS.register(com.jubitus.travellers.debug.freeze.FreezeWatchdog.class);
        // Virtualizes travellers/raiders/workers the instant their chunk unloads (the mirror of
        // VirtualTravellerManager materializing them when a chunk loads).
        MinecraftForge.EVENT_BUS.register(
                com.jubitus.travellers.virtual.ChunkUnloadVirtualizeHandler.class);


        if (event.getSide().isClient()) {
            NetworkRegistry.INSTANCE.registerGuiHandler(Travellers.INSTANCE, new ClientGuiHandler());
        } else {
            NetworkRegistry.INSTANCE.registerGuiHandler(Travellers.INSTANCE, new CommonGuiHandler());
        }

        // Client-only stuff
        if (event.getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(com.jubitus.travellers.sound.client.SoundCodecHooks.class);
            MinecraftForge.EVENT_BUS.register(com.jubitus.travellers.gui.hub.WorkerHubNavState.class);
            ClientProxy.registerRenderers();
            FormationHubKeyHandler formationKeyHandler = new FormationHubKeyHandler();
            FormationHubKeyHandler.register();
            MinecraftForge.EVENT_BUS.register(formationKeyHandler);
            WorldMapKeyHandler worldMapKeyHandler = new WorldMapKeyHandler();
            WorldMapKeyHandler.register();
            MinecraftForge.EVENT_BUS.register(worldMapKeyHandler);
        }


        // --- Register travellers entities if enabled in config ---
        if (TravellersConfig.GENERAL.enableTravellerEntity) {

            // Normal travellers
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Tags.MOD_ID, "traveller"),
                    EntityTraveller.class,
                    Tags.MOD_ID + ".traveller",
                    1,              // ID already used by you
                    this,
                    ENTITY_TRACKING_RANGE, ENTITY_UPDATE_FREQUENCY, true,
                    0xaca288, 0x7D4D33
            );

            // Entity id 5 was the removed legacy merchant: never reuse it.
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Tags.MOD_ID, "traveller_worker"),
                    EntityTravellerWorker.class,              // <-- you'll create this class
                    Tags.MOD_ID + ".traveller_worker",   // old name, no colon
                    6,                                          // <-- NEW unique numeric ID
                    this,
                    ENTITY_TRACKING_RANGE,                      // tracking range (shared constant)
                    ENTITY_UPDATE_FREQUENCY,                    // update frequency
                    true,                                       // send velocity updates
                    0xEAE8B4,                                   // egg primary colour (optional)
                    0x480607                                    // egg secondary colour (optional)
            );
            // Boat
            EntityRegistry.registerModEntity(
                    new ResourceLocation(Tags.MOD_ID, "traveller_boat"),
                    EntityTravellerBoat.class,
                    "traveller_boat",
                    4,
                    this,
                    ENTITY_TRACKING_RANGE,
                    ENTITY_UPDATE_FREQUENCY,
                    true
            );

            // Raider
            if (TravellersConfig.GENERAL.enableTravellerRaider) {
                EntityRegistry.registerModEntity(
                        new ResourceLocation(Tags.MOD_ID, "traveller_raider"),
                        EntityTravellerRaider.class,
                        Tags.MOD_ID + ".traveller_raider",
                        2,
                        this,
                        ENTITY_TRACKING_RANGE, ENTITY_UPDATE_FREQUENCY, true,
                        0x3d2c1e, 0x8b2e1a
                );
            }

            // Note: travellers/raiders/workers no longer register a vanilla biome spawn entry — they
            // spawn exclusively through the virtual population controller (TravellerSoftSpawner).

        } else {
            System.out.println("[Travellers] Traveller entity disabled in config.");
        }

    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        CultureRegistry.initMillenaireData();
        ModSounds.assignVoicesToCultures(); // ✅ add this
    }


    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler
                .instance()
                .getMinecraftServerInstance();
        // Every open trace has to be closed here, or it never gets its summary written.
        com.jubitus.travellers.debug.trace.TravellerTracer.shutdown(server);
        com.jubitus.travellers.debug.profile.MovementProfiler.shutdown();
        com.jubitus.travellers.debug.freeze.FreezeWatchdog.reset();

        // Static per-dimension caches keyed by dimension id alone would otherwise bleed into the next
        // world loaded in this same client session (two different save files can both use dimension 0).
        com.jubitus.travellers.zone.stable.StableCoordinator.resetAll();
        com.jubitus.travellers.zone.ZoneRoleRescan.resetAll();
        com.jubitus.travellers.raid.RaidScheduler.onServerStopping();
        com.jubitus.travellers.zone.stable.StableWatchLog.closeAll();

        com.jubitus.travellers.travel.destination.TravelDestinationChooser.resetAll();
        com.jubitus.travellers.debug.destination.DestinationChoiceLog.close();

        if (server == null) return;

        // Worldmap tiles are persisted off-thread (see MapPyramidPersistenceEvents); make sure
        // any in-flight writes actually land on disk before the process exits.
        com.jubitus.travellers.worldmap.pyramid.MapPyramidStore.shutdownIOAndAwait();

        // Terrain profile grid: flush every dimension (WorldEvent.Unload already did, this is
        // belt-and-braces) and wait for the off-thread region writer to drain.
        com.jubitus.travellers.pathing.terrain.TerrainWorldState.disposeAll();
        com.jubitus.travellers.pathing.terrain.TerrainProfileIO.shutdownAndAwait();

        // Route planner thread pool + cache.
        com.jubitus.travellers.pathing.route.TravelRoutePlanner.shutdown();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        CultureRegistry.initMillenaireData();
        ModSounds.assignVoicesToCultures(); // ✅ add this
    }

    /**
     * Fires once, right after every mod finishes loading — before the main menu is even shown, the
     * earliest point client-local settings (config dir, {@code DialogueClientSettings}'s own
     * properties file) are safely readable. The one hook for "optional model preload on startup".
     */
    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        if (event.getSide().isClient()) {
            com.jubitus.travellers.dialogue.client.DialogueModelPreloader.preloadIfEnabled();
        }
    }

}
