package com.jubitus.travellers.unit;

import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The bed pose (camps, Stable hands, Barrack workers): a cosmetic lie-down, synced for render and
 * never persisted (virtualization never rematerializes a unit mid-sleep). No time skip, no spawn point.
 */
public final class TravellerSleep {

    private final EntityTraveller unit;
    // Server-only exact sleep spot, re-applied every tick so nearby-entity pushes never slide a sleeper off its bed.
    private double anchorX, anchorY, anchorZ;

    TravellerSleep(EntityTraveller unit) {
        this.unit = unit;
    }

    public boolean isSleeping() {
        return unit.getDataManager().get(EntityTraveller.SLEEPING);
    }

    public BlockPos getBedPos() {
        return unit.getDataManager().get(EntityTraveller.SLEEPING_BED_POS);
    }

    /**
     * Lies down in the given bed block. No distance/safety checks (the calling AI already
     * guarantees proximity and context).
     *
     * <p>Mirrors {@link net.minecraft.entity.player.EntityPlayer#trySleep} exactly: the entity is
     * anchored near the HEAD half (nudged 0.4 blocks further along {@code FACING}, away from the
     * foot), and the render is separately shifted back toward the foot by
     * {@link #getRenderOffset()} (mirroring {@code EntityPlayer#setRenderOffsetForSleep}) so
     * the body visually spans the full two-block bed regardless of {@code FACING}/bed type. The
     * two pieces (anchor position and render offset) only look right together.
     */
    public boolean trySleepIn(BlockPos bedPos) {
        if (unit.world.isRemote || !unit.isEntityAlive() || isSleeping()) return false;
        IBlockState state = unit.world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BlockBed)) return false;

        BlockBed.EnumPartType part = state.getValue(BlockBed.PART);
        BlockPos headPos = bedPos;
        if (part != BlockBed.EnumPartType.HEAD) {
            BlockPos candidate = bedPos.offset(state.getValue(BlockHorizontal.FACING));
            IBlockState candidateState = unit.world.getBlockState(candidate);
            if (candidateState.getBlock() instanceof BlockBed
                    && candidateState.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
                headPos = candidate;
                state = candidateState;
            } // else: single/broken bed fallback, sleep on the one block found
        }

        EnumFacing facing = state.getValue(BlockHorizontal.FACING);
        double x = headPos.getX() + 0.5D + facing.getXOffset() * 0.4D;
        double z = headPos.getZ() + 0.5D + facing.getZOffset() * 0.4D;
        // Rest on the bed's OWN collision box, not vanilla's 0.6875 constant — Millénaire beds
        // (BlockMillBed) have custom, often lower collision heights and the constant floats above them.
        double y = headPos.getY() + state.getBoundingBox(unit.world, headPos).maxY;

        unit.setSleepingSize();
        unit.motionX = 0.0D;
        unit.motionY = 0.0D;
        unit.motionZ = 0.0D;
        unit.setPosition(x, y, z);
        this.anchorX = x;
        this.anchorY = y;
        this.anchorZ = z;
        unit.getDataManager().set(EntityTraveller.SLEEPING_BED_POS, headPos);
        unit.getDataManager().set(EntityTraveller.SLEEPING, true);
        return true;
    }

    public void wakeUp() {
        if (unit.world.isRemote || !isSleeping()) return;
        BlockPos bedPos = getBedPos();
        unit.getDataManager().set(EntityTraveller.SLEEPING, false);
        unit.updateSizeForRenderScale();
        // The sleep anchor sits on the bed's own collision box; restoring the full-size hitbox there
        // clips it into the bed or a wall. Step it onto a real floor beside the bed, like a bed respawn.
        if (bedPos != null && !BlockPos.ORIGIN.equals(bedPos)) relocateOffBed(bedPos);
    }

    /**
     * Steps the just-woken entity off the bed onto a real floor cell in front of it. Always moves.
     * Foot-side of the bed first (where you'd step out), then head, then diagonals, then one Y lower;
     * only as an absolute last resort does it stand on the bed itself - never higher, so an indoor
     * bed never sends it to the roof.
     */
    private void relocateOffBed(BlockPos bedHead) {
        BlockPos foot = bedHead;
        IBlockState st = unit.world.getBlockState(bedHead);
        if (st.getBlock() instanceof BlockBed && st.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
            foot = bedHead.offset(st.getValue(BlockHorizontal.FACING).getOpposite());
        }
        BlockPos[] centers = {foot, bedHead};
        for (int dy = 0; dy >= -1; dy--) {
            for (BlockPos c : centers) {
                for (EnumFacing d : EnumFacing.HORIZONTALS) {
                    BlockPos p = c.offset(d);
                    if (p.equals(bedHead) || p.equals(foot)) continue;
                    if (standAt(p.up(dy))) return;
                }
            }
            for (BlockPos c : centers) {
                for (EnumFacing d : EnumFacing.HORIZONTALS) {
                    if (standAt(c.offset(d).offset(d.rotateY()).up(dy))) return;
                }
            }
        }
        standAt(bedHead.up());
    }

    private boolean standAt(BlockPos p) {
        if (!unit.world.getBlockState(p.down()).isSideSolid(unit.world, p.down(), EnumFacing.UP)) return false;
        double cx = p.getX() + 0.5D, cy = p.getY(), cz = p.getZ() + 0.5D;
        if (!isPositionClear(cx, cy, cz)) return false;
        Relocate.teleport(unit, cx, cy, cz);
        return true;
    }

    private boolean isPositionClear(double x, double y, double z) {
        AxisAlignedBB box = new AxisAlignedBB(
                x - unit.width / 2.0D, y, z - unit.width / 2.0D,
                x + unit.width / 2.0D, y + unit.height, z + unit.width / 2.0D).contract(0.02D, 0.0D, 0.02D);
        return unit.world.getCollisionBoxes(unit, box).isEmpty() && !unit.world.containsAnyLiquid(box);
    }

    /** Re-pins the sleeper to its exact anchor, undoing any drift from collisions or stray AI movement. */
    void holdPosition() {
        unit.motionX = 0.0D;
        unit.motionY = 0.0D;
        unit.motionZ = 0.0D;
        if (unit.posX != anchorX || unit.posY != anchorY || unit.posZ != anchorZ) {
            unit.setPosition(anchorX, anchorY, anchorZ);
        }
    }

    /** Vanilla's own hardcoded mapping ({@code EntityPlayer#getBedOrientationInDegrees}), not {@code getHorizontalAngle} (90° off). */
    @SideOnly(Side.CLIENT)
    public float getBedOrientationInDegrees() {
        BlockPos bedPos = getBedPos();
        IBlockState state = unit.world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BlockBed)) return 0.0F;
        switch (state.getValue(BlockHorizontal.FACING)) {
            case SOUTH: return 90.0F;
            case WEST: return 0.0F;
            case NORTH: return 270.0F;
            case EAST: return 180.0F;
            default: return 0.0F;
        }
    }

    /**
     * Client-only: how far to shift the rendered model away from the anchor (which sits near the
     * HEAD half), mirroring {@code EntityPlayer#renderOffsetX/Z}, so the body spans the full bed.
     */
    @SideOnly(Side.CLIENT)
    public Vec3d getRenderOffset() {
        BlockPos bedPos = getBedPos();
        IBlockState state = unit.world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BlockBed)) return Vec3d.ZERO;
        EnumFacing facing = state.getValue(BlockHorizontal.FACING);
        // Vanilla uses -1.8 for a full-size player; the model renders at getRenderScale(), so the
        // shift shrinks to keep the shorter body centered in the 2-block bed: -0.9 * (1 + scale).
        double d = -0.9D * (1.0D + unit.getRenderScale());
        return new Vec3d(d * facing.getXOffset(), 0.0D, d * facing.getZOffset());
    }
}
