package com.jubitus.travellers.follow;

import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.follow.formations.FormationGeometry;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.follow.formations.WorkerFormationStyle;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.*;

/** Space around a loaded leader (ground anchor, tunnel profile) and the slot offsets that depend on it, incl. cave geometry. */
final class FormationSpace {

    private FormationSpace() {
    }

    static GroundAnchor computeAnchor(World world, EntityLivingBase leader) {
        if (leader.isRiding() && leader.getRidingEntity() instanceof EntityBoat) {
            return new GroundAnchor(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ, false);
        }

        BlockPos feet = new BlockPos(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ);
        boolean airborne = !leader.onGround && !leader.isInWater() && leader.motionY > -0.08D;

        int groundY = ColumnGround.topLayer(world, feet.getX(), feet.getZ(), true);
        if (groundY == ColumnGround.NONE) return new GroundAnchor(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ, false);
        BlockPos top = new BlockPos(feet.getX(), groundY, feet.getZ());
        int verticalGap = feet.getY() - top.getY();

        if (airborne && verticalGap >= 5 && world.canSeeSky(top)) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = new BlockPos(top.getX() + dx, top.getY(), top.getZ() + dz);
                    if (com.jubitus.travellers.ground.Footing.canStandAt(world, p)) {
                        return new GroundAnchor(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, true);
                    }
                }
            }

            // Flying over open water: no dry standable tile below. Anchor to the water surface
            // itself instead of falling through to the leader's raw flight altitude, which would
            // otherwise pull followers up into the air trying to reach them.
            if (world.getBlockState(top).getMaterial().isLiquid()) {
                return new GroundAnchor(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D, true);
            }
        }

        return new GroundAnchor(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ, false);
    }

    static SpaceProfile sampleSpaceProfile(World world,
                                                   EntityLivingBase leader,
                                                   double forwardX,
                                                   double forwardZ,
                                                   FollowMode mode,
                                                   FollowRuntimeConfig cfg) {
        BlockPos base = new BlockPos(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ);

        int total = 0;
        int openForward = 0;
        int openSide = 0;

        double rx = -forwardZ;
        double rz = forwardX;

        for (int step = 1; step <= 4; step++) {
            BlockPos pf = new BlockPos(
                    MathHelper.floor(leader.posX + forwardX * step),
                    MathHelper.floor(leader.posY),
                    MathHelper.floor(leader.posZ + forwardZ * step)
            );
            BlockPos pb = new BlockPos(
                    MathHelper.floor(leader.posX - forwardX * step),
                    MathHelper.floor(leader.posY),
                    MathHelper.floor(leader.posZ - forwardZ * step)
            );
            BlockPos pl = new BlockPos(
                    MathHelper.floor(leader.posX + rx * step),
                    MathHelper.floor(leader.posY),
                    MathHelper.floor(leader.posZ + rz * step)
            );
            BlockPos pr = new BlockPos(
                    MathHelper.floor(leader.posX - rx * step),
                    MathHelper.floor(leader.posY),
                    MathHelper.floor(leader.posZ - rz * step)
            );

            if (isColumnTraversable(world, pf)) openForward++;
            if (isColumnTraversable(world, pb)) openForward++;
            if (isColumnTraversable(world, pl)) openSide++;
            if (isColumnTraversable(world, pr)) openSide++;

            total += 4;
        }

        double openness = total <= 0 ? 1.0D : (double) (openForward + openSide) / (double) total;
        boolean underground = mode == FollowMode.CAVE
                || FollowRecoveryHelper.isLeaderMeaningfullyUnderground(world, leader, cfg)
                || FollowRecoveryHelper.isEnclosedFollowSpace(world, leader, cfg);

        double compactness = underground ? 0.70D : (openness < 0.45D ? 0.88D : 1.0D);
        double tunnelStrength = underground
                ? MathHelper.clamp((openForward / 8.0D) - (openSide / 10.0D) + 0.35D, 0.0D, 1.0D)
                : 0.0D;
        double caveSpread = underground ? MathHelper.clamp(openSide / 8.0D, 0.0D, 1.0D) : 1.0D;

        int lanes;
        if (!underground) {
            lanes = 0;
        } else if (openSide >= 6) {
            lanes = Math.min(3, cfg.group.maxTunnelLanes);
        } else if (openSide >= 3) {
            lanes = Math.min(2, cfg.group.maxTunnelLanes);
        } else {
            lanes = 1;
        }

        return new SpaceProfile(underground, openness, compactness, lanes, tunnelStrength, caveSpread);
    }

    static boolean isColumnTraversable(World world, BlockPos feet) {
        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.up()) || !world.isBlockLoaded(feet.down())) {
            return false;
        }

        if (world.getBlockState(feet.down()).getMaterial().isLiquid()) {
            return false;
        }

        boolean freeFeet = world.getBlockState(feet).getCollisionBoundingBox(world, feet) == null;
        boolean freeHead = world.getBlockState(feet.up()).getCollisionBoundingBox(world, feet.up()) == null;
        return freeFeet && freeHead;
    }

    /** Mounted formations need wider slots so the horses don't interpenetrate - true when the leader
     *  or any listed member is riding a bonded horse. */
    static double formationBodyScale(EntityLivingBase leader,
                                             java.util.List<EntityTravellerWorker> members) {
        if (leader != null && com.jubitus.travellers.pathing.PathBody.isMounted(leader)) {
            return FormationGeometry.MOUNTED_SPACING_SCALE;
        }
        if (members != null) {
            for (EntityTravellerWorker m : members) {
                if (m != null && com.jubitus.travellers.pathing.PathBody.isMounted(m)) {
                    return FormationGeometry.MOUNTED_SPACING_SCALE;
                }
            }
        }
        return 1.0D;
    }

    static double formationBodyScale(EntityLivingBase leader, EntityTravellerWorker member) {
        return formationBodyScale(leader, member == null ? null : java.util.Collections.singletonList(member));
    }

    static FormationGeometry.SlotOffset computeSlotOffset(int ordinal,
                                                int groupSize,
                                                double forwardX,
                                                double forwardZ,
                                                SpaceProfile profile,
                                                FollowMode mode,
                                                FollowRuntimeConfig cfg,
                                                double baseFollowDistance,
                                                UUID leaderId,
                                                WorkerFormationSettings formationSettings,
                                                boolean workerLeader,
                                                double bodyScale) {
        // Close-escort slots, and surface formation geometry, are shared pure math (FormationGeometry)
        // so loaded and virtual/offscreen followers can never visually disagree on formation shape.
        if (mode == FollowMode.CAVE) {
            // Cave/tunnel geometry depends on world block scans (SpaceProfile) that only make sense
            // for a loaded leader — kept here rather than in the shared geometry class.
            int numEscortsCave = formationSettings.numEscorts;
            if (numEscortsCave > 0 && ordinal < numEscortsCave) {
                int effectiveEscorts = Math.min(numEscortsCave, groupSize);
                FormationGeometry.SlotOffset off =
                        FormationGeometry.computeEscortOffset(ordinal, effectiveEscorts, forwardX, forwardZ, bodyScale);
                return off;
            }
            int adjOrdinalCave = ordinal - numEscortsCave;
            int adjGroupSizeCave = Math.max(1, groupSize - numEscortsCave);
            return computeCaveOffset(adjOrdinalCave, adjGroupSizeCave, forwardX, forwardZ, profile, cfg,
                    formationSettings, bodyScale);
        }

        FormationGeometry.SlotOffset off = FormationGeometry.computeSlotOffset(
                ordinal, groupSize, forwardX, forwardZ, baseFollowDistance, leaderId, formationSettings, workerLeader,
                bodyScale);
        // Higher player cohesion lets followers fill in beside/ahead of the leader instead of being
        // pushed strictly behind it - see WorkerFormationSettings.playerCohesion.
        double frontPenalty = off.frontPenaltyStrength * (1.0D - 0.6D * formationSettings.playerCohesion);
        return new FormationGeometry.SlotOffset(off.dx, off.dz, off.preferredRadius, off.arriveRadius, frontPenalty);
    }

    static FormationGeometry.SlotOffset computeCaveOffset(int ordinal,
                                                int groupSize,
                                                double forwardX,
                                                double forwardZ,
                                                SpaceProfile profile,
                                                FollowRuntimeConfig cfg,
                                                WorkerFormationSettings settings,
                                                double bodyScale) {
        double rx = -forwardZ;
        double rz = forwardX;

        WorkerFormationStyle style = resolveCaveStyle(settings, profile);

        // Mirrors computeSurfaceOffset: only inter-slot spacing scales for a mounted body, never the
        // base leader distance. A mounted follower in a tunnel needs the same wider lane spacing a
        // surface formation gets, or it computes pedestrian-tight geometry sized for a ~0.6-wide body.
        double spacingMul = settings.spacing * bodyScale;
        double dist = Math.max(cfg.cave.baseLeaderDistance, settings.followDistance * 0.85D);

        double laneSpacing = Math.max(0.50D, cfg.cave.laneSpacing * spacingMul * (settings.adaptiveCave ? 0.72D : 0.92D));
        double rowSpacing = Math.max(0.85D, cfg.cave.rowSpacing * spacingMul * (settings.adaptiveCave ? 0.78D : 0.95D));

        int maxTunnelLanes = Math.max(1, profile.suggestedTunnelLanes);
        if (settings.adaptiveCave) {
            // Adaptive mode already limits lanes to what the tunnel physically fits
            // (profile.suggestedTunnelLanes); width just lets the player additionally narrow
            // that down, graded like every other style instead of a binary 1-vs-2 switch.
            maxTunnelLanes = Math.min(maxTunnelLanes, Math.max(1, settings.width));
        } else {
            maxTunnelLanes = Math.min(Math.max(1, settings.width), Math.max(1, cfg.group.maxTunnelLanes));
        }

        switch (style) {
            case LINE: {
                int perLine = Math.max(1, Math.min(maxTunnelLanes, Math.min(3, settings.width)));
                int rank = ordinal / perLine;
                int lineIndex = ordinal % perLine;

                double alongLine = (lineIndex - (perLine - 1) * 0.5D) * laneSpacing;
                double back = dist + rank * rowSpacing;

                return new FormationGeometry.SlotOffset(
                        -forwardX * back + rx * alongLine,
                        -forwardZ * back + rz * alongLine,
                        back,
                        cfg.cave.arriveRadius + Math.min(0.40D, rank * 0.05D),
                        0.08D
                );
            }

            case WEDGE:
            case COMPACT: {
                int lanes = Math.max(1, Math.min(maxTunnelLanes, 2));
                int row = ordinal / lanes;
                int lane = ordinal % lanes;
                double side = lanes <= 1 ? 0.0D : (lane == 0 ? -laneSpacing * 0.5D : laneSpacing * 0.5D);
                double back = dist + row * rowSpacing;
                return new FormationGeometry.SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.cave.arriveRadius + Math.min(0.45D, row * 0.05D),
                        0.08D
                );
            }

            case COLUMN:
            default: {
                int lanes = Math.max(1, Math.min(maxTunnelLanes, 2));
                int row = ordinal / lanes;
                int lane = ordinal % lanes;
                double side = lanes == 1 ? 0.0D : (lane == 0 ? -laneSpacing * 0.42D : laneSpacing * 0.42D);
                double back = dist + row * rowSpacing * 0.95D;
                return new FormationGeometry.SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.cave.arriveRadius + Math.min(0.45D, row * 0.05D),
                        0.08D
                );
            }
        }
    }

    static WorkerFormationStyle resolveCaveStyle(WorkerFormationSettings settings, SpaceProfile profile) {
        if (settings.style == WorkerFormationStyle.ADAPTIVE) {
            return profile.tunnelStrength >= 0.40D ? WorkerFormationStyle.COLUMN : WorkerFormationStyle.LINE;
        }

        if (!settings.adaptiveCave) {
            return settings.style;
        }

        if (profile.tunnelStrength >= 0.40D) {
            return WorkerFormationStyle.COLUMN;
        }

        if (settings.style == WorkerFormationStyle.WEDGE) {
            return WorkerFormationStyle.LINE;
        }

        return settings.style;
    }

    static final class GroundAnchor {
        final double x;
        final double y;
        final double z;
        final boolean useProjectedGround;

        GroundAnchor(double x, double y, double z, boolean useProjectedGround) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.useProjectedGround = useProjectedGround;
        }
    }

    static final class SpaceProfile {
        final boolean underground;
        final double openness;
        final double compactness;
        final int suggestedTunnelLanes;
        final double tunnelStrength;
        final double caveSpread;

        SpaceProfile(boolean underground,
                             double openness,
                             double compactness,
                             int suggestedTunnelLanes,
                             double tunnelStrength,
                             double caveSpread) {
            this.underground = underground;
            this.openness = openness;
            this.compactness = compactness;
            this.suggestedTunnelLanes = suggestedTunnelLanes;
            this.tunnelStrength = tunnelStrength;
            this.caveSpread = caveSpread;
        }
    }
}
