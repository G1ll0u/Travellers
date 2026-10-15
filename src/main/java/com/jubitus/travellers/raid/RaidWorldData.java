package com.jubitus.travellers.raid;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player raid state, persisted in the overworld's storage so a restart resumes the schedule. */
public class RaidWorldData extends WorldSavedData {

    public static final String DATA_NAME = "TravellersRaids";

    /** One player's schedule and, while a raid is running, its identity. */
    public static final class PlayerRaidState {
        public long firstSeenTick;
        public long earliestTick;
        public RaidSchedule.Slot slot = RaidSchedule.Slot.NIGHT;
        public int slotOffset;
        @Nullable public UUID activeRaidId;
        public long activeSinceTick;
    }

    private final Map<UUID, PlayerRaidState> states = new HashMap<>();
    /** Raider uuid (same loaded or virtual) -> the raid it belongs to. */
    private final Map<UUID, UUID> raidOfMember = new HashMap<>();

    public RaidWorldData() {
        super(DATA_NAME);
    }

    public RaidWorldData(String name) {
        super(name);
    }

    public static RaidWorldData get(MinecraftServer server) {
        MapStorage storage = server.getWorld(0).getPerWorldStorage();
        RaidWorldData data = (RaidWorldData) storage.getOrLoadData(RaidWorldData.class, DATA_NAME);
        if (data == null) {
            data = new RaidWorldData();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /** True when this unit is a member of a running raid. Server worlds only; false anywhere else. */
    public static boolean isRaider(net.minecraft.world.World world, UUID unit) {
        MinecraftServer server = world.getMinecraftServer();
        return server != null && !world.isRemote && get(server).raidOfMember.containsKey(unit);
    }

    /** The player a raider is marching on, or null when it is not in a raid or the raid has no player. */
    @Nullable
    public static UUID targetPlayer(net.minecraft.world.World world, UUID unit) {
        MinecraftServer server = world.getMinecraftServer();
        if (server == null || world.isRemote) return null;
        RaidWorldData d = get(server);
        UUID raid = d.raidOfMember.get(unit);
        return raid == null ? null : d.playerOfRaid(raid);
    }

    @Nullable
    public UUID playerOfRaid(UUID raidId) {
        for (Map.Entry<UUID, PlayerRaidState> e : states.entrySet()) {
            if (raidId.equals(e.getValue().activeRaidId)) return e.getKey();
        }
        return null;
    }

    public void addMember(UUID raidId, UUID unit) {
        raidOfMember.put(unit, raidId);
        markDirty();
    }

    public java.util.List<UUID> membersOf(UUID raidId) {
        java.util.List<UUID> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, UUID> e : raidOfMember.entrySet()) {
            if (raidId.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** Releases every member: they turn into ordinary raiders. */
    public void clearRaid(UUID raidId) {
        if (raidOfMember.values().removeIf(raidId::equals)) markDirty();
    }

    public void forgetMember(UUID unit) {
        if (raidOfMember.remove(unit) != null) markDirty();
    }

    public java.util.Set<Map.Entry<UUID, PlayerRaidState>> allStates() {
        return states.entrySet();
    }

    @Nullable
    public PlayerRaidState find(UUID player) {
        return states.get(player);
    }

    public PlayerRaidState create(UUID player) {
        PlayerRaidState s = new PlayerRaidState();
        states.put(player, s);
        markDirty();
        return s;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        states.clear();
        raidOfMember.clear();
        NBTTagList members = nbt.getTagList("members", 10);
        for (int i = 0; i < members.tagCount(); i++) {
            NBTTagCompound m = members.getCompoundTagAt(i);
            raidOfMember.put(m.getUniqueId("unit"), m.getUniqueId("raid"));
        }
        NBTTagList list = nbt.getTagList("players", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            PlayerRaidState s = new PlayerRaidState();
            s.firstSeenTick = t.getLong("firstSeen");
            s.earliestTick = t.getLong("earliest");
            int slotOrdinal = t.getInteger("slot");
            RaidSchedule.Slot[] slots = RaidSchedule.Slot.values();
            s.slot = slots[Math.max(0, Math.min(slots.length - 1, slotOrdinal))];
            s.slotOffset = t.getInteger("offset");
            if (t.hasUniqueId("raid")) s.activeRaidId = t.getUniqueId("raid");
            s.activeSinceTick = t.getLong("activeSince");
            states.put(t.getUniqueId("player"), s);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<UUID, PlayerRaidState> e : states.entrySet()) {
            PlayerRaidState s = e.getValue();
            NBTTagCompound t = new NBTTagCompound();
            t.setUniqueId("player", e.getKey());
            t.setLong("firstSeen", s.firstSeenTick);
            t.setLong("earliest", s.earliestTick);
            t.setInteger("slot", s.slot.ordinal());
            t.setInteger("offset", s.slotOffset);
            if (s.activeRaidId != null) t.setUniqueId("raid", s.activeRaidId);
            t.setLong("activeSince", s.activeSinceTick);
            list.appendTag(t);
        }
        nbt.setTag("players", list);
        NBTTagList members = new NBTTagList();
        for (Map.Entry<UUID, UUID> e : raidOfMember.entrySet()) {
            NBTTagCompound m = new NBTTagCompound();
            m.setUniqueId("unit", e.getKey());
            m.setUniqueId("raid", e.getValue());
            members.appendTag(m);
        }
        nbt.setTag("members", members);
        return nbt;
    }
}
