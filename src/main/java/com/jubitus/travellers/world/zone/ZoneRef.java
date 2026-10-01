package com.jubitus.travellers.world.zone;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A worker's one zone assignment: a Barrack (lodging) or a Stable (lodging + work). The zone is
 * identified by its persisted {@link PlayerZoneRecord#id}; the centre, dimension, name and type are
 * cached for travel, display and the offscreen side. A reference read from a save older than zone ids
 * has no id yet and is matched by owner + centre until {@link #resolve} fills it in.
 */
public final class ZoneRef {

    @Nullable public UUID id;
    public final BlockPos center;
    public final int dim;
    public String name;
    public ZoneType type;

    public ZoneRef(@Nullable UUID id, BlockPos center, int dim, String name, ZoneType type) {
        this.id = id;
        this.center = center;
        this.dim = dim;
        this.name = name == null ? "" : name;
        this.type = type;
    }

    public static ZoneRef of(PlayerZoneRecord zone, int dim) {
        return new ZoneRef(zone.id, zone.center, dim, zone.name, zone.type);
    }

    /** The worker duty run at this zone, or null for a lodging-only zone. */
    @Nullable
    public String dutyKind() {
        return ZoneTypeDefs.get(type).dutyKind;
    }

    public boolean isDuty() {
        return dutyKind() != null;
    }

    /** True if this reference points at {@code zone} (by id, or by centre for a not-yet-resolved one). */
    public boolean matches(PlayerZoneRecord zone) {
        return id != null ? id.equals(zone.id) : center.equals(zone.center);
    }

    /** The zone this points at among {@code owner}'s zones in its dimension, or null (gone, or the
     * dimension is not loaded - see {@link #isDimensionLoaded}). Fills in a missing id. */
    @Nullable
    public PlayerZoneRecord resolve(UUID owner) {
        WorldServer w = DimensionManager.getWorld(dim);
        if (w == null) return null;
        PlayerZoneManager mgr = PlayerZoneManager.get(w);
        PlayerZoneRecord zone = id != null ? mgr.getById(id) : mgr.getByOwnerAndCenter(owner, center);
        if (zone != null && owner.equals(zone.ownerUUID)) {
            id = zone.id;
            name = zone.name;
            type = zone.type;
            return zone;
        }
        return null;
    }

    public boolean isDimensionLoaded() {
        return DimensionManager.getWorld(dim) != null;
    }

    public void write(NBTTagCompound nbt) {
        if (id != null) nbt.setUniqueId("ZoneId", id);
        nbt.setInteger("ZoneX", center.getX());
        nbt.setInteger("ZoneY", center.getY());
        nbt.setInteger("ZoneZ", center.getZ());
        nbt.setInteger("ZoneDim", dim);
        nbt.setString("ZoneName", name);
        nbt.setInteger("ZoneType", type.ordinal());
    }

    /** Reads the assignment from a worker's NBT: the current tags, else the pre-zone-id ones (a
     * Stable duty, else a Barrack home; HomeType 1 was the removed Millénaire barracks building). */
    @Nullable
    public static ZoneRef read(NBTTagCompound nbt) {
        if (nbt.hasKey("ZoneX")) {
            UUID id = nbt.hasUniqueId("ZoneId") ? nbt.getUniqueId("ZoneId") : null;
            return new ZoneRef(id, new BlockPos(nbt.getInteger("ZoneX"), nbt.getInteger("ZoneY"), nbt.getInteger("ZoneZ")),
                    nbt.getInteger("ZoneDim"), nbt.getString("ZoneName"), ZoneType.fromNbt(nbt.getInteger("ZoneType")));
        }
        if (nbt.hasKey("DutyX")) {
            return new ZoneRef(null, new BlockPos(nbt.getInteger("DutyX"), nbt.getInteger("DutyY"), nbt.getInteger("DutyZ")),
                    nbt.getInteger("DutyDim"), "", ZoneType.STABLE);
        }
        if (nbt.hasKey("HomeX") && nbt.getInteger("HomeType") != 1) {
            return new ZoneRef(null, new BlockPos(nbt.getInteger("HomeX"), nbt.getInteger("HomeY"), nbt.getInteger("HomeZ")),
                    nbt.getInteger("HomeDim"), nbt.getString("HomeName"), ZoneType.BARRACK);
        }
        return null;
    }

    /** Removes every assignment tag, current and legacy. */
    public static void clear(NBTTagCompound nbt) {
        for (String k : new String[]{"ZoneIdMost", "ZoneIdLeast", "ZoneX", "ZoneY", "ZoneZ", "ZoneDim", "ZoneName",
                "ZoneType", "HomeX", "HomeY", "HomeZ", "HomeDim", "HomeName", "HomeType",
                "DutyX", "DutyY", "DutyZ", "DutyDim", "DutyKind"}) {
            nbt.removeTag(k);
        }
    }
}
