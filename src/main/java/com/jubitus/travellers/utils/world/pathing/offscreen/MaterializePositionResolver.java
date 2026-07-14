package com.jubitus.travellers.utils.world.pathing.offscreen;

import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

import com.jubitus.travellers.utils.world.mill.MillVillageIndex;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Decides where a virtual worker should appear when it materializes.
 *
 * Rules:
 * - Escort followers with a stored slot offset (captured at virtualization from
 *   FollowFormationCoordinator) are placed at their exact formation slot relative
 *   to the leader. This avoids the visible re-shuffle that would occur if they
 *   all appeared in a ring and then pathed to their slots.
 * - Escort followers without a slot offset (new/unassigned workers) fall back to
 *   a deterministic UUID ring around the leader so they don't all stack.
 * - The leader's Y is always used as the vertical reference for escort followers,
 *   ensuring they appear at the same level (cave, building, surface) as the group.
 * - Solo workers that traveled far get a random spread.
 * - Y is resolved against loaded-chunk terrain, searching outward from the
 *   reference Y so underground workers stay underground.
 */
public final class MaterializePositionResolver {

    // Spread ring radius for followers that converged to leader (deterministic per UUID, blocks).
    // Only used when no slot offset is available.
    private static final double FOLLOW_SPREAD_MIN = 1.5;
    private static final double FOLLOW_SPREAD_MAX = 4.5;

    // Random spread radius for solo travelers that moved far (blocks, ±)
    private static final double SOLO_SPREAD = 2.5;

    private MaterializePositionResolver() {}

    // -------------------------------------------------------------------------

    public static final class ResolvedPosition {
        public final double x, y, z;

        ResolvedPosition(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Resolves the spawn position for {@code r} materializing in {@code world}.
     *
     * @param allRecords the full virtual records map — used to locate a virtual escort leader
     */
    public static ResolvedPosition resolve(WorldServer world,
                                           VirtualTravellerRecord r,
                                           Map<UUID, VirtualTravellerRecord> allRecords) {
        double baseX = Math.floor(r.posX) + 0.5;
        double baseZ = Math.floor(r.posZ) + 0.5;
        double referenceY = r.posY;

        // Mid-battle pawn: appear exactly where the battle sim placed it — no formation slot,
        // ring spread or building avoidance (the fight is where it is). Y is still resolved.
        if (r.battleId != null) {
            int bbx = (int) Math.floor(baseX);
            int bbz = (int) Math.floor(baseZ);
            double y = referenceY;
            if (world.getChunkProvider().chunkExists(bbx >> 4, bbz >> 4)) {
                y = findSafeFloorY(world, bbx, bbz, referenceY);
                y = ensureNotInSolid(world, bbx, y, bbz);
            }
            return new ResolvedPosition(baseX, y, baseZ);
        }

        // Group traveller/raider followers: spawn in a deterministic ring around the leader.
        // Must be checked before escortLeaderUUID (workers use that field, not this one).
        if (r.groupLeaderUUID != null) {
            double[] leaderPos = findLeaderPosition(world, r.groupLeaderUUID, allRecords);
            if (leaderPos != null) {
                referenceY = leaderPos[1];
                int[] off = followerSpreadOffset(r.uuid);
                double cx = Math.floor(leaderPos[0] + off[0]) + 0.5;
                double cz = Math.floor(leaderPos[2] + off[1]) + 0.5;
                if (chunkLoaded(world, cx, cz)) {
                    baseX = cx;
                    baseZ = cz;
                }
            }
        }

        if (r.escortLeaderUUID != null) {
            double[] leaderPos = findLeaderPosition(world, r.escortLeaderUUID, allRecords);
            if (leaderPos != null) {
                // Use leader's Y so underground/building followers spawn at the right level.
                referenceY = leaderPos[1];

                if (r.hasEscortSlotOffset) {
                    // Restore the worker to its exact formation slot: apply the offset that was
                    // captured when the worker virtualized (from FollowFormationCoordinator).
                    // This makes the group appear already in formation, with no visible re-shuffle.
                    double candidateX = Math.floor(leaderPos[0] + r.escortSlotDx) + 0.5;
                    double candidateZ = Math.floor(leaderPos[2] + r.escortSlotDz) + 0.5;
                    if (chunkLoaded(world, candidateX, candidateZ)) {
                        baseX = candidateX;
                        baseZ = candidateZ;
                    }
                } else {
                    // Slot offset not available (worker never had a slot assignment).
                    // Fall back to a deterministic UUID ring so workers don't all stack.
                    int[] off = followerSpreadOffset(r.uuid);
                    double cx = baseX + off[0];
                    double cz = baseZ + off[1];
                    if (chunkLoaded(world, cx, cz)) {
                        baseX = cx;
                        baseZ = cz;
                    }
                }
            }

        } else if (r.hasTraveledFar) {
            // Solo traveler that moved: random spread to avoid stacking
            int ox = (int) Math.round((world.rand.nextDouble() - 0.5) * SOLO_SPREAD * 2);
            int oz = (int) Math.round((world.rand.nextDouble() - 0.5) * SOLO_SPREAD * 2);
            double cx = Math.floor(r.posX) + ox + 0.5;
            double cz = Math.floor(r.posZ) + oz + 0.5;
            if (chunkLoaded(world, cx, cz)) {
                baseX = cx;
                baseZ = cz;
            }
        }

        int bx = (int) Math.floor(baseX);
        int bz = (int) Math.floor(baseZ);

        // Never materialize on/inside a Millénaire building footprint (e.g. a Town Hall roof).
        // Pure metadata check, works whether or not the chunk is loaded.
        BlockPos safe = MillVillageIndex.resolveOutsideBuildings(
                world, new BlockPos(bx, (int) referenceY, bz), 256);
        bx = safe.getX();
        bz = safe.getZ();
        baseX = bx + 0.5;
        baseZ = bz + 0.5;

        double spawnY = referenceY;
        if (world.getChunkProvider().chunkExists(bx >> 4, bz >> 4)) {
            spawnY = findSafeFloorY(world, bx, bz, referenceY);
            spawnY = ensureNotInSolid(world, bx, spawnY, bz);
        }

        return new ResolvedPosition(baseX, spawnY, baseZ);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Deterministic per-UUID spread offset.
     * Maps each UUID to a fixed angle and radius so every member of a group
     * occupies a different slot in a ring, even across multiple materializations.
     */
    public static int[] followerSpreadOffset(UUID uuid) {
        long h = uuid.getLeastSignificantBits() ^ uuid.getMostSignificantBits();
        double angle = (h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL * 2.0 * Math.PI;
        double radius = FOLLOW_SPREAD_MIN
                + ((h >>> 32 & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL)
                * (FOLLOW_SPREAD_MAX - FOLLOW_SPREAD_MIN);
        return new int[]{
                (int) Math.round(Math.cos(angle) * radius),
                (int) Math.round(Math.sin(angle) * radius)
        };
    }

    @Nullable
    private static double[] findLeaderPosition(WorldServer world,
                                               UUID leaderUUID,
                                               Map<UUID, VirtualTravellerRecord> allRecords) {
        VirtualTravellerRecord rec = allRecords.get(leaderUUID);
        if (rec != null) return new double[]{ rec.posX, rec.posY, rec.posZ };
        Entity e = world.getEntityFromUuid(leaderUUID);
        if (e != null && !e.isDead) return new double[]{ e.posX, e.posY, e.posZ };
        return null;
    }

    private static boolean chunkLoaded(WorldServer world, double x, double z) {
        return world.getChunkProvider().chunkExists(
                (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    // -------------------------------------------------------------------------
    // Y resolution — public so barracks managers can reuse them
    // -------------------------------------------------------------------------

    /**
     * Finds a safe Y (entity feet) at (bx, bz), searching outward from referenceY.
     * Goes down first at each offset to preserve underground positions
     * (cave workers stay underground instead of being pushed to the surface).
     */
    public static double findSafeFloorY(WorldServer world, int bx, int bz, double referenceY) {
        int refY = (int) Math.floor(referenceY);
        int maxY = world.getActualHeight() - 3;

        for (int offset = 0; offset <= 32; offset++) {
            int yDown = refY - offset;
            if (yDown >= 1 && isSafeFloor(world, bx, yDown, bz)) return yDown + 1.0;
            if (offset > 0) {
                int yUp = refY + offset;
                if (yUp <= maxY && isSafeFloor(world, bx, yUp, bz)) return yUp + 1.0;
            }
        }

        // Last resort: top-down scan skipping foliage
        for (int y = maxY; y > 1; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            IBlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof BlockLeaves) continue;
            if (state.getBlock() instanceof BlockLog) continue;
            if (state.getBlock().isAir(state, world, pos)) continue;
            if (isSafeFloor(world, bx, y, bz)) return y + 1.0;
        }

        return referenceY;
    }

    private static boolean isSafeFloor(WorldServer world, int bx, int y, int bz) {
        BlockPos floor = new BlockPos(bx, y, bz);
        IBlockState st = world.getBlockState(floor);
        if (st.getBlock().isAir(st, world, floor)) return false;
        IBlockState s1 = world.getBlockState(floor.up());
        IBlockState s2 = world.getBlockState(floor.up(2));
        return s1.getBlock().isAir(s1, world, floor.up())
                && s2.getBlock().isAir(s2, world, floor.up(2));
    }

    /**
     * Pushes spawnY up until the entity has two consecutive air blocks to stand in.
     * Guards against spread landing a worker inside a wall.
     */
    public static double ensureNotInSolid(WorldServer world, int bx, double spawnY, int bz) {
        int y = (int) Math.floor(spawnY);
        int maxY = world.getActualHeight() - 2;
        for (int i = 0; y + i <= maxY; i++) {
            BlockPos p1 = new BlockPos(bx, y + i, bz);
            BlockPos p2 = new BlockPos(bx, y + i + 1, bz);
            IBlockState s1 = world.getBlockState(p1);
            IBlockState s2 = world.getBlockState(p2);
            if (s1.getBlock().isAir(s1, world, p1) && s2.getBlock().isAir(s2, world, p2)) {
                return y + i;
            }
        }
        return spawnY;
    }

    /**
     * Nudges any living entity standing inside {@code [min, max]} (inclusive, block coords) up onto
     * solid ground if a bulk terrain edit (grading a shelter footprint, restoring a snapshot) just
     * left it embedded. World edits like {@code setBlockState} never displace entities on their own,
     * so this must be called explicitly after any such bulk change touching ground the mob could be
     * standing on.
     */
    public static void unstickEntitiesInVolume(WorldServer world, BlockPos min, BlockPos max) {
        AxisAlignedBB box = new AxisAlignedBB(
                min.getX(), min.getY() - 1, min.getZ(),
                max.getX() + 1, max.getY() + 2, max.getZ() + 1);
        for (EntityLivingBase e : world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            int bx = MathHelper.floor(e.posX);
            int bz = MathHelper.floor(e.posZ);
            double safeY = ensureNotInSolid(world, bx, e.posY, bz);
            if (safeY != e.posY) {
                e.setPositionAndUpdate(e.posX, safeY, e.posZ);
            }
        }
    }
}
