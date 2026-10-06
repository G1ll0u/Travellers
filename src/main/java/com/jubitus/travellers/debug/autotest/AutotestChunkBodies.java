package com.jubitus.travellers.debug.autotest;

import com.jubitus.travellers.virtual.VirtualTravellerManager;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Watches unit bodies written into and read back from chunk files: a body vanilla kills on load (saved
 * outside its chunk) or refuses as a duplicate (its unit is alive elsewhere) is a failure, reported with
 * the circumstances of the save that wrote it. Only on a server started by the autotest runner.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class AutotestChunkBodies {

    private static final boolean WATCHING = Boolean.getBoolean("travellers.autotest");
    private static final Map<UUID, String> lastSave = new HashMap<>();

    private AutotestChunkBodies() {
    }

    @SubscribeEvent
    public static void onSave(ChunkDataEvent.Save event) {
        if (!WATCHING || !(event.getWorld() instanceof WorldServer)) return;
        WorldServer world = (WorldServer) event.getWorld();
        Chunk chunk = event.getChunk();
        NBTTagList list = event.getData().getCompoundTag("Level").getTagList("Entities", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            scanSaved(world, chunk, list.getCompoundTagAt(i), null);
        }
    }

    private static void scanSaved(WorldServer world, Chunk chunk, NBTTagCompound tag, String vehicle) {
        String id = tag.getString("id");
        if (id.startsWith("travellers:")) {
            UUID uuid = tag.getUniqueId("UUID");
            Entity live = world.getEntityFromUuid(uuid);
            String where = live == null ? "not loaded" : "registered in chunk " + live.chunkCoordX + "," + live.chunkCoordZ;
            String circumstances = String.format("saved in chunk %d,%d at tick %d%s%s, live body %s", chunk.x, chunk.z,
                    world.getTotalWorldTime(), vehicle != null ? " as a rider of " + vehicle : "",
                    serverRunning() ? "" : " during shutdown", where);
            lastSave.put(uuid, circumstances);
            if (!inChunk(tag, chunk)) {
                AutotestInvariants.fail("unit saved outside its chunk: " + describe(tag) + ", " + circumstances);
            }
        }
        NBTTagList passengers = tag.getTagList("Passengers", 10);
        for (int i = 0; i < passengers.tagCount(); i++) {
            scanSaved(world, chunk, passengers.getCompoundTagAt(i), id);
        }
    }

    // INVARIANT: ChunkDataEvent.Load fires once bodies are in the chunk's lists (vanilla already killed misplaced ones) but before they join the world.
    @SubscribeEvent
    public static void onLoad(ChunkDataEvent.Load event) {
        if (!WATCHING || !(event.getWorld() instanceof WorldServer)) return;
        WorldServer world = (WorldServer) event.getWorld();
        Chunk chunk = event.getChunk();
        NBTTagList list = event.getData().getCompoundTag("Level").getTagList("Entities", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            scanLoaded(world, chunk, list.getCompoundTagAt(i));
        }
    }

    private static void scanLoaded(WorldServer world, Chunk chunk, NBTTagCompound tag) {
        if (tag.getString("id").startsWith("travellers:")) {
            UUID uuid = tag.getUniqueId("UUID");
            Entity body = bodyInChunk(chunk, uuid);
            Entity live = world.getEntityFromUuid(uuid);
            String saved = lastSave.getOrDefault(uuid, "saved before this run");
            String where = " in chunk " + chunk.x + "," + chunk.z + ", " + saved;
            if (body == null || body.isDead) {
                AutotestInvariants.fail("unit killed on load (outside its chunk): " + describe(tag) + where);
            } else if (live != null && !live.isDead) {
                Chunk loaded = com.jubitus.travellers.ground.chunk.LoadedChunks.peek(world, chunk.x, chunk.z);
                AutotestInvariants.fail("duplicate body refused on load: " + describe(tag) + where + ", unit alive in chunk "
                        + live.chunkCoordX + "," + live.chunkCoordZ + " for " + live.ticksExisted + " ticks, riding "
                        + (live.getRidingEntity() != null) + ", chunk already loaded " + (loaded == null ? "no" : loaded == chunk ? "as this object" : "as another object")
                        + ", loaded by " + loader());
            } else if (VirtualTravellerManager.get(world).isVirtual(uuid)) {
                AutotestInvariants.fail("body loaded for a virtual unit: " + describe(tag) + where);
            }
        }
        NBTTagList passengers = tag.getTagList("Passengers", 10);
        for (int i = 0; i < passengers.tagCount(); i++) {
            scanLoaded(world, chunk, passengers.getCompoundTagAt(i));
        }
    }

    /** The first few frames above the chunk loader, so a reload names whoever asked for it. */
    private static String loader() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement f : new Throwable().getStackTrace()) {
            String c = f.getClassName();
            if (c.startsWith("com.jubitus.travellers.debug.autotest") || c.startsWith("net.minecraftforge.fml.common.eventhandler")
                    || c.startsWith("java.") || c.startsWith("sun.") || c.contains("ASMEventHandler")) continue;
            if (n > 0) sb.append(" < ");
            sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
            if (++n == 8) break;
        }
        return sb.toString();
    }

    private static Entity bodyInChunk(Chunk chunk, UUID uuid) {
        for (net.minecraft.util.ClassInheritanceMultiMap<Entity> bucket : chunk.getEntityLists()) {
            for (Entity e : bucket) {
                if (uuid.equals(e.getUniqueID())) return e;
            }
        }
        return null;
    }

    private static boolean inChunk(NBTTagCompound tag, Chunk chunk) {
        NBTTagList pos = tag.getTagList("Pos", 6);
        return MathHelper.floor(pos.getDoubleAt(0)) >> 4 == chunk.x && MathHelper.floor(pos.getDoubleAt(2)) >> 4 == chunk.z;
    }

    private static String describe(NBTTagCompound tag) {
        NBTTagList pos = tag.getTagList("Pos", 6);
        return String.format("%s %s %s (%.1f, %.1f, %.1f)", tag.getString("id"), tag.getString("CustomName"),
                tag.getUniqueId("UUID"), pos.getDoubleAt(0), pos.getDoubleAt(1), pos.getDoubleAt(2));
    }

    private static boolean serverRunning() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server != null && server.isServerRunning();
    }
}
