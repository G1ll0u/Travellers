package com.jubitus.travellers.utils.world.pathing.offscreen;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIAutoEat;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.message.formations.FormationLeaderInfo;
import com.jubitus.travellers.utils.world.MPBIntegration;
import com.jubitus.travellers.utils.world.camp.CampOccupant;
import com.jubitus.travellers.utils.world.camp.CampsiteManager;
import com.jubitus.travellers.utils.world.camp.CampsiteRecord;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.*;

public class VirtualTravellerManager extends WorldSavedData {

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

    // How often to refresh dynamic goals (ticks)
    private static final int GOAL_REFRESH_INTERVAL = 20;

    // Check materialization every N ticks
    private static final int MATERIALIZE_CHECK_INTERVAL = 5;

    // Check virtualization every N ticks (reduced for faster dead-zone exit)
    private static final int VIRTUALIZE_CHECK_INTERVAL = 10;

    private final Map<UUID, VirtualTravellerRecord> records = new HashMap<>();
    private final Map<UUID, Long> graceUntil = new HashMap<>();

    private long lastSimTick = -1;
    private long lastMatCheckTick = -1;
    private long lastVirtCheckTick = -1;

    public VirtualTravellerManager(String name) {
        super(name);
    }

    public VirtualTravellerManager() {
        super(DATA_NAME);
    }

    public static VirtualTravellerManager get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        VirtualTravellerManager mgr = (VirtualTravellerManager) storage.getOrLoadData(VirtualTravellerManager.class, DATA_NAME);
        if (mgr == null) {
            mgr = new VirtualTravellerManager(DATA_NAME);
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

    // Blend factor for smoothing a virtual entity's facing toward its movement direction each sim
    // step. Purely cosmetic (only visible via a materialized entity's initial facing), but without
    // it a leader's rotYaw stays frozen at whatever it was at virtualization until the next
    // waypoint snaps it - a worker-formation leader's own jittery heading would otherwise feed
    // straight into VirtualFormationSimulator's follower offsets.
    private static final float YAW_SMOOTHING_BLEND = 0.35F;

    private void advanceVirtual() {
        for (VirtualTravellerRecord r : records.values()) {
            if (r.battleId != null) continue; // battle sim owns movement
            if (r.goal == null) continue;
            double dx = r.goal.getX() + 0.5 - r.posX;
            double dz = r.goal.getZ() + 0.5 - r.posZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.5) continue;
            double step = Math.min(SIM_STEP, dist);
            r.posX += (dx / dist) * step;
            r.posZ += (dz / dist) * step;
            float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            r.rotYaw = blendYawTowards(r.rotYaw, targetYaw, YAW_SMOOTHING_BLEND);
            if (!r.hasTraveledFar) {
                double odx = r.posX - r.virtualizedPosX;
                double odz = r.posZ - r.virtualizedPosZ;
                if (odx * odx + odz * odz >= FAR_TRAVEL_THRESHOLD_SQ) {
                    r.hasTraveledFar = true;
                }
            }
        }
    }

    /** Blends {@code from} toward {@code to} by {@code factor}, taking the shortest angular path. */
    private static float blendYawTowards(float from, float to, float factor) {
        float delta = net.minecraft.util.math.MathHelper.wrapDegrees(to - from);
        return from + delta * factor;
    }

    private void refreshAllGoals(WorldServer world, long now) {
        for (VirtualTravellerRecord r : records.values()) {
            if (r.dimension != world.provider.getDimension()) continue;
            simulateHealing(r, now);
            if (r.battleId != null) continue; // travel/camp goals suspended while fighting
            refreshGoal(world, r, now);
        }
    }

    /**
     * Offscreen counterpart to {@link com.jubitus.travellers.entity.entityAI.ambient.EntityAIAutoEat}:
     * gradually heals a virtual record using the same threshold/lockout knobs as the physical AI, so a
     * traveller/worker that fought (and survived) a virtual battle recovers HP while offscreen instead
     * of staying wounded until a player happens to materialize it. Raiders are excluded (they don't
     * auto-eat physically either — see EntityTravellerRaider.initEntityAI). Called every
     * GOAL_REFRESH_INTERVAL (20 ticks), matching this method's own cadence.
     */
    private void simulateHealing(VirtualTravellerRecord r, long now) {
        if (!TravellersConfig.BATTLE_EVENTS.virtualCombatHealingEnabled) return;
        if ("RAIDER".equals(r.entityClass)) return;
        if (r.battleId != null) return; // still fighting: physical AI blocks eating during combat too
        if (r.entityNbt == null) return;

        int lastCombatTick = r.entityNbt.getInteger("LastCombatTick");
        if (now - lastCombatTick < TravellersConfig.BATTLE_EVENTS.postCombatHealDelayTicks) return;

        float max = r.getCombatProfile().maxHealth;
        float hp = r.getHealthResolved();
        if (hp >= max - 0.001F) {
            r.recoveringToFull = false;
            return;
        }
        if (!r.recoveringToFull && hp > max * TravellersConfig.COMBAT.eatHealthThreshold) return;

        r.recoveringToFull = true;
        float healed = Math.min(max, hp + EntityAIAutoEat.AVG_HEAL_PER_TICK * GOAL_REFRESH_INTERVAL);
        r.health = healed;
        r.entityNbt.setFloat("Health", healed);
        if (healed >= max - 0.001F) r.recoveringToFull = false;
    }

    /**
     * Returns true if the record is camping (halted at its slot) this tick. Also self-heals: if the
     * occupant is gone (departed/evicted), clears the cache and returns false so travel resumes.
     */
    private boolean handleCamp(WorldServer world, VirtualTravellerRecord r) {
        CampsiteManager cm = CampsiteManager.get(world);
        if (r.campSiteId != null) {
            CampsiteRecord camp = cm.getById(r.campSiteId);
            CampOccupant occ = camp != null ? camp.occupants.get(r.campSlotIndex) : null;
            if (occ == null || !occ.travellerUUID.equals(r.uuid)) {
                r.campSiteId = null;
                r.campSlotIndex = -1;
                r.campCooldownUntil = world.getTotalWorldTime() + 1200L; // ~1 min before camping again
                return false; // resume normal routing
            }
            BlockPos sp = cm.slotStandPos(r.campSiteId, r.campSlotIndex);
            if (sp != null) {
                r.posX = sp.getX() + 0.5;
                r.posY = sp.getY();
                r.posZ = sp.getZ() + 0.5;
                r.goal = sp;
            }
            return true;
        }
        return tryCampClaim(world, r, cm);
    }

    /** Attempts to claim a campsite for a passing traveller/raider. Groups join their leader's camp. */
    private boolean tryCampClaim(WorldServer world, VirtualTravellerRecord r, CampsiteManager cm) {
        if (!TravellersConfig.CAMP_EVENTS.enabled) return false;
        if (world.getTotalWorldTime() < r.campCooldownUntil) return false; // just left a camp

        String culture = r.entityNbt != null ? r.entityNbt.getString("CultureId") : "";

        CampsiteRecord camp;
        if (r.groupLeaderUUID != null) {
            // Follower: only camp if the leader is already camping, and join that exact site.
            VirtualTravellerRecord leader = records.get(r.groupLeaderUUID);
            if (leader == null || leader.campSiteId == null) return false;
            camp = cm.getById(leader.campSiteId);
            if (camp == null || !camp.canClaim(r.entityClass, culture)) return false;
        } else {
            camp = cm.findClaimable(r.dimension, r.posX, r.posZ, r.entityClass, culture,
                    TravellersConfig.CAMP_EVENTS.campSeekRadius);
            if (camp == null) return false;
            // Night: always stop. Day: roll the configured chance. Followers skip this (they follow the leader).
            if (!CampsiteManager.isNight(world)
                    && world.rand.nextDouble() >= TravellersConfig.CAMP_EVENTS.daytimeStopChance) {
                return false;
            }
        }
        long now = world.getTotalWorldTime();
        // Followers use the leader's departTick so the whole group leaves together.
        long depart = CampsiteManager.computeDepartTick(world, now);
        if (r.groupLeaderUUID != null) {
            for (com.jubitus.travellers.utils.world.camp.CampOccupant o : camp.occupants.values()) {
                if (o.travellerUUID.equals(r.groupLeaderUUID)) { depart = o.departTick; break; }
            }
        }
        int slot = cm.claim(camp, r.uuid, r.entityClass, culture, now, depart);
        if (slot < 0) return false;

        r.campSiteId = camp.id;
        r.campSlotIndex = slot;
        BlockPos sp = cm.slotStandPos(camp.id, slot);
        if (sp != null) {
            r.posX = sp.getX() + 0.5;
            r.posY = sp.getY();
            r.posZ = sp.getZ() + 0.5;
            r.goal = sp;
        }
        return true;
    }

    private void refreshGoal(WorldServer world, VirtualTravellerRecord r, long now) {
        // Non-worker travellers/raiders: advance through route, replan at end
        if (!"WORKER".equals(r.entityClass)) {
            // Camp lifecycle takes priority: halt at the slot while camping, or claim a passing campsite.
            if (handleCamp(world, r)) return;
            // Group follower: track leader's virtual position, offset by the same deterministic
            // ring the materializer uses, so the group marches as a loose pack (not a stack).
            if (r.groupLeaderUUID != null) {
                VirtualTravellerRecord leaderRec = records.get(r.groupLeaderUUID);
                if (leaderRec != null) {
                    int[] off = MaterializePositionResolver.followerSpreadOffset(r.uuid);
                    r.goal = new BlockPos((int) leaderRec.posX + off[0], (int) leaderRec.posY,
                            (int) leaderRec.posZ + off[1]);
                } else {
                    // Leader materialized: followers keep their current goal until they also materialize
                    Entity liveLeader = world.getEntityFromUuid(r.groupLeaderUUID);
                    if (liveLeader != null && !liveLeader.isDead) {
                        r.goal = liveLeader.getPosition();
                    }
                }
                return;
            }
            // Leader or solo: advance through route, replan at end
            if (r.goal != null) {
                double dx = r.goal.getX() + 0.5 - r.posX;
                double dz = r.goal.getZ() + 0.5 - r.posZ;
                if (dx * dx + dz * dz < SIM_STEP * SIM_STEP) {
                    r.routeIndex++;
                }
            }
            if (r.routeIndex < 0 || r.routeIndex >= r.travelRoute.size()) {
                replannTravellerRoute(world, r);
                return;
            }
            r.goal = r.travelRoute.get(r.routeIndex);
            return;
        }

        // Non-hired, idle workers may camp exactly like travellers (join/found a civilian camp).
        // Hired or actively-working workers (mid-mission/escort) never stop to camp.
        if (TravellersConfig.CAMP_EVENTS.workersCanCamp && !r.isHiredFollower
                && "IDLE".equals(r.workerMode) && handleCamp(world, r)) return;

        // Escort follower: track leader (virtual or live) — highest priority after catchup
        if (r.escortLeaderUUID != null) {
            VirtualTravellerRecord leaderRec = records.get(r.escortLeaderUUID);
            if (leaderRec != null) {
                // Idle/anchored leader: keep followers stationary at their own virtualized positions.
                if (!leaderRec.followOrderActive && !leaderRec.goOrderActive) {
                    r.goal = null;
                    return;
                }
                // Formation-shaped marching: a live, heading-aware slot from the shared geometry
                // (same math the loaded FollowFormationCoordinator path uses), so the group keeps
                // its shape and actually walks toward a settings change instead of snapping to it.
                VirtualFormationSimulator.applyFormationGoal(world, records, r, r.escortLeaderUUID,
                        leaderRec.posX, leaderRec.posY, leaderRec.posZ, true, now);
                return;
            }
            Entity leader = world.getEntityFromUuid(r.escortLeaderUUID);
            if (leader instanceof com.jubitus.travellers.entity.travellers.EntityTravellerWorker) {
                com.jubitus.travellers.entity.travellers.EntityTravellerWorker liveLeader =
                        (com.jubitus.travellers.entity.travellers.EntityTravellerWorker) leader;
                if (!liveLeader.isFollowOrderActive() && !liveLeader.isGoOrderActive()) {
                    r.goal = null;
                    return;
                }
            }
            if (leader != null && !leader.isDead) {
                VirtualFormationSimulator.applyFormationGoal(world, records, r, r.escortLeaderUUID,
                        leader.posX, leader.posY, leader.posZ, true, now);
                return;
            }
            // Leader gone: clear so we fall through to owner-follow below
            r.escortLeaderUUID = null;
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
        // Hired follower with no active mission and follow ON: hold a live formation slot
        // relative to the owner, same shared geometry as the escort-leader case above.
        if (r.isHiredFollower && r.followOwnerUUID != null && r.followOrderActive) {
            EntityPlayer owner = world.getPlayerEntityByUUID(r.followOwnerUUID);
            if (owner != null && !owner.isDead) {
                VirtualFormationSimulator.applyFormationGoal(world, records, r, r.followOwnerUUID,
                        owner.posX, owner.posY, owner.posZ, false, now);
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

        List<VirtualTravellerRecord> hiredArrivals = null;
        List<UUID> recruitArrivals = null;
        List<UUID> virtualDonations = null;
        List<UUID> getEquipArrivals = null;

        int dim = world.provider.getDimension();
        for (VirtualTravellerRecord r : records.values()) {
            if (r.dimension != dim) continue;
            if (r.battleId != null) continue; // no job arrivals mid-battle

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
            if ("WORKER".equals(r.entityClass) && !r.isHiredFollower && "IDLE".equals(r.workerMode)
                    && r.goal != null && (now - r.lastDonationTick) > 400) {
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
                VirtualTravellerRecord r = records.get(id);
                if (r != null) tryVirtualEquipExchange(world, r);
            }
        }

        // Hired workers: virtual delivery (no chunk loading, worker stays virtual then RETURNING)
        if (hiredArrivals != null) {
            for (VirtualTravellerRecord r : hiredArrivals) {
                tryHiredVirtualDelivery(world, r);
            }
        }

        // Hired workers on RECRUITING missions: processed after iteration to avoid ConcurrentModificationException
        if (recruitArrivals != null) {
            for (UUID id : recruitArrivals) {
                VirtualTravellerRecord r = records.get(id);
                if (r != null) tryVirtualRecruitment(world, r);
            }
        }

        // Non-hired workers: virtual donation then reroute to next village, max DELIVERY_PER_TICK per tick
        if (virtualDonations != null) {
            int remaining = DELIVERY_PER_TICK;
            for (UUID id : virtualDonations) {
                if (remaining <= 0) break;
                VirtualTravellerRecord r = records.get(id);
                if (r != null) {
                    tryVirtualDonate(world, r);
                    r.lastDonationTick = now;
                    rerouteToNextVillage(world, r);
                    remaining--;
                }
            }
        }
    }

    private void tryHiredVirtualDelivery(WorldServer world, VirtualTravellerRecord r) {
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
        r.invalidateNbtCaches();

        // Update record to RETURNING so the virtual worker heads back to the employer
        r.workerMode = "RETURNING";
        r.jobGoal = null;
        BlockPos returnGoal = worker.getVirtualGoal(); // returns employerReturnPos when RETURNING
        if (returnGoal != null) r.goal = returnGoal;

        markDirty();
    }

    private void tryVirtualRecruitment(WorldServer world, VirtualTravellerRecord r) {
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
                VirtualTravellerRecord rec = new VirtualTravellerRecord(
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

            org.millenaire.common.world.MillWorldData recruitWd = MillVillageIndex.getWD(world);
            org.millenaire.common.village.Building recruitTh = recruitWd != null
                    ? MillVillageIndex.getTownHallBuilding(recruitWd, stop) : null;
            String villageName = recruitTh != null ? recruitTh.getVillageQualifiedName() : "a village";
            com.jubitus.travellers.notify.NotificationService.notify(owner,
                    com.jubitus.travellers.notify.NotificationDefs.RECRUIT_WORKERS_RECRUITED,
                    String.valueOf(recruitCount), villageName);
        }

        // Save recruiter updated state
        NBTTagCompound updatedNbt = new NBTTagCompound();
        recruiter.writeToNBT(updatedNbt);
        r.entityNbt = updatedNbt;
        r.invalidateNbtCaches();
        r.workerMode = recruiter.getMode().name();
        r.jobGoal = recruiter.getJobGoal(); // next stop, or null if returning
        BlockPos nextGoal = recruiter.getVirtualGoal();
        if (nextGoal != null) r.goal = nextGoal;

        markDirty();
    }

    private void tryVirtualEquipExchange(WorldServer world, VirtualTravellerRecord r) {
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
        r.invalidateNbtCaches();

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

    private void tryVirtualDonate(WorldServer world, VirtualTravellerRecord r) {
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

    private void rerouteToNextVillage(WorldServer world, VirtualTravellerRecord r) {
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

    /** Picks a new destination and builds a route for a virtual traveller/raider. */
    private void replannTravellerRoute(WorldServer world, VirtualTravellerRecord r) {
        List<BlockPos> centers = MillVillageIndex.getAllSettlementCenters(world);
        if (centers.isEmpty()) return;

        BlockPos current = r.goal;
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos c : centers) {
            if (current == null || c.getX() != current.getX() || c.getZ() != current.getZ())
                candidates.add(c);
        }
        if (candidates.isEmpty()) candidates = centers;

        BlockPos dest = candidates.get(world.rand.nextInt(candidates.size()));

        r.travelRoute.clear();
        boolean useRoads = MPBIntegration.PRESENT
                && (!"RAIDER".equals(r.entityClass)
                    || TravellersConfig.SPAWN_RAIDERS.raidersUseUltreiaPaths);
        if (useRoads) {
            List<BlockPos> mpbRoute = MPBIntegration.findRoadRoute(
                    world, (int) r.posX, (int) r.posZ, dest.getX(), dest.getZ(), 48);
            if (mpbRoute != null && !mpbRoute.isEmpty()) {
                r.travelRoute.addAll(mpbRoute);
            }
        }
        if (r.travelRoute.isEmpty()
                || (r.travelRoute.get(r.travelRoute.size() - 1).getX() != dest.getX()
                    || r.travelRoute.get(r.travelRoute.size() - 1).getZ() != dest.getZ())) {
            r.travelRoute.add(dest);
        }
        r.routeIndex = 0;
        r.goal = r.travelRoute.get(0);
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

        for (VirtualTravellerRecord r : records.values()) {
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
                VirtualTravellerRecord r = records.remove(id);
                if (r != null) materializeEntity(world, r, now, false);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Virtualization of real workers
    // -------------------------------------------------------------------------

    private void checkVirtualize(WorldServer world, long now) {
        int viewChunks = world.getMinecraftServer().getPlayerList().getViewDistance();
        double virtDistBlocks = viewChunks * 16.0;

        List<EntityPlayerMP> players = getPlayersInDim(world);

        // Snapshot entity list to avoid ConcurrentModificationException
        List<EntityTravellerWorker> workers = new ArrayList<>();
        List<EntityTraveller> travellers = new ArrayList<>();
        for (Entity e : world.loadedEntityList) {
            if (e.isDead) continue;
            if (e instanceof EntityTravellerWorker) workers.add((EntityTravellerWorker) e);
            else if (e instanceof EntityTraveller) travellers.add((EntityTraveller) e);
        }

        com.jubitus.travellers.utils.world.battle.VirtualBattleManager battleMgr =
                com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get(world);

        for (EntityTravellerWorker worker : workers) {
            if (graceUntil.containsKey(worker.getUniqueID())) continue;
            if (!canVirtualizeMaybeBattling(worker, battleMgr)) continue;
            if (isPlayerNear(worker.posX, worker.posZ, players, virtDistBlocks)) continue;
            virtualizeWorker(world, worker, now);
        }

        for (EntityTraveller entity : travellers) {
            if (graceUntil.containsKey(entity.getUniqueID())) continue;
            // Unlike workers, travellers/raiders can proximity-acquire a passive player as attackTarget
            // (RaiderAttackTargetAI) and stay "in combat" (travellerNoAggroOffscreen) for as long as that
            // player is nearby. Distance-based virtualization must ignore that state entirely, or a raider
            // idling at the view-distance edge near a stationary player would never leave the world.
            if (!entity.canVirtualizeIgnoringCombat()) continue;
            if (isPlayerNear(entity.posX, entity.posZ, players, virtDistBlocks)) continue;
            virtualizeTraveller(world, entity, now);
        }
    }

    /** Battle participants may re-virtualize mid-combat (the sim takes over); others keep the combat gate. */
    private static boolean canVirtualizeMaybeBattling(
            EntityTraveller entity, com.jubitus.travellers.utils.world.battle.VirtualBattleManager battleMgr) {
        if (entity.canVirtualize()) return true;
        return battleMgr.isParticipant(entity.getUniqueID()) && entity.canVirtualizeIgnoringCombat();
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
    private static void applyRecordStateToWorker(VirtualTravellerRecord r, EntityTravellerWorker worker) {
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

        VirtualTravellerRecord r = new VirtualTravellerRecord(
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

        // Seed the persistent formation slot from the loaded coordinator's current ordinal (if any)
        // so VirtualFormationSimulator keeps the worker in the same slot instead of appending it
        // at the back of the line the moment it goes virtual.
        if (r.escortLeaderUUID != null && r.formationOrdinal < 0) {
            r.formationOrdinal = com.jubitus.travellers.entity.entityAI.follow.FollowFormationCoordinator
                    .snapshotOrdinal(r.escortLeaderUUID, world.provider.getDimension(), worker.getUniqueID());
        }

        // Compute initial goal
        BlockPos initialGoal = worker.getVirtualGoal();
        r.goal = initialGoal;
        r.health = worker.getHealth();

        // Non-hired workers camp exactly like travellers; re-derive occupation from the campsite.
        if (TravellersConfig.CAMP_EVENTS.workersCanCamp && !r.isHiredFollower) {
            deriveCampOccupation(world, worker.getUniqueID(), now, r);
        }

        records.put(r.uuid, r);
        com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get(world)
                .restoreBattleIdIfParticipant(r);

        TravellerChunkManager.INSTANCE.untrackChunkloader(worker);
        worker.setDead();

        markDirty();
        // Followers are NOT force-virtualized here; each one virtualizes individually
        // once it leaves the player's view distance (via checkVirtualize).
    }

    /**
     * Re-derives {@code r.campSiteId}/{@code campSlotIndex} from the campsite occupant (the source of
     * truth) at virtualize time, and resets the occupant's virtual build clock so loaded time spent
     * pacing/fighting between block placements doesn't retroactively convert into virtual progress.
     * Shared by traveller/raider and (non-hired, idle) worker virtualization.
     */
    private void deriveCampOccupation(WorldServer world, UUID uuid, long now, VirtualTravellerRecord r) {
        CampsiteRecord camp = CampsiteManager.get(world).campOf(uuid);
        if (camp == null) return;
        for (Map.Entry<Integer, CampOccupant> e : camp.occupants.entrySet()) {
            if (e.getValue().travellerUUID.equals(uuid)) {
                r.campSiteId = camp.id;
                r.campSlotIndex = e.getKey();
                CampOccupant occ = e.getValue();
                if (occ.buildState != CampOccupant.BuildState.BUILT) {
                    occ.lastProgressTick = now;
                }
                break;
            }
        }
    }

    /** Virtualizes a non-worker EntityTraveller (traveller or raider). */
    public void virtualizeTraveller(WorldServer world, EntityTraveller entity, long now) {
        if (records.containsKey(entity.getUniqueID())) return;

        NBTTagCompound entityNbt = new NBTTagCompound();
        entity.writeToNBT(entityNbt);

        VirtualTravellerRecord r = new VirtualTravellerRecord(
                entity.getUniqueID(),
                entity.posX, entity.posY, entity.posZ,
                entity.rotationYaw,
                world.provider.getDimension(),
                entityNbt
        );

        r.entityClass = entity instanceof EntityTravellerRaider ? "RAIDER" : "TRAVELLER";
        r.groupLeaderUUID = entity.getGroupLeaderUUID();

        deriveCampOccupation(world, entity.getUniqueID(), now, r);

        // Assign home village so runVirtualPopulationTopUp doesn't over-spawn for this village.
        // Use the entity's origin village if available, otherwise the nearest known village.
        com.jubitus.travellers.utils.world.mill.MillVillageIndex.VillageInfo nearest =
                com.jubitus.travellers.utils.world.mill.MillVillageIndex.findNearest2D(
                        world, entity.getPosition(), 3000);
        if (nearest != null) {
            r.homeVillage = new BlockPos(nearest.center.getX(), 0, nearest.center.getZ());
        }

        // Capture active travel route from TravelCommon
        List<BlockPos> liveRoute = entity.getTravelRoute();
        int liveIndex = entity.getVillageRouteIndex();
        if (!liveRoute.isEmpty() && liveIndex >= 0 && liveIndex < liveRoute.size()) {
            r.travelRoute = new ArrayList<>(liveRoute);
            r.routeIndex = liveIndex;
            r.goal = liveRoute.get(liveIndex);
        } else if (entity.getTargetVillage() != null) {
            // No multi-stop route, just a single destination
            r.travelRoute = new ArrayList<>();
            r.travelRoute.add(entity.getTargetVillage());
            r.routeIndex = 0;
            r.goal = entity.getTargetVillage();
        }

        r.virtualizedTick = now;
        r.health = entity.getHealth();

        records.put(r.uuid, r);
        com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get(world)
                .restoreBattleIdIfParticipant(r);
        entity.setDead();
        markDirty();
    }

    private void materializeWorker(WorldServer world, VirtualTravellerRecord r, long now, boolean forceLoadChunk) {
        materializeEntity(world, r, now, forceLoadChunk);
    }

    /** Pairing of the raw camp slot (or null if not camping) and the resolved safe spawn position. */
    private static final class CampSpawnResult {
        @Nullable final BlockPos campStand;
        final MaterializePositionResolver.ResolvedPosition pos;

        CampSpawnResult(@Nullable BlockPos campStand, MaterializePositionResolver.ResolvedPosition pos) {
            this.campStand = campStand;
            this.pos = pos;
        }
    }

    /**
     * Camp-aware spawn positioning shared by traveller/raider/worker materialize: places a camping
     * entity inside its slot on a real floor (entrance point if the shelter already exists, to avoid
     * spawning inside a wall) rather than the raw virtual position. Returns {@code fallback} unchanged
     * when the record isn't camping.
     */
    private CampSpawnResult resolveCampSpawnPos(WorldServer world, VirtualTravellerRecord r,
                                                 MaterializePositionResolver.ResolvedPosition fallback) {
        if (r.campSiteId == null) return new CampSpawnResult(null, fallback);
        CampsiteManager campMgr = CampsiteManager.get(world);
        BlockPos campStand = campMgr.slotStandPos(r.campSiteId, r.campSlotIndex);
        if (campStand == null) return new CampSpawnResult(null, fallback);

        CampsiteRecord campRec = campMgr.getById(r.campSiteId);
        CampOccupant occ = campRec != null ? campRec.occupants.get(r.campSlotIndex) : null;
        BlockPos spawnPos = (occ != null && occ.hasAnyStructure())
                ? campMgr.slotApproachPos(r.campSiteId, r.campSlotIndex)
                : campStand;
        if (spawnPos == null) spawnPos = campStand;
        double cy = MaterializePositionResolver.findSafeFloorY(
                world, spawnPos.getX(), spawnPos.getZ(), spawnPos.getY());
        cy = MaterializePositionResolver.ensureNotInSolid(world, spawnPos.getX(), cy, spawnPos.getZ());
        return new CampSpawnResult(campStand,
                new MaterializePositionResolver.ResolvedPosition(spawnPos.getX() + 0.5, cy, spawnPos.getZ() + 0.5));
    }

    private void materializeEntity(WorldServer world, VirtualTravellerRecord r, long now, boolean forceLoadChunk) {
        if (world.getEntityFromUuid(r.uuid) != null) return;

        MaterializePositionResolver.ResolvedPosition pos =
                MaterializePositionResolver.resolve(world, r, records);

        int bx = (int) Math.floor(pos.x);
        int bz = (int) Math.floor(pos.z);
        if (forceLoadChunk) {
            world.getChunkProvider().provideChunk(bx >> 4, bz >> 4);
        }

        // Health may have changed virtually (battle sim) — sync into the frozen NBT before restore
        if (r.health >= 0f && r.entityNbt != null) {
            r.entityNbt.setFloat("Health", r.health);
        }

        if ("TRAVELLER".equals(r.entityClass) || "RAIDER".equals(r.entityClass)) {
            // Camping entity: spawn inside its slot on a real floor (never legs-in-ground or floating).
            CampSpawnResult campSpawn = resolveCampSpawnPos(world, r, pos);
            BlockPos campStand = campSpawn.campStand;
            pos = campSpawn.pos;

            // For travellers on an MPB route, snap spawn position to the nearest road point.
            // This makes the entity appear on the road it was virtually walking, rather than
            // slightly beside it (virtual movement interpolates linearly between road waypoints).
            // Raiders are excluded because they don't use roads.
            if (campStand == null
                    && "TRAVELLER".equals(r.entityClass)
                    && MPBIntegration.PRESENT
                    && !r.travelRoute.isEmpty()) {
                BlockPos roadPos = MPBIntegration.findNearestRoadPos(world, bx, bz, 24);
                if (roadPos != null && world.getChunkProvider().chunkExists(roadPos.getX() >> 4, roadPos.getZ() >> 4)) {
                    double roadY = MaterializePositionResolver.findSafeFloorY(
                            world, roadPos.getX(), roadPos.getZ(), pos.y);
                    roadY = MaterializePositionResolver.ensureNotInSolid(
                            world, roadPos.getX(), roadY, roadPos.getZ());
                    pos = new MaterializePositionResolver.ResolvedPosition(roadPos.getX() + 0.5, roadY, roadPos.getZ() + 0.5);
                }
            }

            EntityTraveller entity = "RAIDER".equals(r.entityClass)
                    ? new EntityTravellerRaider(world)
                    : new EntityTraveller(world);
            entity.readFromNBT(r.entityNbt);
            entity.setUniqueId(r.uuid);
            entity.setPositionAndRotation(pos.x, pos.y, pos.z, r.rotYaw, 0.0f);
            entity.motionX = 0;
            entity.motionY = 0;
            entity.motionZ = 0;
            // Unconditional: entityNbt may still carry a stale (e.g. dead-leader) UUID that r.groupLeaderUUID
            // has since cleared (see VirtualBattleManager.endBattle) — always overwrite, null included.
            entity.setGroupLeaderUUID(r.groupLeaderUUID);
            if (campStand != null) {
                // Camping: stay put at the slot. Real camp behaviour (build/sleep/wander) is AI (P4).
                entity.setReadyToDepart(false);
                entity.setVillageIdling(true);
                entity.setVillageIdleTicks(0);
                // Sleep is a purely-loaded cosmetic pose, never simulated virtually or persisted to
                // NBT — but force it off anyway so a fresh materialize can never render "lying down"
                // while EntityAICampStay (re-evaluating from scratch) thinks the entity is standing.
                entity.wakeUpTraveller();
            } else if (!r.travelRoute.isEmpty()) {
                // Restore only the final destination — EntityAITravel + MPBRouteTracker replans
                // a fresh road route from the spawn point. Passing the full MPB waypoint list
                // would cause readyToDepart to toggle on a road waypoint (not a village), breaking Roam AI.
                BlockPos dest = r.travelRoute.get(r.travelRoute.size() - 1);
                entity.restoreVirtualRoute(java.util.Collections.singletonList(dest), 0);
            } else {
                entity.setReadyToDepart(true);
                entity.setVillageIdleTicks(0);
                entity.setVillageIdling(false);
            }
            world.spawnEntity(entity);
            if (r.battleId != null) {
                com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get(world)
                        .onParticipantMaterialized(world, r.uuid, entity);
            }
        } else {
            // Non-hired workers camp like travellers; hired/working ones never have a campSiteId set.
            CampSpawnResult campSpawn = resolveCampSpawnPos(world, r, pos);
            pos = campSpawn.pos;

            EntityTravellerWorker worker = new EntityTravellerWorker(world);
            worker.readFromNBT(r.entityNbt);
            worker.setUniqueId(r.uuid);
            worker.setPositionAndRotation(pos.x, pos.y, pos.z, r.rotYaw, 0.0f);
            worker.motionX = 0;
            worker.motionY = 0;
            worker.motionZ = 0;
            applyRecordStateToWorker(r, worker);
            if (campSpawn.campStand != null) {
                // Camping: stay put at the slot, matching the traveller/raider camp-spawn branch above.
                worker.setReadyToDepart(false);
                worker.setVillageIdling(true);
                worker.setVillageIdleTicks(0);
                worker.wakeUpTraveller();
            }
            world.spawnEntity(worker);
            if (r.battleId != null) {
                com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get(world)
                        .onParticipantMaterialized(world, r.uuid, worker);
            }

            if (r.homeVillage != null) {
                com.jubitus.travellers.utils.world.spawn.TravellerVillageRegistry reg =
                        com.jubitus.travellers.utils.world.spawn.TravellerVillageRegistry.get(world);
                if (reg.getWorkerFor(r.homeVillage) == null) {
                    reg.setWorkerFor(r.homeVillage, worker.getUniqueID());
                }
            }
        }

        graceUntil.put(r.uuid, now + MATERIALIZE_GRACE_TICKS);
        markDirty();
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public boolean isVirtual(UUID uuid) {
        return records.containsKey(uuid);
    }

    @Nullable
    public BlockPos getVirtualPosition(UUID uuid) {
        VirtualTravellerRecord r = records.get(uuid);
        if (r == null) return null;
        return new BlockPos((int) r.posX, (int) r.posY, (int) r.posZ);
    }

    public int getTotalCount() {
        return records.size();
    }

    public int getHiredCount() {
        int n = 0;
        for (VirtualTravellerRecord r : records.values()) {
            if (r.isHiredFollower) n++;
        }
        return n;
    }

    /** Returns virtual worker block positions in the given dimension within the radius. */
    public List<BlockPos> getPositionsInRadius(int dim, int centerX, int centerZ, int radiusBlocks) {
        return getPositionsInRadius(dim, centerX, centerZ, radiusBlocks, "WORKER");
    }

    /** Returns virtual entity positions of a given entityClass in the given dimension within the radius. */
    public List<BlockPos> getPositionsInRadius(int dim, int centerX, int centerZ, int radiusBlocks, String entityClass) {
        List<BlockPos> result = new ArrayList<>();
        int r2 = radiusBlocks * radiusBlocks;
        for (VirtualTravellerRecord rec : records.values()) {
            if (rec.dimension != dim) continue;
            if (!entityClass.equals(rec.entityClass)) continue;
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

    /**
     * Removes all virtual records whose entityClass matches any of the given values.
     * Returns the number of records removed.
     */
    public int clearByClass(String... classes) {
        java.util.Set<String> target = new java.util.HashSet<>(java.util.Arrays.asList(classes));
        int removed = 0;
        java.util.Iterator<VirtualTravellerRecord> it = records.values().iterator();
        while (it.hasNext()) {
            if (target.contains(it.next().entityClass)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) markDirty();
        return removed;
    }

    public boolean isWorkerOwnedByPlayer(UUID workerUUID, UUID playerUUID) {
        VirtualTravellerRecord r = records.get(workerUUID);
        if (r == null) return false;
        return r.isHiredFollower && playerUUID.equals(r.followOwnerUUID);
    }

    /**
     * Removes all WORKER virtual records that are not currently hired by a player.
     * Returns the number of records removed.
     */
    public int clearWorkersExceptHired() {
        int removed = 0;
        java.util.Iterator<VirtualTravellerRecord> it = records.values().iterator();
        while (it.hasNext()) {
            VirtualTravellerRecord r = it.next();
            if ("WORKER".equals(r.entityClass) && !r.isHiredFollower) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) markDirty();
        return removed;
    }

    public void cmdGroupComeBack(UUID leaderUUID, UUID ownerUUID) {
        for (VirtualTravellerRecord r : records.values()) {
            if (r.followOwnerUUID == null || !r.followOwnerUUID.equals(ownerUUID)) continue;
            if (leaderUUID.equals(r.escortLeaderUUID)) {
                r.escortLeaderUUID = null;
                r.followOrderActive = true;
                r.goOrderActive = false;
            }
        }
        VirtualTravellerRecord leader = records.get(leaderUUID);
        if (leader != null && ownerUUID.equals(leader.followOwnerUUID)) {
            leader.escortLeaderUUID = null;
            leader.followOrderActive = true;
            leader.goOrderActive = false;
        }
        markDirty();
    }

    public void cmdLeaderGoTo(UUID leaderUUID, UUID ownerUUID, BlockPos target) {
        VirtualTravellerRecord r = records.get(leaderUUID);
        if (r == null) return;
        if (!ownerUUID.equals(r.followOwnerUUID)) return;
        r.goOrderActive = true;
        r.followOrderActive = false;
        r.goal = target;
        markDirty();
    }

    public void cmdGroupMerge(UUID sourceLeaderUUID, UUID targetLeaderUUID, UUID ownerUUID) {
        for (VirtualTravellerRecord r : records.values()) {
            if (r.followOwnerUUID == null || !r.followOwnerUUID.equals(ownerUUID)) continue;
            if (sourceLeaderUUID.equals(r.escortLeaderUUID)) {
                r.escortLeaderUUID = targetLeaderUUID;
                r.followOrderActive = true;
            }
        }
        VirtualTravellerRecord src = records.get(sourceLeaderUUID);
        if (src != null && ownerUUID.equals(src.followOwnerUUID)) {
            src.escortLeaderUUID = targetLeaderUUID;
            src.followOrderActive = true;
            src.goOrderActive = false;
        }
        markDirty();
    }

    public int cmdMakeLeader(UUID leaderUUID, UUID ownerUUID) {
        int assigned = 0;
        for (VirtualTravellerRecord r : records.values()) {
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
        for (VirtualTravellerRecord r : records.values()) {
            if (!ownerUUID.equals(r.followOwnerUUID)) continue;
            if (r.escortLeaderUUID != null) virtualLeaders.add(r.escortLeaderUUID);
        }

        for (UUID leaderId : virtualLeaders) {
            VirtualTravellerRecord leaderRec = records.get(leaderId);
            if (leaderRec == null) continue;
            if (!ownerUUID.equals(leaderRec.followOwnerUUID)) continue;

            int count = 0;
            for (VirtualTravellerRecord r : records.values()) {
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
            FormationLeaderInfo vInfo = new FormationLeaderInfo(leaderId, name, count,
                    leaderRec.posX, leaderRec.posZ, false, true);
            vInfo.cultureId = leaderRec.entityNbt.hasKey("CultureId") ? leaderRec.entityNbt.getString("CultureId") : "";
            vInfo.textureIndex = leaderRec.entityNbt.hasKey("TextureIdx") ? leaderRec.entityNbt.getInteger("TextureIdx") : 0;
            vInfo.ownerColor = leaderRec.ownerColor;
            vInfo.entityId = -1;
            result.add(vInfo);
        }

        return result;
    }

    public Iterable<VirtualTravellerRecord> getRecordsView() {
        return records.values();
    }

    @javax.annotation.Nullable
    public VirtualTravellerRecord getRecord(UUID uuid) {
        return records.get(uuid);
    }

    public java.util.Collection<VirtualTravellerRecord> getAllRecords() {
        return records.values();
    }

    /** Number of virtual workers whose home village matches the given key (Y ignored). */
    public int getCountForVillage(BlockPos key) {
        int n = 0;
        for (VirtualTravellerRecord r : records.values()) {
            if (r.homeVillage == null) continue;
            if (r.homeVillage.getX() == key.getX() && r.homeVillage.getZ() == key.getZ()) n++;
        }
        return n;
    }

    /** Number of virtual entities of a given entityClass whose home village matches the key (Y ignored). */
    public int countByHomeVillage(BlockPos key, String entityClass) {
        int n = 0;
        for (VirtualTravellerRecord r : records.values()) {
            if (!entityClass.equals(r.entityClass)) continue;
            if (r.homeVillage == null) continue;
            if (r.homeVillage.getX() == key.getX() && r.homeVillage.getZ() == key.getZ()) n++;
        }
        return n;
    }

    /** Count all virtual records of a given entityClass. */
    public int countByClass(String entityClass) {
        int n = 0;
        for (VirtualTravellerRecord r : records.values()) {
            if (entityClass.equals(r.entityClass)) n++;
        }
        return n;
    }

    /** Add a pre-built record (used for virtual spawning without world entity). */
    public void addRecord(VirtualTravellerRecord r) {
        records.put(r.uuid, r);
        markDirty();
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
                VirtualTravellerRecord r = VirtualTravellerRecord.fromNbt(list.getCompoundTagAt(i));
                records.put(r.uuid, r);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (VirtualTravellerRecord r : records.values()) {
            list.appendTag(r.toNbt());
        }
        nbt.setTag("VW", list);
        return nbt;
    }
}
