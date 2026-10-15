package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.MissionNotifications;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.travel.TravelCommon;
import com.jubitus.travellers.unit.equipment.EquipOrders;
import com.jubitus.travellers.unit.equipment.EquipTargetKind;
import com.jubitus.travellers.zone.ZoneRef;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * A hired worker's trips to a fixed place under its own travel AI: home (its zone), its duty zone,
 * a Go order and a Get Equipment order. Holds their state, arrival and save keys.
 */
public final class WorkerTrips {

    private final EntityTravellerWorker worker;
    private boolean goingHome;
    private boolean goingToDuty;
    // A Go order; Get Equipment uses the same target with mode GET_EQUIPMENT.
    private boolean goOrderActive;
    @Nullable private BlockPos goOrderTargetPos;
    private int goOrderTargetDim;
    private boolean getEquipStay;
    private EquipTargetKind getEquipTargetKind = EquipTargetKind.LONE_BARRACK;

    WorkerTrips(EntityTravellerWorker worker) {
        this.worker = worker;
    }

    public boolean isGoingHome() {
        return goingHome;
    }

    public boolean isGoingToDuty() {
        return goingToDuty;
    }

    public boolean isGoOrderActive() {
        return goOrderActive;
    }

    @Nullable
    public BlockPos getGoOrderTargetPos() {
        return goOrderTargetPos;
    }

    /** True while any of these trips is under way. */
    public boolean isUnderWay() {
        return goOrderActive || goingHome || goingToDuty;
    }

    /**
     * The fixed destination of a home or duty trip, or null. Unlike a follow/escort slot it must keep
     * progressing offscreen and survive virtualize/materialize regardless of {@code mode}.
     */
    @Nullable
    public BlockPos getActivePersonalTravelGoal() {
        ZoneRef zone = worker.zone;
        if (zone == null || zone.dim != worker.dimension) return null;
        if (goingToDuty && zone.isDuty()) return zone.center;
        if (goingHome) return zone.center;
        return null;
    }

    public void startGoHome() {
        if (worker.world.isRemote) return;
        ZoneRef zone = worker.zone;
        if (zone == null || zone.dim != worker.dimension) return;

        this.goingHome = true;
        worker.routeLocked = true;
        // Follow off, but no anchor until arrived.
        worker.followOrderActive = false;
        worker.idleAnchorPos = null;
        worker.setReturnUsingFollow(false);
        travelTo(zone.center);
    }

    /** Sends the worker to its duty zone; {@code mode} becomes ON_DUTY and stays so once arrived. */
    public void startDutyTravel() {
        if (worker.world.isRemote) return;
        BlockPos dutyCenter = worker.getDutyZoneCenter();
        if (dutyCenter == null || worker.zone.dim != worker.dimension) return;

        worker.mode = EntityTravellerWorker.WorkerMode.ON_DUTY;
        this.goingToDuty = true;
        worker.routeLocked = true;
        worker.followOrderActive = false;
        worker.idleAnchorPos = null;
        travelTo(dutyCenter);
    }

    public void startGoTo(BlockPos pos, int dim) {
        if (worker.world.isRemote || pos == null || dim != worker.dimension) return;

        // Cancel anything that could fight with this order; a job's travel stops but its inventory stays.
        cancelCompetingTravel();
        if (worker.isJobActive()) {
            worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
            worker.delivery.jobDelivered = false;
        }
        beginGoOrder(pos, dim);
    }

    public EquipTargetKind getEquipTargetKind() {
        return getEquipTargetKind;
    }

    public void startGetEquip(BlockPos target, boolean stay, @Nullable EquipTargetKind kind) {
        if (worker.world.isRemote || target == null) return;

        this.getEquipStay = stay;
        this.getEquipTargetKind = kind == null ? EquipTargetKind.LONE_BARRACK : kind;
        worker.mode = EntityTravellerWorker.WorkerMode.GET_EQUIPMENT;
        cancelCompetingTravel();
        worker.delivery.jobDelivered = false;
        beginGoOrder(target, worker.dimension);
    }

    private void cancelCompetingTravel() {
        this.goingHome = false;
        worker.catchup.stop();
        worker.returnUsingFollow = false;
        worker.delivery.awaitingPickup = false;
    }

    private void beginGoOrder(BlockPos target, int dim) {
        // Follow off, no anchor until arrived.
        worker.followOrderActive = false;
        worker.idleAnchorPos = null;
        this.goOrderActive = true;
        this.goOrderTargetPos = target;
        this.goOrderTargetDim = dim;
        worker.routeLocked = true;
        travelTo(target);
    }

    /** A one-stop route the travel AI leaves on at once. */
    private void travelTo(BlockPos target) {
        worker.getNavigator().clearPath();
        TravelCommon tc = worker.getTravelCommon();
        tc.setCurrentWaypoint(null);
        tc.routeTo(target);
        tc.setDepartCooldownTicks(0);
    }

    public void cancelGoOrder() {
        goOrderActive = false;
        goOrderTargetPos = null;
        goOrderTargetDim = 0;
        worker.stopAllTravelNow();
    }

    /** Following again ends a trip home. Returns true if one was under way. */
    boolean cancelGoHome() {
        if (!goingHome) return false;
        goingHome = false;
        worker.stopAllTravelNow();
        return true;
    }

    /** The zone is released: no trip toward it is left. */
    void onZoneReleased() {
        goingHome = false;
        goingToDuty = false;
    }

    /** Back at its bed after dying: no order or trip survives. */
    void clearForBedRespawn() {
        goOrderActive = false;
        goOrderTargetPos = null;
        goOrderTargetDim = 0;
        goingHome = false;
        goingToDuty = false;
    }

    /** Server tick: arrival at home, at the duty zone, or at a Go / Get Equipment target. */
    void tickArrival(TravelCommon tc) {
        ZoneRef zone = worker.zone;
        if (goingHome && zone != null && zone.dim == worker.dimension && isWithin(tc, zone.center)) {
            goingHome = false;
            anchorAt(zone.center);
            EntityPlayer owner = worker.getFollowOwner();
            if (owner != null) NotificationService.notify(owner, NotificationDefs.WORKER_ARRIVED_HOME);
        }

        // Unlike home, mode stays ON_DUTY: the duty is the ongoing state once arrived.
        BlockPos dutyCenter = worker.getDutyZoneCenter();
        if (goingToDuty && dutyCenter != null && zone.dim == worker.dimension && isWithin(tc, dutyCenter)) {
            goingToDuty = false;
            anchorAt(dutyCenter);
        }

        if (goOrderActive && goOrderTargetPos != null && goOrderTargetDim == worker.dimension) {
            boolean equip = worker.mode == EntityTravellerWorker.WorkerMode.GET_EQUIPMENT;
            int r = equip ? TravellersConfig.WORKER.workerEquipArrivalRadius : TravellersConfig.WORKER.workerGoArrivalRadius;
            double dx = worker.posX - (goOrderTargetPos.getX() + 0.5D);
            double dz = worker.posZ - (goOrderTargetPos.getZ() + 0.5D);
            if (dx * dx + dz * dz <= (double) (r * r)) {
                goOrderActive = false;
                worker.stopAllTravelNow();
                if (equip) {
                    EquipOrders.onArrived(worker, goOrderTargetPos, getEquipTargetKind);
                    finishGetEquip(getEquipStay, goOrderTargetPos);
                } else {
                    worker.followOrderActive = false;
                    worker.idleAnchorPos = goOrderTargetPos;
                    MissionNotifications.arrived(worker.world.getMinecraftServer(),
                            worker.getFollowOwnerId(), worker.getUniqueID());
                }
            }
        }
    }

    private boolean isWithin(TravelCommon tc, BlockPos center) {
        int r = tc.arrivalRadiusBlocks(center);
        return worker.getDistanceSqToCenter(center) <= (double) (r * r);
    }

    private void anchorAt(BlockPos pos) {
        worker.stopAllTravelNow();
        worker.followOrderActive = false;
        worker.idleAnchorPos = pos;
    }

    /** Called by VirtualTravellerManager when a Go order is reached offscreen (mirrors the loaded arrival). */
    public void finishGoOrderVirtually(BlockPos target) {
        goOrderActive = false;
        goOrderTargetPos = null;
        worker.followOrderActive = false;
        worker.idleAnchorPos = target;
    }

    /** Called by VirtualTravellerManager after a virtual equipment exchange. */
    public void finishGetEquipVirtually(boolean stay, BlockPos target) {
        goOrderActive = false;
        goOrderTargetPos = null;
        finishGetEquip(stay, target);
    }

    // Staying anchors there; otherwise the worker goes back to following its owner.
    private void finishGetEquip(boolean stay, BlockPos target) {
        if (stay) {
            worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
            worker.followOrderActive = false;
            worker.idleAnchorPos = target;
        } else {
            worker.finishJob();
            if (worker.isHiredFollower()) worker.setFollowOrderActive(true);
        }
    }

    // NBT keys unchanged.

    void writeNbt(NBTTagCompound nbt) {
        nbt.setBoolean("GetEquipStay", getEquipStay);
        nbt.setString("GetEquipTargetKind", getEquipTargetKind.name());
        nbt.setBoolean("GoingHome", goingHome);
        nbt.setBoolean("GoingToDuty", goingToDuty);
        nbt.setBoolean("GoOrderActive", goOrderActive);
        if (goOrderTargetPos != null) {
            nbt.setInteger("GoX", goOrderTargetPos.getX());
            nbt.setInteger("GoY", goOrderTargetPos.getY());
            nbt.setInteger("GoZ", goOrderTargetPos.getZ());
            nbt.setInteger("GoDim", goOrderTargetDim);
        }
    }

    /** Home and duty trips; needs the zone already read. A duty zone restores mode ON_DUTY. */
    void readZoneTripsNbt(NBTTagCompound nbt) {
        getEquipStay = nbt.getBoolean("GetEquipStay");
        getEquipTargetKind = nbt.hasKey("GetEquipTargetKind")
                ? EquipTargetKind.fromNbt(nbt.getString("GetEquipTargetKind"))
                // Legacy saves stored a boolean instead - true meant CONTROLLED_VILLAGE, false LONE_BARRACK.
                : (nbt.getBoolean("GetEquipWholeVillage") ? EquipTargetKind.CONTROLLED_VILLAGE : EquipTargetKind.LONE_BARRACK);

        goingHome = nbt.getBoolean("GoingHome");
        goingToDuty = nbt.getBoolean("GoingToDuty");
        ZoneRef zone = worker.zone;
        BlockPos dutyCenter = worker.getDutyZoneCenter();
        if (dutyCenter != null) {
            // Duty is a persistent state: mode is restored whether still travelling there or arrived.
            worker.mode = EntityTravellerWorker.WorkerMode.ON_DUTY;
            if (goingToDuty && zone.dim == worker.dimension) resumeRouteTo(dutyCenter);
        } else if (worker.mode == EntityTravellerWorker.WorkerMode.ON_DUTY) {
            worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
        }
        if (goingHome && zone != null && zone.dim == worker.dimension) resumeRouteTo(zone.center);
    }

    /** The Go / Get Equipment order; read after the follow state so restoring follow cannot cancel it. */
    void readGoOrderNbt(NBTTagCompound nbt) {
        goOrderActive = nbt.getBoolean("GoOrderActive");
        if (nbt.hasKey("GoX")) {
            goOrderTargetPos = new BlockPos(nbt.getInteger("GoX"), nbt.getInteger("GoY"), nbt.getInteger("GoZ"));
            goOrderTargetDim = nbt.getInteger("GoDim");
        } else {
            goOrderTargetPos = null;
            goOrderTargetDim = 0;
        }
        if (goOrderActive && goOrderTargetPos != null) resumeRouteTo(goOrderTargetPos);
    }

    // After a reload the travel AI continues toward the destination if no route survived.
    private void resumeRouteTo(BlockPos target) {
        if (worker.world.isRemote) return;
        TravelCommon tc = worker.getTravelCommon();
        boolean routeMissing = tc.route == null || tc.route.isEmpty() || tc.routeIndex < 0;
        if (!routeMissing) return;
        tc.routeTo(target);
        tc.setDepartCooldownTicks(0);
        worker.getNavigator().clearPath();
    }
}
