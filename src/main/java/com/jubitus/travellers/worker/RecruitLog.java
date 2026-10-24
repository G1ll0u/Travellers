package com.jubitus.travellers.worker;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What happened on a recruiting tour, for the report the owner reads when the recruiter is back. */
public final class RecruitLog {

    public static final int PENDING = 0, VISITED = 1, SKIPPED = 2;

    /** Per culture: {joined in a village, joined on the road, died}. */
    final Map<String, int[]> byCulture = new LinkedHashMap<>();
    /** Per stop position: {state, recruits who joined there}. */
    final Map<Long, int[]> stops = new LinkedHashMap<>();
    /** Where recruits joined on the road: {position, count}. */
    final List<long[]> roadJoins = new ArrayList<>();
    final List<Long> deaths = new ArrayList<>();
    final long startTick;
    int spent;

    RecruitLog(long startTick) {
        this.startTick = startTick;
    }

    void visited(BlockPos stop) {
        stops.computeIfAbsent(stop.toLong(), k -> new int[2])[0] = VISITED;
    }

    void skipped(BlockPos stop) {
        stops.computeIfAbsent(stop.toLong(), k -> new int[2])[0] = SKIPPED;
    }

    void joined(String culture, boolean inVillage, BlockPos stop, int price) {
        culture(culture)[inVillage ? 0 : 1]++;
        if (inVillage) stops.computeIfAbsent(stop.toLong(), k -> new int[2])[1]++;
        spent += price;
    }

    void joinedOnRoad(BlockPos at, int count) {
        roadJoins.add(new long[]{at.toLong(), count});
    }

    /** The recruiter itself fell (a successor carries on): a cross on the map, not a lost recruit. */
    public void recruiterDied(BlockPos at) {
        deaths.add(at.toLong());
    }

    void died(String culture, BlockPos at) {
        culture(culture)[2]++;
        deaths.add(at.toLong());
    }

    private int[] culture(String key) {
        return byCulture.computeIfAbsent(key, k -> new int[3]);
    }

    NBTTagCompound write() {
        NBTTagCompound t = new NBTTagCompound();
        NBTTagCompound c = new NBTTagCompound();
        for (Map.Entry<String, int[]> e : byCulture.entrySet()) c.setIntArray(e.getKey(), e.getValue());
        t.setTag("Cultures", c);
        NBTTagList s = new NBTTagList();
        for (Map.Entry<Long, int[]> e : stops.entrySet()) {
            NBTTagCompound st = new NBTTagCompound();
            st.setLong("Pos", e.getKey());
            st.setIntArray("Log", e.getValue());
            s.appendTag(st);
        }
        t.setTag("Stops", s);
        NBTTagList r = new NBTTagList();
        for (long[] j : roadJoins) {
            NBTTagCompound rj = new NBTTagCompound();
            rj.setLong("Pos", j[0]);
            rj.setInteger("Count", (int) j[1]);
            r.appendTag(rj);
        }
        t.setTag("Road", r);
        NBTTagList d = new NBTTagList();
        for (long p : deaths) {
            NBTTagCompound dt = new NBTTagCompound();
            dt.setLong("Pos", p);
            d.appendTag(dt);
        }
        t.setTag("Deaths", d);
        t.setLong("Start", startTick);
        t.setInteger("Spent", spent);
        return t;
    }

    static RecruitLog read(NBTTagCompound t, long fallbackStart) {
        RecruitLog log = new RecruitLog(t.hasKey("Start") ? t.getLong("Start") : fallbackStart);
        NBTTagCompound c = t.getCompoundTag("Cultures");
        for (String k : c.getKeySet()) {
            int[] v = c.getIntArray(k);
            if (v.length == 3) log.byCulture.put(k, v);
        }
        NBTTagList s = t.getTagList("Stops", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < s.tagCount(); i++) {
            int[] v = s.getCompoundTagAt(i).getIntArray("Log");
            if (v.length == 2) log.stops.put(s.getCompoundTagAt(i).getLong("Pos"), v);
        }
        NBTTagList r = t.getTagList("Road", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < r.tagCount(); i++) {
            log.roadJoins.add(new long[]{r.getCompoundTagAt(i).getLong("Pos"), r.getCompoundTagAt(i).getInteger("Count")});
        }
        NBTTagList d = t.getTagList("Deaths", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < d.tagCount(); i++) log.deaths.add(d.getCompoundTagAt(i).getLong("Pos"));
        log.spent = t.getInteger("Spent");
        return log;
    }
}
