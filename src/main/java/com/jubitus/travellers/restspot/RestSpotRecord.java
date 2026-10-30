package com.jubitus.travellers.restspot;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/** One rest spot: where it is, how roomy, and its beauty score with the part each criterion added. */
public final class RestSpotRecord {

    public final UUID id;
    /** Feet position at the middle of the walkable patch. */
    public final BlockPos center;
    public final int walkableCells;
    public final int water;
    public final int height;
    public final int openSky;
    public final int flowers;
    public final int biome;

    RestSpotRecord(UUID id, BlockPos center, int walkableCells, int water, int height, int openSky, int flowers, int biome) {
        this.id = id;
        this.center = center;
        this.walkableCells = walkableCells;
        this.water = water;
        this.height = height;
        this.openSky = openSky;
        this.flowers = flowers;
        this.biome = biome;
    }

    public int score() {
        return water + height + openSky + flowers + biome;
    }

    NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setUniqueId("Id", id);
        tag.setLong("Pos", center.toLong());
        tag.setInteger("Cells", walkableCells);
        tag.setInteger("Water", water);
        tag.setInteger("Height", height);
        tag.setInteger("Sky", openSky);
        tag.setInteger("Flowers", flowers);
        tag.setInteger("Biome", biome);
        return tag;
    }

    static RestSpotRecord readFromNBT(NBTTagCompound tag) {
        return new RestSpotRecord(tag.getUniqueId("Id"), BlockPos.fromLong(tag.getLong("Pos")),
                tag.getInteger("Cells"), tag.getInteger("Water"), tag.getInteger("Height"),
                tag.getInteger("Sky"), tag.getInteger("Flowers"), tag.getInteger("Biome"));
    }
}
