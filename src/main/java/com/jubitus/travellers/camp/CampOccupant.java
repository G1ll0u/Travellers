package com.jubitus.travellers.camp;

import com.jubitus.travellers.camp.build.BuildStep;
import com.jubitus.travellers.camp.shelter.TerrainSnapshot;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * One traveller/raider occupying a campsite slot. Logical state (buildState/buildProgress) is the
 * source of truth; reconciliation at materialization/chunk-load brings the physical world in line
 * with it (see CampBuildCoordinator). Progress survives virtualization and restarts for free since
 * it's just an int on this NBT-persisted record.
 */
public class CampOccupant {

    /** BUILDING: buildProgress counts up from 0. TEARING_DOWN: buildProgress counts back down to 0. */
    public enum BuildState {BUILDING, BUILT, TEARING_DOWN}

    public final UUID travellerUUID;
    public final String entityClass; // "TRAVELLER" or "RAIDER"
    public final String cultureId;
    public final int slotIndex;
    public final long arrivalTick;
    public long departTick;

    public BuildState buildState = BuildState.BUILDING;
    /** Steps of the shelter's build plan currently meant to be present, counted from index 0. */
    public int buildProgress = 0;
    /**
     * World tick of the last progress change (by any means). Virtual (offscreen) progression derives
     * how many steps to advance from elapsed time since this, so it must be reset to "now" whenever
     * buildState transitions (e.g. BUILT -> TEARING_DOWN) to avoid a bogus jump from stale elapsed time.
     */
    public long lastProgressTick;
    /**
     * Locked in once picked at build start so the same template is used across the whole build.
     * Both fields are needed to resolve it unambiguously: two different cultures may have a template
     * of the same name. {@code shelterTemplateCulture} is null only for saves from before culture
     * subfolders existed - {@link com.jubitus.travellers.camp.CampBuildCoordinator} falls
     * back to {@link #cultureId} in that case.
     */
    @Nullable public String shelterTemplateName;
    @Nullable public String shelterTemplateCulture;

    // Terrain captured once, the moment building starts (before grading); restores everything
    // (structure + graded subfloor) when teardown finishes, or if the occupant dies mid-build.
    @Nullable public TerrainSnapshot snapshot;
    /**
     * Ground-fill cells (packed x,y,z triplets, in build order) computed once at first terrain touch,
     * prepended to the template plan as the builder's first steps — the terrain-matched "banking up"
     * that used to be instant dirt grading. Persisted so the full plan (and thus what buildProgress
     * indexes) stays identical across restarts; recomputing from the world after some fill was placed
     * would shrink the plan and shift every index. Null on occupants that never touched terrain (and
     * on pre-fill saves, whose progress counted template steps only).
     */
    @Nullable public int[] fillCells;
    /** Fill material state ids ({@link net.minecraft.block.Block#getStateId}): floor-level cells / buried cells. */
    public int fillSurfaceStateId;
    public int fillSubStateId;
    /** Position of the bed block once the shelter has a floor/frame up; null until then. */
    @Nullable public BlockPos bedPos;

    // --- transient, not persisted: recomputed cheaply as needed ---
    /** How many steps reconcile last confirmed are physically present; -1 = unknown (recompute). */
    public transient int physicalMark = -1;
    @Nullable public transient List<BuildStep> cachedPlan;

    public CampOccupant(UUID travellerUUID, String entityClass, String cultureId, int slotIndex,
                        long arrivalTick, long departTick) {
        this.travellerUUID = travellerUUID;
        this.entityClass = entityClass;
        this.cultureId = cultureId;
        this.slotIndex = slotIndex;
        this.arrivalTick = arrivalTick;
        this.departTick = departTick;
        this.lastProgressTick = arrivalTick;
    }

    /** True once any block of the shelter has been placed (used to decide safe materialize/approach positions). */
    public boolean hasAnyStructure() {
        return buildProgress > 0;
    }

    /**
     * World-space footprint of this occupant's shelter, or {@code null} before it has ever touched
     * terrain (snapshot is only captured at first build touch). Used to keep a materializing bonded
     * mount out of the shelter's interior - a horse belongs outside camp, not standing in a tent.
     */
    @Nullable
    public net.minecraft.util.math.AxisAlignedBB getShelterBounds() {
        return snapshot != null ? snapshot.getBounds() : null;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setUniqueId("U", travellerUUID);
        tag.setString("EC", entityClass);
        tag.setString("Cul", cultureId);
        tag.setInteger("Slot", slotIndex);
        tag.setLong("Arr", arrivalTick);
        tag.setLong("Dep", departTick);
        tag.setString("BS", buildState.name());
        tag.setInteger("BP", buildProgress);
        tag.setLong("LPT", lastProgressTick);
        if (shelterTemplateName != null) tag.setString("Tpl", shelterTemplateName);
        if (shelterTemplateCulture != null) tag.setString("TplCul", shelterTemplateCulture);
        if (snapshot != null) tag.setTag("Snap", snapshot.toNbt());
        if (fillCells != null) {
            tag.setIntArray("FillC", fillCells);
            tag.setInteger("FillS", fillSurfaceStateId);
            tag.setInteger("FillB", fillSubStateId);
        }
        if (bedPos != null) {
            tag.setInteger("BX", bedPos.getX());
            tag.setInteger("BY", bedPos.getY());
            tag.setInteger("BZ", bedPos.getZ());
        }
        return tag;
    }

    public static CampOccupant fromNbt(NBTTagCompound tag) {
        CampOccupant o = new CampOccupant(
                tag.getUniqueId("U"),
                tag.getString("EC"),
                tag.getString("Cul"),
                tag.getInteger("Slot"),
                tag.getLong("Arr"),
                tag.getLong("Dep"));
        o.buildState = tag.hasKey("BS") ? BuildState.valueOf(tag.getString("BS")) : BuildState.BUILT;
        o.buildProgress = tag.getInteger("BP");
        o.lastProgressTick = tag.getLong("LPT");
        if (tag.hasKey("Tpl")) o.shelterTemplateName = tag.getString("Tpl");
        if (tag.hasKey("TplCul")) o.shelterTemplateCulture = tag.getString("TplCul");
        if (tag.hasKey("Snap")) o.snapshot = TerrainSnapshot.fromNbt(tag.getCompoundTag("Snap"));
        if (tag.hasKey("FillC")) {
            o.fillCells = tag.getIntArray("FillC");
            o.fillSurfaceStateId = tag.getInteger("FillS");
            o.fillSubStateId = tag.getInteger("FillB");
        }
        if (tag.hasKey("BX")) o.bedPos = new BlockPos(tag.getInteger("BX"), tag.getInteger("BY"), tag.getInteger("BZ"));
        return o;
    }
}
