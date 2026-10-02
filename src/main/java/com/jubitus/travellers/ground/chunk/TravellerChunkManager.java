package com.jubitus.travellers.ground.chunk;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server tick hub for everything this mod simulates outside the player's presence, and the owner of
 * this mod's (now empty) chunk-loading ticket.
 *
 * <p><b>This class no longer force-loads anything.</b> It used to keep a corridor of chunks loaded
 * along a hired worker's route so it could complete missions far from any player - the mod's original
 * answer to "how does a worker get anywhere while nobody is watching". That answer has been replaced
 * wholesale by the offscreen simulation ({@link VirtualTravellerManager}), which walks units with zero
 * chunk loading, and the two are fundamentally incompatible: a unit that force-loads the chunk it is
 * standing on makes that chunk loaded by definition, and "is my chunk loaded" is exactly the predicate
 * that decides whether a unit should be a real entity at all. Such a worker could never become virtual
 * again, so it would keep its own chunks - and every chunk it walked through - loaded forever, which is
 * the lag this whole system exists to avoid.
 *
 * <p>The ForgeChunkManager callback is kept solely to release tickets restored from worlds saved by an
 * older version, so their forced chunks are let go on load instead of lingering.
 */
public class TravellerChunkManager implements ForgeChunkManager.LoadingCallback {
    public static final TravellerChunkManager INSTANCE = new TravellerChunkManager();

    // Dimension -> state
    private final Map<Integer, WorldState> worldStates = new HashMap<>();

    private TravellerChunkManager() {
    }

    public static void registerCallback() {
        ForgeChunkManager.setForcedChunkLoadingCallback(Travellers.INSTANCE, INSTANCE);
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        if (!(event.world instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.world;
        WorldState state = getOrCreateState(ws);
        long now = ws.getTotalWorldTime();

        // Offscreen units: simulate movement and handle materialization/virtualization
        VirtualTravellerManager.get(ws).tick(ws, now);

        // Newly loaded chunks: read once each, fed to the terrain grid and the worldmap
        ChunkReader.tick(ws);

        // Terrain profile grid: publish finished region loads, flush dirty regions
        com.jubitus.travellers.pathing.terrain.TerrainWorldState terrainState =
                com.jubitus.travellers.pathing.terrain.TerrainWorldState.get(ws);
        if (terrainState != null) terrainState.tick(ws, now);

        // Debug traces keep a small rolling buffer per loaded traveller; drop the ones whose entity
        // is gone so a long session can't grow that map without bound.
        if (now % 200L == 0L) {
            com.jubitus.travellers.debug.trace.TravellerTracer.pruneStale(ws);
        }
        // Feed the live on-screen panel. Only while something is actually traced, and only a few
        // times a second - the client stops showing it on its own once the packets stop.
        if (now % 5L == 0L && com.jubitus.travellers.debug.trace.TravellerTracer.isActive()) {
            int[][] ids = new int[1][];
            java.util.List<String> lines =
                    com.jubitus.travellers.debug.trace.TravellerTracer.buildHudLines(ws, ids);
            if (!lines.isEmpty()) {
                com.jubitus.travellers.server.net.handler.TravellerFollowNet.CHANNEL.sendToDimension(
                        new com.jubitus.travellers.server.net.message.debug.MsgTraceHud(lines, ids[0]),
                        ws.provider.getDimension());
            }
        }

        // Campsite discovery probes + camp lifecycle (budgeted, no chunk force-loading)
        com.jubitus.travellers.camp.CampsiteManager.get(ws).tick(ws, now);

        // Paced physical reset job started by "/travellersdebug camp reset", if one is running
        com.jubitus.travellers.camp.CampResetCoordinator.INSTANCE.tick(ws, now);

        // Virtual battles: encounter detection, offscreen combat sim, deferred loot
        com.jubitus.travellers.battle.VirtualBattleManager.get(ws).tick(ws, now);
        com.jubitus.travellers.zone.ZoneRoleRescan.tick(ws, now);
        com.jubitus.travellers.zone.stable.StableCoordinator.get(ws).tick(ws, now);
        if (ws.provider.getDimension() == 0) com.jubitus.travellers.zone.ZoneAssignments.tickAudit(ws, now);

        // Loose ex-mounts left by a dead traveller/raider/worker with nowhere to send their horse:
        // remove the ones past their grace period, but only once no player is close enough to notice.
        com.jubitus.travellers.mount.OrphanMountCleanup.tick(ws, now);

        // Riders left on a vehicle that no longer exists, and clients that never learned who is
        // sitting on which horse. Both are invisible to the rider's own tick - see the class doc.
        com.jubitus.travellers.mount.MountSyncReconciler.tick(ws, now);

        // Close doors / fence gates behind travellers once nobody still needs them held open
        com.jubitus.travellers.travel.TravellerDoorways.tick(ws, now);

        // Worldmap: continuous re-sampling of currently-loaded chunks (terrain edits, growing villages)
        com.jubitus.travellers.server.map.pyramid.MapLoadedChunkResampler.INSTANCE.tick(ws, now);

        // Paced physical resweep job started by "/travellersdebug map resweep", if one is running
        com.jubitus.travellers.server.map.pyramid.MapResweepCoordinator.INSTANCE.tick(ws, now);

        // Dead hired workers coming back beside their bed (once per in-game morning, persisted)
        com.jubitus.travellers.zone.PlayerZoneManager.get(ws).tickRespawns(ws);
    }

    private WorldState getOrCreateState(WorldServer ws) {
        return worldStates.computeIfAbsent(ws.provider.getDimension(), dim -> new WorldState());
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getWorld() instanceof WorldServer)) return;
        worldStates.remove(((WorldServer) event.getWorld()).provider.getDimension());
    }

    /**
     * Called by Forge on world load with any ticket this mod persisted previously. Nothing requests
     * tickets any more, so every one of these belongs to an older save: release it, which drops the
     * chunks it was holding. Not releasing them would leave a set of permanently forced chunks with
     * nothing left in the mod that knows what they were for.
     */
    @Override
    public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
        if (world.isRemote) return;
        for (ForgeChunkManager.Ticket ticket : tickets) {
            ForgeChunkManager.releaseTicket(ticket);
        }
    }

    public WorldState getWorldState(World world) {
        if (!(world instanceof WorldServer)) return null;
        return worldStates.get(((WorldServer) world).provider.getDimension());
    }

    public static class WorldState {
    }
}
