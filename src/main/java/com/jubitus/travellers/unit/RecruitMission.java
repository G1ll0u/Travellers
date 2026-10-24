package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.travel.TravelCommon;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.worker.RecruitPlan;
import com.jubitus.travellers.worker.RecruitTour;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * A hired worker's recruiting tour (GuiWorkerRecruitCultures): walks the {@link RecruitTour}'s stops and
 * waits at each, then a return leg to the owner driven by the follow AI. The tour object is the state;
 * this class only drives the loaded body along it.
 */
public final class RecruitMission {

    private static final int STOP_RADIUS = 10;
    private static final int VILLAGE_RADIUS = 24;
    private static final int STALL_TICKS = 20 * 5;
    private static final double PROGRESS_EPS_SQ = 2.0D * 2.0D;
    private static final double MOTION_EPSILON = 0.0004D;

    private final EntityTravellerWorker worker;

    @Nullable
    private RecruitTour tour;
    private BlockPos currentStopPos = null;
    private int arrivalWatchdogTicks = 0;
    private double bestDistSq = Double.MAX_VALUE;

    RecruitMission(EntityTravellerWorker worker) {
        this.worker = worker;
    }

    public static int computeRouteDistance2D(BlockPos start, List<BlockPos> villages, BlockPos end) {
        if (villages == null || villages.isEmpty()) {
            return EntityTravellerWorker.dist2D(start, end);
        }
        int total = 0;
        BlockPos prev = start;
        for (BlockPos v : villages) {
            total += EntityTravellerWorker.dist2D(prev, v);
            prev = v;
        }
        total += EntityTravellerWorker.dist2D(prev, end);
        return total;
    }

    public static int computeFee(int routeDistance, int villagesCount) {
        int flat = TravellersConfig.RECRUITMENT.recruitFeeFlat;
        int perVillage = TravellersConfig.RECRUITMENT.recruitFeePerVillage * villagesCount;
        int perDist = (int) Math.ceil(routeDistance * TravellersConfig.RECRUITMENT.recruitFeePerBlock);
        return Math.max(0, flat + perVillage + perDist);
    }

    /**
     * A safe outdoor spot near a village center (the surface, never inside or on a Millénaire
     * building): a few random columns first, then the nearest ring by ring. The center itself as a last resort.
     */
    public static BlockPos findSpawnPos(World world, BlockPos villageCenter, Random rand) {
        if (world == null || villageCenter == null) return villageCenter;

        final int tries = 24;
        final int radius = 10;
        for (int i = 0; i < tries; i++) {
            BlockPos spot = spotInColumn(world, villageCenter.getX() + rand.nextInt(radius * 2 + 1) - radius,
                    villageCenter.getZ() + rand.nextInt(radius * 2 + 1) - radius);
            if (spot != null) return spot;
        }

        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    BlockPos spot = spotInColumn(world, villageCenter.getX() + dx, villageCenter.getZ() + dz);
                    if (spot != null) return spot;
                }
            }
        }
        return villageCenter;
    }

    @Nullable
    private static BlockPos spotInColumn(World world, int x, int z) {
        int y = ColumnGround.topLayer(world, x, z, false);
        if (y == ColumnGround.NONE) return null;
        BlockPos feet = new BlockPos(x, y, z);
        return LandingSpots.isSafe(world, feet, null, false, false) ? feet : null;
    }

    public boolean canStart() {
        return worker.mode == EntityTravellerWorker.WorkerMode.IDLE
                && !worker.delivery.isFarewellActive()
                && !worker.delivery.isWaitingForPickup()
                && tour == null;
    }

    // Outbound tour + follow-AI return leg to owner
    public boolean isOnMission() {
        return tour != null;
    }

    public boolean isReturningToOwner() {
        return tour != null && tour.isReturning();
    }

    @Nullable
    public RecruitTour getTour() {
        return tour;
    }

    @Nullable
    public BlockPos getCurrentStop() {
        return currentStopPos;
    }

    // Dropped without notice (bed respawn).
    void abandon() {
        this.tour = null;
        this.currentStopPos = null;
    }

    public void start(RecruitTour tour) {
        if (worker.world.isRemote) return;
        adoptTour(tour);
    }

    /**
     * Takes over a tour (a new order, or the virtual record's after readFromNBT - the record is
     * authoritative) and puts the body in the matching state: walking to its stop, or back to the owner.
     */
    public void adoptTour(@Nullable RecruitTour tour) {
        this.tour = tour;
        if (tour == null) {
            currentStopPos = null;
            if (worker.mode == EntityTravellerWorker.WorkerMode.RECRUITING) worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
            return;
        }
        RecruitPlan.Stop stop = tour.currentStop();
        if (stop == null) {
            beginReturnToOwner();
            return;
        }
        worker.mode = EntityTravellerWorker.WorkerMode.RECRUITING;
        worker.routeLocked = true;
        if (worker.isHiredFollower()) worker.setFollowOrderActive(true);
        setLegTarget(stop.center);
    }

    // Server tick of the outbound tour (worker in RECRUITING mode).
    void tickTour() {
        if (worker.mode != EntityTravellerWorker.WorkerMode.RECRUITING || tour == null
                || !(worker.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) worker.world;
        long now = ws.getTotalWorldTime();
        RecruitPlan.Stop stop = tour.currentStop();
        if (stop == null) {
            beginReturnToOwner();
            return;
        }
        if (!tour.isStaying()) {
            if (hasReachedVillage(stop.center)) tour.arrive(now);
            return;
        }
        if (!tour.isStayOver(now)) return;
        UUID ownerId = worker.getFollowOwnerId();
        RecruitPlan.Stop next = ownerId == null ? null : tour.leave(ws, ownerId);
        if (next != null) setLegTarget(next.center);
        else beginReturnToOwner();
    }

    private void finish(@Nullable EntityPlayer owner) {
        this.tour = null;
        this.currentStopPos = null;
        resetArrivalWatchdog();

        TravelCommon tc = worker.getTravelCommon();
        tc.clearRoute(false);
        worker.getNavigator().clearPath();

        // Back to companion idle
        worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
        worker.routeLocked = true; // keep companion mode
        worker.setFollowOrderActive(true);

        if (owner != null) {
            NotificationService.notifyAt(owner, NotificationDefs.RECRUIT_MISSION_COMPLETED, NoticePlace.of(worker));
        }
    }

    private void beginReturnToOwner() {
        if (tour != null && !tour.isReturning()) tour.turnBack();
        this.currentStopPos = null;
        resetArrivalWatchdog();

        // Follow AI + catchup travel do the long-range work: Follow order ON, Travel AI route off, and
        // not "job active" (IDLE companion mode, routeLocked so it doesn't roam).
        if (worker.isHiredFollower()) worker.setFollowOrderActive(true); // clears idleAnchorPos
        worker.getTravelCommon().clearRoute(false);
        worker.getNavigator().clearPath();
        worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
        worker.routeLocked = true;
        worker.catchup.stop();
    }

    // Server tick of the return leg to the owner.
    void tickReturnToOwner() {
        if (!isReturningToOwner()) return;
        EntityPlayer owner = worker.getFollowOwner();
        if (owner == null || owner.isDead || owner.dimension != worker.dimension) {
            finish(null);
            return;
        }
        double dx = worker.posX - owner.posX;
        double dz = worker.posZ - owner.posZ;
        // Follow AI pulls him in; "dist > 200 -> startOwnerCatchupTravel()" kicks in on its own.
        if (dx * dx + dz * dz <= 8.0D * 8.0D) finish(owner);
    }

    private double distSq2DTo(BlockPos pos) {
        if (pos == null) return Double.MAX_VALUE;
        double dx = (worker.posX) - (pos.getX() + 0.5D);
        double dz = (worker.posZ) - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private void resetArrivalWatchdog() {
        this.arrivalWatchdogTicks = 0;
        this.bestDistSq = Double.MAX_VALUE;
    }

    private BlockPos resolveTownHallAnchor(BlockPos villageCenter) {
        if (worker.world == null || worker.world.isRemote || villageCenter == null) return villageCenter;

        MillSettlements.Settlement s = MillSettlements.at(worker.world, villageCenter);
        return s != null ? s.center : villageCenter;
    }

    private BlockPos resolveStopPos(BlockPos villageCenter) {
        BlockPos anchor = resolveTownHallAnchor(villageCenter);
        BlockPos safe = findSpawnPos(worker.world, anchor, worker.getRNG());
        return safe != null ? safe : anchor;
    }

    private void setLegTarget(BlockPos villageCenter) {
        BlockPos stop = resolveStopPos(villageCenter);

        this.currentStopPos = stop;
        resetArrivalWatchdog();

        TravelCommon tc = worker.getTravelCommon();
        tc.setTargetVillage(stop);
        worker.getTravelCommon().setTargetVillage(stop);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        worker.getNavigator().clearPath();
    }

    private boolean hasReachedVillage(BlockPos villageCenter) {
        if (villageCenter == null) return false;

        BlockPos stop = this.currentStopPos != null
                ? this.currentStopPos
                : resolveStopPos(villageCenter);

        double stopDistSq = distSq2DTo(stop);
        double villageDistSq = distSq2DTo(villageCenter);

        if (stopDistSq <= (double) (STOP_RADIUS * STOP_RADIUS)) {
            return true;
        }

        if (stopDistSq + PROGRESS_EPS_SQ < this.bestDistSq) {
            this.bestDistSq = stopDistSq;
            this.arrivalWatchdogTicks = 0;
            return false;
        }

        boolean nearVillage = villageDistSq <= (double) (VILLAGE_RADIUS * VILLAGE_RADIUS);
        if (!nearVillage) {
            this.arrivalWatchdogTicks = 0;
            return false;
        }

        boolean stalled = worker.getNavigator().noPath()
                || (worker.motionX * worker.motionX + worker.motionZ * worker.motionZ) <= MOTION_EPSILON;

        if (stalled) {
            this.arrivalWatchdogTicks++;
        } else if (this.arrivalWatchdogTicks > 0) {
            this.arrivalWatchdogTicks--;
        }

        return this.arrivalWatchdogTicks >= STALL_TICKS;
    }

    void writeNbt(NBTTagCompound nbt) {
        if (tour != null) nbt.setTag(RecruitTour.NBT_KEY, tour.write());
        if (currentStopPos != null) nbt.setLong("RecruitStop", currentStopPos.toLong());
        nbt.setInteger("RecruitArrivalWatchdogTicks", arrivalWatchdogTicks);
        nbt.setDouble("RecruitBestDistSq", bestDistSq);
    }

    void readNbt(NBTTagCompound nbt) {
        tour = RecruitTour.read(nbt.hasKey(RecruitTour.NBT_KEY) ? nbt.getCompoundTag(RecruitTour.NBT_KEY) : null);
        currentStopPos = nbt.hasKey("RecruitStop") ? BlockPos.fromLong(nbt.getLong("RecruitStop")) : null;
        arrivalWatchdogTicks = nbt.getInteger("RecruitArrivalWatchdogTicks");
        bestDistSq = nbt.hasKey("RecruitBestDistSq") ? nbt.getDouble("RecruitBestDistSq") : Double.MAX_VALUE;
    }

    /** After the worker's own mode is read: a RECRUITING worker without a tour (an old-style mission) goes idle. */
    void afterReadNbt() {
        if (tour == null && worker.mode == EntityTravellerWorker.WorkerMode.RECRUITING) {
            worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
        }
        if (isReturningToOwner() && worker.isHiredFollower()) worker.setFollowOrderActive(true);
    }
}
