package com.jubitus.travellers.utils.world.pathing.offscreen;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.message.formations.FormationLeaderInfo;
import com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.*;

public class VirtualWorkerManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersVirtualWorkers";

    // Walking speed: blocks/second * seconds/tick
    private static final double VIRTUAL_SPEED_BPT = 4.3 / 20.0;

    // Ticks to advance per simulation step (run every N ticks)
    private static final int SIM_INTERVAL = 20;
    private static final double SIM_STEP = VIRTUAL_SPEED_BPT * SIM_INTERVAL;

    // After player-triggered materialization, block re-virtualization for this many ticks
    private static final int MATERIALIZE_GRACE_TICKS = 60;

    // Force-materialize when the worker arrives within this range of a job destination (blocks)
    private static final double JOB_ARRIVAL_FORCE_DIST = 48.0;

    // Max forced materializations per tick (hired workers are prioritized)
    private static final int DELIVERY_PER_TICK = 2;

    // Random spread applied at materialization to prevent collision stacking (blocks, ±radius)
    private static final double MATERIALIZE_SPREAD = 2.5;

    // How often to refresh dynamic goals (ticks)
    private static final int GOAL_REFRESH_INTERVAL = 20;

    // Check materialization every N ticks
    private static final int MATERIALIZE_CHECK_INTERVAL = 5;

    // Check virtualization every N ticks (reduced for faster dead-zone exit)
    private static final int VIRTUALIZE_CHECK_INTERVAL = 10;

    private final Map<UUID, VirtualWorkerRecord> records = new HashMap<>();
    private final Map<UUID, Long> graceUntil = new HashMap<>();

    private long lastSimTick = -1;
    private long lastMatCheckTick = -1;
    private long lastVirtCheckTick = -1;

    public VirtualWorkerManager(String name) {
        super(name);
    }

    public VirtualWorkerManager() {
        super(DATA_NAME);
    }

    public static VirtualWorkerManager get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        VirtualWorkerManager mgr = (VirtualWorkerManager) storage.getOrLoadData(VirtualWorkerManager.class, DATA_NAME);
        if (mgr == null) {
            mgr = new VirtualWorkerManager(DATA_NAME);
            storage.setData(DATA_NAME, mgr);
        }
        return mgr;
    }

    public void tick(WorldServer world, long now) {
        // Clean expired grace periods
        graceUntil.entrySet().removeIf(e -> now > e.getValue());

        // Refresh goals for dynamic targets (catchup / follow owner)
        if (now - lastGoalRefresh >= GOAL_REFRESH_INTERVAL) {
            lastGoalRefresh = now;
            refreshAllGoals(world, now);
        }

        // Advance virtual positions
        if (now - lastSimTick >= SIM_INTERVAL) {
            lastSimTick = now;
            advanceVirtual();
        }

        // Check for job arrivals (forced materialization)
        checkJobArrivals(world, now);

        // Check for player-triggered materialization
        if (now - lastMatCheckTick >= MATERIALIZE_CHECK_INTERVAL) {
            lastMatCheckTick = now;
            checkPlayerMaterialize(world, now);
        }

        // Check which real workers should become virtual
        if (now - lastVirtCheckTick >= VIRTUALIZE_CHECK_INTERVAL) {
            lastVirtCheckTick = now;
            checkVirtualize(world, now);
        }

        if (!records.isEmpty()) markDirty();
    }

    private long lastGoalRefresh = -1;

    // -------------------------------------------------------------------------
    // Movement simulation
    // -------------------------------------------------------------------------

    // Workers must travel more than this many blocks from their virtualization point
    // before we consider them to have "traveled far" (and use surface-clamped Y on spawn).
    // Keeps formation-adjustment moves (a few blocks toward the leader) from triggering the clamp.
    private static final double FAR_TRAVEL_THRESHOLD_SQ = 16.0 * 16.0;

    private void advanceVirtual() {
        for (VirtualWorkerRecord r : records.values()) {
            if (r.goal == null) continue;
            double dx = r.goal.getX() + 0.5 - r.posX;
            double dz = r.goal.getZ() + 0.5 - r.posZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.5) continue;
            double step = Math.min(SIM_STEP, dist);
            r.posX += (dx / dist) * step;
            r.posZ += (dz / dist) * step;
            if (!r.hasTraveledFar) {
                double odx = r.posX - r.virtualizedPosX;
                double odz = r.posZ - r.virtualizedPosZ;
                if (odx * odx + odz * odz >= FAR_TRAVEL_THRESHOLD_SQ) {
                    r.hasTraveledFar = true;
                }
            }
        }
    }

    private void refreshAllGoals(WorldServer world, long now) {
        for (VirtualWorkerRecord r : records.values()) {
            if (r.dimension != world.provider.getDimension()) continue;
            refreshGoal(world, r);
        }
    }

    private void refreshGoal(WorldServer world, VirtualWorkerRecord r) {
        // Escort follower: track leader (virtual or live) — highest priority after catchup
        if (r.escortLeaderUUID != null) {
            VirtualWorkerRecord leaderRec = records.get(r.escortLeaderUUID);
            if (leaderRec != null) {
                r.goal = new BlockPos((int) leaderRec.posX, (int) leaderRec.posY, (int) leaderRec.posZ);
                return;
            }
            Entity leader = world.getEntityFromUuid(r.escortLeaderUUID);
            if (leader != null && !leader.isDead) {
                r.goal = leader.getPosition();
                return;
            }
            // Leader gone: clear so we fall through to owner-follow below
        }

        // Catchup: always track live target
        if (r.isCatchupMode && r.catchupTargetUUID != null) {
            Entity target = world.getEntityFromUuid(r.catchupTargetUUID);
            if (target != null && !target.isDead) {
                r.goal = target.getPosition();
                return;
            }
        }
        // Hired worker returning from a job: track owner's current position dynamically
        // (covers both sale missions and recruit return — owner may have moved since mission start)
        if ("RETURNING".equals(r.workerMode) && r.isHiredFollower && r.followOwnerUUID != null) {
            EntityPlayer owner = world.getPlayerEntityByUUID(r.followOwnerUUID);
            if (owner != null && !owner.isDead) {
                r.goal = owner.getPosition();
            }
            return;
        }
        // Active mission or go order: goal is fixed at virtualization, do not override
        if (!"IDLE".equals(r.workerMode)) return;
        if (r.goOrderActive) return;
        // Hired follower with no active mission and follow ON: track owner position
        if (r.isHiredFollower && r.followOwnerUUID != null && r.followOrderActive) {
            EntityPlayer owner = world.getPlayerEntityByUUID(r.followOwnerUUID);
            if (owner != null && !owner.isDead) {
                r.goal = owner.getPosition();
                return;
            }
        }
        // Follow OFF or no owner found: keep goal unchanged so the worker stays put
        // Otherwise goal stays unchanged (non-hired IDLE roaming toward target village)
    }

    // -------------------------------------------------------------------------
    // Forced materialization when worker arrives at job destination
    // -------------------------------------------------------------------------

    private void checkJobArrivals(WorldServer world, long now) {
        if (records.isEmpty()) return;

        List<VirtualWorkerRecord> hiredArrivals = null;
        List<UUID> recruitArrivals = null;
        List<UUID> virtualDonations = null;
        List<UUID> getEquipArrivals = null;

        int dim = world.provider.getDimension();
        for (VirtualWorkerRecord r : records.values()) {
            if (r.dimension != dim) continue;

            // Hired workers on TO_VILLAGE missions: virtual delivery (inventory trade, no chunk load)
            if (r.isHiredFollower && r.jobGoal != null && "TO_VILLAGE".equals(r.workerMode)) {
                double dx = r.jobGoal.getX() + 0.5 - r.posX;
                double dz = r.jobGoal.getZ() + 0.5 - r.posZ;
                if (dx * dx + dz * dz <= JOB_ARRIVAL_FORCE_DIST * JOB_ARRIVAL_FORCE_DIST) {
                    if (hiredArrivals == null) hiredArrivals = new ArrayList<>();
                    hiredArrivals.add(r);
                }
                continue;
            }

            // GET_EQUIPMENT missions: virtual exchange when near barracks (no chunk loading for pathfinding)
            if (r.isHiredFollower && r.goal != null && "GET_EQUIPMENT".equals(r.workerMode)) {
                double dx = r.goal.getX() + 0.5 - r.posX;
                double dz = r.goal.getZ() + 0.5 - r.posZ;
                if (dx * dx + dz * dz <= JOB_ARRIVAL_FORCE_DIST * JOB_ARRIVAL_FORCE_DIST) {
                    if (getEquipArrivals == null) getEquipArrivals = new ArrayList<>();
                    getEquipArrivals.add(r.uuid);
                }
                continue;
            }

            // Hired workers on RECRUITING missions: collect for post-iteration processing (records.put inside)
            if (r.isHiredFollower && r.jobGoal != null && "RECRUITING".equals(r.workerMode)) {
                double dx = r.jobGoal.getX() + 0.5 - r.posX;
                double dz = r.jobGoal.getZ() + 0.5 - r.posZ;
                if (dx * dx + dz * dz <= JOB_ARRIVAL_FORCE_DIST * JOB_ARRIVAL_FORCE_DIST) {
                    if (recruitArrivals == null) recruitArrivals = new ArrayList<>();
                    recruitArrivals.add(r.uuid);
                }
                continue;
            }

            // Non-hired IDLE workers at target village: donate directly via MillWorldData, no chunk loading
            if (!r.isHiredFollower && "IDLE".equals(r.workerMode) && r.goal != null) {
                double dx = r.goal.getX() + 0.5 - r.posX;
                double dz = r.goal.getZ() + 0.5 - r.posZ;
                if (dx * dx + dz * dz <= JOB_ARRIVAL_FORCE_DIST * JOB_ARRIVAL_FORCE_DIST) {
                    if (virtualDonations == null) virtualDonations = new ArrayList<>();
                    virtualDonations.add(r.uuid);
                }
            }
        }

        // GET_EQUIPMENT arrivals: virtual exchange (force-loads only chest chunks, no entity spawn/pathfinding)
        if (getEquipArrivals != null) {
            for (UUID id : getEquipArrivals) {
                VirtualWorkerRecord r = records.get(id);
                if (r != null) tryVirtualEquipExchange(world, r);
            }
        }

        // Hired workers: virtual delivery (no chunk loading, worker stays virtual then RETURNING)
        if (hiredArrivals != null) {
            for (VirtualWorkerRecord r : hiredArrivals) {
                tryHiredVirtualDelivery(world, r);
            }
        }

        // Hired workers on RECRUITING missions: processed after iteration to avoid ConcurrentModificationException
        if (recruitArrivals != null) {
            for (UUID id : recruitArrivals) {
                VirtualWorkerRecord r = records.get(id);
                if (r != null) tryVirtualRecruitment(world, r);
            }
        }

        // Non-hired workers: virtual donation then reroute to next village, max DELIVERY_PER_TICK per tick
        if (virtualDonations != null) {
            int remaining = DELIVERY_PER_TICK;
            for (UUID id : virtualDonations) {
                if (remaining <= 0) break;
                VirtualWorkerRecord r = records.get(id);
                if (r != null) {
                    tryVirtualDonate(world, r);
                    rerouteToNextVillage(world, r);
                    remaining--;
                }
            }
        }
    }

    private void tryHiredVirtualDelivery(WorldServer world, VirtualWorkerRecord r) {
        if (r.jobGoal == null) return;

        // Build a temporary entity (not spawned) to run the delivery logic
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);

        // Run delivery (uses MillWorldData only — no chunk loading)
        worker.donateInventoryToVillage(r.jobGoal);

        // Persist updated state (empty cargo, money added, mode=RETURNING)
        NBTTagCompound updatedNbt = new NBTTagCompound();
        worker.writeToNBT(updatedNbt);
        r.entityNbt = updatedNbt;

        // Update record to RETURNING so the virtual worker heads back to the employer
        r.workerMode = "RETURNING";
        r.jobGoal = null;
        BlockPos returnGoal = worker.getVirtualGoal(); // returns employerReturnPos when RETURNING
        if (returnGoal != null) r.goal = returnGoal;

        markDirty();
    }

    private void tryVirtualRecruitment(WorldServer world, VirtualWorkerRecord r) {
        BlockPos stop = r.jobGoal;
        if (stop == null) return;

        // Load recruiter temporarily to advance recruit state
        EntityTravellerWorker recruiter = new EntityTravellerWorker(world);
        recruiter.readFromNBT(r.entityNbt);
        recruiter.setUniqueId(r.uuid);
        recruiter.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);

        int recruitCount = recruiter.advanceRecruitStopVirtually(stop);

        // Create virtual recruits with proper culture/name via onInitialSpawn
        EntityPlayer owner = r.followOwnerUUID != null
                ? world.getPlayerEntityByUUID(r.followOwnerUUID) : null;
        if (owner != null && recruitCount > 0) {
            DifficultyInstance diff = new DifficultyInstance(
                    world.getDifficulty(), world.getWorldTime(), 0, 0.5f);

            for (int i = 0; i < recruitCount; i++) {
                EntityTravellerWorker recruit = new EntityTravellerWorker(world);
                recruit.setPosition(stop.getX() + 0.5, stop.getY(), stop.getZ() + 0.5);
                recruit.onInitialSpawn(diff, null); // sets culture, name, equipment
                recruit.setHomeVillage(null);
                recruit.hireAsFollower(owner);
                recruit.setEscortLeaderUUID(r.uuid); // must be before writeToNBT so it persists

                NBTTagCompound recruitNbt = new NBTTagCompound();
                recruit.writeToNBT(recruitNbt);

                UUID recruitId = recruit.getUniqueID();
                VirtualWorkerRecord rec = new VirtualWorkerRecord(
                        recruitId, stop.getX(), stop.getY(), stop.getZ(),
                        recruit.rotationYaw, r.dimension, recruitNbt);
                rec.isHiredFollower = true;
                rec.name = recruit.getName();
                rec.ownerColor = recruit.getOwnerMarkColor() & 0xFFFFFF;
                rec.followOwnerUUID = r.followOwnerUUID;
                rec.escortLeaderUUID = r.uuid;
                rec.workerMode = "IDLE";
                rec.goal = new BlockPos((int) r.posX, (int) r.posY, (int) r.posZ);
                records.put(recruitId, rec);
            }

            owner.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "Recruited " + recruitCount + " workers at a village."));
        }

        // Save recruiter updated state
        NBTTagCompound updatedNbt = new NBTTagCompound();
        recruiter.writeToNBT(updatedNbt);
        r.entityNbt = updatedNbt;
        r.workerMode = recruiter.getMode().name();
        r.jobGoal = recruiter.getJobGoal(); // next stop, or null if returning
        BlockPos nextGoal = recruiter.getVirtualGoal();
        if (nextGoal != null) r.goal = nextGoal;

        markDirty();
    }

    private void tryVirtualEquipExchange(WorldServer world, VirtualWorkerRecord r) {
        BlockPos target = r.goal;
        if (target == null) return;

        boolean useWholeVillage = r.entityNbt.getBoolean("GetEquipWholeVillage");
        boolean stay = r.entityNbt.getBoolean("GetEquipStay");

        // Load only the specific chest chunks of the building — avoids spawning the entity
        // and avoids pathfinding failure in unloaded terrain near the barracks.
        com.jubitus.travellers.utils.world.mill.WorkerEquipExchanger.forceLoadChestChunks(world, target, useWholeVillage);

        // Temporary entity (not spawned) carries equipment state for the swap
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);

        com.jubitus.travellers.utils.world.mill.WorkerEquipExchanger.exchangeWithFollowers(worker, world, target, useWholeVillage);

        // Finalize mission state on temp entity before persisting
        worker.finishGetEquipVirtually(stay, target);
        NBTTagCompound updatedNbt = new NBTTagCompound();
        worker.writeToNBT(updatedNbt);
        r.entityNbt = updatedNbt;

        // Mirror finalized state into the virtual record
        r.workerMode = "IDLE";
        r.goOrderActive = false;
        if (stay) {
            r.followOrderActive = false;
            r.goal = target;
        } else {
            r.followOrderActive = true;
            // Seed the return goal; refreshGoal will keep it current
            if (r.followOwnerUUID != null) {
                net.minecraft.entity.player.EntityPlayer owner = world.getPlayerEntityByUUID(r.followOwnerUUID);
                if (owner != null && !owner.isDead) r.goal = owner.getPosition();
            }
        }

        markDirty();
    }

    private void tryVirtualDonate(WorldServer world, VirtualWorkerRecord r) {
        BlockPos center = r.goal;
        if (center == null) return;
        org.millenaire.common.world.MillWorldData wd =
                com.jubitus.travellers.utils.world.mill.MillVillageIndex.getWD(world);
        if (wd == null) return;
        org.millenaire.common.village.Building b =
                com.jubitus.travellers.utils.world.mill.MillVillageIndex.findBuildingForDonation(wd, center, 64);
        if (b == null) return;
        com.jubitus.travellers.utils.world.mill.TravellerMillHelper.performVirtualDonation(b);
    }

    private void rerouteToNextVillage(WorldServer world, VirtualWorkerRecord r) {
        java.util.List<BlockPos> centers =
                com.jubitus.travellers.utils.world.mill.MillVillageIndex.getAllSettlementCenters(world);
        if (centers.isEmpty()) return;

        BlockPos current = r.goal;
        // Pick a random village that is not the current one
        java.util.List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos c : centers) {
            if (current == null || c.getX() != current.getX() || c.getZ() != current.getZ()) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) return;

        BlockPos next = candidates.get(world.rand.nextInt(candidates.size()));
        r.goal = next;
        markDirty();
    }

    // -------------------------------------------------------------------------
    // Player-triggered materialization
    // -------------------------------------------------------------------------

    private void checkPlayerMaterialize(WorldServer world, long now) {
        if (records.isEmpty()) return;

        int viewChunks = world.getMinecraftServer().getPlayerList().getViewDistance();
        // Materialize one chunk inside the view distance so the player doesn't see pop-in
        double matDistBlocks = (viewChunks - 1) * 16.0;

        List<EntityPlayerMP> players = getPlayersInDim(world);
        if (players.isEmpty()) return;

        List<UUID> toMaterialize = null;

        for (VirtualWorkerRecord r : records.values()) {
            if (r.dimension != world.provider.getDimension()) continue;
            // Each follower materializes independently when it enters the player's view distance.
            // Once real, it uses tickVirtualLeaderFollow() to walk toward its virtual leader.
            for (EntityPlayerMP p : players) {
                double dx = r.posX - p.posX;
                double dz = r.posZ - p.posZ;
                if (dx * dx + dz * dz <= matDistBlocks * matDistBlocks) {
                    if (toMaterialize == null) toMaterialize = new ArrayList<>();
                    toMaterialize.add(r.uuid);
                    break;
                }
            }
        }

        if (toMaterialize != null) {
            for (UUID id : toMaterialize) {
                VirtualWorkerRecord r = records.remove(id);
                if (r != null) materializeWorker(world, r, now, false);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Virtualization of real workers
    // -------------------------------------------------------------------------

    private void checkVirtualize(WorldServer world, long now) {
        int viewChunks = world.getMinecraftServer().getPlayerList().getViewDistance();
        // Virtualize at exactly the view distance boundary so workers leaving the
        // loaded area are captured before Travel AI loses its chunks.
        double virtDistBlocks = viewChunks * 16.0;

        List<EntityPlayerMP> players = getPlayersInDim(world);

        // Snapshot entity list to avoid ConcurrentModificationException
        List<EntityTravellerWorker> workers = new ArrayList<>();
        for (Entity e : world.loadedEntityList) {
            if (e instanceof EntityTravellerWorker && !e.isDead) {
                workers.add((EntityTravellerWorker) e);
            }
        }

        for (EntityTravellerWorker worker : workers) {
            if (graceUntil.containsKey(worker.getUniqueID())) continue;
            if (!worker.canVirtualize()) continue;
            if (isPlayerNear(worker.posX, worker.posZ, players, virtDistBlocks)) continue;
            virtualizeWorker(world, worker, now);
        }
    }

    // -------------------------------------------------------------------------
    // Virtualize / Materialize
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Record → entity state reconciliation
    // -------------------------------------------------------------------------

    /**
     * After readFromNBT, the entity reflects state at virtualization time. This method
     * applies whatever the live record fields say, overriding stale NBT values for the
     * fields that commands can change while the worker is virtual (follow order, go order,
     * escort leader). Called once, immediately after readFromNBT in materializeWorker.
     */
    private static void applyRecordStateToWorker(VirtualWorkerRecord r, EntityTravellerWorker worker) {
        // Escort leader may have changed (merge command)
        if (!java.util.Objects.equals(r.escortLeaderUUID, worker.getEscortLeaderUUID())) {
            worker.setEscortLeaderUUID(r.escortLeaderUUID);
        }

        boolean recFollow = r.followOrderActive;
        boolean recGoOrder = r.goOrderActive;

        if (recFollow && !recGoOrder) {
            // Follow re-enabled (e.g., "come back"): cancel any active go order + travel route.
            if (worker.isGoOrderActive()) {
                worker.cancelGoOrderAndTravel();
            }
            if (!worker.isFollowOrderActive()) {
                worker.setFollowOrderActive(true);
            }
        } else if (recGoOrder && r.goal != null) {
            // Go order active with a potentially updated target (new destination issued while virtual).
            if (!r.goal.equals(worker.getGoOrderTargetPos())) {
                worker.startGoToDestination(r.goal, r.dimension);
            }
        } else if (!recGoOrder && worker.isGoOrderActive()) {
            // Go order was cancelled (follow is also off here, implied by previous branches).
            worker.cancelGoOrderAndTravel();
        }
    }

    private void virtualizeWorker(WorldServer world, EntityTravellerWorker worker, long now) {
        NBTTagCompound entityNbt = new NBTTagCompound();
        worker.writeToNBT(entityNbt); // full NBT: name, culture, voice, equipment, etc.

        VirtualWorkerRecord r = new VirtualWorkerRecord(
                worker.getUniqueID(),
                worker.posX, worker.posY, worker.posZ,
                worker.rotationYaw,
                world.provider.getDimension(),
                entityNbt
        );

        r.isHiredFollower = worker.isHiredFollower();
        r.name = worker.getName();
        r.ownerColor = worker.getOwnerMarkColor() & 0xFFFFFF;
        r.followOwnerUUID = worker.getFollowOwnerUUID();
        r.followOrderActive = worker.isFollowOrderActive();
        r.isCatchupMode = worker.isCatchupTravel();
        r.catchupTargetUUID = worker.getCatchupTargetUUID();
        r.workerMode = worker.getMode().name();
        r.jobGoal = worker.getJobGoal();
        r.goOrderActive = worker.isGoOrderActive();
        r.homeVillage = worker.getHomeVillage();
        r.escortLeaderUUID = worker.getEscortLeaderUUID();
        r.virtualizedTick = now;

        // Compute initial goal
        BlockPos initialGoal = worker.getVirtualGoal();
        r.goal = initialGoal;

        records.put(r.uuid, r);

        TravellerChunkManager.INSTANCE.untrackChunkloader(worker);
        worker.setDead();

        markDirty();
        // Followers are NOT force-virtualized here; each one virtualizes individually
        // once it leaves the player's view distance (via checkVirtualize).
    }

    private void materializeWorker(WorldServer world, VirtualWorkerRecord r, long now, boolean forceLoadChunk) {
        if (world.getEntityFromUuid(r.uuid) != null) return;

        // Always snap to block center: guarantees ≥0.5 blocks distance from any wall,
        // which prevents the horizontal suffocation check (±0.24 eye offset) from clipping walls.
        double spawnX = Math.floor(r.posX) + 0.5;
        double spawnZ = Math.floor(r.posZ) + 0.5;

        // Spread only for workers that have traveled far, to avoid stacking at the same block.
        // Use integer block offsets so the result is also a block center.
        // Only use spread if the target chunk is already loaded.
        if (r.hasTraveledFar) {
            int ox = (int) Math.round((world.rand.nextDouble() - 0.5) * MATERIALIZE_SPREAD * 2);
            int oz = (int) Math.round((world.rand.nextDouble() - 0.5) * MATERIALIZE_SPREAD * 2);
            double cx = Math.floor(r.posX) + ox + 0.5;
            double cz = Math.floor(r.posZ) + oz + 0.5;
            int cbx = (int) Math.floor(cx);
            int cbz = (int) Math.floor(cz);
            if (world.getChunkProvider().chunkExists(cbx >> 4, cbz >> 4)) {
                spawnX = cx;
                spawnZ = cz;
            }
        }

        int bx = (int) Math.floor(spawnX);
        int bz = (int) Math.floor(spawnZ);

        if (forceLoadChunk) {
            world.getChunkProvider().provideChunk(bx >> 4, bz >> 4);
        }

        double spawnY = r.posY;
        if (world.getChunkProvider().chunkExists(bx >> 4, bz >> 4)) {
            spawnY = findSafeFloorY(world, bx, bz, r.posY);
            spawnY = ensureNotInSolid(world, bx, spawnY, bz);
        }

        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.setPositionAndRotation(spawnX, spawnY, spawnZ, r.rotYaw, 0.0f);
        worker.motionX = 0;
        worker.motionY = 0;
        worker.motionZ = 0;

        applyRecordStateToWorker(r, worker);

        world.spawnEntity(worker);
        graceUntil.put(r.uuid, now + MATERIALIZE_GRACE_TICKS);
        markDirty();
    }

    /**
     * Finds a safe Y for the entity to stand at (bx, bz).
     * Searches outward from referenceY first — this preserves underground positions
     * (cave workers stay underground, not teleported to the surface).
     * Falls back to a top-down surface scan only if nothing is found nearby.
     */
    private static double findSafeFloorY(WorldServer world, int bx, int bz, double referenceY) {
        int refY = (int) Math.floor(referenceY);
        int maxY = world.getActualHeight() - 3;

        // Search outward from refY: down first at each offset, then up.
        // This keeps cave workers in their cave and surface workers on the surface.
        for (int offset = 0; offset <= 32; offset++) {
            int yDown = refY - offset;
            if (yDown >= 1 && isSafeFloor(world, bx, yDown, bz)) return yDown + 1.0;
            if (offset > 0) {
                int yUp = refY + offset;
                if (yUp <= maxY && isSafeFloor(world, bx, yUp, bz)) return yUp + 1.0;
            }
        }

        // Last resort: top-down scan skipping foliage (handles extreme terrain changes)
        for (int y = maxY; y > 1; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
            net.minecraft.block.Block block = state.getBlock();
            if (block instanceof net.minecraft.block.BlockLeaves) continue;
            if (block instanceof net.minecraft.block.BlockLog) continue;
            if (block.isAir(state, world, pos)) continue;
            if (isSafeFloor(world, bx, y, bz)) return y + 1.0;
        }

        return referenceY;
    }

    private static boolean isSafeFloor(WorldServer world, int bx, int y, int bz) {
        BlockPos floor = new BlockPos(bx, y, bz);
        net.minecraft.block.state.IBlockState st = world.getBlockState(floor);
        if (st.getBlock().isAir(st, world, floor)) return false;
        net.minecraft.block.state.IBlockState s1 = world.getBlockState(floor.up());
        net.minecraft.block.state.IBlockState s2 = world.getBlockState(floor.up(2));
        return s1.getBlock().isAir(s1, world, floor.up())
                && s2.getBlock().isAir(s2, world, floor.up(2));
    }

    /**
     * If spawnY lands inside a solid block (e.g. spread pushed the worker into a wall),
     * pushes Y up until two consecutive air blocks are found.
     */
    private static double ensureNotInSolid(WorldServer world, int bx, double spawnY, int bz) {
        int y = (int) Math.floor(spawnY);
        int maxY = world.getActualHeight() - 2;
        for (int i = 0; y + i <= maxY; i++) {
            BlockPos p1 = new BlockPos(bx, y + i, bz);
            BlockPos p2 = new BlockPos(bx, y + i + 1, bz);
            net.minecraft.block.state.IBlockState s1 = world.getBlockState(p1);
            net.minecraft.block.state.IBlockState s2 = world.getBlockState(p2);
            if (s1.getBlock().isAir(s1, world, p1) && s2.getBlock().isAir(s2, world, p2)) {
                return y + i;
            }
        }
        return spawnY;
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public boolean isVirtual(UUID uuid) {
        return records.containsKey(uuid);
    }

    @Nullable
    public BlockPos getVirtualPosition(UUID uuid) {
        VirtualWorkerRecord r = records.get(uuid);
        if (r == null) return null;
        return new BlockPos((int) r.posX, (int) r.posY, (int) r.posZ);
    }

    public int getTotalCount() {
        return records.size();
    }

    public int getHiredCount() {
        int n = 0;
        for (VirtualWorkerRecord r : records.values()) {
            if (r.isHiredFollower) n++;
        }
        return n;
    }

    /** Returns virtual worker block positions in the given dimension within the radius. */
    public List<BlockPos> getPositionsInRadius(int dim, int centerX, int centerZ, int radiusBlocks) {
        List<BlockPos> result = new ArrayList<>();
        int r2 = radiusBlocks * radiusBlocks;
        for (VirtualWorkerRecord rec : records.values()) {
            if (rec.dimension != dim) continue;
            int dx = (int) rec.posX - centerX;
            int dz = (int) rec.posZ - centerZ;
            if (dx * dx + dz * dz <= r2) {
                result.add(new BlockPos((int) rec.posX, (int) rec.posY, (int) rec.posZ));
            }
        }
        return result;
    }

    /** Remove the virtual record for a worker that died via other means. */
    public void removeRecord(UUID uuid) {
        if (records.remove(uuid) != null) markDirty();
    }

    public boolean isWorkerOwnedByPlayer(UUID workerUUID, UUID playerUUID) {
        VirtualWorkerRecord r = records.get(workerUUID);
        if (r == null) return false;
        return r.isHiredFollower && playerUUID.equals(r.followOwnerUUID);
    }

    public void cmdGroupComeBack(UUID leaderUUID, UUID ownerUUID) {
        for (VirtualWorkerRecord r : records.values()) {
            if (r.followOwnerUUID == null || !r.followOwnerUUID.equals(ownerUUID)) continue;
            if (leaderUUID.equals(r.escortLeaderUUID)) {
                r.escortLeaderUUID = null;
                r.followOrderActive = true;
                r.goOrderActive = false;
            }
        }
        VirtualWorkerRecord leader = records.get(leaderUUID);
        if (leader != null && ownerUUID.equals(leader.followOwnerUUID)) {
            leader.escortLeaderUUID = null;
            leader.followOrderActive = true;
            leader.goOrderActive = false;
        }
        markDirty();
    }

    public void cmdLeaderGoTo(UUID leaderUUID, UUID ownerUUID, BlockPos target) {
        VirtualWorkerRecord r = records.get(leaderUUID);
        if (r == null) return;
        if (!ownerUUID.equals(r.followOwnerUUID)) return;
        r.goOrderActive = true;
        r.followOrderActive = false;
        r.goal = target;
        markDirty();
    }

    public void cmdGroupMerge(UUID sourceLeaderUUID, UUID targetLeaderUUID, UUID ownerUUID) {
        for (VirtualWorkerRecord r : records.values()) {
            if (r.followOwnerUUID == null || !r.followOwnerUUID.equals(ownerUUID)) continue;
            if (sourceLeaderUUID.equals(r.escortLeaderUUID)) {
                r.escortLeaderUUID = targetLeaderUUID;
                r.followOrderActive = true;
            }
        }
        VirtualWorkerRecord src = records.get(sourceLeaderUUID);
        if (src != null && ownerUUID.equals(src.followOwnerUUID)) {
            src.escortLeaderUUID = targetLeaderUUID;
            src.followOrderActive = true;
            src.goOrderActive = false;
        }
        markDirty();
    }

    public int cmdMakeLeader(UUID leaderUUID, UUID ownerUUID) {
        int assigned = 0;
        for (VirtualWorkerRecord r : records.values()) {
            if (!r.isHiredFollower) continue;
            if (r.followOwnerUUID == null || !r.followOwnerUUID.equals(ownerUUID)) continue;
            if (r.uuid.equals(leaderUUID)) continue;
            if (r.escortLeaderUUID != null) continue;
            if (!r.followOrderActive) continue;
            if (!"IDLE".equals(r.workerMode)) continue;
            r.escortLeaderUUID = leaderUUID;
            assigned++;
        }
        if (assigned > 0) markDirty();
        return assigned;
    }

    public List<FormationLeaderInfo> buildLeaderList(UUID ownerUUID, double playerX, double playerZ,
                                                      WorldServer world) {
        List<FormationLeaderInfo> result = new ArrayList<>();

        java.util.Set<UUID> virtualLeaders = new java.util.HashSet<>();
        for (VirtualWorkerRecord r : records.values()) {
            if (!ownerUUID.equals(r.followOwnerUUID)) continue;
            if (r.escortLeaderUUID != null) virtualLeaders.add(r.escortLeaderUUID);
        }

        for (UUID leaderId : virtualLeaders) {
            VirtualWorkerRecord leaderRec = records.get(leaderId);
            if (leaderRec == null) continue;
            if (!ownerUUID.equals(leaderRec.followOwnerUUID)) continue;

            int count = 0;
            for (VirtualWorkerRecord r : records.values()) {
                if (ownerUUID.equals(r.followOwnerUUID) && leaderId.equals(r.escortLeaderUUID)) count++;
            }
            for (net.minecraft.entity.Entity e : world.loadedEntityList) {
                if (e instanceof EntityTravellerWorker) {
                    EntityTravellerWorker w = (EntityTravellerWorker) e;
                    if (!e.isDead && w.isHiredFollower() && ownerUUID.equals(w.getFollowOwnerId())
                            && leaderId.equals(w.getEscortLeaderUUID())) {
                        count++;
                    }
                }
            }

            String name = leaderRec.entityNbt.hasKey("CustomName")
                    ? leaderRec.entityNbt.getString("CustomName")
                    : "Henchman";
            result.add(new FormationLeaderInfo(leaderId, name, count,
                    leaderRec.posX, leaderRec.posZ, false, true));
        }

        return result;
    }

    public Iterable<VirtualWorkerRecord> getRecordsView() {
        return records.values();
    }

    @javax.annotation.Nullable
    public VirtualWorkerRecord getRecord(UUID uuid) {
        return records.get(uuid);
    }

    public java.util.Collection<VirtualWorkerRecord> getAllRecords() {
        return records.values();
    }

    /** Number of virtual workers whose home village matches the given key (Y ignored). */
    public int getCountForVillage(BlockPos key) {
        int n = 0;
        for (VirtualWorkerRecord r : records.values()) {
            if (r.homeVillage == null) continue;
            if (r.homeVillage.getX() == key.getX() && r.homeVillage.getZ() == key.getZ()) n++;
        }
        return n;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<EntityPlayerMP> getPlayersInDim(WorldServer world) {
        List<EntityPlayerMP> list = new ArrayList<>();
        for (EntityPlayerMP p : world.getMinecraftServer().getPlayerList().getPlayers()) {
            if (p.world == world) list.add(p);
        }
        return list;
    }

    private static boolean isPlayerNear(double px, double pz,
                                        List<EntityPlayerMP> players, double maxDist) {
        double maxD2 = maxDist * maxDist;
        for (EntityPlayerMP p : players) {
            double dx = px - p.posX;
            double dz = pz - p.posZ;
            if (dx * dx + dz * dz <= maxD2) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        records.clear();
        NBTTagList list = nbt.getTagList("VW", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            try {
                VirtualWorkerRecord r = VirtualWorkerRecord.fromNbt(list.getCompoundTagAt(i));
                records.put(r.uuid, r);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (VirtualWorkerRecord r : records.values()) {
            list.appendTag(r.toNbt());
        }
        nbt.setTag("VW", list);
        return nbt;
    }
}
