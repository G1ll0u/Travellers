package com.jubitus.travellers.restspot;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.SeatFinder;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One rest spot: where it is, how roomy, and its beauty score with the part each criterion added. */
public final class RestSpotRecord {

    /** How far the walkable patch reaches from its middle. */
    public static final int REACH = 12;
    /** A unit has reached the spot within this distance of its middle. */
    public static final int ARRIVAL_RADIUS = 6;

    public final UUID id;
    /** Feet position at the middle of the walkable patch. */
    public final BlockPos center;
    public final int walkableCells;
    public final int water;
    public final int height;
    public final int openSky;
    public final int flowers;
    public final int biome;
    /** Where to sit with the best views, best first; null until worked out (spots found before seats existed). */
    @Nullable
    List<SeatFinder.Seat> seats;

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

    /** Debug text (map tooltip, debug command): score and what each criterion added. */
    public String describe() {
        return "Rest spot " + id.toString().substring(0, 8)
                + "\nScore " + score() + " (keeps from " + TravellersConfig.REST_SPOTS.minScore + ")"
                + "\nWater +" + water + "  Height +" + height
                + "\nOpen sky +" + openSky + "  Flowers +" + flowers
                + "\nBiome " + (biome >= 0 ? "+" : "") + biome
                + "\nRoom " + walkableCells + " blocks"
                + "\nSeats " + (seats == null ? "not worked out yet" : seats.size());
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
        if (seats != null) {
            NBTTagList list = new NBTTagList();
            for (SeatFinder.Seat s : seats) {
                NBTTagCompound st = new NBTTagCompound();
                st.setLong("Pos", s.feet.toLong());
                st.setByte("Dir", (byte) s.dir);
                st.setInteger("Score", s.score);
                list.appendTag(st);
            }
            tag.setTag("Seats", list);
        }
        return tag;
    }

    static RestSpotRecord readFromNBT(NBTTagCompound tag) {
        RestSpotRecord r = new RestSpotRecord(tag.getUniqueId("Id"), BlockPos.fromLong(tag.getLong("Pos")),
                tag.getInteger("Cells"), tag.getInteger("Water"), tag.getInteger("Height"),
                tag.getInteger("Sky"), tag.getInteger("Flowers"), tag.getInteger("Biome"));
        if (tag.hasKey("Seats", Constants.NBT.TAG_LIST)) {
            NBTTagList list = tag.getTagList("Seats", Constants.NBT.TAG_COMPOUND);
            List<SeatFinder.Seat> seats = new ArrayList<>(list.tagCount());
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound st = list.getCompoundTagAt(i);
                seats.add(new SeatFinder.Seat(BlockPos.fromLong(st.getLong("Pos")), st.getByte("Dir"), st.getInteger("Score")));
            }
            r.seats = Collections.unmodifiableList(seats);
        }
        return r;
    }
}
