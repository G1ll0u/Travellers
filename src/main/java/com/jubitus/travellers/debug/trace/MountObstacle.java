package com.jubitus.travellers.debug.trace;

import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Diagnostic only: what a pressed horse is pushing against, read from the real world the way vanilla's
 * {@code Entity.move} step-up would meet it. Shared by the trace events and the profiler's wedge counts.
 */
public final class MountObstacle {

    public enum Kind {
        /** Logs, Dynamic Trees branches, other tree wood. */
        WOOD,
        LEAVES,
        /** Anything else solid. */
        TERRAIN,
        /** The floor ahead is a climbable step, but the body cannot rise in place: something sits above it. */
        HEADROOM_AT_STEP,
        /** Nothing found ahead (an entity, or the contact already ended). */
        NONE
    }

    /** How far ahead of the body the contact is looked for. */
    private static final double PROBE_AHEAD = 0.35D;

    public final Kind kind;
    @Nullable public final String block;
    /** Blocking block relative to the body's feet block. */
    public final int dx, dy, dz;
    /** Rise of the step ahead, 0 when the obstacle is not a step. */
    public final double rise;

    private MountObstacle(Kind kind, @Nullable String block, int dx, int dy, int dz, double rise) {
        this.kind = kind;
        this.block = block;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.rise = rise;
    }

    public String describe() {
        if (kind == Kind.NONE) return "obstacle=NONE";
        return String.format(Locale.ROOT, "obstacle=%s block=%s at %+d,%+d,%+d%s", kind, block, dx, dy, dz,
                rise > 0.0D ? String.format(Locale.ROOT, " stepRise=%.2f", rise) : "");
    }

    /** The obstacle in front of {@code body} when it moves along {@code yawDeg}. */
    public static MountObstacle probe(Entity body, float yawDeg) {
        World world = body.world;
        AxisAlignedBB box = body.getEntityBoundingBox();
        double yaw = yawDeg * 0.017453292D;
        AxisAlignedBB ahead = box.offset(-Math.sin(yaw) * PROBE_AHEAD, 0.0D, Math.cos(yaw) * PROBE_AHEAD);
        double feet = box.minY;
        BlockPos feetPos = new BlockPos(body.posX, feet + 1.0E-3D, body.posZ);

        // Lowest-reaching tall obstacle, and the highest step top.
        BlockPos tall = null;
        double tallBottom = Double.MAX_VALUE;
        double stepTop = Double.NaN;
        BlockPos stepPos = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = MathHelper.floor(ahead.minX); x <= MathHelper.floor(ahead.maxX); x++) {
            for (int y = MathHelper.floor(ahead.minY); y <= MathHelper.floor(ahead.maxY); y++) {
                for (int z = MathHelper.floor(ahead.minZ); z <= MathHelper.floor(ahead.maxZ); z++) {
                    pos.setPos(x, y, z);
                    AxisAlignedBB bb = collisionBox(world, pos);
                    if (bb == null || !bb.intersects(ahead) || bb.intersects(box)) continue;
                    if (bb.maxY - feet <= body.stepHeight + 1.0E-3D) {
                        if (Double.isNaN(stepTop) || bb.maxY > stepTop) {
                            stepTop = bb.maxY;
                            stepPos = pos.toImmutable();
                        }
                    } else if (bb.minY < tallBottom) {
                        tallBottom = bb.minY;
                        tall = pos.toImmutable();
                    }
                }
            }
        }
        if (tall != null) return of(world, classify(world, tall), tall, feetPos, 0.0D);
        if (stepPos == null) return new MountObstacle(Kind.NONE, null, 0, 0, 0, 0.0D);

        double rise = stepTop - feet;
        // Vanilla raises the body in place first: something above the takeoff spot caps the climb.
        BlockPos above = firstCollision(world, new AxisAlignedBB(box.minX, box.maxY, box.minZ,
                box.maxX, box.maxY + rise, box.maxZ));
        if (above != null) return of(world, Kind.HEADROOM_AT_STEP, above, feetPos, rise);
        // Room to rise, but the body does not fit on top of the step.
        BlockPos onTop = firstCollision(world, ahead.offset(0.0D, rise, 0.0D));
        if (onTop != null) return of(world, classify(world, onTop), onTop, feetPos, rise);
        return of(world, classify(world, stepPos), stepPos, feetPos, rise);
    }

    private static MountObstacle of(World world, Kind kind, BlockPos at, BlockPos feet, double rise) {
        IBlockState state = world.getBlockState(at);
        ResourceLocation id = state.getBlock().getRegistryName();
        return new MountObstacle(kind, id == null ? "?" : id.toString(),
                at.getX() - feet.getX(), at.getY() - feet.getY(), at.getZ() - feet.getZ(), rise);
    }

    /** WOOD / LEAVES / TERRAIN for the block at {@code pos}. */
    public static Kind classify(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if (BlockRules.isLeaves(world, pos, state)) return Kind.LEAVES;
        if (BlockRules.isTreePart(world, pos, state)) return Kind.WOOD;
        return Kind.TERRAIN;
    }

    @Nullable
    private static AxisAlignedBB collisionBox(World world, BlockPos pos) {
        if (!LoadedChunks.isLoaded(world, pos)) return null;
        IBlockState state = world.getBlockState(pos);
        AxisAlignedBB bb = state.getCollisionBoundingBox(world, pos);
        return bb == null ? null : bb.offset(pos);
    }

    @Nullable
    private static BlockPos firstCollision(World world, AxisAlignedBB area) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = MathHelper.floor(area.minY); y <= MathHelper.floor(area.maxY); y++) {
            for (int x = MathHelper.floor(area.minX); x <= MathHelper.floor(area.maxX); x++) {
                for (int z = MathHelper.floor(area.minZ); z <= MathHelper.floor(area.maxZ); z++) {
                    pos.setPos(x, y, z);
                    AxisAlignedBB bb = collisionBox(world, pos);
                    if (bb != null && bb.intersects(area)) return pos.toImmutable();
                }
            }
        }
        return null;
    }
}
