package com.jubitus.travellers.zone;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A player-created zone: a Barrack (worker housing) or a Stable (horse pool), and whatever future
 * types get added. Generalized from the original lone-barrack-only record - the box/overlap/owner
 * bookkeeping is shared by every {@link ZoneType}; what makes a type different lives in its
 * {@link ZoneTypeDef} (creation requirements) and its {@link ZoneTypeData} payload (extra state).
 */
public class PlayerZoneRecord {

    public UUID id;
    public UUID ownerUUID;
    public String name;
    public BlockPos center;
    public int halfWidth;
    public int halfDepth;
    public int halfHeight;
    public ZoneType type = ZoneType.BARRACK;

    /** Detected block positions inside the box, keyed by role ("bed", "chest", "hitch", ...). */
    private final Map<String, List<BlockPos>> blocksByRole = new HashMap<>();

    /** Per-type extension state - a {@link BarrackZoneData} for a BARRACK, etc. Never null after
     * construction or NBT read. */
    public ZoneTypeData data;

    public PlayerZoneRecord() {
        this.id = UUID.randomUUID();
    }

    public PlayerZoneRecord(UUID ownerUUID, String name, BlockPos center,
                             int halfWidth, int halfDepth, int halfHeight, ZoneType type) {
        this.id = UUID.randomUUID();
        this.ownerUUID = ownerUUID;
        this.name = name == null ? "" : name;
        this.center = center;
        this.halfWidth = halfWidth;
        this.halfDepth = halfDepth;
        this.halfHeight = halfHeight;
        this.type = type;
        this.data = ZoneTypeDefs.get(type).dataFactory.get();
    }

    /** Is this block inside the zone's box. */
    public boolean contains(BlockPos pos) {
        return Math.abs(pos.getX() - center.getX()) <= halfWidth
                && Math.abs(pos.getZ() - center.getZ()) <= halfDepth
                && Math.abs(pos.getY() - center.getY()) <= halfHeight;
    }

    /** The zone's box in world coordinates, grown by {@code margin} blocks on every side. */
    public AxisAlignedBB box(double margin) {
        return new AxisAlignedBB(
                center.getX() - halfWidth - margin, center.getY() - halfHeight - margin, center.getZ() - halfDepth - margin,
                center.getX() + halfWidth + 1 + margin, center.getY() + halfHeight + 1 + margin, center.getZ() + halfDepth + 1 + margin);
    }

    public List<BlockPos> getRolePositions(String role) {
        List<BlockPos> l = blocksByRole.get(role);
        return l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
    }

    public void setRolePositions(String role, List<BlockPos> positions) {
        blocksByRole.put(role, new ArrayList<>(positions));
    }

    /** Convenience cast - only valid when {@code type == ZoneType.BARRACK}. */
    public BarrackZoneData barrackData() {
        return (BarrackZoneData) data;
    }

    /** A bed not currently assigned to any worker, or null if all beds are taken. Works for any zone
     * type whose {@link ZoneTypeData} is a {@link ZoneBedData} (Barrack residents, Stable palfreniers). */
    public BlockPos pickFreeBed() {
        List<BlockPos> free = freeBeds();
        return free.isEmpty() ? null : free.get(0);
    }

    public int countFreeBeds() {
        return freeBeds().size();
    }

    private List<BlockPos> freeBeds() {
        List<BlockPos> free = new ArrayList<>();
        if (!(data instanceof ZoneBedData)) return free;
        Set<BlockPos> taken = new HashSet<>(((ZoneBedData) data).bedByWorker().values());
        for (BlockPos b : getRolePositions(ZoneRoles.BED)) {
            if (!taken.contains(b)) free.add(b);
        }
        return free;
    }

    public void writeToNBT(NBTTagCompound tag) {
        tag.setUniqueId("Id", id);
        tag.setUniqueId("Owner", ownerUUID);
        tag.setString("Name", name == null ? "" : name);
        tag.setInteger("CX", center.getX());
        tag.setInteger("CY", center.getY());
        tag.setInteger("CZ", center.getZ());
        tag.setInteger("HalfWidth", halfWidth);
        tag.setInteger("HalfDepth", halfDepth);
        tag.setInteger("HalfHeight", halfHeight);
        tag.setInteger("ZoneType", type.ordinal());

        NBTTagList blocks = new NBTTagList();
        for (Map.Entry<String, List<BlockPos>> e : blocksByRole.entrySet()) {
            for (BlockPos p : e.getValue()) {
                NBTTagCompound b = new NBTTagCompound();
                b.setString("Role", e.getKey());
                b.setInteger("X", p.getX());
                b.setInteger("Y", p.getY());
                b.setInteger("Z", p.getZ());
                blocks.appendTag(b);
            }
        }
        tag.setTag("Blocks", blocks);

        if (data != null) {
            NBTTagCompound dataTag = new NBTTagCompound();
            data.writeToNBT(dataTag);
            tag.setTag("TypeData", dataTag);
        }
    }

    public static PlayerZoneRecord readFromNBT(NBTTagCompound tag) {
        PlayerZoneRecord r = new PlayerZoneRecord();
        r.id = tag.getUniqueId("Id");
        r.ownerUUID = tag.getUniqueId("Owner");
        r.name = tag.getString("Name");
        r.center = new BlockPos(tag.getInteger("CX"), tag.getInteger("CY"), tag.getInteger("CZ"));
        // Support old saves that used "Radius" for both X and Z
        int legacyRadius = tag.getInteger("Radius");
        r.halfWidth = tag.hasKey("HalfWidth") ? tag.getInteger("HalfWidth") : legacyRadius;
        r.halfDepth = tag.hasKey("HalfDepth") ? tag.getInteger("HalfDepth") : legacyRadius;
        r.halfHeight = tag.getInteger("HalfHeight");
        // Pre-Stable saves have no ZoneType tag at all - every existing record is a Barrack.
        r.type = tag.hasKey("ZoneType") ? ZoneType.fromNbt(tag.getInteger("ZoneType")) : ZoneType.BARRACK;

        if (tag.hasKey("Blocks")) {
            NBTTagList blocks = tag.getTagList("Blocks", 10);
            for (int i = 0; i < blocks.tagCount(); i++) {
                NBTTagCompound b = blocks.getCompoundTagAt(i);
                String role = b.getString("Role");
                r.blocksByRole.computeIfAbsent(role, k -> new ArrayList<>())
                        .add(new BlockPos(b.getInteger("X"), b.getInteger("Y"), b.getInteger("Z")));
            }
        } else {
            // Pre-generalization save: read the old dedicated "Coffers"/"Beds" lists into roles.
            r.blocksByRole.put(ZoneRoles.CHEST, readLegacyPositionList(tag, "Coffers"));
            r.blocksByRole.put(ZoneRoles.BED, readLegacyPositionList(tag, "Beds"));
        }

        r.data = ZoneTypeDefs.get(r.type).dataFactory.get();
        if (tag.hasKey("TypeData")) {
            r.data.readFromNBT(tag.getCompoundTag("TypeData"));
        } else if (r.type == ZoneType.BARRACK && tag.hasKey("BedAssignments")) {
            // Pre-generalization save: bed assignments lived directly on the record's own tag.
            r.data.readFromNBT(tag);
        }
        return r;
    }

    private static List<BlockPos> readLegacyPositionList(NBTTagCompound tag, String key) {
        List<BlockPos> out = new ArrayList<>();
        NBTTagList list = tag.getTagList(key, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            out.add(new BlockPos(c.getInteger("X"), c.getInteger("Y"), c.getInteger("Z")));
        }
        return out;
    }
}
