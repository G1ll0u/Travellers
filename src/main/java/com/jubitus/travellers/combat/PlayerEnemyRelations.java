package com.jubitus.travellers.combat;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Account-wide "declared enemy" list: player -> set of player UUIDs their hired workers
 * treat as hostile, regardless of which specific worker/group made the declaration.
 * Overworld-resolved (not per-dimension) since this is an account-wide relationship,
 * same pattern as WorkerOwnerVisualsSavedData/NotificationQueueData.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = "travellers")
public class PlayerEnemyRelations extends WorldSavedData {

    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public static void onLogout(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.player.world.isRemote) return;
        get(e.player.world).noteLogout(e.player.getUniqueID());
    }

    private static final String DATA_NAME = "travellers_player_enemy_relations";

    private final Map<UUID, Set<UUID>> hostilesByPlayer = new HashMap<>();
    // Wall-clock millis of each player's last logout; online players are never protected.
    private final Map<UUID, Long> lastSeenMillis = new HashMap<>();

    public PlayerEnemyRelations() {
        super(DATA_NAME);
    }

    public PlayerEnemyRelations(String name) {
        super(name);
    }

    public static PlayerEnemyRelations get(World world) {
        WorldServer overworld = world.getMinecraftServer() != null
                ? world.getMinecraftServer().getWorld(0)
                : (world instanceof WorldServer ? (WorldServer) world : null);
        if (overworld == null) {
            throw new IllegalStateException("PlayerEnemyRelations.get() called with no server context");
        }

        MapStorage storage = overworld.getPerWorldStorage();
        PlayerEnemyRelations data = (PlayerEnemyRelations) storage.getOrLoadData(
                PlayerEnemyRelations.class, DATA_NAME
        );
        if (data == null) {
            data = new PlayerEnemyRelations(DATA_NAME);
            storage.setData(DATA_NAME, data);
            data.markDirty();
        }
        return data;
    }

    public boolean isHostile(UUID owner, UUID target) {
        if (owner == null || target == null) return false;
        Set<UUID> set = hostilesByPlayer.get(owner);
        return set != null && set.contains(target);
    }

    /** Returns an unmodifiable snapshot — never a live-mutable view. */
    public Set<UUID> getHostiles(UUID owner) {
        if (owner == null) return Collections.emptySet();
        Set<UUID> set = hostilesByPlayer.get(owner);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    /** Returns true if this call actually changed the stored state. */
    public boolean setHostile(UUID owner, UUID target, boolean hostile) {
        if (owner == null || target == null || owner.equals(target)) return false;

        Set<UUID> set = hostilesByPlayer.computeIfAbsent(owner, k -> new HashSet<>());
        boolean changed = hostile ? set.add(target) : set.remove(target);
        if (changed) markDirty();
        return changed;
    }

    public void noteLogout(UUID player) {
        lastSeenMillis.put(player, System.currentTimeMillis());
        markDirty();
    }

    /** True when the player has been offline longer than the configured protection delay. */
    public boolean isOfflineProtected(World world, UUID player) {
        int days = com.jubitus.travellers.config.TravellersConfig.PLAYER_AGGRO.offlineProtectionDays;
        if (days <= 0 || player == null) return false;
        if (world.getMinecraftServer() == null
                || world.getMinecraftServer().getPlayerList().getPlayerByUUID(player) != null) return false;
        Long seen = lastSeenMillis.get(player);
        return seen != null && System.currentTimeMillis() - seen > days * 86_400_000L;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        hostilesByPlayer.clear();
        lastSeenMillis.clear();
        if (nbt.hasKey("LastSeen", Constants.NBT.TAG_LIST)) {
            NBTTagList seen = nbt.getTagList("LastSeen", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < seen.tagCount(); i++) {
                NBTTagCompound t = seen.getCompoundTagAt(i);
                if (t.hasUniqueId("Id")) lastSeenMillis.put(t.getUniqueId("Id"), t.getLong("At"));
            }
        }

        if (!nbt.hasKey("Players", Constants.NBT.TAG_LIST)) return;
        NBTTagList players = nbt.getTagList("Players", Constants.NBT.TAG_COMPOUND);

        for (int i = 0; i < players.tagCount(); i++) {
            NBTTagCompound ptag = players.getCompoundTagAt(i);
            if (!ptag.hasUniqueId("Id")) continue;

            UUID id = ptag.getUniqueId("Id");
            Set<UUID> set = new HashSet<>();

            if (ptag.hasKey("Enemies", Constants.NBT.TAG_LIST)) {
                NBTTagList enemies = ptag.getTagList("Enemies", Constants.NBT.TAG_COMPOUND);
                for (int j = 0; j < enemies.tagCount(); j++) {
                    NBTTagCompound et = enemies.getCompoundTagAt(j);
                    if (et.hasUniqueId("Player")) set.add(et.getUniqueId("Player"));
                }
            }

            if (!set.isEmpty()) hostilesByPlayer.put(id, set);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList players = new NBTTagList();

        for (Map.Entry<UUID, Set<UUID>> e : hostilesByPlayer.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;

            NBTTagCompound ptag = new NBTTagCompound();
            ptag.setUniqueId("Id", e.getKey());

            NBTTagList enemies = new NBTTagList();
            for (UUID target : e.getValue()) {
                NBTTagCompound et = new NBTTagCompound();
                et.setUniqueId("Player", target);
                enemies.appendTag(et);
            }
            ptag.setTag("Enemies", enemies);
            players.appendTag(ptag);
        }

        nbt.setTag("Players", players);
        NBTTagList seen = new NBTTagList();
        for (Map.Entry<UUID, Long> e : lastSeenMillis.entrySet()) {
            NBTTagCompound t = new NBTTagCompound();
            t.setUniqueId("Id", e.getKey());
            t.setLong("At", e.getValue());
            seen.appendTag(t);
        }
        nbt.setTag("LastSeen", seen);
        return nbt;
    }
}
