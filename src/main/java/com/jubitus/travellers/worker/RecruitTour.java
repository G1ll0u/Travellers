package com.jubitus.travellers.worker;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * A recruiting tour in progress: the stops, what is wanted and already found, who was already
 * asked. One object moved between the loaded worker and its virtual record, so both sides run the same
 * arrive / wait / leave decisions on it.
 */
public final class RecruitTour {

    public static final String NBT_KEY = "RecruitTour";

    private final List<RecruitPlan.Stop> stops;
    private final Map<String, Integer> wanted;
    /** Recruits that joined, with their culture; a dead one frees its place again. */
    private final Map<UUID, String> recruits = new LinkedHashMap<>();
    /** Last place each recruit was seen, where its death is marked on the report. */
    private final Map<UUID, Long> recruitPos = new LinkedHashMap<>();
    private final RecruitLog log;
    // INVARIANT: one roll per unit per tour, on the road and in villages alike.
    private final Set<UUID> asked = new HashSet<>();
    /** Where the player stood when ordering it: the range of extra villages is measured from here. */
    private final BlockPos origin;
    private int index;
    private int extraStops;
    /** World time the wait at the current stop ends; -1 while walking. */
    private long stayUntil = -1L;
    private boolean returning;
    /** Back at the owner's side, waiting for them to read the report and take the purse. */
    private boolean back;

    private RecruitTour(List<RecruitPlan.Stop> stops, Map<String, Integer> wanted, BlockPos origin, RecruitLog log) {
        this.stops = stops;
        this.wanted = wanted;
        this.origin = origin;
        this.log = log;
    }

    public static RecruitTour of(RecruitPlan plan, BlockPos origin, long now) {
        return new RecruitTour(new ArrayList<>(plan.stops), new LinkedHashMap<>(plan.wanted), origin, new RecruitLog(now));
    }

    public RecruitLog log() {
        return log;
    }

    public List<RecruitPlan.Stop> stops() {
        return stops;
    }

    public Map<String, Integer> wanted() {
        return wanted;
    }

    public boolean isBack() {
        return back;
    }

    public void markBack() {
        back = true;
    }

    @Nullable
    public RecruitPlan.Stop currentStop() {
        return returning || index >= stops.size() ? null : stops.get(index);
    }

    public boolean isReturning() {
        return returning;
    }

    public boolean isStaying() {
        return stayUntil >= 0L;
    }

    public int missing(String cultureKey) {
        return Math.max(0, wanted.getOrDefault(cultureKey, 0) - found(cultureKey));
    }

    public boolean wasAsked(UUID unit) {
        return asked.contains(unit);
    }

    public void markAsked(UUID unit) {
        asked.add(unit);
    }

    public void addRecruit(UUID unit, String cultureKey, BlockPos at, boolean inVillage, int price) {
        recruits.put(unit, cultureKey);
        recruitPos.put(unit, at.toLong());
        RecruitPlan.Stop stop = currentStop();
        log.joined(cultureKey, inVillage && stop != null, stop != null ? stop.center : at, price);
    }

    /**
     * Forgets recruits that no longer exist anywhere (killed): their places open again, their price stays
     * spent. {@code whereAlive} gives a live recruit's position, null for a dead one.
     */
    public void forgetDead(Function<UUID, BlockPos> whereAlive) {
        for (java.util.Iterator<Map.Entry<UUID, String>> it = recruits.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, String> e = it.next();
            BlockPos at = whereAlive.apply(e.getKey());
            if (at != null) {
                recruitPos.put(e.getKey(), at.toLong());
                continue;
            }
            Long last = recruitPos.remove(e.getKey());
            log.died(e.getValue(), last != null ? BlockPos.fromLong(last) : origin);
            it.remove();
        }
    }

    /** Recruits of this culture still alive. */
    public int found(String cultureKey) {
        int n = 0;
        for (String c : recruits.values()) if (c.equals(cultureKey)) n++;
        return n;
    }

    public boolean isComplete() {
        for (String c : wanted.keySet()) if (missing(c) > 0) return false;
        return true;
    }

    /** Reached the current stop: the wait starts. */
    public void arrive(long now) {
        RecruitPlan.Stop stop = currentStop();
        if (stop != null) log.visited(stop.center);
        stayUntil = now + TravellersConfig.RECRUITMENT.recruitStayMinutes * 60L * 20L;
    }

    public boolean isStayOver(long now) {
        return isStaying() && (now >= stayUntil || isComplete());
    }

    /**
     * Leaves the current stop for the next one still worth visiting, adding an extra village of a culture
     * still missing at the end of the list (at most as many as were planned). Returns null when the tour
     * turns back to the owner.
     */
    @Nullable
    public RecruitPlan.Stop leave(WorldServer ws, UUID ownerId) {
        stayUntil = -1L;
        index++;
        while (!isComplete()) {
            while (index < stops.size() && !worthVisiting(ws, ownerId, stops.get(index))) {
                log.skipped(stops.get(index).center);
                index++;
            }
            if (index < stops.size()) return stops.get(index);
            if (extraStops >= stops.size() - extraStops) break;
            RecruitPlan.Stop extra = extraStop(ws, ownerId);
            if (extra == null) break;
            stops.add(extra);
            extraStops++;
        }
        returning = true;
        return null;
    }

    /** Ends the tour where it stands (cancelled, owner gone): straight back to the owner. */
    public void turnBack() {
        stayUntil = -1L;
        returning = true;
    }

    // A village destroyed, or whose culture turned hostile to the player, is skipped.
    private boolean worthVisiting(WorldServer ws, UUID ownerId, RecruitPlan.Stop stop) {
        return MillSettlements.at(ws, stop.center) != null
                && RecruitCultures.verdict(ws, ownerId, stop.cultureKey) == RecruitCultures.Verdict.OK;
    }

    @Nullable
    private RecruitPlan.Stop extraStop(WorldServer ws, UUID ownerId) {
        BlockPos from = stops.isEmpty() ? origin : stops.get(stops.size() - 1).center;
        Set<Long> planned = new HashSet<>();
        for (RecruitPlan.Stop s : stops) planned.add(s.center.toLong());
        RecruitPlan.Stop best = null;
        double bestSq = Double.MAX_VALUE;
        for (MillSettlements.Settlement s : MillSettlements.villages(ws)) {
            if (s.cultureKey == null || missing(s.cultureKey) == 0 || planned.contains(s.center.toLong())
                    || !RecruitCultures.inRange(origin, s.center)
                    || RecruitCultures.verdict(ws, ownerId, s.cultureKey) != RecruitCultures.Verdict.OK) continue;
            double d = from.distanceSq(s.center.getX(), from.getY(), s.center.getZ());
            if (d < bestSq) {
                bestSq = d;
                best = new RecruitPlan.Stop(s.center, s.cultureKey);
            }
        }
        return best;
    }

    public NBTTagCompound write() {
        NBTTagCompound t = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (RecruitPlan.Stop s : stops) {
            NBTTagCompound c = new NBTTagCompound();
            c.setLong("Pos", s.center.toLong());
            c.setString("Culture", s.cultureKey);
            list.appendTag(c);
        }
        t.setTag("Stops", list);
        t.setTag("Wanted", writeCounts(wanted));
        NBTTagList recruitList = new NBTTagList();
        for (Map.Entry<UUID, String> e : recruits.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setUniqueId("Id", e.getKey());
            c.setString("Culture", e.getValue());
            Long at = recruitPos.get(e.getKey());
            if (at != null) c.setLong("Pos", at);
            recruitList.appendTag(c);
        }
        t.setTag("Recruits", recruitList);
        NBTTagList askedList = new NBTTagList();
        for (UUID id : asked) {
            NBTTagCompound c = new NBTTagCompound();
            c.setUniqueId("Id", id);
            askedList.appendTag(c);
        }
        t.setTag("Asked", askedList);
        t.setLong("Origin", origin.toLong());
        t.setInteger("Index", index);
        t.setInteger("Extra", extraStops);
        t.setLong("StayUntil", stayUntil);
        t.setBoolean("Returning", returning);
        t.setBoolean("Back", back);
        t.setTag("Log", log.write());
        return t;
    }

    @Nullable
    public static RecruitTour read(@Nullable NBTTagCompound t) {
        if (t == null || !t.hasKey("Stops", Constants.NBT.TAG_LIST)) return null;
        List<RecruitPlan.Stop> stops = new ArrayList<>();
        NBTTagList list = t.getTagList("Stops", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            stops.add(new RecruitPlan.Stop(BlockPos.fromLong(c.getLong("Pos")), c.getString("Culture")));
        }
        RecruitTour tour = new RecruitTour(stops, readCounts(t.getCompoundTag("Wanted")),
                BlockPos.fromLong(t.getLong("Origin")), RecruitLog.read(t.getCompoundTag("Log"), 0L));
        NBTTagList recruitList = t.getTagList("Recruits", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < recruitList.tagCount(); i++) {
            NBTTagCompound c = recruitList.getCompoundTagAt(i);
            tour.recruits.put(c.getUniqueId("Id"), c.getString("Culture"));
            if (c.hasKey("Pos")) tour.recruitPos.put(c.getUniqueId("Id"), c.getLong("Pos"));
        }
        NBTTagList askedList = t.getTagList("Asked", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < askedList.tagCount(); i++) tour.asked.add(askedList.getCompoundTagAt(i).getUniqueId("Id"));
        tour.index = t.getInteger("Index");
        tour.extraStops = t.getInteger("Extra");
        tour.stayUntil = t.getLong("StayUntil");
        tour.returning = t.getBoolean("Returning");
        tour.back = t.getBoolean("Back");
        return tour;
    }

    private static NBTTagCompound writeCounts(Map<String, Integer> counts) {
        NBTTagCompound c = new NBTTagCompound();
        for (Map.Entry<String, Integer> e : counts.entrySet()) c.setInteger(e.getKey(), e.getValue());
        return c;
    }

    private static Map<String, Integer> readCounts(NBTTagCompound c) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String k : c.getKeySet()) out.put(k, c.getInteger(k));
        return out;
    }
}
