package com.jubitus.travellers.entity.entityAI.follow.formations;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;

import java.util.HashMap;
import java.util.Map;

public final class WorkerFormationSavedData extends WorldSavedData {

    public static final String DATA_NAME = "travellers_worker_formations_v1";

    private final Map<String, WorkerFormationSettings> settingsByScope = new HashMap<>();

    public WorkerFormationSavedData() {
        super(DATA_NAME);
    }

    public WorkerFormationSavedData(String name) {
        super(name);
    }

    public static WorkerFormationSavedData get(World world) {
        WorldServer root = resolveRootWorld(world);
        MapStorage storage = root.getPerWorldStorage();

        WorkerFormationSavedData data =
                (WorkerFormationSavedData) storage.getOrLoadData(WorkerFormationSavedData.class, DATA_NAME);

        if (data == null) {
            data = new WorkerFormationSavedData();
            storage.setData(DATA_NAME, data);
        }

        return data;
    }

    public WorkerFormationSettings getSettings(WorkerFormationScopeKey scope) {
        WorkerFormationSettings out = settingsByScope.get(scope.asMapKey());
        return out == null ? WorkerFormationSettings.defaults() : out.copy();
    }

    public void setSettings(WorkerFormationScopeKey scope, WorkerFormationSettings settings) {
        if (scope == null || settings == null) {
            return;
        }

        settingsByScope.put(scope.asMapKey(), settings.copy().clamp());
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        settingsByScope.clear();

        NBTTagList list = nbt.getTagList("Entries", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            if (!entry.hasKey("Scope", Constants.NBT.TAG_COMPOUND)) {
                continue;
            }
            if (!entry.hasKey("Settings", Constants.NBT.TAG_COMPOUND)) {
                continue;
            }

            WorkerFormationScopeKey scope = WorkerFormationScopeKey.fromNbt(entry.getCompoundTag("Scope"));
            WorkerFormationSettings settings = WorkerFormationSettings.fromNbt(entry.getCompoundTag("Settings"));
            settingsByScope.put(scope.asMapKey(), settings);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();

        for (Map.Entry<String, WorkerFormationSettings> e : settingsByScope.entrySet()) {
            String mapKey = e.getKey();
            WorkerFormationSettings settings = e.getValue();
            if (settings == null) {
                continue;
            }

            String[] split = mapKey.split(":", 2);
            if (split.length != 2) {
                continue;
            }

            NBTTagCompound entry = new NBTTagCompound();
            entry.setTag("Scope", new WorkerFormationScopeKey(
                    WorkerFormationScopeKey.Type.valueOf(split[0]),
                    java.util.UUID.fromString(split[1])
            ).toNbt());
            entry.setTag("Settings", settings.toNbt());
            list.appendTag(entry);
        }

        nbt.setTag("Entries", list);
        return nbt;
    }

    private static WorldServer resolveRootWorld(World world) {
        if (world instanceof WorldServer) {
            WorldServer ws = (WorldServer) world;
            if (ws.getMinecraftServer() != null && ws.getMinecraftServer().getWorld(0) != null) {
                return ws.getMinecraftServer().getWorld(0);
            }
            return ws;
        }

        WorldServer overworld = DimensionManager.getWorld(0);
        if (overworld != null) {
            return overworld;
        }

        throw new IllegalStateException("No server world available for WorkerFormationSavedData");
    }
}