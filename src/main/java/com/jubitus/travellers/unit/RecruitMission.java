package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.travel.TravelCommon;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;
import org.millenaire.common.village.Building;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A hired worker's recruiting tour (GuiWorkerRecruit): an outbound tour of villages where it spawns
 * recruits escorting it, then a return leg to the owner driven by the follow AI.
 */
public final class RecruitMission {

    private static final int STOP_RADIUS = 10;
    private static final int VILLAGE_RADIUS = 24;
    private static final int STALL_TICKS = 20 * 5;
    private static final double PROGRESS_EPS_SQ = 2.0D * 2.0D;
    private static final double MOTION_EPSILON = 0.0004D;

    private final EntityTravellerWorker worker;

    private final List<BlockPos> villages = new ArrayList<>();
    // track which villages already spawned recruits (avoid double spawn)
    private final Set<BlockPos> spawnedAt = new HashSet<>();
    private int index = 0;
    private BlockPos returnPos = null;
    private boolean active = false;
    // return phase: follow owner home
    private boolean returningToOwner = false;

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
                && !this.active;
    }

    // Outbound village tour + follow-AI return leg to owner
    public boolean isOnMission() {
        return worker.mode == EntityTravellerWorker.WorkerMode.RECRUITING || this.returningToOwner;
    }

    public boolean isReturningToOwner() {
        return returningToOwner;
    }

    @Nullable
    public BlockPos getCurrentStop() {
        return currentStopPos;
    }

    // Dropped without notice (bed respawn).
    void abandon() {
        this.active = false;
        this.returningToOwner = false;
    }

    public List<BlockPos> pickVillages(EntityPlayer player) {
        World world = worker.world;
        BlockPos origin = player.getPosition();

        int maxRadius = TravellersConfig.RECRUITMENT.recruitMaxVillageRadius;
        int minN = TravellersConfig.RECRUITMENT.recruitVillagesMin;
        int maxN = TravellersConfig.RECRUITMENT.recruitVillagesMax;

        List<BlockPos> centers = MillSettlements.snapshot(world).villageCenters;
        List<BlockPos> inRange = new ArrayList<>();

        for (BlockPos c : centers) {
            int d = EntityTravellerWorker.dist2D(origin, c);

            // NEW: enforce minimum distance for recruitment
            if (d < TravellersConfig.RECRUITMENT.recruitMinVillageDistance) {
                continue;
            }

            if (d <= maxRadius) {
                inRange.add(c);
            }
        }


        inRange.sort(Comparator.comparingInt(c -> EntityTravellerWorker.dist2D(origin, c)));

        if (inRange.isEmpty()) return Collections.emptyList();

        int n = minN + worker.getRNG().nextInt(Math.max(1, (maxN - minN + 1)));
        n = Math.min(n, inRange.size());

        return new ArrayList<>(inRange.subList(0, n));
    }

    public void start(EntityPlayer player, List<BlockPos> villages, BlockPos returnPos) {
        if (worker.world.isRemote) return;
        if (villages == null || villages.isEmpty()) return;

        this.villages.clear();
        this.villages.addAll(villages);

        this.index = 0;
        this.returnPos = returnPos;
        this.active = true;
        this.spawnedAt.clear();
        this.currentStopPos = null;
        resetArrivalWatchdog();

        worker.mode = EntityTravellerWorker.WorkerMode.RECRUITING;
        worker.routeLocked = true;

        if (worker.isHiredFollower()) {
            worker.setFollowOrderActive(true);
        }

        TravelCommon tc = worker.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;

        for (BlockPos v : this.villages) {
            tc.route.add(resolveStopPos(v));
        }
        if (returnPos != null) {
            tc.route.add(returnPos);
        }

        tc.routeIndex = 0;
        setLegTarget(this.villages.get(0));
    }

    // Server tick of the outbound tour (worker in RECRUITING mode).
    void tickTour() {
        if (worker.mode != EntityTravellerWorker.WorkerMode.RECRUITING || !active) return;

        TravelCommon tc = worker.getTravelCommon();

        if (index < villages.size()) {
            BlockPos targetVillage = villages.get(index);

            if (hasReachedVillage(targetVillage)) {
                if (!spawnedAt.contains(targetVillage)) {
                    spawnedAt.add(targetVillage);
                    spawnRecruitsAtVillage(targetVillage);
                }

                index++;

                if (index < villages.size()) {
                    tc.routeIndex = index;
                    setLegTarget(villages.get(index));
                } else {
                    this.currentStopPos = null;
                    resetArrivalWatchdog();

                    beginReturnToOwner();

                    if (returnPos != null) {
                        tc.setTargetVillage(returnPos);
                        worker.getTravelCommon().setTargetVillage(returnPos);
                        tc.setCurrentWaypoint(null);
                        tc.setReadyToDepart(true);
                        tc.setDepartCooldownTicks(0);
                        worker.getNavigator().clearPath();
                    }
                }
            }

            return;
        }

        EntityPlayer owner = worker.getFollowOwner();
        if (owner != null && !owner.isDead && owner.dimension == worker.dimension) {
            double dx = worker.posX - owner.posX;
            double dz = worker.posZ - owner.posZ;
            double distHsq = dx * dx + dz * dz;

            if (distHsq <= 8.0D * 8.0D) {
                finish(owner);
            }
        } else {
            finish(null);
        }
    }

    private void spawnRecruitsAtVillage(BlockPos villageCenter) {
        if (!(worker.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) worker.world;

        EntityPlayer owner = worker.getFollowOwner();
        if (owner == null) return;

        int min = TravellersConfig.RECRUITMENT.recruitSpawnMin;
        int max = TravellersConfig.RECRUITMENT.recruitSpawnMax;
        int count = min + worker.getRNG().nextInt(Math.max(1, (max - min + 1)));

        for (int i = 0; i < count; i++) {
            BlockPos spawnPos = findSpawnPos(ws, villageCenter, worker.getRNG());
            EntityTravellerWorker w = new EntityTravellerWorker(ws);

            w.setPosition(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D);
            w.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), null);

            // IMPORTANT: no home village, so no respawn-on-death
            w.setHomeVillage(null);

            // Make them hired followers of the SAME owner, but escort THIS recruiter as leader
            w.hireAsFollower(owner);
            w.setEscortLeader(worker);

            ws.spawnEntity(w);
        }

        String villageName = resolveVillageName(villageCenter);
        NotificationService.notifyAt(owner, NotificationDefs.RECRUIT_WORKERS_RECRUITED,
                new com.jubitus.travellers.notify.NoticePlace(villageCenter, ws.provider.getDimension(), worker.getUniqueID()),
                String.valueOf(count), villageName);
    }

    /**
     * Best-effort village name lookup for player-facing recruit notifications.
     */
    private String resolveVillageName(BlockPos villageCenter) {
        Building th = MillSettlements.townHall(worker.world, villageCenter);
        return th != null ? th.getVillageQualifiedName() : "a village";
    }

    private void finish(@Nullable EntityPlayer owner) {
        this.active = false;
        this.villages.clear();
        this.spawnedAt.clear();
        this.index = 0;
        this.returnPos = null;
        this.currentStopPos = null;
        resetArrivalWatchdog();

        // Clear travel route
        TravelCommon tc = worker.getTravelCommon();
        tc.clearRoute(false);
        worker.getNavigator().clearPath();

        // Back to companion idle
        worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
        worker.routeLocked = true; // keep companion mode
        worker.setFollowOrderActive(true);

        if (owner != null) {
            NotificationService.notifyAt(owner, NotificationDefs.RECRUIT_MISSION_COMPLETED,
                    com.jubitus.travellers.notify.NoticePlace.of(worker));
        }
    }

    /**
     * Called when this worker is a temporary (non-spawned) entity loaded from virtual record NBT.
     * Marks the stop as done, advances recruit index, sets next leg or transitions to return.
     * Returns the number of recruits to create (0 if already done at this stop).
     */
    public int advanceStopVirtually(BlockPos stop) {
        if (stop == null || spawnedAt.contains(stop)) return 0;
        spawnedAt.add(stop);

        int count = TravellersConfig.RECRUITMENT.recruitSpawnMin
                + worker.getRNG().nextInt(Math.max(1, TravellersConfig.RECRUITMENT.recruitSpawnMax - TravellersConfig.RECRUITMENT.recruitSpawnMin + 1));

        index++;
        if (index < villages.size()) {
            worker.getTravelCommon().routeIndex = index;
            setLegTarget(villages.get(index));
        } else {
            // Last stop done — transition to returning via follow AI (mirrors beginReturnToOwner)
            currentStopPos = null;
            returningToOwner = true;
            worker.mode = EntityTravellerWorker.WorkerMode.RETURNING;
            worker.routeLocked = true;
            // Clear TravelAI entirely: getVirtualGoal() tracks the player's current position,
            // and once materialized the real-entity path uses follow AI (isReturnUsingFollow=true)
            TravelCommon tc = worker.getTravelCommon();
            tc.clearRoute(false);
        }
        return count;
    }

    private void beginReturnToOwner() {
        // We want Follow AI + catchupTravel to do the long-range work
        this.returningToOwner = true;

        // Make sure Follow order is ON
        if (worker.isHiredFollower()) {
            worker.setFollowOrderActive(true); // clears idleAnchorPos
        }

        // Stop Travel AI route completely
        TravelCommon tc = worker.getTravelCommon();
        tc.clearRoute(false);

        worker.getNavigator().clearPath();

        // IMPORTANT:
        // If your Follow AI is gated by "job active", we must NOT be "job active" anymore.
        // So drop to IDLE companion mode (but keep routeLocked=true so he doesn't auto-roam).
        worker.mode = EntityTravellerWorker.WorkerMode.IDLE;
        worker.routeLocked = true;

        // Also ensure we are not in catchupTravel stale state
        worker.catchup.stop();
    }

    // Server tick of the return leg to the owner.
    void tickReturnToOwner() {
        if (!returningToOwner) return;
        EntityPlayer owner = worker.getFollowOwner();
        if (owner == null || owner.isDead || owner.dimension != worker.dimension) {
            // Owner missing → abort safely
            returningToOwner = false;
            finish(null);
            return;
        }

        double dx = worker.posX - owner.posX;
        double dz = worker.posZ - owner.posZ;
        double distHsq = dx * dx + dz * dz;

        // When close enough, finish
        if (distHsq <= 8.0D * 8.0D) {
            returningToOwner = false;
            finish(owner);
        }

        // No navigation commands here:
        // Follow AI will pull him in, and your existing "dist > 200 → startOwnerCatchupTravel()"
        // will kick in automatically because mode is IDLE and followOrderActive is true.
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
        if (currentStopPos != null) {
            nbt.setInteger("RecruitStopX", currentStopPos.getX());
            nbt.setInteger("RecruitStopY", currentStopPos.getY());
            nbt.setInteger("RecruitStopZ", currentStopPos.getZ());
        }
        nbt.setInteger("RecruitArrivalWatchdogTicks", arrivalWatchdogTicks);
        nbt.setDouble("RecruitBestDistSq", bestDistSq);
        nbt.setBoolean("RecruitActive", active);
        nbt.setBoolean("RecruitReturning", returningToOwner);
        nbt.setInteger("RecruitIndex", index);
        nbt.setTag("RecruitVillages", writePositions(villages));
        nbt.setTag("RecruitSpawnedAt", writePositions(spawnedAt));
    }

    void readNbt(NBTTagCompound nbt) {
        if (nbt.hasKey("RecruitStopX")) {
            currentStopPos = new BlockPos(
                    nbt.getInteger("RecruitStopX"),
                    nbt.getInteger("RecruitStopY"),
                    nbt.getInteger("RecruitStopZ")
            );
        } else {
            currentStopPos = null;
        }

        arrivalWatchdogTicks = nbt.getInteger("RecruitArrivalWatchdogTicks");
        bestDistSq = nbt.hasKey("RecruitBestDistSq") ? nbt.getDouble("RecruitBestDistSq") : Double.MAX_VALUE;

        active = nbt.getBoolean("RecruitActive");
        returningToOwner = nbt.getBoolean("RecruitReturning");
        index = nbt.getInteger("RecruitIndex");

        villages.clear();
        readPositions(nbt, "RecruitVillages", villages);
        spawnedAt.clear();
        readPositions(nbt, "RecruitSpawnedAt", spawnedAt);
    }

    private static NBTTagList writePositions(Collection<BlockPos> positions) {
        NBTTagList list = new NBTTagList();
        for (BlockPos p : positions) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", p.getX());
            c.setInteger("y", p.getY());
            c.setInteger("z", p.getZ());
            list.appendTag(c);
        }
        return list;
    }

    private static void readPositions(NBTTagCompound nbt, String key, Collection<BlockPos> into) {
        if (!nbt.hasKey(key, Constants.NBT.TAG_LIST)) return;
        NBTTagList list = nbt.getTagList(key, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            into.add(new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z")));
        }
    }
}
