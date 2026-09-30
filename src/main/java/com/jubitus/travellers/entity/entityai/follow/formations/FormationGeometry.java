package com.jubitus.travellers.entity.entityai.follow.formations;

import com.jubitus.travellers.entity.entityai.follow.FollowRuntimeConfig;
import net.minecraft.util.math.MathHelper;

import java.util.UUID;

/**
 * Pure, leader-agnostic formation slot geometry: given a heading direction, an ordinal, a group
 * size and the player's WorkerFormationSettings, computes where that ordinal's slot sits relative
 * to the leader. No world access, no entity dependency - shared by the loaded-entity path
 * (FollowFormationCoordinator, which still owns heading smoothing, member registration and
 * ordinal assignment) and the virtual/offscreen path (VirtualFormationSimulator), so the two can
 * never visually disagree about formation shape.
 * <p>
 * Cave/tunnel geometry stays in FollowFormationCoordinator - it depends on world block scans
 * (SpaceProfile) that are meaningless (and forbidden - offscreen must not load chunks) for virtual
 * followers, which always resolve to surface geometry.
 */
public final class FormationGeometry {

    /**
     * Lateral/rank spacing multiplier applied when the formation is mounted. A vanilla horse hitbox
     * is ~1.4 blocks wide against a pedestrian lane spacing of ~1.8, so at scale 1.0 mounted slots
     * sit barely a body apart and the horses interpenetrate. ~1.75x brings the gap to roughly one
     * clear horse-width. Only the inter-slot terms scale - the follow distance (leash length) does
     * not.
     */
    public static final double MOUNTED_SPACING_SCALE = 1.75D;

    /** Spread radius bounds for {@link #computeRingOffset}, matching the values the legacy
     *  per-follower spread offset used (see {@code MaterializePositionResolver.followerSpreadOffset},
     *  which this method replaces for any caller migrated onto the unified formation system). */
    private static final double RING_SPREAD_MIN = 1.5D;
    private static final double RING_SPREAD_MAX = 4.5D;

    private FormationGeometry() {
    }

    public static final class SlotOffset {
        public final double dx;
        public final double dz;
        public final double preferredRadius;
        public final double arriveRadius;
        public final double frontPenaltyStrength;

        public SlotOffset(double dx, double dz, double preferredRadius, double arriveRadius,
                           double frontPenaltyStrength) {
            this.dx = dx;
            this.dz = dz;
            this.preferredRadius = preferredRadius;
            this.arriveRadius = arriveRadius;
            this.frontPenaltyStrength = frontPenaltyStrength;
        }
    }

    /**
     * Full slot offset for an ordinal, including escort-slot handling. Always resolves surface
     * geometry (mode/underground concerns are the loaded-path's problem, see class doc).
     * <p>
     * There is deliberately no bodyScale-less convenience overload: every caller must decide its
     * own mount-aware scale (see {@code FollowFormationCoordinator.formationBodyScale}) rather than
     * silently defaulting to {@code 1.0}, which previously let a mounted virtual/offscreen formation
     * compute pedestrian-tight spacing.
     *
     * @param bodyScale extra lateral/rank spacing multiplier for large-bodied followers - pass
     *                  {@link #MOUNTED_SPACING_SCALE} for a mounted formation, {@code 1.0} otherwise.
     */
    public static SlotOffset computeSlotOffset(int ordinal,
                                                int groupSize,
                                                double forwardX,
                                                double forwardZ,
                                                double baseFollowDistance,
                                                UUID leaderId,
                                                WorkerFormationSettings settings,
                                                boolean workerLeader,
                                                double bodyScale) {
        int numEscorts = settings.numEscorts;
        if (numEscorts > 0 && ordinal < numEscorts) {
            int effectiveEscorts = Math.min(numEscorts, groupSize);
            return computeEscortOffset(ordinal, effectiveEscorts, forwardX, forwardZ, bodyScale);
        }

        int adjOrdinal = ordinal - numEscorts;
        int adjGroupSize = Math.max(1, groupSize - numEscorts);

        return computeSurfaceOffset(adjOrdinal, adjGroupSize, forwardX, forwardZ, baseFollowDistance, leaderId,
                settings, workerLeader, bodyScale);
    }

    /**
     * Fixed per-follower spread offset: a personal position that depends only on the follower's own
     * UUID, never on its ordinal/rank, group size or the leader's heading. Unlike every other style,
     * a RING follower's slot does not rotate with the leader's facing - only the leader's own
     * movement carries it along. Used for traveller/raider groups, which have no owning player to
     * assign ordinals/ranks to in the first place. Callers must bypass the ordinal-based
     * {@link #computeSlotOffset} pool-and-match pipeline entirely for RING - see
     * {@code FollowFormationCoordinator}/{@code VirtualFormationSimulator} call sites.
     */
    public static SlotOffset computeRingOffset(UUID followerId) {
        long h = followerId.getLeastSignificantBits() ^ followerId.getMostSignificantBits();
        double angle = (h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL * 2.0 * Math.PI;
        double radius = RING_SPREAD_MIN
                + ((h >>> 32 & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL) * (RING_SPREAD_MAX - RING_SPREAD_MIN);
        double dx = Math.cos(angle) * radius;
        double dz = Math.sin(angle) * radius;
        return new SlotOffset(dx, dz, radius, FollowRuntimeConfig.get().surface.arriveRadius, 0.0D);
    }

    public static SlotOffset computeEscortOffset(int escortIndex, int numEscorts, double forwardX, double forwardZ,
                                                 double bodyScale) {
        double rx = -forwardZ;
        double rz = forwardX;

        double laneGap = 1.4D * bodyScale;
        double side;
        double back = 2.5D;
        if (numEscorts == 1) {
            side = 0.0D;
        } else if (numEscorts == 2) {
            side = escortIndex == 0 ? -laneGap : laneGap;
        } else {
            int perRow = Math.min(numEscorts, 4);
            int row = escortIndex / perRow;
            int col = escortIndex % perRow;
            int rowSize = row == 0 ? Math.min(numEscorts, perRow) : (numEscorts - perRow);
            side = rowSize <= 1 ? 0.0D : (col - (rowSize - 1) * 0.5D) * laneGap;
            back = 2.5D + row * 2.0D * bodyScale;
        }

        return new SlotOffset(
                -forwardX * back + rx * side,
                -forwardZ * back + rz * side,
                back,
                1.1D,
                0.0D
        );
    }

    public static SlotOffset computeSurfaceOffset(int ordinal,
                                                   int groupSize,
                                                   double forwardX,
                                                   double forwardZ,
                                                   double baseFollowDistance,
                                                   UUID leaderId,
                                                   WorkerFormationSettings settings,
                                                   boolean workerLeader,
                                                   double bodyScale) {
        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        double rx = -forwardZ;
        double rz = forwardX;

        WorkerFormationStyle style = resolveSurfaceStyle(settings.style, groupSize, workerLeader);

        double spacingMul = settings.spacing * bodyScale;

        // No group-size back-off: the formation distance is exactly what the GUI says,
        // regardless of how many followers there are.
        double baseDist = Math.max(baseFollowDistance, settings.followDistance);

        double ringSpacing = Math.max(1.00D, cfg.surface.ringSpacing * spacingMul);
        double rowSpacing = Math.max(1.15D, cfg.surface.rowSpacing * spacingMul);
        double laneSpacing = Math.max(1.05D, cfg.surface.laneSpacing * spacingMul);
        double stagger = cfg.surface.staggerOffset * Math.min(1.65D, 0.70D + settings.width * 0.18D) * bodyScale;

        switch (style) {
            case LINE: {
                int perLine = Math.max(1, settings.width);
                int rank = ordinal / perLine;
                int lineIndex = ordinal % perLine;

                double alongLine = (lineIndex - (perLine - 1) * 0.5D) * laneSpacing;
                double back = baseDist + rank * rowSpacing;

                return new SlotOffset(
                        -forwardX * back + rx * alongLine,
                        -forwardZ * back + rz * alongLine,
                        back,
                        cfg.surface.arriveRadius + Math.min(0.35D, rank * 0.04D),
                        0.70D
                );
            }

            case WEDGE: {
                int capWidth = Math.max(1, Math.min(settings.width, cfg.group.maxSurfaceLanes));
                int row = 0;
                int remaining = ordinal;
                int rowCount = 1;

                while (remaining >= rowCount) {
                    remaining -= rowCount;
                    row++;
                    rowCount = Math.min(Math.max(2, row * 2), capWidth);
                }

                double back = baseDist + row * rowSpacing;
                double side = rowCount == 1 ? 0.0D : (remaining - (rowCount - 1) * 0.5D) * laneSpacing * 0.82D;

                return new SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.surface.arriveRadius + Math.min(0.35D, row * 0.06D),
                        1.05D
                );
            }

            case COMPACT:
            default: {
                int ringWidth = Math.max(1, Math.min(settings.width, cfg.group.maxSurfaceLanes));
                int firstRing = Math.max(6, Math.min(ringWidth * 2, groupSize));

                // Arc span the ring fans across also scales with width — without this, a group
                // small enough to fit entirely in the ring (very common: any group under ~2x
                // width) never visibly changes shape when width is adjusted, since firstRing was
                // the only width-driven term and it's clamped by groupSize. Calibrated so the
                // default width (8) reproduces the original fixed 270 degrees exactly.
                double arcSpan = MathHelper.clamp(120.0D + ringWidth * 18.75D, 120.0D, 320.0D);
                double deadzoneDeg = arcSpan * (24.0D / 270.0D);

                if (ordinal < firstRing) {
                    double t = firstRing <= 1 ? 0.5D : (double) ordinal / (double) (firstRing - 1);
                    double angleDeg = -arcSpan * 0.5D + arcSpan * t;

                    if (Math.abs(angleDeg) < deadzoneDeg) {
                        int sideSign = (((leaderId.hashCode() >> 1) + ordinal) & 1) == 0 ? -1 : 1;
                        angleDeg = deadzoneDeg * sideSign;
                    }

                    double angle = Math.toRadians(angleDeg);
                    double radius = baseDist + (ordinal >= Math.max(4, firstRing / 2) ? ringSpacing * 0.75D : 0.0D);

                    double back = Math.cos(angle) * radius;
                    double side = Math.sin(angle) * radius;

                    return new SlotOffset(
                            -forwardX * back + rx * side,
                            -forwardZ * back + rz * side,
                            radius,
                            cfg.surface.arriveRadius,
                            1.10D
                    );
                }

                int idx = ordinal - firstRing;
                int lanes = Math.max(3, ringWidth);
                int row = 1 + idx / lanes;
                int col = idx % lanes;

                double side = (col - (lanes - 1) * 0.5D) * laneSpacing;
                if ((row & 1) == 1) {
                    int sign = (((leaderId.hashCode() >> row) + col) & 1) == 0 ? -1 : 1;
                    side += stagger * sign;
                }

                double back = baseDist + ringSpacing + rowSpacing * row;

                return new SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.surface.arriveRadius + Math.min(0.40D, row * 0.08D),
                        0.80D
                );
            }
        }
    }

    public static WorkerFormationStyle resolveSurfaceStyle(WorkerFormationStyle requested,
                                                             int groupSize,
                                                             boolean workerLeader) {
        // COLUMN is surface-removed (still used for cave/tunnel geometry elsewhere) - normalize
        // here too in case a caller passes it directly without going through
        // WorkerFormationSettings.clamp() first.
        if (requested == WorkerFormationStyle.COLUMN) {
            requested = WorkerFormationStyle.LINE;
        }
        if (requested != WorkerFormationStyle.ADAPTIVE) {
            return requested;
        }

        if (workerLeader) {
            if (groupSize <= 6) return WorkerFormationStyle.WEDGE;
            return WorkerFormationStyle.LINE;
        }

        if (groupSize <= 8) return WorkerFormationStyle.WEDGE;
        if (groupSize <= 60) return WorkerFormationStyle.COMPACT;
        return WorkerFormationStyle.LINE;
    }
}
