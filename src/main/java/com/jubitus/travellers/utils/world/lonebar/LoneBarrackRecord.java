package com.jubitus.travellers.utils.world.lonebar;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LoneBarrackRecord {

    public UUID id;
    public UUID ownerUUID;
    public String name;
    public BlockPos center;
    public int halfWidth;
    public int halfDepth;
    public int halfHeight;
    public List<BlockPos> cofferPositions = new ArrayList<>();
    /** Detected bed (head) positions inside the box. Worker capacity == bedPositions.size(). */
    public List<BlockPos> bedPositions = new ArrayList<>();
    /** Worker UUID -> the bed position assigned to it (for respawn). Reconciled in MsgWorkerBarracksData.buildFor. */
    public Map<UUID, BlockPos> bedByWorker = new HashMap<>();

    public LoneBarrackRecord() {
        this.id = UUID.randomUUID();
    }

    public LoneBarrackRecord(UUID ownerUUID, String name, BlockPos center, int halfWidth, int halfDepth, int halfHeight) {
        this.id = UUID.randomUUID();
        this.ownerUUID = ownerUUID;
        this.name = name == null ? "" : name;
        this.center = center;
        this.halfWidth = halfWidth;
        this.halfDepth = halfDepth;
        this.halfHeight = halfHeight;
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
        NBTTagList cofferList = new NBTTagList();
        for (BlockPos p : cofferPositions) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("X", p.getX());
            c.setInteger("Y", p.getY());
            c.setInteger("Z", p.getZ());
            cofferList.appendTag(c);
        }
        tag.setTag("Coffers", cofferList);

        NBTTagList bedList = new NBTTagList();
        for (BlockPos p : bedPositions) {
            NBTTagCompound b = new NBTTagCompound();
            b.setInteger("X", p.getX());
            b.setInteger("Y", p.getY());
            b.setInteger("Z", p.getZ());
            bedList.appendTag(b);
        }
        tag.setTag("Beds", bedList);

        NBTTagList bedAssign = new NBTTagList();
        for (Map.Entry<UUID, BlockPos> e : bedByWorker.entrySet()) {
            NBTTagCompound a = new NBTTagCompound();
            a.setUniqueId("WID", e.getKey());
            a.setInteger("X", e.getValue().getX());
            a.setInteger("Y", e.getValue().getY());
            a.setInteger("Z", e.getValue().getZ());
            bedAssign.appendTag(a);
        }
        tag.setTag("BedAssignments", bedAssign);
    }

    /** A bed not currently assigned to any worker, or null if all beds are taken. */
    public BlockPos pickFreeBed() {
        java.util.Set<BlockPos> taken = new java.util.HashSet<>(bedByWorker.values());
        for (BlockPos b : bedPositions) {
            if (!taken.contains(b)) return b;
        }
        return null;
    }

    public static LoneBarrackRecord readFromNBT(NBTTagCompound tag) {
        LoneBarrackRecord r = new LoneBarrackRecord();
        r.id = tag.getUniqueId("Id");
        r.ownerUUID = tag.getUniqueId("Owner");
        r.name = tag.getString("Name");
        r.center = new BlockPos(tag.getInteger("CX"), tag.getInteger("CY"), tag.getInteger("CZ"));
        // Support old saves that used "Radius" for both X and Z
        int legacyRadius = tag.getInteger("Radius");
        r.halfWidth = tag.hasKey("HalfWidth") ? tag.getInteger("HalfWidth") : legacyRadius;
        r.halfDepth = tag.hasKey("HalfDepth") ? tag.getInteger("HalfDepth") : legacyRadius;
        r.halfHeight = tag.getInteger("HalfHeight");
        NBTTagList cofferList = tag.getTagList("Coffers", 10);
        for (int i = 0; i < cofferList.tagCount(); i++) {
            NBTTagCompound c = cofferList.getCompoundTagAt(i);
            r.cofferPositions.add(new BlockPos(c.getInteger("X"), c.getInteger("Y"), c.getInteger("Z")));
        }

        NBTTagList bedList = tag.getTagList("Beds", 10);
        for (int i = 0; i < bedList.tagCount(); i++) {
            NBTTagCompound b = bedList.getCompoundTagAt(i);
            r.bedPositions.add(new BlockPos(b.getInteger("X"), b.getInteger("Y"), b.getInteger("Z")));
        }

        NBTTagList bedAssign = tag.getTagList("BedAssignments", 10);
        for (int i = 0; i < bedAssign.tagCount(); i++) {
            NBTTagCompound a = bedAssign.getCompoundTagAt(i);
            r.bedByWorker.put(a.getUniqueId("WID"),
                    new BlockPos(a.getInteger("X"), a.getInteger("Y"), a.getInteger("Z")));
        }
        return r;
    }
}
