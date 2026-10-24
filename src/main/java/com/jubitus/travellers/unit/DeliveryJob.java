package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.MissionNotifications;
import com.jubitus.travellers.notify.NotificationDef;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.zone.ZoneRef;
import net.minecraft.server.MinecraftServer;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.travel.TravelCommon;
import com.jubitus.travellers.village.MillGoods;
import com.jubitus.travellers.village.MillMoney;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.ItemStackHandler;
import org.millenaire.common.item.MillItems;
import org.millenaire.common.utilities.MillLog;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A worker's sale or donation trip (GuiWorker): carry the cargo to a village, trade it, walk back to
 * the employer and wait until the employer collects the cargo, then a short farewell.
 */
public final class DeliveryJob {

    private static final double RETURN_FOLLOW_SWITCH_DIST = TravellersConfig.WORKER_FORMATION.returnFollowSwitchDist; // tweak (24–40 works well)
    // How long to wait after loot pickup before saying goodbye & roaming again
    private static final int POST_JOB_FAREWELL_TICKS = 0; // 5 seconds (tweak)
    // How often (in ticks) to refresh target when returning
    private static final int RETURN_REPATH_INTERVAL_TICKS = 200; // ~10 seconds

    private final EntityTravellerWorker worker;

    // "Employer" = player who hired this worker for the current/last job
    @Nullable
    private UUID employerId;
    @Nullable
    private BlockPos employerReturnPos;   // where to go back (captured when job starts)
    @Nullable
    BlockPos jobVillageCenter = null;
    boolean jobDelivered = false;
    boolean awaitingPickup = false;
    // If true, cargo is treated as a donation (extra rep, no money)
    private boolean donationJob = false;
    private boolean hasUnsoldGoods = false;
    private boolean pickupNotified = false;
    // Delivered but the employer is not in this world: heading home (or waiting) until they are back.
    private boolean employerAbsent = false;
    /** NBT key of employerAbsent; the offscreen return reads and writes it directly. */
    public static final String EMPLOYER_ABSENT_KEY = "EmployerAbsent";
    // Cooldown counter for re-pathing
    private int returnRepathCooldown = 0;
    private int farewellTicks = 0;
    private boolean farewellActive = false;

    DeliveryJob(EntityTravellerWorker worker) {
        this.worker = worker;
    }

    /**
     * Compute travel cost in deniers based on village distance and how many cargo
     * slots are used.
     * <p>
     * distance: 2D distance in blocks (same as Millénaire uses for mill)
     * usedSlots: number of non-empty cargo slots (0..18)
     * <p>
     * Formula (tweak as you like):
     * cost = ceil( basePerSlot * usedSlots * (distance / 800.0) )
     * <p>
     * So:
     * - distance ~800 and 1 full slot -> ~basePerSlot deniers
     * - distance ~800 and 18 full slots -> ~18 * basePerSlot deniers
     */
    public static int computeTravelCost(int distance, int usedSlots) {
        if (distance <= 0 || usedSlots <= 0) {
            return 0;
        }

        double basePerSlot = TravellersConfig.WORKER.workerTravelBaseCostPerSlot;
        double baseDistance = TravellersConfig.WORKER.workerTravelDistanceBase;
        if (baseDistance <= 0.0D) {
            baseDistance = 800.0D;
        }

        double factor = (double) distance / baseDistance;
        double cost = basePerSlot * usedSlots * factor;

        // Flat base + global multiplier
        cost = (cost + TravellersConfig.WORKER.workerTravelFlatBaseCost)
                * TravellersConfig.WORKER.workerTravelCostMultiplier;

        return (int) Math.ceil(cost);
    }

    public boolean isWaitingForPickup() {
        return worker.mode == EntityTravellerWorker.WorkerMode.RETURNING && this.jobDelivered && this.awaitingPickup;
    }

    // Outbound trip to village + full return leg to owner
    public boolean isOnMission() {
        return worker.mode == EntityTravellerWorker.WorkerMode.TO_VILLAGE
                || (worker.mode == EntityTravellerWorker.WorkerMode.RETURNING && this.jobDelivered);
    }

    // Delivered and walking back (or waiting) with the trade's proceeds.
    boolean isReturning() {
        return worker.mode == EntityTravellerWorker.WorkerMode.RETURNING && this.jobDelivered;
    }

    boolean isFarewellActive() {
        return farewellActive;
    }

    @Nullable
    BlockPos getEmployerReturnPos() {
        return employerReturnPos;
    }

    @Nullable
    private EntityPlayer getEmployer() {
        if (employerId == null) return null;
        return worker.world.getPlayerEntityByUUID(employerId);
    }

    // called by server when player confirms job in GUI
    public void start(EntityPlayer employer, BlockPos villageCenter, boolean donate) {
        this.employerId = employer.getUniqueID();
        this.employerReturnPos = worker.getApproximateSurfacePosNear(employer);
        this.jobVillageCenter = villageCenter;
        worker.mode = EntityTravellerWorker.WorkerMode.TO_VILLAGE;
        this.jobDelivered = false;
        this.donationJob = donate;
        this.awaitingPickup = false;
        this.pickupNotified = false;
        this.returnRepathCooldown = 0;


        TravelCommon tc = worker.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;

        // Route: go to village, then back to employer
        tc.route.add(villageCenter);
        if (employerReturnPos != null) {
            tc.route.add(employerReturnPos);
        }

        tc.routeIndex = 0;
        tc.setTargetVillage(villageCenter);
        worker.getTravelCommon().setTargetVillage(villageCenter);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        worker.routeLocked = true;
        worker.getNavigator().clearPath();
        com.jubitus.travellers.debug.MissionDebugLog.loaded(worker, "delivery-start");
    }

    /**
     * Called when we have finished donating to the target village.
     * After this, worker will travel back to employerReturnPos.
     */
    private void onDelivered() {
        if (this.jobDelivered) return;
        this.jobDelivered = true;
        worker.mode = EntityTravellerWorker.WorkerMode.RETURNING;

        if (worker.isHiredFollower()) {
            worker.setFollowOrderActive(true); // clears idleAnchorPos
        }

        EntityPlayer employer = getEmployer();
        if (employer != null) {
            updateReturnRoute(worker.getApproximateSurfacePosNear(employer));
        } else if (employerReturnPos != null) {
            updateReturnRoute(employerReturnPos);
        }

    }

    // Job state back to nothing (worker.finishJob).
    void clear() {
        this.jobVillageCenter = null;
        this.jobDelivered = false;
        this.donationJob = false;
        this.hasUnsoldGoods = false;
        this.awaitingPickup = false;
        this.pickupNotified = false;
        this.employerAbsent = false;
        this.returnRepathCooldown = 0;
    }

    // Server tick: arrival at the village, the return leg, the farewell.
    void tick(TravelCommon tc) {
        // 1) Going TO the village (unchanged)
        if (worker.mode == EntityTravellerWorker.WorkerMode.TO_VILLAGE && jobVillageCenter != null && !jobDelivered) {
            int r = tc.arrivalRadiusBlocks(jobVillageCenter);
            if (worker.getDistanceSqToCenter(jobVillageCenter) <= (double) (r * r)) {
                deliverCargo(jobVillageCenter);
                // deliverCargo calls onDelivered() already
            }
        }

        // 2) RETURNING: re-path, walk to player, then wait for pickup
// RETURNING: re-path with travel AI (long range), vanilla path only at close range
        if (worker.mode == EntityTravellerWorker.WorkerMode.RETURNING && jobDelivered) {
            EntityPlayer employer = getEmployer();

            if (employer != null) {

                // Use horizontal distance (better for caves / Y differences)
                double dx = worker.posX - employer.posX;
                double dz = worker.posZ - employer.posZ;
                double distHsq = dx * dx + dz * dz;

                if (employerAbsent) {
                    employerAbsent = false;
                    awaitingPickup = false;
                    worker.returnUsingFollow = false;
                    if (hasWalkBackTo(distHsq)) {
                        MissionNotifications.cargoComingBack(worker.world.getMinecraftServer(), employerId, worker.getName(),
                                NoticePlace.of(worker));
                    }
                    updateReturnRoute(worker.getApproximateSurfacePosNear(employer));
                }

// --- Switch from Travel AI -> Follow AI when we can SEE the employer ---
                if (!awaitingPickup) {
                    // horizontal distance is better than 3D (caves / Y offsets)

                    double switchSq = RETURN_FOLLOW_SWITCH_DIST * RETURN_FOLLOW_SWITCH_DIST;

                    boolean sees = worker.canEntityBeSeen(employer);

                    // Use a little hysteresis so it doesn't flicker on/off
                    double switchOffSq = (RETURN_FOLLOW_SWITCH_DIST + 10.0D);
                    switchOffSq *= switchOffSq;

                    if (!worker.returnUsingFollow) {
                        if (sees && distHsq <= switchSq) {
                            worker.returnUsingFollow = true;

                            // Kill Travel AI state so it can't teleport when path drops
                            TravelCommon tc2 = worker.getTravelCommon();
                            tc2.clearRoute(false);

                            worker.getNavigator().clearPath();

                            // Ensure follow isn't off (clears idleAnchor)
                            if (worker.isHiredFollower()) {
                                worker.setFollowOrderActive(true);
                            }
                        }
                    } else {
                        // If we lost sight / got far again, let Travel AI take over again
                        if (!sees || distHsq > switchOffSq) {
                            worker.returnUsingFollow = false;
                        }
                    }
                }

                int near = 8;
                double nearSq = (double) near * (double) near;

                // If we were waiting but the player walked away, resume chasing/following
                if (awaitingPickup) {
                    double resumeSq = 12.0D * 12.0D;
                    if (distHsq > resumeSq) {
                        awaitingPickup = false;

                        // If hired follower, follow AI can take over now
                        if (worker.isHiredFollower()) {
                            worker.setFollowOrderActive(true);
                        } else {
                            updateReturnRoute(worker.getApproximateSurfacePosNear(employer));
                        }
                    }
                }

                // Keep long-range travel AI updated
                if (!awaitingPickup && !worker.returnUsingFollow) {
                    // ONLY travel AI logic when we are NOT using follow
                    if (returnRepathCooldown-- <= 0) {
                        returnRepathCooldown = RETURN_REPATH_INTERVAL_TICKS;
                        BlockPos newApprox = worker.getApproximateSurfacePosNear(employer);

                        if (this.employerReturnPos == null
                                || this.employerReturnPos.distanceSq(newApprox) > (32 * 32)) {
                            updateReturnRoute(newApprox);
                        }
                    }
                }


                // Arrival + message
                if (!awaitingPickup && distHsq <= nearSq) {

                    tc.clearRoute(false);
                    worker.getNavigator().clearPath();

                    awaitingPickup = true;
                    // Stay on the tight follow escort until the player takes the goods (no timeout)
                    worker.returnUsingFollow = true;
                    if (worker.isHiredFollower()) {
                        worker.setFollowOrderActive(true);
                    }

                    if (!pickupNotified) {
                        pickupNotified = true;
                        worker.voice.playHello();
                        NotificationService.notifyAt(employer, this.hasUnsoldGoods
                                ? NotificationDefs.WORKER_CARGO_ARRIVED_PARTIAL
                                : NotificationDefs.WORKER_CARGO_ARRIVED_FULL, NoticePlace.of(worker));
                    }
                }

            } else {
                tickEmployerAbsent(tc);
            }
        }
        if (farewellActive) {
            if (--farewellTicks <= 0) {
                farewellActive = false;

                // Now fully clear job state and unlock auto-roaming
                worker.finishJob();
            } else {
                // During farewell, keep him from wandering off:
                worker.getNavigator().clearPath();
            }
        }
    }

    // Employer not in this world: home to the Barrack (else the last known spot) and wait; notified once.
    private void tickEmployerAbsent(TravelCommon tc) {
        BlockPos home = homeFor(worker.zone, worker.dimension);
        if (!employerAbsent) {
            employerAbsent = true;
            awaitingPickup = false;
            worker.returnUsingFollow = false;
            MissionNotifications.cargoEmployerAbsent(worker.world.getMinecraftServer(), employerId, worker.getName(), home != null,
                    NoticePlace.of(worker));
            BlockPos target = home != null ? home : employerReturnPos;
            if (target != null) routeTo(target);
        }
        BlockPos target = home != null ? home : employerReturnPos;
        if (target == null || awaitingPickup) return;
        int r = tc.arrivalRadiusBlocks(target);
        if (worker.getDistanceSqToCenter(target) <= (double) (r * r)) {
            tc.clearRoute(false);
            worker.getNavigator().clearPath();
            awaitingPickup = true;
        }
    }

    /** True when a returning worker is far enough from its employer for the "coming back" notice; a nearby one just arrives. */
    public static boolean hasWalkBackTo(double horizontalDistSq) {
        return horizontalDistSq > RETURN_FOLLOW_SWITCH_DIST * RETURN_FOLLOW_SWITCH_DIST;
    }

    /** Where a returning worker waits for an absent employer: its home zone, or null (last known spot). */
    @Nullable
    public static BlockPos homeFor(@Nullable ZoneRef zone, int dimension) {
        return zone != null && zone.dim == dimension ? zone.center : null;
    }

    /**
     * Process cargo inventory as a Millénaire trade or donation with the village.
     * If donationJob is true -> donation (no money, extra reputation).
     * Else -> normal selling (money + base reputation).
     */
    public void deliverCargo(BlockPos villageCenter) {
        if (!(worker.world instanceof WorldServer)) return;
        MinecraftServer server = worker.world.getMinecraftServer();

        com.jubitus.travellers.debug.MissionDebugLog.loaded(worker, "trade-before");
        MillGoods.Delivery d = MillGoods.deliverCargo((WorldServer) worker.world, villageCenter,
                worker.getTravelInventory(), employerId, this.donationJob);
        com.jubitus.travellers.debug.MissionDebugLog.loaded(worker, "trade-after");

        if (!d.reached) {
            // The village is gone: the cargo comes back untouched.
            this.hasUnsoldGoods = true;
            notifyEmployer(server, NotificationDefs.TRADE_VILLAGE_GONE, worker.getName());
            onDelivered();
            return;
        }
        if (d.unsold) this.hasUnsoldGoods = true;
        if (d.refused) notifyEmployer(server, NotificationDefs.TRADE_REFUSED);
        if (d.reputation != 0 && TravellersConfig.WORKER.workerNotifyOnVillageTrade) {
            notifyTradeSummary(server, d.villageName, this.donationJob, d.items, d.money, d.reputation, d.traded);
        }
        if (!this.donationJob && d.money > 0) addMoneyToCargo(d.money);
        onDelivered();
    }

    // Queued for an offline employer.
    private void notifyEmployer(@Nullable MinecraftServer server, NotificationDef def, String... args) {
        if (server != null && employerId != null) {
            NotificationService.notifyOfflineAt(server, employerId, def, NoticePlace.of(worker), null, args);
        }
    }

    /** One toast per transaction: totals plus the three most valuable items (2nd line), "(+N)" for the rest. */
    private void notifyTradeSummary(@Nullable MinecraftServer server, String villageName, boolean donateMode,
                                    int totalItems, int totalMoney, int totalRep,
                                    Map<String, int[]> tradedByName) {
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(tradedByName.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]));
        StringBuilder items = new StringBuilder("\n");
        int shown = Math.min(3, sorted.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) items.append(", ");
            items.append(sorted.get(i).getKey()).append(" x").append(sorted.get(i).getValue()[0]);
        }
        if (sorted.size() > shown) items.append(" (+").append(sorted.size() - shown).append(")");

        if (donateMode) {
            notifyEmployer(server, NotificationDefs.TRADE_DONATED_SUMMARY, villageName,
                    String.valueOf(totalItems), String.valueOf(totalRep), items.toString());
        } else {
            notifyEmployer(server, NotificationDefs.TRADE_SOLD_SUMMARY, villageName,
                    String.valueOf(totalItems), MillMoney.format(totalMoney),
                    String.valueOf(totalRep), items.toString());
        }
    }

    /** Puts the payment's coins into the worker's cargo, then its backpack; what fits in neither goes in its purse. */
    private void addMoneyToCargo(int deniersTotal) {
        for (ItemStack coins : MillMoney.coins(deniersTotal)) {
            int value = MillMoney.coinValue(coins.getItem());
            ItemStack remaining = insertStackInHandler(worker.getTravelInventory(), coins);
            if (!remaining.isEmpty()) remaining = insertStackInHandler(worker.inventory, remaining);
            if (!remaining.isEmpty()) worker.purse.add(remaining.getCount() * value);
        }
    }

    /**
     * Try to insert a stack into an ItemStackHandler, filling partial stacks first.
     * Returns any leftover.
     */
    private static ItemStack insertStackInHandler(ItemStackHandler handler, ItemStack stack) {
        // First try to merge into existing stacks
        for (int i = 0; i < handler.getSlots() && !stack.isEmpty(); i++) {
            ItemStack inSlot = handler.getStackInSlot(i);
            if (!inSlot.isEmpty()
                    && ItemStack.areItemsEqual(inSlot, stack)
                    && ItemStack.areItemStackTagsEqual(inSlot, stack)) {
                stack = handler.insertItem(i, stack, false);
            }
        }

        // Then look for empty slots
        for (int i = 0; i < handler.getSlots() && !stack.isEmpty(); i++) {
            ItemStack inSlot = handler.getStackInSlot(i);
            if (inSlot.isEmpty()) {
                stack = handler.insertItem(i, stack, false);
            }
        }

        return stack;
    }

    private void updateReturnRoute(BlockPos newReturnPos) {
        if (newReturnPos == null) return;

        this.employerReturnPos = newReturnPos;
        routeTo(newReturnPos);
    }

    // Keeps employerReturnPos (the employer's last known spot) untouched.
    private void routeTo(BlockPos newReturnPos) {
        TravelCommon tc = worker.getTravelCommon();
        tc.routeTo(newReturnPos);
        tc.setDepartCooldownTicks(0);

        worker.getNavigator().clearPath();
    }

    public void onLootCollected(EntityPlayer player) {
        // Only finish if we were on a return job
        if (worker.mode == EntityTravellerWorker.WorkerMode.RETURNING && this.jobDelivered) {
            com.jubitus.travellers.debug.MissionDebugLog.loaded(worker, "pickup-done");
            worker.purse.handTo(player);
            if (worker.purse.get() > 0) NotificationService.notify(player, NotificationDefs.WORKER_PURSE_NO_ROOM);

            // We’re done with the job logically, but we keep routeLocked
            // so he doesn’t start travelling yet.
            this.awaitingPickup = false;

            // Start farewell phase
            this.farewellActive = true;
            this.farewellTicks = POST_JOB_FAREWELL_TICKS;

            // Switch to IDLE but keep routeLocked=true until farewell is over
            worker.mode = EntityTravellerWorker.WorkerMode.IDLE;

            // (Optional) tiny acknowledgement right away, or remove if you only
            // want the delayed goodbye.
            ITextComponent farewell = new TextComponentTranslation("travellers.dialogue.worker_farewell");
            farewell.getStyle().setColor(TextFormatting.WHITE);
            player.sendMessage(farewell);
        }
    }

    void writeNbt(NBTTagCompound nbt) {
        if (employerId != null) nbt.setUniqueId("EmployerId", employerId);
        if (employerReturnPos != null) {
            nbt.setInteger("EmpX", employerReturnPos.getX());
            nbt.setInteger("EmpY", employerReturnPos.getY());
            nbt.setInteger("EmpZ", employerReturnPos.getZ());
        }
        if (jobVillageCenter != null) {
            nbt.setInteger("JobVX", jobVillageCenter.getX());
            nbt.setInteger("JobVY", jobVillageCenter.getY());
            nbt.setInteger("JobVZ", jobVillageCenter.getZ());
        }
        nbt.setBoolean("JobDelivered", jobDelivered);
        nbt.setBoolean("DonationJob", donationJob);
        nbt.setBoolean("AwaitingPickup", awaitingPickup);
        nbt.setBoolean("HasUnsoldGoods", hasUnsoldGoods);
        nbt.setBoolean(EMPLOYER_ABSENT_KEY, employerAbsent);
        nbt.setBoolean("PostJobFarewellActive", farewellActive);
        nbt.setInteger("PostJobFarewellTicks", farewellTicks);
    }

    // The worker drops awaitingPickup afterwards when its restored mode is not a delivered return.
    void readNbt(NBTTagCompound nbt) {
        employerId = nbt.hasUniqueId("EmployerId") ? nbt.getUniqueId("EmployerId") : null;
        if (nbt.hasKey("EmpX")) {
            employerReturnPos = new BlockPos(
                    nbt.getInteger("EmpX"),
                    nbt.getInteger("EmpY"),
                    nbt.getInteger("EmpZ"));
        } else {
            employerReturnPos = null;
        }
        if (nbt.hasKey("JobVX")) {
            jobVillageCenter = new BlockPos(
                    nbt.getInteger("JobVX"),
                    nbt.getInteger("JobVY"),
                    nbt.getInteger("JobVZ"));
        } else {
            jobVillageCenter = null;
        }
        jobDelivered = nbt.getBoolean("JobDelivered");
        donationJob = nbt.getBoolean("DonationJob");
        awaitingPickup = nbt.getBoolean("AwaitingPickup");
        hasUnsoldGoods = nbt.getBoolean("HasUnsoldGoods");
        employerAbsent = nbt.getBoolean(EMPLOYER_ABSENT_KEY);
        farewellActive = nbt.getBoolean("PostJobFarewellActive");
        farewellTicks = nbt.getInteger("PostJobFarewellTicks");
        // If farewell isn't active, no need to keep a non-zero counter
        if (!farewellActive) {
            farewellTicks = 0;
        }
    }
}
