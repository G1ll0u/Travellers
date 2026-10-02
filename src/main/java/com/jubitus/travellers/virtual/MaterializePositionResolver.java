package com.jubitus.travellers.virtual;

import net.minecraft.block.Block;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.Footing;
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
import com.jubitus.travellers.camp.CampTerrainValidator;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.village.MillFootprints;

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

    // How far an entity must have drifted from its virtualization point (blocks) before its frozen Y
    // is considered stale. Formation adjustments and battle jostling stay under this.
    private static final double DRIFT_THRESHOLD = 16.0;

    // Vertical window searched around a still-valid frozen Y. Kept tight on purpose: a wide window
    // lets the down-first search walk out of a slightly-off surface Y straight into a cave.
    private static final int FROZEN_Y_SEARCH_RADIUS = 4;

    // How far above its own frozen floor a resolved spawn may end up before it is treated as having
    // been pushed onto a roof / upper floor rather than nudged out of a single block.
    private static final double SAME_LEVEL_LIFT_TOLERANCE = 3.0;

    // Vertical slack when looking for free space on "the same floor": enough for a step or a stair,
    // not enough to reach the storey above.
    private static final int SAME_LEVEL_Y_SLACK = 2;

    private MaterializePositionResolver() {}

    /**
     * True when the record has moved far enough from where it was virtualized that its frozen Y no
     * longer describes the terrain under it (offscreen simulation never advances Y).
     * <p>
     * Measured live against the current position rather than latched: a follower scattered by a
     * virtual battle that has since walked back to its formation slot must restore exactly where it
     * stood, not be surface-clamped out of the building its group was waiting in.
     */
    public static boolean hasDrifted(VirtualTravellerRecord r) {
        double dx = r.posX - r.virtualizedPosX;
        double dz = r.posZ - r.virtualizedPosZ;
        return dx * dx + dz * dz >= DRIFT_THRESHOLD * DRIFT_THRESHOLD;
    }

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
                && hasDrifted(r);

        // Mid-battle pawn: appear exactly where the battle sim placed it — no formation slot,
        // ring spread or building avoidance (the fight is where it is). Y is still resolved.
        if (r.battleId != null) {
            BlockPos out = MillFootprints.pushOutside(world,
                    new BlockPos(MathHelper.floor(baseX), MathHelper.floor(referenceY), MathHelper.floor(baseZ)));
            int bbx = out.getX();
            int bbz = out.getZ();
            double y = referenceY;
            if (world.getChunkProvider().chunkExists(bbx >> 4, bbz >> 4)) {
                y = findSafeFloorY(world, bbx, bbz, referenceY, preferSurface);
                y = ensureNotInSolid(world, bbx, y, bbz);
                y = sinkToRealGround(world, bbx, y, bbz, TravellersConfig.MATERIALIZATION.canopySinkMaxDrop);
            }
            return new ResolvedPosition(bbx + 0.5D, y, bbz + 0.5D);
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
            } else if (!hasDrifted(r)) {
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

        } else if (hasDrifted(r)) {
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
        BlockPos safe = MillFootprints.pushOutside(world, new BlockPos(bx, (int) referenceY, bz));

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
            // Surface-resolved entity: the ground under its column may well be a rooftop (Millénaire
            // building the index didn't resolve, player build, another mod's structure), so look
            // around for real ground rather than trusting the column we happen to have landed on.
            Ground g = preferSurface
                    ? findNaturalGround(world, bx, bz, TravellersConfig.MATERIALIZATION.naturalGroundSearchRadius)
                    : null;
            if (g != null) {
                bx = g.x;
                bz = g.z;
                baseX = bx + 0.5;
                baseZ = bz + 0.5;
                spawnY = g.y;
            } else {
                spawnY = findSafeFloorY(world, bx, bz, referenceY, preferSurface);
            }
            spawnY = ensureNotInSolid(world, bx, spawnY, bz);

            // An entity that kept its own valid frozen Y must never come back higher than the floor it
            // was standing on: if its exact column is now blocked (it drifted into a wall, or a player
            // built there), ensureNotInSolid walks up until it clears the obstacle - which inside a
            // building means it pops out on the roof. Look sideways on its own floor instead.
            if (!preferSurface && spawnY > referenceY + SAME_LEVEL_LIFT_TOLERANCE) {
                BlockPos onLevel = findSpotAtLevel(world, bx, bz, referenceY,
                        TravellersConfig.MATERIALIZATION.sameLevelSearchRadius);
                if (onLevel != null) {
                    baseX = onLevel.getX() + 0.5;
                    baseZ = onLevel.getZ() + 0.5;
                    spawnY = onLevel.getY();
                }
            }
            spawnY = sinkToRealGround(world, bx, spawnY, bz, TravellersConfig.MATERIALIZATION.canopySinkMaxDrop);

            // sinkToRealGround can't help the one column the tree's own trunk stands in (its head
            // room is the trunk) - step sideways to real ground there.
            BlockPos escaped = escapeCanopyColumn(world, bx, bz, spawnY,
                    TravellersConfig.MATERIALIZATION.naturalGroundSearchRadius);
            if (escaped != null) {
                baseX = escaped.getX() + 0.5;
                baseZ = escaped.getZ() + 0.5;
                spawnY = escaped.getY();
            }

            // The ground searches above may step sideways, back into a building.
            if (MillFootprints.isForbiddenLanding(world, baseX, spawnY, baseZ)) {
                BlockPos out = MillFootprints.pushOutside(world,
                        new BlockPos(MathHelper.floor(baseX), MathHelper.floor(spawnY), MathHelper.floor(baseZ)));
                if (chunkLoaded(world, out.getX(), out.getZ())) {
                    baseX = out.getX() + 0.5;
                    baseZ = out.getZ() + 0.5;
                    spawnY = surfaceY(world, out.getX(), out.getZ(), spawnY);
                }
            }
        }

        return new ResolvedPosition(baseX, spawnY, baseZ);
    }

    private static double surfaceY(WorldServer world, int x, int z, double referenceY) {
        double y = findSafeFloorY(world, x, z, referenceY, true);
        y = ensureNotInSolid(world, x, y, z);
        return sinkToRealGround(world, x, y, z, TravellersConfig.MATERIALIZATION.canopySinkMaxDrop);
    }

    /**
     * Last gate before the spawn: guarantees the resolved point is somewhere the entity can actually
     * exist, falling back to the record's own raw position when it isn't.
     * <p>
     * {@link #resolve} and its callers move the spawn point for good reasons - a formation slot, a
     * Millenaire building footprint push-out (which walks out of the way of a whole Town Hall), a camp
     * shelter, a Stable bed - and several of those displacements cannot check where they land without
     * duplicating this class's knowledge. But the eligibility check that let this record materialize
     * at all was made on {@code r.posX/r.posZ}, so a displaced point carries no such guarantee:
     * {@code World.spawnEntity} silently refuses an unloaded chunk, and a point inside vanilla's
     * 32-block no-tick band spawns a statue that is virtualized straight back out.
     * <p>
     * The raw record position is the one column already proven good this tick, so that is the
     * fallback - re-resolving its Y the same way {@link #resolve} would, since the frozen Y of a
     * record that has travelled is stale.
     */
    public static ResolvedPosition ensureSpawnable(WorldServer world,
                                                   VirtualTravellerRecord r,
                                                   ResolvedPosition pos) {
        if (!VirtualTravellerManager.isFrozenByVanillaAt(world, pos.x, pos.z)) return pos;

        int bx = MathHelper.floor(r.posX);
        int bz = MathHelper.floor(r.posZ);
        // Still never inside a building, as long as stepping out keeps it on ticking ground.
        BlockPos out = MillFootprints.pushOutside(world, new BlockPos(bx, MathHelper.floor(r.posY), bz));
        if ((out.getX() != bx || out.getZ() != bz)
                && !VirtualTravellerManager.isFrozenByVanillaAt(world, out.getX() + 0.5D, out.getZ() + 0.5D)) {
            bx = out.getX();
            bz = out.getZ();
        }
        boolean preferSurface = TravellersConfig.MATERIALIZATION.surfaceClampOnMaterialize
                && hasDrifted(r);
        double y = r.posY;
        double px = bx + 0.5, pz = bz + 0.5;
        if (chunkLoaded(world, bx, bz)) {
            y = findSafeFloorY(world, bx, bz, r.posY, preferSurface);
            y = ensureNotInSolid(world, bx, y, bz);
            y = sinkToRealGround(world, bx, y, bz, TravellersConfig.MATERIALIZATION.canopySinkMaxDrop);
            BlockPos escaped = escapeCanopyColumn(world, bx, bz, y,
                    TravellersConfig.MATERIALIZATION.naturalGroundSearchRadius);
            if (escaped != null) {
                px = escaped.getX() + 0.5;
                pz = escaped.getZ() + 0.5;
                y = escaped.getY();
            }
        }
        return new ResolvedPosition(px, y, pz);
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
        if (preferSurface) {
            double surf = columnGroundY(world, bx, bz);
            if (!Double.isNaN(surf)) return surf;
        }

        int near = ColumnGround.nearestStandable(world, bx, bz, (int) Math.floor(referenceY), FROZEN_Y_SEARCH_RADIUS);
        if (near != ColumnGround.NONE) return near;

        double surf = columnGroundY(world, bx, bz);
        if (!Double.isNaN(surf)) return surf;

        return referenceY;
    }

    /**
     * Spirals outward from (bx, bz) for a standable spot on roughly the same floor as {@code refY}
     * (feet Y returned). Unlike {@link #columnGroundY} it does not care what is above: an indoor
     * floor is a valid answer, a ceiling is what we are trying to stay under. Never loads a chunk.
     */
    @Nullable
    private static BlockPos findSpotAtLevel(WorldServer world, int bx, int bz, double refY, int maxRadius) {
        int refBlockY = (int) Math.floor(refY);
        for (int r = 0; r <= Math.max(0, maxRadius); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = bx + dx;
                    int z = bz + dz;
                    if (!chunkLoaded(world, x, z)) continue;
                    for (int dy = 0; dy <= SAME_LEVEL_Y_SLACK; dy++) {
                        int down = refBlockY - 1 - dy;
                        if (down >= 1 && isSafeFloor(world, x, down, z)) return new BlockPos(x, down + 1, z);
                        if (dy > 0) {
                            int up = refBlockY - 1 + dy;
                            if (isSafeFloor(world, x, up, z)) return new BlockPos(x, up + 1, z);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** A resolved standing spot: the ground column that was picked, and whether it is natural terrain. */
    public static final class Ground {
        public final int x, z;
        public final double y;
        public final boolean natural;

        Ground(int x, double y, int z, boolean natural) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.natural = natural;
        }
    }

    /**
     * Looks for a column of natural ground at or near (bx, bz), spiralling outward up to
     * {@code maxRadius}. This is what keeps an entity off rooftops: the top of a building column IS
     * its roof, so resolving "the surface" there is always wrong, whether or not the Millénaire
     * building index knew about it. Artificial columns are only used as a last resort (a roof still
     * beats a cave next to lava). Never loads a chunk.
     */
    // How far above the nearest natural ground a man-made surface may still be stood on. Below it the
    // surface reads as a road / bridge / village square the entity belongs on; above it, as a roof.
    private static final double ARTIFICIAL_GROUND_TOLERANCE = 2.0;

    @Nullable
    public static Ground findNaturalGround(WorldServer world, int bx, int bz, int maxRadius) {
        Ground center = resolveColumn(world, bx, bz);
        if (center != null && center.natural) return center;

        Ground natural = null;
        for (int r = 1; r <= Math.max(0, maxRadius) && natural == null; r++) {
            for (int dx = -r; dx <= r && natural == null; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    Ground g = resolveColumn(world, bx + dx, bz + dz);
                    if (g != null && g.natural) {
                        natural = g;
                        break;
                    }
                }
            }
        }

        if (natural == null) return center;
        // Man-made but level with the terrain around it (a road, a bridge, a village square, the
        // ground floor of a house): standing there is coherent, only a roof needs moving off.
        if (center != null && center.y <= natural.y + ARTIFICIAL_GROUND_TOLERANCE) return center;
        return natural;
    }

    /** Resolves one column into a {@link Ground}, or null when it has no usable standing surface. */
    @Nullable
    private static Ground resolveColumn(WorldServer world, int x, int z) {
        if (!chunkLoaded(world, x, z)) return null;
        double y = columnGroundY(world, x, z);
        if (Double.isNaN(y)) return null;
        boolean natural = BlockRules.isNaturalGround(world.getBlockState(new BlockPos(x, (int) y - 1, z)));
        return new Ground(x, y, z, natural);
    }

    /** Y just above the column's open water surface (the top layer is water), else NaN. */
    public static double waterSurfaceY(WorldServer world, int bx, int bz) {
        int y = ColumnGround.topLayer(world, bx, bz, true);
        if (y == ColumnGround.NONE || !BlockRules.isVanillaWater(world.getBlockState(new BlockPos(bx, y, bz)))) {
            return Double.NaN;
        }
        return y + 1.0;
    }

    /** Feet Y of the column's highest standing layer (a roof counts), NaN if none. See {@link ColumnGround}. */
    public static double columnGroundY(WorldServer world, int bx, int bz) {
        int y = ColumnGround.topLayer(world, bx, bz, false);
        return y == ColumnGround.NONE ? Double.NaN : y;
    }

    /**
     * True if an entity can occupy this block: air, or anything without a collision box (tall grass,
     * flowers, torches, crops...). Tree canopy counts too, so the descent passes through it to the
     * ground below instead of treating a leaf block as a floor.
     */
    private static boolean isStandSpace(WorldServer world, BlockPos p) {
        IBlockState st = world.getBlockState(p);
        Block b = st.getBlock();
        if (b.isAir(st, world, p)) return true;
        if (st.getMaterial().isLiquid() || b instanceof IFluidBlock) return false;
        // Match by material as well as class so modded trees (Dynamic Trees etc.) count as canopy
        // the descent passes through, not as a floor.
        if (BlockRules.isLeaves(null, null, st)) return true;
        return st.getCollisionBoundingBox(world, p) == null;
    }

    // The block at y is a floor a unit can stand on (Footing), feet at y + 1.
    private static boolean isSafeFloor(WorldServer world, int bx, int y, int bz) {
        return Footing.canStandAt(world, new BlockPos(bx, y + 1, bz));
    }

    /**
     * Pushes spawnY up until the entity has two consecutive air blocks to stand in.
     * Guards against spread landing a worker inside a wall.
     * <p>
     * A gap whose floor is foliage or a tree part is <em>not</em> accepted as a stopping point (a
     * leaf clump reads as two "stand spaces" with a leaf underfoot - that is how a materialized
     * entity ended up perched in a Dynamic Trees canopy). It keeps climbing for a real floor;
     * {@link #sinkToRealGround} afterwards pulls a genuine air gap back down to the ground below.
     */
    public static double ensureNotInSolid(WorldServer world, int bx, double spawnY, int bz) {
        int y = (int) Math.floor(spawnY);
        int maxY = world.getActualHeight() - 2;
        int firstGap = Integer.MIN_VALUE;
        for (int i = 0; y + i <= maxY; i++) {
            if (isStandSpace(world, new BlockPos(bx, y + i, bz))
                    && isStandSpace(world, new BlockPos(bx, y + i + 1, bz))) {
                if (firstGap == Integer.MIN_VALUE) firstGap = y + i;
                if (y + i - 1 >= 1
                        && BlockRules.isTreePart(world.getBlockState(new BlockPos(bx, y + i - 1, bz)))) {
                    continue;
                }
                return y + i;
            }
        }
        // Nothing but foliage-floored gaps up the whole column: hand back the lowest gap found (the
        // old behaviour) and let sinkToRealGround take it down to real ground.
        return firstGap != Integer.MIN_VALUE ? firstGap : spawnY;
    }

    /**
     * Last-resort canopy guard shared by every traveller type (pedestrians included). If the entity
     * would stand on / hover in foliage or a tree part - a leaf, a branch, a Dynamic Trees trunk -
     * and solid ground sits within {@code maxDrop} blocks below, drop the feet Y onto it.
     * <p>
     * The downward search fires whether the block underfoot is foliage <em>or</em> an air gap
     * (a body left floating between leaf clumps, or above a sparse canopy), but only when a tree part
     * is actually present in the column below - so a genuine cliff edge or a 1-block pillar over open
     * ground (air underfoot, no tree) is left untouched for gravity. Bounded: if no real ground is
     * found in range the original Y is kept, so a unit is never left un-materialized over awkward
     * terrain. No-op when the entity is already on real ground.
     */
    public static double sinkToRealGround(WorldServer world, int bx, double spawnY, int bz, int maxDrop) {
        int feetY = (int) Math.floor(spawnY);
        int floorY = feetY - 1;
        if (floorY < 1) return spawnY;
        if (isSafeFloor(world, bx, floorY, bz)) return spawnY; // already on genuine ground

        boolean sawTree = BlockRules.isCanopy(world.getBlockState(new BlockPos(bx, floorY, bz)));
        for (int d = 1; d <= Math.max(0, maxDrop); d++) {
            int y = floorY - d;
            if (y < 1) break;
            IBlockState st = world.getBlockState(new BlockPos(bx, y, bz));
            if (isSafeFloor(world, bx, y, bz)) return sawTree ? y + 1.0 : spawnY;
            if (BlockRules.isCanopy(st)) sawTree = true;
        }
        return spawnY;
    }

    /**
     * The one canopy case {@link #sinkToRealGround} cannot fix: the column the tree's own trunk
     * stands in has no standable spot at all (the trunk occupies the head room above the ground), so
     * a column-local search only ever returns NaN / foliage there. Spirals outward for a genuine
     * ground column and returns its feet {@link BlockPos}. Returns {@code null} when the spot is
     * already on real ground, or nothing better turned up within {@code radius}. Never loads a chunk.
     */
    @Nullable
    private static BlockPos escapeCanopyColumn(WorldServer world, int bx, int bz, double feetY, int radius) {
        int fy = (int) Math.floor(feetY) - 1;
        if (fy >= 1
                && !BlockRules.isTreePart(world.getBlockState(new BlockPos(bx, fy, bz)))
                && isSafeFloor(world, bx, fy, bz)) {
            return null; // already on genuine ground
        }
        Ground g = findNaturalGround(world, bx, bz, radius);
        if (g == null) return null;
        int gfy = (int) Math.floor(g.y) - 1;
        if (gfy < 1 || BlockRules.isTreePart(world.getBlockState(new BlockPos(g.x, gfy, g.z)))) return null;
        return new BlockPos(g.x, (int) Math.floor(g.y), g.z);
    }

    /**
     * Finds a safe-floor column with standing clearance near {@code (nearX, nearY, nearZ)}, rejecting
     * any column inside {@code forbidden} (typically a camp shelter's footprint - a horse shouldn't
     * materialize standing inside a tent). Mirrors {@link #findSpotAtLevel}'s own ring search exactly,
     * with the added forbidden-box filter. Returns {@code null} if nothing was found within {@code
     * maxRadius} - callers should fall back to the naive "same spot as whatever it's near" placement
     * rather than block materialization entirely.
     */
    @Nullable
    public static BlockPos findClearMountSpot(WorldServer world, double nearX, double nearY, double nearZ,
                                               @Nullable AxisAlignedBB forbidden, int maxRadius) {
        int cx = MathHelper.floor(nearX);
        int cz = MathHelper.floor(nearZ);
        int refY = MathHelper.floor(nearY);

        for (int r = 0; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    if (forbidden != null && x + 1 > forbidden.minX && x < forbidden.maxX
                            && z + 1 > forbidden.minZ && z < forbidden.maxZ) {
                        continue;
                    }
                    if (!chunkLoaded(world, x, z)) continue;
                    for (int dy = 0; dy <= SAME_LEVEL_Y_SLACK; dy++) {
                        int down = refY - 1 - dy;
                        if (down >= 1 && isSafeFloor(world, x, down, z)) return new BlockPos(x, down + 1, z);
                        if (dy > 0) {
                            int up = refY - 1 + dy;
                            if (isSafeFloor(world, x, up, z)) return new BlockPos(x, up + 1, z);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Like {@link #findClearMountSpot}, but tests the actual mounted landing box - horse body and
     * rider silhouette, whichever is wider, both at their real dimensions - rather than just a
     * rider-sized floor column. A column that reads as a valid stand spot for the rider's own narrow
     * hitbox is routinely NOT wide enough for the horse standing on it (a slope, a one-block niche in
     * a cliff face) - the gap behind a horse reappearing embedded in a wall after materialize. Mirrors
     * {@code TravellerMountSupport.mountedLandingBox}'s box math, generalized to run before either
     * entity is actually mounted: both are fully constructed with their real width/height by the time
     * this runs (age-adjusted, NBT-restored), so no live mount relationship is needed to ask the
     * question.
     * <p>
     * With {@code bestEffort} false, returns {@code null} if no fully-clear spot exists within
     * {@code maxRadius}. With it true, never returns {@code null} while any column standing on a real
     * floor was reachable: it keeps the least-embedded candidate seen (fewest colliding block cells), lifts its
     * box clear of whatever it overlaps, and returns that - so a horse on a rough mountainside
     * appears standing on the least-bad ledge rather than suffocating at the rider's exact column.
     */
    @Nullable
    public static BlockPos findClearMountFootprint(WorldServer world, Entity horse, Entity rider,
                                                    double nearX, double nearY, double nearZ,
                                                    @Nullable AxisAlignedBB forbidden, int maxRadius,
                                                    boolean bestEffort) {
        return findFootprint(world, horse, rider, nearX, nearY, nearZ, forbidden, maxRadius, bestEffort);
    }

    /**
     * Like {@link #findClearMountFootprint}, but tests only the <em>horse's own body</em> - no rider
     * silhouette stacked on top. This is the right question when nobody is going to climb on where
     * the horse appears: a rider that is leading or hitching its horse (see
     * {@code TravellerMountSupport.resolveMountSpawnPos}). Asking for the full ~2.6-block mounted
     * silhouette there needlessly refuses spots a horse fits fine and pushes the search outward -
     * closer to the lifted best-effort fallback, which is what put a led horse up in the canopy.
     */
    @Nullable
    public static BlockPos findClearHorseFootprint(WorldServer world, Entity horse,
                                                    double nearX, double nearY, double nearZ,
                                                    @Nullable AxisAlignedBB forbidden, int maxRadius,
                                                    boolean bestEffort) {
        return findFootprint(world, horse, null, nearX, nearY, nearZ, forbidden, maxRadius, bestEffort);
    }

    /**
     * Shared body of {@link #findClearMountFootprint} / {@link #findClearHorseFootprint}.
     * {@code rider} null = test the horse body alone; non-null = the full horse+rider silhouette.
     * <p>
     * With {@code bestEffort} false, returns {@code null} if no fully-clear spot exists within
     * {@code maxRadius}. With it true, never returns {@code null} while any column standing on a real
     * floor was reachable: it keeps the least-embedded candidate seen, lifts its box clear (capped tight, so a
     * refused spot never ends up level with a tree canopy), and returns that.
     */
    @Nullable
    private static BlockPos findFootprint(WorldServer world, Entity horse, @Nullable Entity rider,
                                          double nearX, double nearY, double nearZ,
                                          @Nullable AxisAlignedBB forbidden, int maxRadius,
                                          boolean bestEffort) {
        int cx = MathHelper.floor(nearX);
        int cz = MathHelper.floor(nearZ);
        double half = Math.max(horse.width, rider != null ? rider.width : 0.0D) / 2.0D;

        int bestX = 0, bestZ = 0;
        double bestGroundY = 0.0D;
        int bestHits = Integer.MAX_VALUE;

        for (int r = 0; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    if (forbidden != null && x + 1 > forbidden.minX && x < forbidden.maxX
                            && z + 1 > forbidden.minZ && z < forbidden.maxZ) {
                        continue;
                    }
                    if (!chunkLoaded(world, x, z)) continue;

                    // Mounts are outdoors: resolve against the real surface (skips foliage) rather
                    // than a possibly-canopy-level frozen seed, then drop through any foliage left.
                    double groundY = findSafeFloorY(world, x, z, nearY, true);
                    groundY = ensureNotInSolid(world, x, groundY, z);
                    groundY = sinkToRealGround(world, x, groundY, z,
                            TravellersConfig.MATERIALIZATION.canopySinkMaxDrop);
                    if (MillFootprints.isForbiddenLanding(world, x + 0.5D, groundY, z + 0.5D)) continue;

                    // The hooves must rest on a real floor: never a tree part, vegetation, liquid or
                    // the air between two branches (a trunk column falls back to the seed height,
                    // which may be canopy level). Such columns are skipped at any hit count.
                    int fY = MathHelper.floor(groundY) - 1;
                    if (fY < 1 || !isSafeFloor(world, x, fY, z)) continue;

                    AxisAlignedBB box = mountBox(x, z, groundY, half, horse, rider);
                    int hits = world.getCollisionBoxes(null, box).size();
                    if (hits == 0) {
                        return new BlockPos(x, MathHelper.floor(groundY), z);
                    }
                    if (bestEffort && hits < bestHits) {
                        bestHits = hits;
                        bestX = x;
                        bestZ = z;
                        bestGroundY = groundY;
                    }
                }
            }
        }
        if (!bestEffort || bestHits == Integer.MAX_VALUE) return null;

        // Lift the least-bad box straight up until it clears - a tight cap: a genuinely clear column
        // anywhere in range already won above (returned on hits == 0), so this only runs when the
        // whole neighbourhood is tight, and a big lift there just floats the horse into the treetops.
        // A one- or two-block overlap the physics resolves on the next tick is the better outcome.
        double y = bestGroundY;
        for (int i = 0; i < 2; i++) {
            if (world.getCollisionBoxes(null, mountBox(bestX, bestZ, y, half, horse, rider)).isEmpty()) break;
            y += 1.0D;
        }
        return new BlockPos(bestX, MathHelper.floor(y), bestZ);
    }

    /** The landing silhouette resting on the surface at {@code (x + .5, groundY, z + .5)}: the horse
     * body alone when {@code rider} is null, else horse body plus the rider column stacked on the
     * saddle (whichever is taller/wider). */
    private static AxisAlignedBB mountBox(int x, int z, double groundY, double half,
                                          Entity horse, @Nullable Entity rider) {
        double centerX = x + 0.5D, centerZ = z + 0.5D;
        double top = groundY + horse.height;
        if (rider != null) {
            double riderFeet = groundY + horse.getMountedYOffset() + rider.getYOffset();
            top = Math.max(top, riderFeet + rider.height);
        }
        return new AxisAlignedBB(centerX - half, groundY, centerZ - half,
                centerX + half, top, centerZ + half);
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
