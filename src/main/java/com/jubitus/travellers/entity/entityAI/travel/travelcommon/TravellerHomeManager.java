package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.*;

public class TravellerHomeManager extends WorldSavedData {

    public static final String DATA_NAME = "traveller_home_chests";

    public static class HomeChest {
        public BlockPos pos;
        public int dimension;
        public UUID owner;

        public HomeChest() { }

        public HomeChest(BlockPos pos, int dimension, UUID owner) {
            this.pos = pos;
            this.dimension = dimension;
            this.owner = owner;
        }
    }

    // player UUID -> list of home chests
    private final Map<UUID, List<HomeChest>> homesByPlayer = new HashMap<>();

    public TravellerHomeManager() {
        super(DATA_NAME);
    }

    public TravellerHomeManager(String name) {
        super(name);
    }

    // ----- singleton accessor -----

    public static TravellerHomeManager get(World world) {
        if (!(world instanceof WorldServer)) {
            throw new IllegalStateException("TravellerHomeManager can only be used on server side");
        }
        WorldServer ws = (WorldServer) world;
        MapStorage storage = ws.getPerWorldStorage();
        TravellerHomeManager data = (TravellerHomeManager) storage.getOrLoadData(TravellerHomeManager.class, DATA_NAME);
        if (data == null) {
            data = new TravellerHomeManager();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    // ----- public API -----

    /** Add or mark a chest as a home for this player. */
    public void addHomeChest(EntityPlayer player, BlockPos pos) {
        UUID id = player.getUniqueID();
        int dim = player.world.provider.getDimension();

        List<HomeChest> list = homesByPlayer.computeIfAbsent(id, k -> new ArrayList<>());

        // Avoid duplicates
        for (HomeChest h : list) {
            if (h.dimension == dim && h.pos.equals(pos)) {
                return;
            }
        }

        list.add(new HomeChest(pos, dim, id));
        markDirty();
    }

    /** Get all home chests in this dimension (regardless of owner). */
    public List<BlockPos> getAllHomesInDimension(World world) {
        int dim = world.provider.getDimension();
        List<BlockPos> result = new ArrayList<>();
        for (List<HomeChest> list : homesByPlayer.values()) {
            for (HomeChest h : list) {
                if (h.dimension == dim) {
                    result.add(h.pos);
                }
            }
        }
        return result;
    }

    /** Get homes owned by a specific player in this dimension. */
    public List<BlockPos> getHomesOfPlayer(EntityPlayer player) {
        UUID id = player.getUniqueID();
        int dim = player.world.provider.getDimension();
        List<BlockPos> result = new ArrayList<>();
        List<HomeChest> list = homesByPlayer.get(id);
        if (list == null) return result;

        for (HomeChest h : list) {
            if (h.dimension == dim) {
                result.add(h.pos);
            }
        }
        return result;
    }

    /** Is this position a registered home chest in this dimension? */
    public boolean isHomeChest(World world, BlockPos pos) {
        int dim = world.provider.getDimension();
        for (List<HomeChest> list : homesByPlayer.values()) {
            for (HomeChest h : list) {
                if (h.dimension == dim && h.pos.equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ----- NBT serialization -----

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        homesByPlayer.clear();

        NBTTagList playerList = nbt.getTagList("Players", 10); // 10 = compound
        for (int i = 0; i < playerList.tagCount(); i++) {
            NBTTagCompound playerTag = playerList.getCompoundTagAt(i);
            UUID id = playerTag.getUniqueId("UUID");

            NBTTagList homeList = playerTag.getTagList("Homes", 10);
            List<HomeChest> homes = new ArrayList<>();

            for (int j = 0; j < homeList.tagCount(); j++) {
                NBTTagCompound hTag = homeList.getCompoundTagAt(j);
                int x = hTag.getInteger("X");
                int y = hTag.getInteger("Y");
                int z = hTag.getInteger("Z");
                int dim = hTag.getInteger("Dim");
                homes.add(new HomeChest(new BlockPos(x, y, z), dim, id));
            }

            homesByPlayer.put(id, homes);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList playerList = new NBTTagList();

        for (Map.Entry<UUID, List<HomeChest>> e : homesByPlayer.entrySet()) {
            UUID id = e.getKey();
            List<HomeChest> homes = e.getValue();

            NBTTagCompound playerTag = new NBTTagCompound();
            playerTag.setUniqueId("UUID", id);

            NBTTagList homeList = new NBTTagList();
            for (HomeChest h : homes) {
                NBTTagCompound hTag = new NBTTagCompound();
                hTag.setInteger("X", h.pos.getX());
                hTag.setInteger("Y", h.pos.getY());
                hTag.setInteger("Z", h.pos.getZ());
                hTag.setInteger("Dim", h.dimension);
                homeList.appendTag(hTag);
            }

            playerTag.setTag("Homes", homeList);
            playerList.appendTag(playerTag);
        }

        nbt.setTag("Players", playerList);
        return nbt;
    }
}
