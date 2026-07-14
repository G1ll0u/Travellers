package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.config.TravellersConfig;

public final class FollowRuntimeConfig {

    private static final FollowRuntimeConfig INSTANCE = new FollowRuntimeConfig();

    public final Group group = new Group();
    public final Surface surface = new Surface();
    public final Cave cave = new Cave();
    public final Catchup catchup = new Catchup();
    public final Rescue rescue = new Rescue();


    private FollowRuntimeConfig() {
        reloadFromTravellersConfig();
    }

    public static FollowRuntimeConfig get() {
        return INSTANCE;
    }

    public void reloadFromTravellersConfig() {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;

        group.largeGroupStart = 12;
        group.hugeGroupStart = 28;
        group.maxSurfaceLanes = wf.maxRowWidth;
        group.maxTunnelLanes = wf.maxTunnelWidth;
        group.extraBackoffPerFollower = 0.035D;

        surface.baseLeaderDistancePlayer = wf.leaderDistancePlayer;
        surface.baseLeaderDistanceWorker = wf.leaderDistanceWorkerLeader;
        surface.ringSpacing = wf.ringSpacing;
        surface.rowSpacing = wf.rowSpacing;
        surface.laneSpacing = wf.laneSpacing;
        surface.staggerOffset = 0.47D;
        surface.arriveRadius = wf.arriveRadius;
        surface.verticalTolerance = 12.8D;
        surface.goalSearchHorizontal = 3;
        surface.goalSearchVertical = 10;
        surface.enterModeTicks = 0;
        surface.exitModeTicks = 18;



        cave.minCoverBlocks = 2;
        cave.maxSkyLight = 7;
        cave.minSideSolids = 2;
        cave.enterModeTicks = 60;
        cave.exitModeTicks = 30;
        cave.baseLeaderDistance = 2.1D;
        cave.rowSpacing = wf.caveRowSpacing;
        cave.laneSpacing = wf.caveLaneSpacing;
        cave.arriveRadius = wf.caveArriveRadius;
        cave.verticalTolerance = 4.5D;
        cave.goalSearchHorizontal = 2;
        cave.goalSearchVertical = 12;
        cave.heightAnchorPull = 0.62D;
        cave.orbitTriggerTicks = 36;

        catchup.minUntrackedTicks = 40;
        catchup.startDistanceTracked = Math.max(72.0D, TravellersModConfig.returnFollowSwitchDist * 1.25D);
        catchup.startDistanceUntracked = Math.max(52.0D, TravellersModConfig.returnFollowSwitchDist * 1.20D);
        catchup.startDistanceHidden = Math.max(32.0D, TravellersModConfig.returnFollowSwitchDist);
        catchup.forceStartDistance = Math.max(96.0D, TravellersModConfig.returnFollowSwitchDist * 1.50D);
        catchup.keepLocalUndergroundDistance = 44.0D;
        catchup.verticalEmergencyGap = 14.0D;
        catchup.verticalEmergencyStartDistance = 48.0D;
        catchup.verticalEmergencyPathFailTicks = 18;
        catchup.verticalEmergencyStagnationTicks = 60;
        catchup.stopDistanceVisible = Math.max(18.0D, TravellersModConfig.returnFollowSwitchDist * 0.90D);
        catchup.stopDistanceCave = 14.0D;
        catchup.repathIntervalTicks = 18;
        catchup.dynamicGoalQuantizeSurface = 4;
        catchup.dynamicGoalQuantizeCave = 2;
        catchup.heightAwareVerticalGap = 8.0D;
        catchup.caveBlockCatchupDistance = 80.0D;

        rescue.cooldownTicks = 20 * 1;
        rescue.minActivePathTicks = 20;
        rescue.visibleExtraStagnationTicks = 80;
        rescue.visibleExtraFailedPathTicks = 20;
        rescue.caveMinStagnationTicks = 110;
        rescue.caveMinFailedPathTicks = 28;
        rescue.caveMinLeaderDistance = 2.0D;
        rescue.caveMaxLeaderDistance = 24.0D;
        rescue.caveMaxHorizontal = 12.0D;
        rescue.caveMinVerticalAbove = 4.0D;
        rescue.surfaceMinStagnationTicks = wf.stuckTeleportDelayTicks;
        rescue.surfaceMinFailedPathTicks = 35;
        rescue.surfaceMaxStep = 6.0D;
        rescue.surfaceMinLeaderDistance = 4.0D;
        rescue.surfaceMaxLeaderDistance = wf.stuckTeleportMaxDistance;
        rescue.caveModeGraceTicks = 60;
        rescue.caveNoTeleportLeaderDistance = 4.0D;
        rescue.caveNoTeleportGoalDistance = 5.0D;
        rescue.caveNoTeleportVerticalGap = 4.0D;
        rescue.caveRequireSamePocketFailTicks = 80;
        rescue.caveRequireSamePocketStagnationTicks = 220;
        rescue.caveEmergencyVerticalOnlyMinGap = 2.0D;
        rescue.caveEmergencyOnlyMinStagnationTicks = 260;
    }

    public static final class Group {
        public int largeGroupStart;
        public int hugeGroupStart;
        public int maxSurfaceLanes;
        public int maxTunnelLanes;
        public double extraBackoffPerFollower;
    }

    public static final class Surface {
        public double baseLeaderDistancePlayer;
        public double baseLeaderDistanceWorker;
        public double ringSpacing;
        public double rowSpacing;
        public double laneSpacing;
        public double staggerOffset;
        public double arriveRadius;
        public double verticalTolerance;
        public int goalSearchHorizontal;
        public int goalSearchVertical;
        public int enterModeTicks;
        public int exitModeTicks;
    }

    public static final class Cave {
        public int minCoverBlocks;
        public int maxSkyLight;
        public int minSideSolids;
        public int enterModeTicks;
        public int exitModeTicks;
        public double baseLeaderDistance;
        public double rowSpacing;
        public double laneSpacing;
        public double arriveRadius;
        public double verticalTolerance;
        public int goalSearchHorizontal;
        public int goalSearchVertical;
        public double heightAnchorPull;
        public int orbitTriggerTicks;
    }

    public static final class Catchup {
        public int minUntrackedTicks;
        public double startDistanceTracked;
        public double startDistanceUntracked;
        public double startDistanceHidden;
        public double forceStartDistance;
        public double keepLocalUndergroundDistance;
        public double verticalEmergencyGap;
        public double verticalEmergencyStartDistance;
        public int verticalEmergencyPathFailTicks;
        public int verticalEmergencyStagnationTicks;
        public double stopDistanceVisible;
        public double stopDistanceCave;
        public int repathIntervalTicks;
        public int dynamicGoalQuantizeSurface;
        public int dynamicGoalQuantizeCave;
        public double heightAwareVerticalGap;
        public double caveBlockCatchupDistance;
    }

    public static final class Rescue {
        public int cooldownTicks;
        public int minActivePathTicks;
        public int visibleExtraStagnationTicks;
        public int visibleExtraFailedPathTicks;
        public int caveMinStagnationTicks;
        public int caveMinFailedPathTicks;
        public double caveMinLeaderDistance;
        public double caveMaxLeaderDistance;
        public double caveMaxHorizontal;
        public double caveMinVerticalAbove;
        public int surfaceMinStagnationTicks;
        public int surfaceMinFailedPathTicks;
        public double surfaceMaxStep;
        public double surfaceMinLeaderDistance;
        public double surfaceMaxLeaderDistance;
        public int caveModeGraceTicks;
        public double caveNoTeleportLeaderDistance;
        public double caveNoTeleportGoalDistance;
        public double caveNoTeleportVerticalGap;
        public int caveRequireSamePocketFailTicks;
        public int caveRequireSamePocketStagnationTicks;
        public double caveEmergencyVerticalOnlyMinGap;
        public int caveEmergencyOnlyMinStagnationTicks;
    }
}