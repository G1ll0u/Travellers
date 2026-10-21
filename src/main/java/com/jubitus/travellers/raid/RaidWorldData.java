package com.jubitus.travellers.raid;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-player raid state, persisted in the overworld's storage so a restart resumes the schedule. */
public class RaidWorldData extends WorldSavedData {

    public static final String DATA_NAME = "TravellersRaids";

    /** One player's schedule and, while a raid is running, its identity, plan and current wave. */
    public static final class PlayerRaidState {
        public long firstSeenTick;
        public long earliestTick;
        public RaidSchedule.Slot slot = RaidSchedule.Slot.NIGHT;
        public int slotOffset;
        @Nullable public UUID activeRaidId;
        public long activeSinceTick;
        /** Set exactly while {@link #activeRaidId} is. */
        @Nullable public RaidPlan plan;
        /** Radians, from the player toward the first wave's home village when the raid started. */
        public double baseAngle;
        /** Index of the wave last put on the ground. */
        public int wave;
        public long waveSinceTick;
        /** Raiders the current wave started with. */
        public int waveLaunched;
    }

    private final Map<UUID, PlayerRaidState> states = new HashMap<>();
    /** Raider uuid (same loaded or virtual) -> its raid. */
    private final Map<UUID, UUID> raidOfMember = new HashMap<>();
    /** Raider uuid -> the wave it came with. */
    private final Map<UUID, Integer> waveOfMember = new HashMap<>();

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

    public void addMember(UUID raidId, int wave, UUID unit) {
        raidOfMember.put(unit, raidId);
        waveOfMember.put(unit, wave);
        markDirty();
    }

    /** Member uuid -> the wave it came with. */
    public Map<UUID, Integer> membersOf(UUID raidId) {
        Map<UUID, Integer> out = new HashMap<>();
        for (Map.Entry<UUID, UUID> e : raidOfMember.entrySet()) {
            if (raidId.equals(e.getValue())) out.put(e.getKey(), waveOfMember.getOrDefault(e.getKey(), 0));
        }
        return out;
    }

    /** Every member of every running raid. */
    public Set<UUID> allMembers() {
        return raidOfMember.keySet();
    }

    /** Releases every member: they turn into ordinary raiders. */
    public void clearRaid(UUID raidId) {
        List<UUID> gone = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : raidOfMember.entrySet()) {
            if (raidId.equals(e.getValue())) gone.add(e.getKey());
        }
        for (UUID u : gone) {
            raidOfMember.remove(u);
            waveOfMember.remove(u);
        }
        if (!gone.isEmpty()) markDirty();
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
        waveOfMember.clear();
        NBTTagList members = nbt.getTagList("members", 10);
        for (int i = 0; i < members.tagCount(); i++) {
            NBTTagCompound m = members.getCompoundTagAt(i);
            raidOfMember.put(m.getUniqueId("unit"), m.getUniqueId("raid"));
            waveOfMember.put(m.getUniqueId("unit"), m.getInteger("wave"));
        }
        NBTTagList list = nbt.getTagList("players", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            PlayerRaidState s = new PlayerRaidState();
            s.firstSeenTick = t.getLong("firstSeen");
            s.earliestTick = t.getLong("earliest");
            s.slot = enumAt(RaidSchedule.Slot.values(), t.getInteger("slot"));
            s.slotOffset = t.getInteger("offset");
            if (t.hasUniqueId("raid")) {
                s.activeRaidId = t.getUniqueId("raid");
                s.activeSinceTick = t.getLong("activeSince");
                s.baseAngle = t.getDouble("angle");
                s.wave = t.getInteger("wave");
                s.waveSinceTick = t.getLong("waveSince");
                s.waveLaunched = t.getInteger("waveLaunched");
                s.plan = t.hasKey("plan", 10) ? readPlan(t.getCompoundTag("plan")) : singleWavePlan(s);
            }
            states.put(t.getUniqueId("player"), s);
        }
    }

    /** A raid saved before waves existed: one wave, already on the ground. */
    private RaidPlan singleWavePlan(PlayerRaidState s) {
        int n = membersOf(s.activeRaidId).size();
        s.wave = 0;
        s.waveSinceTick = s.activeSinceTick;
        s.waveLaunched = n;
        List<RaidPlan.Wave> one = new ArrayList<>();
        one.add(new RaidPlan.Wave(Math.max(1, n), RaidPlan.WaveKind.HORDE, "", 0.0));
        return new RaidPlan(RaidPlan.Approach.ONE_FRONT, one);
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
            if (s.activeRaidId != null) {
                t.setUniqueId("raid", s.activeRaidId);
                t.setLong("activeSince", s.activeSinceTick);
                t.setDouble("angle", s.baseAngle);
                t.setInteger("wave", s.wave);
                t.setLong("waveSince", s.waveSinceTick);
                t.setInteger("waveLaunched", s.waveLaunched);
                if (s.plan != null) t.setTag("plan", writePlan(s.plan));
            }
            list.appendTag(t);
        }
        nbt.setTag("players", list);
        NBTTagList members = new NBTTagList();
        for (Map.Entry<UUID, UUID> e : raidOfMember.entrySet()) {
            NBTTagCompound m = new NBTTagCompound();
            m.setUniqueId("unit", e.getKey());
            m.setUniqueId("raid", e.getValue());
            m.setInteger("wave", waveOfMember.getOrDefault(e.getKey(), 0));
            members.appendTag(m);
        }
        nbt.setTag("members", members);
        return nbt;
    }

    private static NBTTagCompound writePlan(RaidPlan plan) {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("approach", plan.approach.ordinal());
        NBTTagList waves = new NBTTagList();
        for (RaidPlan.Wave w : plan.waves) {
            NBTTagCompound wt = new NBTTagCompound();
            wt.setInteger("size", w.size);
            wt.setInteger("kind", w.kind.ordinal());
            wt.setString("culture", w.culture);
            wt.setDouble("offset", w.offsetDegrees);
            waves.appendTag(wt);
        }
        t.setTag("waves", waves);
        return t;
    }

    private static RaidPlan readPlan(NBTTagCompound t) {
        List<RaidPlan.Wave> waves = new ArrayList<>();
        NBTTagList list = t.getTagList("waves", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound wt = list.getCompoundTagAt(i);
            waves.add(new RaidPlan.Wave(wt.getInteger("size"), enumAt(RaidPlan.WaveKind.values(), wt.getInteger("kind")),
                    wt.getString("culture"), wt.getDouble("offset")));
        }
        return new RaidPlan(enumAt(RaidPlan.Approach.values(), t.getInteger("approach")), waves);
    }

    private static <E> E enumAt(E[] values, int ordinal) {
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }
}
