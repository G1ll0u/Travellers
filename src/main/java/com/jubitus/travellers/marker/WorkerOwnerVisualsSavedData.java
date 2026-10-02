package com.jubitus.travellers.marker;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class WorkerOwnerVisualsSavedData extends WorldSavedData {

    public static final String DATA_NAME = "travellers_worker_owner_visuals";
    public static final int DEFAULT_COLOR = OwnerVisualsDefaults.DEFAULT_COLOR;
    public static final String DEFAULT_STYLE = OwnerVisualsDefaults.DEFAULT_STYLE;

    private final Map<UUID, Entry> entries = new HashMap<>();

    public WorkerOwnerVisualsSavedData() {
        super(DATA_NAME);
    }

    public WorkerOwnerVisualsSavedData(String name) {
        super(name);
    }

    public static WorkerOwnerVisualsSavedData get(World world) {
        WorldServer overworld = world.getMinecraftServer() != null
                ? world.getMinecraftServer().getWorld(0)
                : (WorldServer) world;

        MapStorage storage = overworld.getPerWorldStorage();
        WorkerOwnerVisualsSavedData data =
                (WorkerOwnerVisualsSavedData) storage.getOrLoadData(WorkerOwnerVisualsSavedData.class, DATA_NAME);

        if (data == null) {
            data = new WorkerOwnerVisualsSavedData();
            storage.setData(DATA_NAME, data);
        }

        return data;
    }

    public Entry getFor(UUID playerId) {
        Entry entry = entries.get(playerId);
        if (entry == null) {
            return new Entry(DEFAULT_COLOR, DEFAULT_STYLE);
        }
        return entry;
    }

    public void setFor(UUID playerId, int color, String styleId) {
        String safeStyle = WorkerMarkStyle.byId(styleId).id;
        entries.put(playerId, new Entry(color & 0xFFFFFF, safeStyle));
        markDirty();
    }

    /** Pushes a newly-saved mark onto every one of the owner's hired workers, loaded or virtual, so neither ever shows a stale color. */
    public static void applyToWorkers(MinecraftServer server, UUID ownerId, int color, String styleId) {
        String safeStyle = WorkerMarkStyle.byId(styleId).id;
        int safeColor = color & 0xFFFFFF;

        for (WorldServer ws : server.worlds) {
            List<EntityTravellerWorker> workers = ws.getEntities(EntityTravellerWorker.class, w ->
                    w != null
                            && w.isEntityAlive()
                            && w.isHiredFollower()
                            && Objects.equals(w.getFollowOwnerId(), ownerId)
            );

            for (EntityTravellerWorker worker : workers) {
                worker.setOwnerVisuals(safeColor, safeStyle);
            }
        }

        VirtualTravellerManager vwm = VirtualTravellerManager.get(server.worlds[0]);
        boolean changed = false;
        for (VirtualTravellerRecord r : vwm.getRecordsView()) {
            if (r.isHiredFollower && ownerId.equals(r.followOwnerUUID) && r.ownerColor != safeColor) {
                r.ownerColor = safeColor;
                changed = true;
            }
        }
        if (changed) {
            vwm.markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        entries.clear();

        if (!nbt.hasKey("Entries", Constants.NBT.TAG_LIST)) {
            return;
        }

        NBTTagList list = nbt.getTagList("Entries", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            if (!tag.hasKey("PlayerUUID")) {
                continue;
            }

            try {
                UUID playerId = UUID.fromString(tag.getString("PlayerUUID"));
                int color = tag.getInteger("Color") & 0xFFFFFF;
                String styleId = WorkerMarkStyle.byId(tag.getString("Style")).id;
                entries.put(playerId, new Entry(color, styleId));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();

        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("PlayerUUID", e.getKey().toString());
            tag.setInteger("Color", e.getValue().color);
            tag.setString("Style", e.getValue().styleId);
            list.appendTag(tag);
        }

        nbt.setTag("Entries", list);
        return nbt;
    }

    public static final class Entry {
        public final int color;
        public final String styleId;

        public Entry(int color, String styleId) {
            this.color = color & 0xFFFFFF;
            this.styleId = WorkerMarkStyle.byId(styleId).id;
        }
    }
}