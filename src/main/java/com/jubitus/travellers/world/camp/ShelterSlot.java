package com.jubitus.travellers.world.camp;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * One pre-validated 5×5 shelter spot inside a campsite.
 * {@code origin} is the lowest-XZ corner of the footprint at ground level (the block the floor sits on).
 * {@code entranceFacing} points toward the campfire so a placed shelter's door faces the center.
 */
public class ShelterSlot {

    public static final int FOOTPRINT = 5;

    public final BlockPos origin;
    public final EnumFacing entranceFacing;
    /** True once the footprint failed the pre-build natural-terrain check (e.g. a player built there); never claimable again. */
    public boolean disabled = false;

    public ShelterSlot(BlockPos origin, EnumFacing entranceFacing) {
        this.origin = origin;
        this.entranceFacing = entranceFacing;
    }

    /** Center column of the footprint at ground level (used for distance checks and entity targeting). */
    public BlockPos center() {
        return origin.add(FOOTPRINT / 2, 0, FOOTPRINT / 2);
    }

    /**
     * Standing position 2 blocks outside the footprint on the entrance side, at entity-feet Y.
     * Two blocks of clearance ensures the entity cannot overshoot into the footprint while walking here,
     * so the shelter can safely spawn without catching the entity inside a wall.
     */
    public BlockPos approachPos() {
        int fp = FOOTPRINT;
        int floorY = origin.getY() + 1;
        int cx = origin.getX() + fp / 2;
        int cz = origin.getZ() + fp / 2;
        switch (entranceFacing) {
            case SOUTH: return new BlockPos(cx, floorY, origin.getZ() + fp + 1);
            case NORTH: return new BlockPos(cx, floorY, origin.getZ() - 2);
            case EAST:  return new BlockPos(origin.getX() + fp + 1, floorY, cz);
            case WEST:  return new BlockPos(origin.getX() - 2, floorY, cz);
            default:    return center().up();
        }
    }

    /**
     * Nearest point just outside the footprint (1 block clearance, entity-feet Y) to {@code target} —
     * used while building/tearing down block by block so the traveller paces around whichever side of
     * the shelter it's currently working on instead of pathfinding to the door every time.
     */
    public BlockPos perimeterPosNear(BlockPos target) {
        int minX = origin.getX(), maxX = origin.getX() + FOOTPRINT - 1;
        int minZ = origin.getZ(), maxZ = origin.getZ() + FOOTPRINT - 1;
        int floorY = origin.getY() + 1;
        int cx = clamp(target.getX(), minX, maxX);
        int cz = clamp(target.getZ(), minZ, maxZ);

        BlockPos north = new BlockPos(cx, floorY, minZ - 1);
        BlockPos south = new BlockPos(cx, floorY, maxZ + 1);
        BlockPos west = new BlockPos(minX - 1, floorY, cz);
        BlockPos east = new BlockPos(maxX + 1, floorY, cz);

        BlockPos best = north;
        double bestDist = target.distanceSq(north);
        for (BlockPos candidate : new BlockPos[]{south, west, east}) {
            double d = target.distanceSq(candidate);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        return best;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("OX", origin.getX());
        tag.setInteger("OY", origin.getY());
        tag.setInteger("OZ", origin.getZ());
        tag.setInteger("F", entranceFacing.getHorizontalIndex());
        if (disabled) tag.setBoolean("Dis", true);
        return tag;
    }

    public static ShelterSlot fromNbt(NBTTagCompound tag) {
        BlockPos origin = new BlockPos(tag.getInteger("OX"), tag.getInteger("OY"), tag.getInteger("OZ"));
        EnumFacing facing = EnumFacing.byHorizontalIndex(tag.getInteger("F"));
        ShelterSlot slot = new ShelterSlot(origin, facing);
        slot.disabled = tag.getBoolean("Dis");
        return slot;
    }
}
