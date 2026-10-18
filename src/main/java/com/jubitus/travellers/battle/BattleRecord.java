package com.jubitus.travellers.battle;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import net.minecraft.nbt.NBTTagString;

import com.jubitus.travellers.notify.BattleSummary;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One active virtual battle: two sides fighting to the death.
 * Logical state is the source of truth; loaded pawns are tracked but fight via physical AI.
 */
public class BattleRecord {

    public final UUID id;
    public final int dimension;
    /** Recomputed centroid of alive pawns (where stragglers drift); battles anchored at a camp keep the camp center. */
    public BlockPos center;
    /** Where the battle is shown: the first blow, moved only once the blows have clearly drifted away. */
    public BlockPos anchor;
    /** Reach of the battle around {@link #anchor} (capped): for merging, reopening and pulling groups in. */
    public int zoneRadius = MIN_ZONE_RADIUS;
    /** World tick of the first blow, -1 before: until then the battle is not shown to players. */
    public long firstBlowTick = -1;
    /** World tick of the latest blow, -1 before the first. */
    public long lastBlowTick = -1;
    /** Transient: running average of recent blow positions, which the anchor follows past a drift distance. */
    public transient double blowX, blowZ;

    /** Transient: per owner, henchmen fallen since that owner's last battle beat (big battles update the toast now and then). */
    public final transient Map<UUID, List<BattleSummary.CasualtyEntry>> pendingFallen = new HashMap<>();
    /** Transient: per owner, world tick of the last fallen beat. */
    public final transient Map<UUID, Long> lastFallenBeat = new HashMap<>();

    public static final int MIN_ZONE_RADIUS = 16;
    // Share of a new blow's position taken into the running blow position.
    private static final double BLOW_SMOOTHING = 0.1;
    public long startTick;
    /** Non-null when the battle is an assault on an occupied camp (anchors the center). */
    @Nullable public UUID campAnchorId;
    public final Map<UUID, BattleParticipant> participants = new LinkedHashMap<>();
    /** Owners already notified that their workers joined this battle (no notification spam). */
    public final Set<UUID> notifiedOwners = new HashSet<>();
    /** Sim round-robin bookkeeping: world tick of the last sim step applied to this battle. */
    public long lastSimTick;
    /** World tick of the last movement pass over this battle (-1 = none yet). Not saved. */
    public transient long lastMoveTick = -1;
    /** Accumulated equipment/inventory of virtual pawns killed in this battle, distributed on endBattle. */
    public NBTTagList lootPool = new NBTTagList();
    /** Transient: world tick the battle ended, -1 while it runs (an ended battle is kept briefly for its map marker). */
    public long endTick = -1;
    /** Transient: the side left standing alone at the end, or -1 (still running, timeout, or nobody left). */
    public int winnerSide = -1;

    public BattleRecord(UUID id, int dimension, BlockPos center, long startTick) {
        this.id = id;
        this.dimension = dimension;
        this.center = center;
        this.anchor = center;
        this.blowX = center.getX() + 0.5;
        this.blowZ = center.getZ() + 0.5;
        this.startTick = startTick;
        this.lastSimTick = startTick;
    }

    public int countAlive(int side) {
        int n = 0;
        for (BattleParticipant p : participants.values()) {
            if (p.alive && p.side == side) n++;
        }
        return n;
    }

    /** A blow landed (or was parried) at this spot: the battle becomes visible and stays alive. */
    public void recordBlow(double x, double z, long now) {
        if (firstBlowTick < 0) {
            firstBlowTick = now;
            blowX = x;
            blowZ = z;
            anchor = new BlockPos(Math.floor(x), center.getY(), Math.floor(z));
        } else {
            blowX += (x - blowX) * BLOW_SMOOTHING;
            blowZ += (z - blowZ) * BLOW_SMOOTHING;
        }
        lastBlowTick = now;
    }

    /** Distance from (x, z) to the farthest of {@code positions}, kept within [{@link #MIN_ZONE_RADIUS}, cap]. */
    static int zoneRadiusAround(double x, double z, List<double[]> positions, int cap) {
        double far = 0;
        for (double[] pos : positions) {
            double dx = pos[0] - x;
            double dz = pos[1] - z;
            far = Math.max(far, dx * dx + dz * dz);
        }
        return Math.max(MIN_ZONE_RADIUS, Math.min(Math.max(MIN_ZONE_RADIUS, cap), (int) Math.ceil(Math.sqrt(far))));
    }

    public boolean containsGroupKey(String key) {
        for (BattleParticipant p : participants.values()) {
            if (p.alive && p.groupKey.equals(key)) return true;
        }
        return false;
    }

    /** Side the given group key belongs to, or -1 if not present. */
    public int sideOfGroup(String key) {
        for (BattleParticipant p : participants.values()) {
            if (p.groupKey.equals(key)) return p.side;
        }
        return -1;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Id", id.toString());
        tag.setInteger("Dim", dimension);
        tag.setInteger("CX", center.getX());
        tag.setInteger("CY", center.getY());
        tag.setInteger("CZ", center.getZ());
        tag.setLong("Start", startTick);
        tag.setLong("Anchor", anchor.toLong());
        tag.setInteger("Zone", zoneRadius);
        tag.setLong("FirstBlow", firstBlowTick);
        tag.setLong("LastBlow", lastBlowTick);
        tag.setLong("LastSim", lastSimTick);
        if (campAnchorId != null) tag.setString("CampAnchor", campAnchorId.toString());
        NBTTagList list = new NBTTagList();
        for (BattleParticipant p : participants.values()) {
            list.appendTag(p.toNbt());
        }
        tag.setTag("Parts", list);
        NBTTagList notified = new NBTTagList();
        for (UUID owner : notifiedOwners) {
            notified.appendTag(new NBTTagString(owner.toString()));
        }
        tag.setTag("Notified", notified);
        if (lootPool.tagCount() > 0) tag.setTag("Loot", lootPool);
        return tag;
    }

    public static BattleRecord fromNbt(NBTTagCompound tag) {
        BattleRecord r = new BattleRecord(
                UUID.fromString(tag.getString("Id")),
                tag.getInteger("Dim"),
                new BlockPos(tag.getInteger("CX"), tag.getInteger("CY"), tag.getInteger("CZ")),
                tag.getLong("Start"));
        r.lastSimTick = tag.hasKey("LastSim") ? tag.getLong("LastSim") : r.startTick;
        if (tag.hasKey("Anchor")) {
            r.anchor = BlockPos.fromLong(tag.getLong("Anchor"));
            r.blowX = r.anchor.getX() + 0.5;
            r.blowZ = r.anchor.getZ() + 0.5;
        }
        if (tag.hasKey("Zone")) r.zoneRadius = tag.getInteger("Zone");
        // A battle saved before blows were tracked was already under way: treat it as visible.
        r.firstBlowTick = tag.hasKey("FirstBlow") ? tag.getLong("FirstBlow") : r.startTick;
        r.lastBlowTick = tag.hasKey("LastBlow") ? tag.getLong("LastBlow") : r.lastSimTick;
        if (tag.hasKey("CampAnchor")) r.campAnchorId = UUID.fromString(tag.getString("CampAnchor"));
        NBTTagList list = tag.getTagList("Parts", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            BattleParticipant p = BattleParticipant.fromNbt(list.getCompoundTagAt(i));
            r.participants.put(p.uuid, p);
        }
        NBTTagList notified = tag.getTagList("Notified", 8);
        for (int i = 0; i < notified.tagCount(); i++) {
            r.notifiedOwners.add(UUID.fromString(notified.getStringTagAt(i)));
        }
        if (tag.hasKey("Loot", 9)) r.lootPool = tag.getTagList("Loot", 10);
        return r;
    }
}
