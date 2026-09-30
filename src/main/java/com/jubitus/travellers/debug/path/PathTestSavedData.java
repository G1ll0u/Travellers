package com.jubitus.travellers.debug.path;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists the {@code /travellersdebug path} pos1/pos2/marks per player, the same way
 * {@code WorkerOwnerVisualsSavedData} persists per-player mark colours. Without this, every save
 * reload wiped the comparison points - defeating the entire point of the tool, which is testing the
 * *same* route across iterations of a fix. Only the route definition persists; the active test
 * entity, its trace file and whether it was mounted are session-only (see {@link PathTestManager}),
 * same as a trace session never surviving a restart.
 */
public class PathTestSavedData extends WorldSavedData {

    public static final String DATA_NAME = "travellers_path_test";

    private final Map<UUID, Entry> entries = new HashMap<>();

    public PathTestSavedData() {
        super(DATA_NAME);
    }

    public PathTestSavedData(String name) {
        super(name);
    }

    public static PathTestSavedData get(World world) {
        WorldServer overworld = world.getMinecraftServer() != null
                ? world.getMinecraftServer().getWorld(0)
                : (WorldServer) world;

        MapStorage storage = overworld.getPerWorldStorage();
        PathTestSavedData data = (PathTestSavedData) storage.getOrLoadData(PathTestSavedData.class, DATA_NAME);

        if (data == null) {
            data = new PathTestSavedData();
            storage.setData(DATA_NAME, data);
        }

        return data;
    }

    public Entry getFor(UUID playerId) {
        return entries.computeIfAbsent(playerId, k -> new Entry());
    }

    public void setPos1(UUID playerId, BlockPos pos) {
        getFor(playerId).pos1 = pos;
        markDirty();
    }

    public void setPos2(UUID playerId, BlockPos pos) {
        getFor(playerId).pos2 = pos;
        markDirty();
    }

    public void setMarks(UUID playerId, List<BlockPos> marks) {
        getFor(playerId).marks = new ArrayList<>(marks);
        markDirty();
    }

    public void saveRoute(UUID playerId, String name, BlockPos pos1, BlockPos pos2) {
        getFor(playerId).savedRoutes.put(name, new BlockPos[]{pos1, pos2});
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        entries.clear();
        if (!nbt.hasKey("Entries", Constants.NBT.TAG_LIST)) return;

        NBTTagList list = nbt.getTagList("Entries", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            if (!tag.hasKey("PlayerUUID")) continue;

            try {
                UUID playerId = UUID.fromString(tag.getString("PlayerUUID"));
                Entry entry = new Entry();
                if (tag.hasKey("Pos1")) entry.pos1 = readPos(tag.getCompoundTag("Pos1"));
                if (tag.hasKey("Pos2")) entry.pos2 = readPos(tag.getCompoundTag("Pos2"));
                if (tag.hasKey("Marks", Constants.NBT.TAG_LIST)) {
                    NBTTagList marks = tag.getTagList("Marks", Constants.NBT.TAG_COMPOUND);
                    for (int m = 0; m < marks.tagCount(); m++) {
                        entry.marks.add(readPos(marks.getCompoundTagAt(m)));
                    }
                }
                if (tag.hasKey("SavedRoutes", Constants.NBT.TAG_LIST)) {
                    NBTTagList routes = tag.getTagList("SavedRoutes", Constants.NBT.TAG_COMPOUND);
                    for (int r = 0; r < routes.tagCount(); r++) {
                        NBTTagCompound routeTag = routes.getCompoundTagAt(r);
                        if (!routeTag.hasKey("Name")) continue;
                        entry.savedRoutes.put(routeTag.getString("Name"), new BlockPos[]{
                                readPos(routeTag.getCompoundTag("Pos1")),
                                readPos(routeTag.getCompoundTag("Pos2"))});
                    }
                }
                entries.put(playerId, entry);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();

        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (entry.pos1 == null && entry.pos2 == null && entry.marks.isEmpty() && entry.savedRoutes.isEmpty()) continue;

            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("PlayerUUID", e.getKey().toString());
            if (entry.pos1 != null) tag.setTag("Pos1", writePos(entry.pos1));
            if (entry.pos2 != null) tag.setTag("Pos2", writePos(entry.pos2));

            NBTTagList marks = new NBTTagList();
            for (BlockPos p : entry.marks) marks.appendTag(writePos(p));
            tag.setTag("Marks", marks);

            NBTTagList routes = new NBTTagList();
            for (Map.Entry<String, BlockPos[]> r : entry.savedRoutes.entrySet()) {
                NBTTagCompound routeTag = new NBTTagCompound();
                routeTag.setString("Name", r.getKey());
                routeTag.setTag("Pos1", writePos(r.getValue()[0]));
                routeTag.setTag("Pos2", writePos(r.getValue()[1]));
                routes.appendTag(routeTag);
            }
            tag.setTag("SavedRoutes", routes);

            list.appendTag(tag);
        }

        nbt.setTag("Entries", list);
        return nbt;
    }

    private static NBTTagCompound writePos(BlockPos p) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("X", p.getX());
        tag.setInteger("Y", p.getY());
        tag.setInteger("Z", p.getZ());
        return tag;
    }

    private static BlockPos readPos(NBTTagCompound tag) {
        return new BlockPos(tag.getInteger("X"), tag.getInteger("Y"), tag.getInteger("Z"));
    }

    public static final class Entry {
        public BlockPos pos1;
        public BlockPos pos2;
        public List<BlockPos> marks = new ArrayList<>();
        /** Named pos1/pos2 snapshots saved via {@code path save <name>} - unrelated to the live
         * pos1/pos2 above, which keep changing as the player re-marks points. */
        public Map<String, BlockPos[]> savedRoutes = new HashMap<>();
    }
}
