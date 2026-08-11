package com.jubitus.travellers.utils.world.pathing.offscreen;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fluids.IFluidBlock;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import com.jubitus.travellers.utils.world.camp.CampsiteManager;
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
 *   ensuring they appear at the same level (building or surface) as the group.
 * - Solo workers that traveled far get a random spread.
 * - Travel is surface-only, so an entity that MOVED is surface-clamped (its frozen
 *   offscreen Y is stale). One that stayed put keeps its frozen Y so it reappears
 *   exactly where it was, including inside a roofed structure.
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

        // Travel/travel-AI is surface-only: no entity is ever legitimately underground. Offscreen the Y
        // is frozen (never simulated) while XZ can drift far, so once an entity has wandered its stale
        // referenceY no longer matches the terrain below it and the floor search can drop it into a cave
        // (possibly next to lava). Surface-clamp anything that actually MOVED. An entity that stayed put
        // keeps a valid frozen Y and must reappear exactly where it was — the one case that isn't the
        // open surface is a stationary entity inside a roofed structure (building / barracks bed), which
        // a blind surface-clamp would push onto the roof.
        boolean preferSurface = TravellersConfig.MATERIALIZATION.surfaceClampOnMaterialize
                && r.hasTraveledFar;

        // Mid-battle pawn: appear exactly where the battle sim placed it — no formation slot,
        // ring spread or building avoidance (the fight is where it is). Y is still resolved.
        if (r.battleId != null) {
            int bbx = (int) Math.floor(baseX);
            int bbz = (int) Math.floor(baseZ);
            double y = referenceY;
            if (world.getChunkProvider().chunkExists(bbx >> 4, bbz >> 4)) {
                y = findSafeFloorY(world, bbx, bbz, referenceY, preferSurface);
                y = ensureNotInSolid(world, bbx, y, bbz);
            }
            return new ResolvedPosition(baseX, y, baseZ);
        }

        // Group traveller/raider followers: spawn in a deterministic ring around the leader.
        // Must be checked before escortLeaderUUID (workers use that field, not this one).
        if (r.groupLeaderUUID != null) {
            double[] leaderPos = findLeaderPosition(world, r.groupLeaderUUID, allRecords);
            if (leaderPos != null) {
                int[] off = followerSpreadOffset(r.uuid);
                double cx = Math.floor(leaderPos[0] + off[0]) + 0.5;
                double cz = Math.floor(leaderPos[2] + off[1]) + 0.5;
                if (chunkLoaded(world, cx, cz)) {
                    // If the ring column is still close to where the follower itself was last standing
                    // (stationary leader: the ring hasn't moved since virtualize), that raw frozen Y is an
                    // accurate reference for that exact spot - including inside a building - so keep it
                    // and skip the clamp. Only when the ring has drifted away from the follower's own
                    // column (leader moved) is the follower's frozen Y for the WRONG column and must be
                    // replaced with the leader's Y and surface-clamped, same stale-Y trap as elsewhere.
                    double ddx = cx - baseX;
                    double ddz = cz - baseZ;
                    if (ddx * ddx + ddz * ddz > 4.0 * 4.0) {
                        referenceY = leaderPos[1];
                        preferSurface = TravellersConfig.MATERIALIZATION.surfaceClampOnMaterialize;
                    }
                    baseX = cx;
                    baseZ = cz;
                }
            }
        }

        if (r.escortLeaderUUID != null) {
            double[] leaderPos = findLeaderPosition(world, r.escortLeaderUUID, allRecords);
            if (leaderPos != null) {
                // Normally VirtualFormationSimulator has already steered the follower's simulated
                // position to (or close to) its live formation slot by materialization time, so using
                // the raw r.posX/r.posZ is fine. But a follower can also materialize while still
                // meaningfully displaced from its slot - most commonly right after a virtual battle
                // scattered it - in which case spawning at the raw position drops it far from the
                // group it's supposed to defend/be defended by. Prefer the goal (the slot itself)
                // whenever it's known and far enough from the raw position to matter.
                if (r.goal != null) {
                    double gx = r.goal.getX() + 0.5;
                    double gz = r.goal.getZ() + 0.5;
                    double ddx = gx - baseX;
                    double ddz = gz - baseZ;
                    if (ddx * ddx + ddz * ddz > 4.0 * 4.0 && chunkLoaded(world, gx, gz)) {
                        baseX = gx;
                        baseZ = gz;
                        // The follower's own frozen Y was recorded at its OLD (raw) column, which we
                        // just abandoned in favor of the goal/slot column - it no longer describes the
                        // ground under the new XZ, so it can't seed a down-first search there (the same
                        // stale-Y trap as a far-travelled solo entity). Use the leader's Y as the search
                        // seed instead, but resolve it the same surface-first way a moved entity gets.
                        referenceY = leaderPos[1];
                        preferSurface = TravellersConfig.MATERIALIZATION.surfaceClampOnMaterialize;
                    }
                    // Else: still at (or very near) its own raw column - the follower's own frozen Y
                    // (referenceY, untouched) is the accurate reference for that exact spot, and the
                    // leader's Y must NOT be substituted in - a leader a few blocks away can sit over
                    // different local terrain, and seeding the down-first search with a mismatched Y is
                    // exactly what let stationary followers drop into a cave under their own column.
                }
            } else if (!r.hasTraveledFar) {
                // No leader position resolvable and the worker never moved far on its own either
                // (e.g. leader itself never simulated a position yet): fall back to a deterministic
                // UUID ring so workers don't all stack on the exact same spot.
                int[] off = followerSpreadOffset(r.uuid);
                double cx = baseX + off[0];
                double cz = baseZ + off[1];
                if (chunkLoaded(world, cx, cz)) {
                    baseX = cx;
                    baseZ = cz;
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

        // Never materialize inside another camp's occupied shelter footprint (a slot the entity
        // doesn't itself own). Also pure metadata, no chunk loading required.
        if (TravellersConfig.MATERIALIZATION.avoidCampFootprints) {
            safe = CampsiteManager.get(world).resolveOutsideOccupiedSlots(
                    world.provider.getDimension(), safe, r.campSiteId);
        }

        bx = safe.getX();
        bz = safe.getZ();
        baseX = bx + 0.5;
        baseZ = bz + 0.5;

        double spawnY = referenceY;
        if (world.getChunkProvider().chunkExists(bx >> 4, bz >> 4)) {
            spawnY = findSafeFloorY(world, bx, bz, referenceY, preferSurface);
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

    public static double findSafeFloorY(WorldServer world, int bx, int bz, double referenceY) {
        return findSafeFloorY(world, bx, bz, referenceY, false);
    }

    /**
     * Finds a safe Y (entity feet) at (bx, bz).
     * <p>
     * When {@code preferSurface} is false: searches outward from referenceY, going down first at each
     * offset. Used for a stationary entity whose frozen Y is still valid, so it reappears exactly where
     * it was — including inside a roofed structure (building / barracks bed) it must not be lifted out of.
     * <p>
     * When {@code preferSurface} is true: resolves against the actual surface (top-down, skipping
     * foliage) and ignores referenceY entirely — this is what stops a far-wandered entity whose frozen
     * offscreen Y no longer matches the terrain from dropping into a cave below (possibly next to lava).
     */
    public static double findSafeFloorY(WorldServer world, int bx, int bz, double referenceY, boolean preferSurface) {
        int refY = (int) Math.floor(referenceY);
        int maxY = world.getActualHeight() - 3;

        if (preferSurface) {
            double surf = findSurfaceFloorY(world, bx, bz, maxY);
            if (!Double.isNaN(surf)) return surf;
        }

        for (int offset = 0; offset <= 32; offset++) {
            int yDown = refY - offset;
            if (yDown >= 1 && isSafeFloor(world, bx, yDown, bz)) return yDown + 1.0;
            if (offset > 0) {
                int yUp = refY + offset;
                if (yUp <= maxY && isSafeFloor(world, bx, yUp, bz)) return yUp + 1.0;
            }
        }

        // Last resort: top-down scan skipping foliage
        double surf = findSurfaceFloorY(world, bx, bz, maxY);
        if (!Double.isNaN(surf)) return surf;

        return referenceY;
    }

    /** Top-down scan for the highest safe floor at (bx, bz), skipping tree foliage. NaN if none. */
    private static double findSurfaceFloorY(WorldServer world, int bx, int bz, int maxY) {
        for (int y = maxY; y > 1; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            IBlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof BlockLeaves) continue;
            if (state.getBlock() instanceof BlockLog) continue;
            if (state.getBlock().isAir(state, world, pos)) continue;
            if (isSafeFloor(world, bx, y, bz)) return y + 1.0;
        }
        return Double.NaN;
    }

    private static boolean isSafeFloor(WorldServer world, int bx, int y, int bz) {
        BlockPos floor = new BlockPos(bx, y, bz);
        IBlockState st = world.getBlockState(floor);
        if (st.getBlock().isAir(st, world, floor)) return false;
        if (TravellersConfig.MATERIALIZATION.avoidTreeCanopy && !isStableFloorMaterial(st.getMaterial())) {
            return false;
        }
        // A liquid block reads as "not air" and would otherwise pass as a floor with air above it -
        // an entity resolved to stand there has nothing solid underneath and drops straight in
        // (fatal on lava). Mirrors CampTerrainValidator.isCellNatural's hazard gate.
        if (TravellersConfig.MATERIALIZATION.avoidHazardLiquids && isHazardBlock(st.getBlock(), st)) {
            return false;
        }
        IBlockState s1 = world.getBlockState(floor.up());
        IBlockState s2 = world.getBlockState(floor.up(2));
        return s1.getBlock().isAir(s1, world, floor.up())
                && s2.getBlock().isAir(s2, world, floor.up(2));
    }

    /** Liquids (vanilla or modded) and any block registry-name-listed as a camp terrain hazard. */
    private static boolean isHazardBlock(Block b, IBlockState st) {
        if (st.getMaterial().isLiquid() || b instanceof IFluidBlock) return true;
        ResourceLocation blockId = b.getRegistryName();
        return blockId != null && TravellersDerivedConfig.CAMP_TERRAIN_HAZARD_BLACKLIST
                .contains(blockId.toString().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Rejects tree canopy and other foliage as a landing floor (a leaf block is solid enough to
     * pass the plain air check above, so without this a materializing entity could land standing
     * on top of a tree instead of the ground beneath it).
     */
    private static boolean isStableFloorMaterial(Material m) {
        return m != Material.LEAVES
                && m != Material.WOOD
                && m != Material.PLANTS
                && m != Material.VINE
                && m != Material.CACTUS
                && m != Material.GOURD;
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
