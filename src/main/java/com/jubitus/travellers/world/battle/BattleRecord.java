package com.jubitus.travellers.world.battle;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import net.minecraft.nbt.NBTTagString;

import javax.annotation.Nullable;
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
    /** Recomputed centroid of alive virtual pawns; battles anchored at a camp keep the camp center. */
    public BlockPos center;
    public long startTick;
    /** Non-null when the battle is an assault on an occupied camp (anchors the center). */
    @Nullable public UUID campAnchorId;
    public final Map<UUID, BattleParticipant> participants = new LinkedHashMap<>();
    /** Owners already notified that their workers joined this battle (no notification spam). */
    public final Set<UUID> notifiedOwners = new HashSet<>();
    /** Sim round-robin bookkeeping: world tick of the last sim step applied to this battle. */
    public long lastSimTick;
    /** Accumulated equipment/inventory of virtual pawns killed in this battle, distributed on endBattle. */
    public NBTTagList lootPool = new NBTTagList();

    public BattleRecord(UUID id, int dimension, BlockPos center, long startTick) {
        this.id = id;
        this.dimension = dimension;
        this.center = center;
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
