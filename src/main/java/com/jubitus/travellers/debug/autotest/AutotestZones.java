package com.jubitus.travellers.debug.autotest;

import com.jubitus.travellers.Travellers;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Test zones: Forge tickets standing in for players so units tick where a test needs them. Not persisted -
 * TravellerChunkManager releases any ticket restored from a save, so a run re-creates its zones.
 */
public final class AutotestZones {

    private static final List<ForgeChunkManager.Ticket> TICKETS = new ArrayList<>();

    private AutotestZones() {
    }

    /** Returns the number of chunks forced, or -1 when Forge refused a ticket (ticket limit reached). */
    public static int add(WorldServer world, int blockX, int blockZ, int radiusChunks) {
        int cx = blockX >> 4, cz = blockZ >> 4;
        ForgeChunkManager.Ticket ticket = null;
        int forced = 0;
        for (int x = cx - radiusChunks; x <= cx + radiusChunks; x++) {
            for (int z = cz - radiusChunks; z <= cz + radiusChunks; z++) {
                if (ticket == null || ticket.getChunkList().size() >= ticket.getChunkListDepth()) {
                    ticket = ForgeChunkManager.requestTicket(Travellers.INSTANCE, world, ForgeChunkManager.Type.NORMAL);
                    if (ticket == null) return -1;
                    ticket.setChunkListDepth(ticket.getMaxChunkListDepth());
                    TICKETS.add(ticket);
                }
                ForgeChunkManager.forceChunk(ticket, new ChunkPos(x, z));
                // Test worlds are disposable: loading here is deliberate, so the zone is live right away.
                world.getChunkProvider().provideChunk(x, z);
                forced++;
            }
        }
        return forced;
    }

    public static int clear() {
        int n = TICKETS.size();
        for (ForgeChunkManager.Ticket t : TICKETS) ForgeChunkManager.releaseTicket(t);
        TICKETS.clear();
        return n;
    }

    public static int forcedChunkCount() {
        int n = 0;
        for (ForgeChunkManager.Ticket t : TICKETS) n += t.getChunkList().size();
        return n;
    }
}
