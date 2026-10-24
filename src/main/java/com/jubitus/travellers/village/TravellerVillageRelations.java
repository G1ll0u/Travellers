package com.jubitus.travellers.village;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.*;

public class TravellerVillageRelations extends WorldSavedData {

    private static final String DATA_NAME = "travellers_village_relations";

    // player -> set of enemy village centers (dim,x,y,z)
    private final Map<UUID, Set<VillageKey>> enemyVillagesByPlayer = new HashMap<>();

    public TravellerVillageRelations() {
        super(DATA_NAME);
    }

    public TravellerVillageRelations(String name) {
        super(name);
    }

    public static TravellerVillageRelations get(WorldServer ws) {
        MapStorage storage = ws.getPerWorldStorage();
        TravellerVillageRelations data = (TravellerVillageRelations) storage.getOrLoadData(
                TravellerVillageRelations.class, DATA_NAME
        );
        if (data == null) {
            data = new TravellerVillageRelations(DATA_NAME);
            storage.setData(DATA_NAME, data);
            data.markDirty();
        }
        return data;
    }

    public boolean isEnemy(UUID playerId, int dim, BlockPos center) {
        if (playerId == null || center == null) return false;
        Set<VillageKey> set = enemyVillagesByPlayer.get(playerId);
        if (set == null) return false;
        return set.contains(new VillageKey(dim, center));
    }

    /** True when the player counts any village of this culture as an enemy, wherever it stands. */
    public boolean hasEnemyOfCulture(WorldServer ws, UUID playerId, String cultureKey) {
        Set<VillageKey> set = enemyVillagesByPlayer.get(playerId);
        if (set == null || cultureKey == null) return false;
        for (VillageKey k : set) {
            WorldServer kw = net.minecraftforge.common.DimensionManager.getWorld(k.dim);
            if (kw == null) continue;
            MillSettlements.Settlement s = MillSettlements.at(kw, new BlockPos(k.x, k.y, k.z));
            if (s != null && cultureKey.equals(s.cultureKey)) return true;
        }
        return false;
    }

    public void setEnemy(UUID playerId, int dim, BlockPos center, boolean enemy) {
        if (playerId == null || center == null) return;

        Set<VillageKey> set = enemyVillagesByPlayer.computeIfAbsent(playerId, k -> new HashSet<>());
        VillageKey key = new VillageKey(dim, center);

        boolean changed;
        if (enemy) changed = set.add(key);
        else changed = set.remove(key);

        if (changed) markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        enemyVillagesByPlayer.clear();

        if (!nbt.hasKey("Players", Constants.NBT.TAG_LIST)) return;
        NBTTagList players = nbt.getTagList("Players", Constants.NBT.TAG_COMPOUND);

        for (int i = 0; i < players.tagCount(); i++) {
            NBTTagCompound ptag = players.getCompoundTagAt(i);
            if (!ptag.hasUniqueId("Id")) continue;

            UUID id = ptag.getUniqueId("Id");
            Set<VillageKey> set = new HashSet<>();

            if (ptag.hasKey("Enemies", Constants.NBT.TAG_LIST)) {
                NBTTagList enemies = ptag.getTagList("Enemies", Constants.NBT.TAG_COMPOUND);
                for (int j = 0; j < enemies.tagCount(); j++) {
                    NBTTagCompound et = enemies.getCompoundTagAt(j);
                    int dim = et.getInteger("Dim");
                    int x = et.getInteger("X");
                    int y = et.getInteger("Y");
                    int z = et.getInteger("Z");
                    set.add(new VillageKey(dim, new BlockPos(x, y, z)));
                }
            }

            if (!set.isEmpty()) enemyVillagesByPlayer.put(id, set);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList players = new NBTTagList();

        for (Map.Entry<UUID, Set<VillageKey>> e : enemyVillagesByPlayer.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;

            NBTTagCompound ptag = new NBTTagCompound();
            ptag.setUniqueId("Id", e.getKey());

            NBTTagList enemies = new NBTTagList();
            for (VillageKey k : e.getValue()) {
                NBTTagCompound et = new NBTTagCompound();
                et.setInteger("Dim", k.dim);
                et.setInteger("X", k.x);
                et.setInteger("Y", k.y);
                et.setInteger("Z", k.z);
                enemies.appendTag(et);
            }
            ptag.setTag("Enemies", enemies);
            players.appendTag(ptag);
        }

        nbt.setTag("Players", players);
        return nbt;
    }

    private static final class VillageKey {
        final int dim;
        final int x, y, z;

        VillageKey(int dim, BlockPos pos) {
            this.dim = dim;
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }

        @Override
        public int hashCode() {
            int h = dim;
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + z;
            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VillageKey)) return false;
            VillageKey k = (VillageKey) o;
            return dim == k.dim && x == k.x && y == k.y && z == k.z;
        }
    }
}