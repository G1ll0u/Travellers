package com.jubitus.travellers.entity.travellers;

import com.google.common.base.Optional;
import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.entityAI.ambient.camp.EntityAICampJoinFire;
import com.jubitus.travellers.entity.entityAI.ambient.camp.EntityAICampPause;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIRoamInsideVillage;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIOwnerHurtByTargetTraveller;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIOwnerHurtTargetTraveller;
import com.jubitus.travellers.entity.entityAI.follow.EntityAIFollowOwnerTraveller;
import com.jubitus.travellers.entity.entityAI.follow.EntityAIFollowTraveller;
import com.jubitus.travellers.entity.entityAI.travel.EntityAIMerchantDeliverGoods;
import com.jubitus.travellers.entity.entityAI.travel.EntityAITravel;
import com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import com.jubitus.travellers.utils.world.spawn.TravellerSoftSpawner;
import com.jubitus.travellers.utils.world.spawn.TravellerVillageRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.items.ItemStackHandler;
import org.millenaire.common.culture.Culture;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.item.MillItems;
import org.millenaire.common.item.TradeGood;
import org.millenaire.common.ui.ContainerTrade;
import org.millenaire.common.utilities.MillLog;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class EntityTravellerWorker extends EntityTraveller {
    private static final double MOTION_EPSILON = 0.0004D;
    private final java.util.Set<ChunkPos> forced = new java.util.HashSet<>();
    private static final DataParameter<Boolean> USING_TRAVEL_AI =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.BOOLEAN);

    private int stuckAtVillageTicks = 0;
    private static final DataParameter<Boolean> HIRED_FOLLOWER_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.BOOLEAN);

    private static final DataParameter<Optional<UUID>> FOLLOW_OWNER_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.OPTIONAL_UNIQUE_ID);

    // --- follower / hire state (existing) ---
    @Nullable
    public UUID followOwnerId = null;
    public boolean hiredFollower = false;

    // NEW: whether follow AI is currently active (player’s "Follow" order)
    private boolean followOrderActive = false;

    // NEW: where the worker was told to stay when follow was toggled OFF
    @Nullable
    private BlockPos idleAnchorPos = null;

    // NEW: leader logic – if non-null, this worker follows another worker instead of the player
    @Nullable
    private Integer escortLeaderEntityId = null; // entityId of leader worker


    // NEW: 18-slot personal inventory (like a small chest on the worker)
    private final ItemStackHandler personalInventory = new ItemStackHandler(18);
    private final int lastTravelTick = 0;

    public ItemStackHandler getPersonalInventory() {
        return personalInventory;
    }

    // For non-entity look targets
    private boolean followerLookHasPoint = false;
    private double followerLookX;
    private double followerLookY;
    private double followerLookZ;

    // --- follower idle behaviour ---
    private int followerLookCooldown = 0;
    @Nullable
    private Entity followerLookTarget = null;
    private int followerLookTicks = 0;

    // --- follower / hire state ---
    @Nullable
    public UUID getFollowOwnerId() {
        Optional<UUID> o = this.dataManager.get(FOLLOW_OWNER_DM);
        return o.isPresent() ? o.get() : null;
    }
    // Players explicitly marked as hostile by the owner
    private final Set<UUID> hostilePlayers = new java.util.HashSet<>();

    // --- home chest idle behaviour (unemployed roaming) ---
    private static final int HOME_CHEST_WAIT_MAX_TICKS = 5 * 60 * 20; // 5 minutes

    private boolean atHomeChest = false;
    private int homeChestWaitTicks = 0;

    // How long to wait after loot pickup before saying goodbye & roaming again
    private static final int POST_JOB_FAREWELL_TICKS = 0; // 5 seconds (tweak)

    private int postJobFarewellTicks = 0;
    private boolean postJobFarewellActive = false;


    private double baseFollowRange;
    private double boostedFollowRange;
    private boolean usingBoostedNav = false;
    // --- home village (like merchants) ---
    @Nullable
    private BlockPos homeVillageCenter;

    public void setHomeVillage(@Nullable BlockPos pos) {
        this.homeVillageCenter = pos;
    }

    @Nullable
    public BlockPos getHomeVillage() {
        return this.homeVillageCenter;
    }

    // How often (in ticks) to refresh target when returning
    private static final int RETURN_REPATH_INTERVAL_TICKS = 200; // ~10 seconds
    private boolean awaitingPickup = false;
    // Cooldown counter for re-pathing
    private int returnRepathCooldown = 0;
    // If true, cargo is treated as a donation (extra rep, no money)
    private boolean donationJob = false;

    // 18-slot cargo inventory used for deliveries (separate from personal backpack)
    private final ItemStackHandler travelInventory = new ItemStackHandler(18);
    private static final int WORKER_SLOTS = 18; // cargo slots
    public ItemStackHandler getTravelInventory() {
        return travelInventory;
    }

    public enum WorkerMode {
        IDLE,
        TO_VILLAGE,
        RETURNING
    }

    @Nullable
    private BlockPos jobVillageCenter = null;
    private WorkerMode mode = WorkerMode.IDLE;
    private boolean jobDelivered = false;

    // "Employer" = player who hired this worker for the current/last job
    @Nullable
    private UUID employerId;
    @Nullable
    private BlockPos employerReturnPos;   // where to go back (captured when job starts)

    // When true, base EntityTraveller will NOT auto-plan routes
    private boolean routeLocked = false;

    public EntityTravellerWorker(World worldIn) {
        super(worldIn);
        this.enablePersistence();

        removeTaskOfType(EntityAIRoamInsideVillage.class);
        removeTaskOfType(EntityAITravel.class);
        removeTaskOfType(EntityAIFollowTraveller.class);
        removeTaskOfType(EntityAILookIdle.class);
        removeTaskOfType(EntityAICampJoinFire.class);
        removeTaskOfType(EntityAICampPause.class);

        this.targetTasks.addTask(0, new EntityAIOwnerHurtByTargetTraveller(this));
        this.targetTasks.addTask(1, new EntityAIOwnerHurtTargetTraveller(this));
        // Worker travel AI (for jobs)
        this.aiTravel = new EntityAITravel(
                this,
                TravellersModConfig.movementSpeedWhileTravel * 0.85,
                TravellersModConfig.workerStepBlocks
        );
        this.tasks.addTask(9, aiTravel);

        // Same donate goods AI as merchants
        this.tasks.addTask(12,
                new EntityAIMerchantDeliverGoods(
                        this,
                        TravellersModConfig.movementSpeedWhileTravel
                )
        );

        // NEW: follow-owner AI (companion mode)
        this.tasks.addTask(9, new EntityAIFollowOwnerTraveller(
                this,
                0.45F,
                1.0F,   // start following if farther than 6 blocks
                1.0F    // stop following once within 4 blocks
        ));
    }

    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(USING_TRAVEL_AI, false);
        this.dataManager.register(HIRED_FOLLOWER_DM, false);
        this.dataManager.register(FOLLOW_OWNER_DM, Optional.absent());
    }
    public boolean isUsingTravelAI() {
        return this.dataManager.get(USING_TRAVEL_AI);
    }

    public void setUsingTravelAI(boolean using) {
        if (!world.isRemote) {
            this.dataManager.set(USING_TRAVEL_AI, using);
        }
    }

    // Base hook: auto-planner will skip this entity when true
    @Override
    public boolean isRouteLocked() {
        return routeLocked;
    }
    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        // Let base handle culture pick, equipment, personality, name, etc.
        livingdata = super.onInitialSpawn(difficulty, livingdata);

        // Then just override which pool the texture index refers to.
        String cid = this.getCultureId(); // you already have setCultureId; getter should exist or be added
        CultureDef c = CultureRegistry.get(cid);
        if (c != null) {
            int count = c.getWorkerTextureCount();
            int idx = this.rand.nextInt(Math.max(1, count <= 0 ? 1 : count));
            this.setTextureIndex(idx);
        }
        IAttributeInstance moveAttr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (moveAttr != null) {
            // 20–30% faster than base travellers
            moveAttr.setBaseValue(TravellersModConfig.movementSpeed);
        }
        return livingdata;
    }
    // called by server when player confirms job in GUI
    // Old signature, default to non-donation job
    public void startVillageDeliveryJob(EntityPlayer employer, BlockPos villageCenter) {
        startVillageDeliveryJob(employer, villageCenter, false);
    }

    // New signature with donation flag
    public void startVillageDeliveryJob(EntityPlayer employer, BlockPos villageCenter, boolean donate) {
        this.employerId = employer.getUniqueID();
        this.employerReturnPos = getApproximateSurfacePosNear(employer);
        this.jobVillageCenter = villageCenter;
        this.mode = WorkerMode.TO_VILLAGE;
        this.jobDelivered = false;
        this.donationJob = donate;
        this.awaitingPickup = false;
        this.returnRepathCooldown = 0;


        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;

        // Route: go to village, then back to employer
        tc.route.add(villageCenter);
        if (employerReturnPos != null) {
            tc.route.add(employerReturnPos);
        }

        tc.routeIndex = 0;
        tc.setTargetVillage(villageCenter);
        this.setTargetVillage(villageCenter);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        this.routeLocked = true;
        this.getNavigator().clearPath();
    }


    /**
     * Called when we have finished donating to the target village.
     * After this, worker will travel back to employerReturnPos.
     */
    public void onJobDelivered() {
        if (this.jobDelivered) return; // guard
        this.jobDelivered = true;
        this.mode = WorkerMode.RETURNING;

        // Make sure we head back toward the employer (approximate surface XZ)
        EntityPlayer employer = getEmployer();
        if (employer != null) {
            BlockPos approx = getApproximateSurfacePosNear(employer);
            updateReturnRoute(approx);
        } else if (employerReturnPos != null) {
            updateReturnRoute(employerReturnPos);
        }
    }



    /**
     * Final clean up when worker has come back to the employer.
     * NOTE: we keep routeLocked = true so the worker will NOT auto-roam anymore.
     */
    private void finishJob() {
        this.mode = WorkerMode.IDLE;
        this.jobVillageCenter = null;
        this.jobDelivered = false;
        this.donationJob = false;
        this.hasUnsoldGoods = false;
        this.awaitingPickup = false;
        this.returnRepathCooldown = 0;

        // IMPORTANT: keep him from auto-roaming after the job
        this.routeLocked = false;

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;
        tc.setTargetVillage(null);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }



    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        // Server-side: register/update this worker as a chunkloader
        if (!world.isRemote) {
            TravellerChunkManager.INSTANCE.onChunkloaderTick(
                    this,
                    TravellersModConfig.workerChunkRadius

            );
            updateDynamicNavigation();
            tickFollowerIdleBehaviour();
            tickStayAtIdleAnchor(); // NEW
            clearAggroIfOffscreen();
            boolean travel = this.isJobActive(); // or your real condition
            this.setUsingTravelAI(travel);
        }

        if (world.isRemote) return;

        TravelCommon tc = this.getTravelCommon();

        // 1) Going TO the village (unchanged)
        if (mode == WorkerMode.TO_VILLAGE && jobVillageCenter != null && !jobDelivered) {
            int r = tc.arrivalRadiusBlocks(jobVillageCenter);
            if (this.getDistanceSqToCenter(jobVillageCenter) <= (double) (r * r)) {
                donateInventoryToVillage(jobVillageCenter);
                // donateInventoryToVillage calls onJobDelivered() already
            }
        }

        // 2) RETURNING: re-path, walk to player, then wait for pickup
        if (mode == WorkerMode.RETURNING && jobDelivered) {
            EntityPlayer employer = getEmployer();

            if (employer != null) {
                double distSq = this.getDistanceSq(employer);

                // If we can SEE the player and are within 16 blocks, use vanilla pathing
                // to walk directly to them for the last meters.
                if (!awaitingPickup &&
                        distSq > 4.0D * 4.0D &&
                        distSq <= 9.0D * 9.0D) {
                    this.getNavigator().tryMoveToEntityLiving(employer, this.getAIMoveSpeed());
                }

                // Periodic long-range repath back towards approximate surface XZ
                if (!awaitingPickup) {
                    if (returnRepathCooldown-- <= 0) {
                        returnRepathCooldown = RETURN_REPATH_INTERVAL_TICKS;
                        BlockPos newApprox = getApproximateSurfacePosNear(employer);
                        if (this.employerReturnPos == null ||
                                this.employerReturnPos.distanceSq(newApprox) > (32 * 32)) {
                            updateReturnRoute(newApprox);
                        }
                    }
                }

                // When close enough, STOP and wait for player to open GUI
// Use the same "near" distance as villages (VillageNearDistance)
                int near = 8;
                double nearSq = (double) near * (double) near;

// When close enough to the employer, STOP and wait for pickup
                if (!awaitingPickup && distSq <= nearSq) {
                    // Stop using the route + travel AI
                    tc.route.clear();
                    tc.routeIndex = -1;
                    tc.setTargetVillage(null);
                    tc.setCurrentWaypoint(null);
                    tc.setReadyToDepart(false);
                    tc.setDepartCooldownTicks(0);
                    this.getNavigator().clearPath();

                    awaitingPickup = true;

                    // Optional: little sound cue on arrival
                    this.playHelloSound();

                    // Arrival message (loot not yet handed over)
                    String msg;
                    if (this.hasUnsoldGoods) {
                        msg = TextFormatting.WHITE + "I'm here. I couldn't sell everything, "
                                + "some goods are still in my pack.";
                    } else {
                        msg = TextFormatting.WHITE + "I'm here with your goods. Check my pack.";
                    }
                    employer.sendMessage(new TextComponentString(msg));
                }

            } else if (employerReturnPos != null && !awaitingPickup) {
                // Fallback: if we lost the player entity, just go to last known target and wait there
                int r = tc.arrivalRadiusBlocks(employerReturnPos);
                if (this.getDistanceSqToCenter(employerReturnPos) <= (double) (r * r)) {
                    tc.route.clear();
                    tc.routeIndex = -1;
                    tc.setTargetVillage(null);
                    tc.setCurrentWaypoint(null);
                    tc.setReadyToDepart(false);
                    tc.setDepartCooldownTicks(0);
                    this.getNavigator().clearPath();
                    awaitingPickup = true;
                }
            }
        }
        if (!world.isRemote && postJobFarewellActive) {
            if (--postJobFarewellTicks <= 0) {
                postJobFarewellActive = false;

                EntityPlayer employer = getEmployer();
                if (employer != null) {
                    employer.sendMessage(new TextComponentString(
                            TextFormatting.WHITE + "Thank you for the job. I'm off to travel again!"
                    ));
                }

                // Now fully clear job state and unlock auto-roaming
                finishJob();
            } else {
                // During farewell, keep him from wandering off:
                this.getNavigator().clearPath();
            }
        }
        // --- NEW: idle roaming behaviour at player home chests ---
        // Only when not currently doing a job
        if (this.mode == WorkerMode.IDLE && !this.postJobFarewellActive && !this.isWaitingForPickup()) {
            tickHomeChestWaiting();
        }
        if (!world.isRemote) {
            if (this.mode == WorkerMode.IDLE
                    && !this.isJobActive()
                    && !this.isHiredFollower()
                    && tc.getTargetVillage() != null
                    && tc.isAtTargetStop()
                    && this.getNavigator().noPath()
                    && !tc.isReadyToDepart()) {

                stuckAtVillageTicks++;

                if (stuckAtVillageTicks > 20 * 120) { // 2 minutes, tweak as you like
                    BlockPos v = tc.getTargetVillage();
                    MillLog.major(this, "[TravellersWorker] Watchdog: stuck at village "
                            + v + " – forcing new route.");

                    // Let travel AI move on
                    tc.setReadyToDepart(true);
                    tc.setDepartCooldownTicks(0);
                    tc.routeIndex = -1;
                    this.getNavigator().clearPath();

                    stuckAtVillageTicks = 0;

                    // Optionally mark village bad for this worker:
                    tc.markVillageBad(v);
                }

            } else {
                stuckAtVillageTicks = 0;
            }
        }


    }
    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            TravellerChunkManager.INSTANCE.untrackChunkloader(this);

            if (homeVillageCenter != null && world instanceof WorldServer) {
                WorldServer ws = (WorldServer) world;
                TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);

                // Clear only if this UUID is the registered one
                reg.clearWorkerFor(homeVillageCenter, this.getUniqueID());

                // Immediately respawn a replacement at home
                TravellerSoftSpawner.respawnWorkerAtHome(ws, homeVillageCenter);
            }

            // If we were on a job, tell the employer what happened
            if (this.mode != WorkerMode.IDLE) {
                EntityPlayer employer = getEmployer();
                if (employer != null) {
                    employer.sendMessage(new TextComponentString(
                            TextFormatting.RED + "Your worker has died during his periple."
                    ));
                }
            }
        }

        super.onDeath(cause);
    }



    @Override
    protected boolean canDespawn() {
        // Never allow vanilla despawn logic to remove workers
        return false;
    }

    @Override
    public boolean isNoDespawnRequired() {
        // Tell vanilla "this entity must never despawn"
        return true;
    }

    @Override
    protected void despawnEntity() {
        // NO-OP: workers never despawn due to distance / idle checks
        // (they can still die from damage via setDead/onDeath)
    }

    @Nullable
    private EntityPlayer getEmployerPlayer() {
        if (employerId == null) return null;
        return this.world.getPlayerEntityByUUID(employerId);
    }

    // Use worker stats if you want them different
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();

        baseFollowRange = TravellersModConfig.workerFollowRange;
        boostedFollowRange = baseFollowRange * 4.0D;

        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH)
                .setBaseValue(TravellersModConfig.workerMaxHealth);

        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE)
                .setBaseValue(baseFollowRange);
        IAttributeInstance moveAttr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (moveAttr != null) {
            // Make workers a bit quicker than generic travellers
            // TravellersModConfig.movementSpeed is the base used in EntityTraveller.onInitialSpawn
            moveAttr.setBaseValue(TravellersModConfig.movementSpeed);
        }
    }
    @Override
    public void onConfigReload() {
        super.onConfigReload();

        // Sync worker-specific ranges from config
        this.baseFollowRange   = TravellersModConfig.workerFollowRange;
        this.boostedFollowRange = baseFollowRange * 4.0D;

        IAttributeInstance followAttr = this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE);
        if (followAttr != null) {
            followAttr.setBaseValue(baseFollowRange);
        }

        // Optional: also restore worker-specific move speed if you want
        IAttributeInstance moveAttr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (moveAttr != null) {
            moveAttr.setBaseValue(TravellersModConfig.movementSpeed);
        }

        // Same idea if you have worker-specific HP etc:
        IAttributeInstance hpAttr = this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH);
        if (hpAttr != null) {
            hpAttr.setBaseValue(TravellersModConfig.workerMaxHealth);
            if (this.getHealth() > this.getMaxHealth()) {
                this.setHealth(this.getMaxHealth());
            }
        }
    }


    // NBT: persist employer + job so restart doesn’t lose the mission
    @Override
    public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);

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
        nbt.setInteger("WorkerMode", mode.ordinal());
        nbt.setBoolean("JobDelivered", jobDelivered);
        nbt.setBoolean("RouteLocked", routeLocked);
        nbt.setBoolean("DonationJob", donationJob);
        nbt.setTag("TravelInventory", this.travelInventory.serializeNBT());

        nbt.setBoolean("AwaitingPickup", awaitingPickup);
        nbt.setBoolean("HasUnsoldGoods", hasUnsoldGoods);
        nbt.setBoolean("PostJobFarewellActive", postJobFarewellActive);
        nbt.setInteger("PostJobFarewellTicks", postJobFarewellTicks);

        // NEW: personal inventory
        nbt.setTag("PersonalInventory", this.personalInventory.serializeNBT());
        // NEW: home village
        if (homeVillageCenter != null) {
            nbt.setInteger("HomeVillageX", homeVillageCenter.getX());
            nbt.setInteger("HomeVillageY", homeVillageCenter.getY());
            nbt.setInteger("HomeVillageZ", homeVillageCenter.getZ());
        }
        if (followOwnerId != null) {
            nbt.setUniqueId("FollowOwnerId", followOwnerId);
        }
        nbt.setBoolean("HiredFollower", hiredFollower);

        if (!hostilePlayers.isEmpty()) {
            NBTTagList list = new NBTTagList();
            for (UUID id : hostilePlayers) {
                NBTTagCompound c = new NBTTagCompound();
                c.setUniqueId("Player", id);
                list.appendTag(c);
            }
            nbt.setTag("HostilePlayers", list);
        }
        nbt.setBoolean("FollowOrderActive", followOrderActive);
        if (idleAnchorPos != null) {
            nbt.setInteger("IdleAx", idleAnchorPos.getX());
            nbt.setInteger("IdleAy", idleAnchorPos.getY());
            nbt.setInteger("IdleAz", idleAnchorPos.getZ());
        }
        if (escortLeaderEntityId != null) {
            nbt.setInteger("EscortLeaderId", escortLeaderEntityId);
        }
        nbt.setBoolean("HiredFollower", this.dataManager.get(HIRED_FOLLOWER_DM));
        Optional<UUID> o = this.dataManager.get(FOLLOW_OWNER_DM);
        if (o.isPresent()) nbt.setUniqueId("FollowOwnerId", o.get());
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);

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
        int m = nbt.getInteger("WorkerMode");
        mode = (m >= 0 && m < WorkerMode.values().length) ? WorkerMode.values()[m] : WorkerMode.IDLE;
        jobDelivered = nbt.getBoolean("JobDelivered");
        routeLocked = nbt.getBoolean("RouteLocked");
        donationJob = nbt.getBoolean("DonationJob"); // NEW
        if (nbt.hasKey("TravelInventory")) {
            this.travelInventory.deserializeNBT(nbt.getCompoundTag("TravelInventory"));
        }

        this.awaitingPickup = nbt.getBoolean("AwaitingPickup");
        this.hasUnsoldGoods = nbt.getBoolean("HasUnsoldGoods");
        this.postJobFarewellActive = nbt.getBoolean("PostJobFarewellActive");
        this.postJobFarewellTicks = nbt.getInteger("PostJobFarewellTicks");


        // NEW: personal inventory
        if (nbt.hasKey("PersonalInventory")) {
            this.personalInventory.deserializeNBT(nbt.getCompoundTag("PersonalInventory"));
        }

        // NEW: home village
        if (nbt.hasKey("HomeVillageX")) {
            int x = nbt.getInteger("HomeVillageX");
            int y = nbt.getInteger("HomeVillageY");
            int z = nbt.getInteger("HomeVillageZ");
            this.homeVillageCenter = new BlockPos(x, y, z);
        } else {
            this.homeVillageCenter = null;
        }
        if (nbt.hasUniqueId("FollowOwnerId")) {
            this.followOwnerId = nbt.getUniqueId("FollowOwnerId");
        } else {
            this.followOwnerId = null;
        }
        this.hiredFollower = nbt.getBoolean("HiredFollower");

        hostilePlayers.clear();
        if (nbt.hasKey("HostilePlayers", Constants.NBT.TAG_LIST)) {
            NBTTagList list = nbt.getTagList("HostilePlayers", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound c = list.getCompoundTagAt(i);
                if (c.hasUniqueId("Player")) {
                    hostilePlayers.add(c.getUniqueId("Player"));
                }
            }
        }
        this.followOrderActive = nbt.getBoolean("FollowOrderActive");
        if (nbt.hasKey("IdleAx")) {
            this.idleAnchorPos = new BlockPos(
                    nbt.getInteger("IdleAx"),
                    nbt.getInteger("IdleAy"),
                    nbt.getInteger("IdleAz")
            );
        } else {
            this.idleAnchorPos = null;
        }

        if (nbt.hasKey("EscortLeaderId")) {
            this.escortLeaderEntityId = nbt.getInteger("EscortLeaderId");
        } else {
            this.escortLeaderEntityId = null;
        }
        if (this.mode != WorkerMode.RETURNING || !this.jobDelivered) {
            this.awaitingPickup = false;
        }

// If farewell isn't active, no need to keep a non-zero counter
        if (!this.postJobFarewellActive) {
            this.postJobFarewellTicks = 0;
        }
        boolean hired = nbt.getBoolean("HiredFollower");
        Optional<UUID> owner = nbt.hasUniqueId("FollowOwnerId")
                ? Optional.of(nbt.getUniqueId("FollowOwnerId"))
                : Optional.absent();

        this.hiredFollower = hired;
        this.followOwnerId = owner.isPresent() ? owner.get() : null;

        this.dataManager.set(HIRED_FOLLOWER_DM, hired);
        this.dataManager.set(FOLLOW_OWNER_DM, owner);

    }



    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;

        if (shouldHelloFor(player)) {
            playHelloSound();
            markHello(player);
        } else {
            playTalkSound();
        }

        // 1) If we are back from a job and waiting for pickup, ALWAYS open the cargo GUI
        if (this.mode == WorkerMode.RETURNING && this.jobDelivered && this.awaitingPickup) {
            player.openGui(
                    Travellers.INSTANCE,
                    TravellersGuiIds.WORKER,
                    world,
                    this.getEntityId(),
                    0, 0
            );
            return true;
        }

        // 2) If actually away doing a job, block interaction as before
        if (this.mode != WorkerMode.IDLE) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "I'm already on a job. Wait for me to come back."
            ));
            return true;
        }

        // 3) Normal idle interaction → open HUB GUI (Transport / Follow)
        player.openGui(
                Travellers.INSTANCE,
                TravellersGuiIds.WORKER_HUB,
                world,
                this.getEntityId(),
                0, 0
        );
        return true;
    }



    /**
     * Donate backpack contents to the target village & then mark job delivered.
     * Call this from your worker-specific delivery AI when at the town hall.
     */
    /**
     * Process cargo inventory as a Millénaire trade or donation with the village.
     * If donationJob is true -> donation (no money, extra reputation).
     * Else -> normal selling (money + base reputation).
     */
    public void donateInventoryToVillage(BlockPos villageCenter) {
        World world = this.world;
        if (world.isRemote) return;

        MillWorldData wd = MillVillageIndex.getWD(world);
        if (wd == null) return;

        Building th = MillVillageIndex.getTownHallBuilding(wd, villageCenter);
        if (th == null) return;

        EntityPlayer employer = getEmployer();
        if (employer == null) {
            // Fallback: just store goods without money/rep if we somehow lost employer
            simpleStoreCargoToVillage(th);
            onJobDelivered();
            return;
        }

        processCargoTradeWithVillage(th, employer, this.donationJob);

        onJobDelivered();
    }

    /**
     * Simple legacy behaviour: just dump cargo into town hall without money/rep.
     */
    private void simpleStoreCargoToVillage(Building th) {
        ItemStackHandler inv = this.getTravelInventory();

        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            int meta = stack.getMetadata();
            int count = stack.getCount();

            int stored = th.storeGoods(item, meta, count);
            if (stored > 0) {
                inv.extractItem(slot, stored, false);
            }
        }
    }
    /**
     * Emulate Millénaire's "player sells to village" or "player donates to village"
     * logic for all items in the worker's cargo.
     *
     * SELL MODE (donateMode == false):
     *   - Village buys items at TradeGood.getCalculatedBuyingPrice().
     *   - Reputation: price * qty.
     *   - Money: converted to coins and placed in the worker's cargo inventory.
     *
     * DONATE MODE (donateMode == true):
     *   - Village receives items.
     *   - Reputation: price * qty * DONATION_REP_MULTIPLIER.
     *   - No money is given.
     */
    private void processCargoTradeWithVillage(Building building, EntityPlayer employer, boolean donateMode) {
        ItemStackHandler inv = this.getTravelInventory();

        MillLog.major(this, "[TravellersWorker] Starting cargo trade at "
                + building.getVillageQualifiedName()
                + " donateMode=" + donateMode);
        MillLog.major(this, "[TravellersWorker] World isRemote=" + building.world.isRemote);

        Building townHall = building.getTownHall();
        if (townHall == null) {
            MillLog.major(this, "[TravellersWorker] No town hall found; dumping cargo without money/rep.");
            simpleStoreCargoToVillage(building);
            return;
        }

        Culture culture = townHall.culture;
        if (culture == null) {
            MillLog.major(this, "[TravellersWorker] No culture on town hall; dumping cargo.");
            simpleStoreCargoToVillage(building);
            return;
        }

        int totalMoney = 0; // in deniers
        int totalRep   = 0; // reputation points

        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            int meta  = stack.getMetadata();

            String regName = (item.getRegistryName() != null
                    ? item.getRegistryName().toString()
                    : item.toString());

            MillLog.major(this, "[TravellersWorker] Slot " + slot + " stack="
                    + stack.getDisplayName() + " (" + regName + ":" + meta + ") count=" + stack.getCount());

            InvItem invItem = InvItem.createInvItem(item, meta);

            TradeGood good = culture.getTradeGood(invItem);
            boolean canSell = false;
            int unitPrice = 0;

            if (good != null) {
                // Dynamic price
                int unitPriceCalc = good.getCalculatedBuyingPrice(building, employer);
                // Basic price, using villageType overrides if any
                int unitPriceBasicWithBuilding = good.getBasicBuyingPrice(building);
                // Raw TradeGood.buyingPrice
                int unitPriceBasicRaw = good.getBasicBuyingPrice(null);

                unitPrice = unitPriceCalc;
                if (unitPrice <= 0) unitPrice = unitPriceBasicWithBuilding;
                if (unitPrice <= 0) unitPrice = unitPriceBasicRaw;

                MillLog.major(this,
                        "[TravellersWorker]  TradeGood matched: " + good.key +
                                " calc=" + unitPriceCalc +
                                " basicWithBuilding=" + unitPriceBasicWithBuilding +
                                " basicRaw=" + unitPriceBasicRaw +
                                " -> chosen unitPrice=" + unitPrice);

                canSell = unitPrice > 0;
            } else {
                MillLog.major(this, "[TravellersWorker]  No TradeGood for this item.");
            }

            // SELL JOB: if no TradeGood or no positive price -> keep item, give it back later
            if (!donateMode && !canSell) {
                this.hasUnsoldGoods = true;
                MillLog.major(this, "[TravellersWorker]  Village will not buy this, keeping it for return.");
                // Do NOT store, do NOT extract; it stays in cargo
                continue;
            }

            // Now we know either:
            //  - donateMode == true (donation job), or
            //  - sell job with canSell == true and a valid unitPrice

            int qty = stack.getCount();
            int stored = building.storeGoods(item, meta, qty);
            if (stored <= 0) {
                MillLog.major(this, "[TravellersWorker]  storeGoods returned " + stored + " – nothing stored.");
                if (!donateMode) {
                    this.hasUnsoldGoods = true; // couldn't deliver, keep it
                }
                continue;
            }

            inv.extractItem(slot, stored, false);

            int repGain = 0;
            int moneyGain = 0;

            if (!donateMode && canSell) {
                int baseValue = unitPrice * stored;
                repGain   = baseValue; // same as normal selling rep
                moneyGain = baseValue;
            } else if (donateMode && good != null && unitPrice > 0) {
                int baseValue = unitPrice * stored;
                int repMult   = ContainerTrade.DONATION_REP_MULTIPLIER;
                repGain       = baseValue * repMult;
            }

            totalRep   += repGain;
            totalMoney += moneyGain;

            townHall.adjustLanguage(employer, stored);

            if (repGain != 0 || moneyGain != 0) {
                employer.sendMessage(new TextComponentString(
                        TextFormatting.YELLOW + "[Worker debug] " +
                                (donateMode ? "Donated " : "Sold ") +
                                stored + "x " + stack.getDisplayName() +
                                " for " + moneyGain + "d, rep +" + repGain
                ));
            }
        }

        // Apply reputation once
        if (totalRep != 0) {
            MillLog.major(this, "[TravellersWorker] Applying reputation change: " + totalRep);
            building.adjustReputation(employer, totalRep);
            if (TravellersModConfig.workerNotifyOnVillageTrade) {
                String villageName = building.getVillageQualifiedName();
                employer.sendMessage(new TextComponentString(
                        TextFormatting.GREEN + "[" + villageName + "] "
                                + "accepted your offer and your reputation increased."
                ));
            }
        } else {
            MillLog.major(this, "[TravellersWorker] No reputation change (totalRep == 0).");
        }

        // Money (SELL mode only) into worker cargo as coins
        if (!donateMode && totalMoney > 0) {
            MillLog.major(this, "[TravellersWorker] Total money in deniers: " + totalMoney);

            addMoneyToWorkerInventory(totalMoney);
        } else if (!donateMode) {
            MillLog.major(this, "[TravellersWorker] No money earned (totalMoney == 0).");
        } else {
            MillLog.major(this, "[TravellersWorker] Donation mode: no money given.");
        }

        building.invalidateInventoryCache();
        if (!building.world.isRemote) {
            building.sendChestPackets(employer);
        }
    }


    @Nullable
    private EntityPlayer getEmployer() {
        if (employerId == null) return null;
        return this.world.getPlayerEntityByUUID(employerId);
    }

    /**
     * Convert a total amount of deniers into DENIER / DENIER_ARGENT / DENIER_OR
     * and put them into the worker's cargo inventory. If inventory is full, drop
     * the leftover in the world at the worker's feet.
     */
    private void addMoneyToWorkerInventory(int deniersTotal) {
        if (deniersTotal <= 0) return;

        int denierOr = deniersTotal / (64 * 64);
        deniersTotal %= (64 * 64);
        int denierArgent = deniersTotal / 64;
        int denier = deniersTotal % 64;

        MillLog.major(this, "[TravellersWorker] Coin breakdown: "
                + denierOr + "o, " + denierArgent + "a, " + denier + "d");

        addCoinsToCargo(MillItems.DENIER_OR, denierOr);
        addCoinsToCargo(MillItems.DENIER_ARGENT, denierArgent);
        addCoinsToCargo(MillItems.DENIER, denier);
    }

    /**
     * Insert a given number of coin items into the cargo inventory, splitting into
     * valid stack sizes. Any leftovers that don't fit get dropped in the world.
     */
    private void addCoinsToCargo(Item coinItem, int amount) {
        if (amount <= 0) return;

        ItemStackHandler inv = this.getTravelInventory();
        int maxStack = coinItem.getItemStackLimit(new ItemStack(coinItem));

        while (amount > 0) {
            int toPlace = Math.min(maxStack, amount);
            ItemStack stack = new ItemStack(coinItem, toPlace);
            amount -= toPlace;

            ItemStack remaining = insertStackInHandler(inv, stack);
            if (!remaining.isEmpty()) {
                MillLog.major(this, "[TravellersWorker] Cargo full, dropping "
                        + remaining.getCount() + "x " + remaining.getDisplayName());
                this.entityDropItem(remaining, 0.0F);
            }
        }
    }

    /**
     * Try to insert a stack into an ItemStackHandler, filling partial stacks first.
     * Returns any leftover.
     */
    private ItemStack insertStackInHandler(ItemStackHandler handler, ItemStack stack) {
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

    /**
     * Compute travel cost in deniers based on village distance and how many cargo
     * slots are used.
     *
     * distance: 2D distance in blocks (same as Millénaire uses for mill)
     * usedSlots: number of non-empty cargo slots (0..18)
     *
     * Formula (tweak as you like):
     *   cost = ceil( basePerSlot * usedSlots * (distance / 800.0) )
     *
     * So:
     *  - distance ~800 and 1 full slot -> ~basePerSlot deniers
     *  - distance ~800 and 18 full slots -> ~18 * basePerSlot deniers
     */
    public static int computeTravelCost(int distance, int usedSlots) {
        if (distance <= 0 || usedSlots <= 0) {
            return 0;
        }

        double basePerSlot = TravellersModConfig.workerTravelBaseCostPerSlot;
        double baseDistance = TravellersModConfig.workerTravelDistanceBase;
        if (baseDistance <= 0.0D) {
            baseDistance = 800.0D;
        }

        double factor = (double) distance / baseDistance;
        double cost = basePerSlot * usedSlots * factor;

        // Flat base + global multiplier
        cost = (cost + TravellersModConfig.workerTravelFlatBaseCost)
                * TravellersModConfig.workerTravelCostMultiplier;

        return (int) Math.ceil(cost);
    }

    /**
     * Count non-empty cargo slots on this worker.
     */
    public int getUsedTravelSlots() {
        int used = 0;
        ItemStackHandler inv = this.getTravelInventory();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                used++;
            }
        }
        return used;
    }
    private boolean hasUnsoldGoods = false;

    public boolean hasUnsoldGoods() {
        return hasUnsoldGoods;
    }
    // Approximate surface position above player's XZ
    private BlockPos getApproximateSurfacePosNear(@Nullable EntityPlayer player) {
        if (player == null) {
            return this.employerReturnPos != null
                    ? this.employerReturnPos
                    : this.getPosition();
        }

        BlockPos playerPos = player.getPosition();
        BlockPos columnTop;

        try {
            columnTop = this.world.getTopSolidOrLiquidBlock(
                    new BlockPos(playerPos.getX(), 0, playerPos.getZ())
            );
        } catch (Exception e) {
            columnTop = new BlockPos(playerPos.getX(), (int) this.posY, playerPos.getZ());
        }

        // If the player is clearly underground / in a cave, follow their actual Y
        boolean underground =
                playerPos.getY() + 2 < columnTop.getY() &&
                        !this.world.canSeeSky(playerPos);

        if (underground) {
            // Go roughly to the player's real position (cave height)
            return playerPos;
        } else {
            // Normal overground behaviour
            return columnTop;
        }
    }



    private void updateReturnRoute(BlockPos newReturnPos) {
        if (newReturnPos == null) return;

        this.employerReturnPos = newReturnPos;

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;
        tc.route.add(newReturnPos);
        tc.routeIndex = 0;
        tc.setTargetVillage(newReturnPos); // just a generic target
        this.setTargetVillage(newReturnPos);
        tc.setCurrentWaypoint(null);

        // *** IMPORTANT: allow travel AI to start immediately ***
        tc.setReadyToDepart(true);      // <-- was false
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    public void onLootCollected(EntityPlayer player) {
        // Only finish if we were on a return job
        if (this.mode == WorkerMode.RETURNING && this.jobDelivered) {

            // We’re done with the job logically, but we keep routeLocked
            // so he doesn’t start travelling yet.
            this.awaitingPickup = false;

            // Start farewell phase
            this.postJobFarewellActive = true;
            this.postJobFarewellTicks = POST_JOB_FAREWELL_TICKS;

            // Switch to IDLE but keep routeLocked=true until farewell is over
            this.mode = WorkerMode.IDLE;

            // (Optional) tiny acknowledgement right away, or remove if you only
            // want the delayed goodbye.
            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "I did what I had to do. Let me rest a bit."
            ));
        }
    }

    public boolean isWaitingForPickup() {
        return this.mode == WorkerMode.RETURNING && this.jobDelivered && this.awaitingPickup;
    }
    public boolean isIdleForRoaming() {
        // free to behave like a normal travellers/merchant
        return this.mode == WorkerMode.IDLE && !this.isWaitingForPickup();
    }
    private void tickHomeChestWaiting() {
        if (world.isRemote) return;
        if (this.mode != WorkerMode.IDLE) {
            // Only do this when unemployed
            atHomeChest = false;
            homeChestWaitTicks = 0;
            return;
        }

        TravelCommon tc = this.getTravelCommon();
        BlockPos target = tc.getTargetVillage(); // generic "current stop" target

        if (target == null) {
            atHomeChest = false;
            homeChestWaitTicks = 0;
            return;
        }

        // Only care if we're actually at the target
        if (!tc.isAtTargetStop()) {
            atHomeChest = false;
            homeChestWaitTicks = 0;
            return;
        }

        // Is this target a registered home chest?
        if (!TravellerHomeManager.get(world).isHomeChest(world, target)) {
            atHomeChest = false;
            homeChestWaitTicks = 0;
            return;
        }

        // From here: we are an idle worker at a home chest
        atHomeChest = true;

        // Keep travel AI from departing
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);
        this.getNavigator().clearPath();

        homeChestWaitTicks++;

        // Look for any nearby player (owner or not)
        final double playerRadius = 8.0D;
        AxisAlignedBB box = this.getEntityBoundingBox().grow(playerRadius, 4.0D, playerRadius);
        boolean playerNearby = !world.getEntitiesWithinAABB(EntityPlayer.class, box).isEmpty();

        // Optional: say one-time hello when someone first comes
        if (playerNearby && homeChestWaitTicks == 20) { // 1 second after arrival
            this.playHelloSound();
        }

        // Leave if:
        //  - we waited full 5 minutes, OR
        //  - a player is nearby (we've "seen" someone at this home)
        if (homeChestWaitTicks >= HOME_CHEST_WAIT_MAX_TICKS || playerNearby) {
            homeChestWaitTicks = 0;
            atHomeChest = false;

            // Allow travel AI to plan next hop
            tc.setReadyToDepart(true);
            tc.setDepartCooldownTicks(0);
        }
    }
    private void updateDynamicNavigation() {
        if (world.isRemote) return;

        boolean shouldBoost = isInPlayerViewDistance();
        if (shouldBoost == usingBoostedNav) return; // no change

        usingBoostedNav = shouldBoost;

        // FOLLOW_RANGE
        IAttributeInstance followAttr =
                this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE);
        if (followAttr != null) {
            followAttr.setBaseValue(shouldBoost ? boostedFollowRange : baseFollowRange);
        }

        // stepBlocks (see next section: needs setter on EntityAITravel)
    }
    public boolean isFollowOrderActive() {
        return followOrderActive;
    }

    /**
     * Set whether this worker should actively follow its owner/leader.
     * When turned off, we remember the current position as an anchor.
     */
    public void setFollowOrderActive(boolean active) {
        if (this.followOrderActive == active) return;

        this.followOrderActive = active;

        if (!active) {
            // stay here
            this.idleAnchorPos = this.getPosition();
            this.getNavigator().clearPath();
        } else {
            this.idleAnchorPos = null;
        }
    }

    @Nullable
    public BlockPos getIdleAnchorPos() {
        return idleAnchorPos;
    }

    public void clearIdleAnchor() {
        this.idleAnchorPos = null;
    }

    /** Hire/dismiss already exist – just update to default follow state. */
    public void hireAsFollower(EntityPlayer owner) {
        UUID id = owner.getUniqueID();
        this.followOwnerId = id;
        this.hiredFollower = true;
        if (!world.isRemote) {
            this.dataManager.set(HIRED_FOLLOWER_DM, true);
            this.dataManager.set(FOLLOW_OWNER_DM, Optional.of(id));
            this.setUsingTravelAI(false);
        }
        // Cancel any job
        this.mode = WorkerMode.IDLE;
        this.jobVillageCenter = null;
        this.jobDelivered = false;
        this.awaitingPickup = false;
        this.routeLocked = true;  // companion mode

        this.followOrderActive = true; // <--- key line
        this.idleAnchorPos = null;

        this.getNavigator().clearPath();
    }

    public void dismissFollower() {
        this.hiredFollower = false;
        this.followOwnerId = null;
        this.hostilePlayers.clear();
        this.routeLocked = false;
        if (!world.isRemote) {
            this.dataManager.set(HIRED_FOLLOWER_DM, false);
            this.dataManager.set(FOLLOW_OWNER_DM, Optional.absent());
            this.setUsingTravelAI(false);
        }
        this.followOrderActive = false;
        this.idleAnchorPos = null;
    }


    public boolean isHiredFollower() {
        return this.dataManager.get(HIRED_FOLLOWER_DM)
                && this.dataManager.get(FOLLOW_OWNER_DM).isPresent();
    }

    @Nullable
    public EntityPlayer getFollowOwner() {
        if (followOwnerId == null) return null;
        return this.world.getPlayerEntityByUUID(followOwnerId);
    }

    public boolean isJobActive() {
        return this.mode != WorkerMode.IDLE && !this.isWaitingForPickup();
    }

    // Mark/unmark specific players as hostile
    public void setPlayerHostile(EntityPlayer p, boolean hostile) {
        if (p == null) return;
        if (hostile) {
            hostilePlayers.add(p.getUniqueID());
        } else {
            hostilePlayers.remove(p.getUniqueID());
        }
    }

    public boolean isPlayerMarkedHostile(EntityPlayer p) {
        return p != null && hostilePlayers.contains(p.getUniqueID());
    }
    private void tickFollowerIdleBehaviour() {

        if (!world.isRemote && this.isHiredFollower()) {
            // if any worker is escorting me
            AxisAlignedBB box = this.getEntityBoundingBox().grow(10, 4, 10);
            boolean hasFollowers = !world.getEntitiesWithinAABB(EntityTravellerWorker.class, box,
                    w -> w != null && w != this && w.isEscortingLeader(this)).isEmpty();
            if (hasFollowers) {
                return; // or skip separation/orbit parts
            }
        }

        if (!isHiredFollower()) {
            followerLookTarget = null;
            followerLookTicks = 0;
            followerLookCooldown = 0;
            return;
        }
        if (!isFollowOrderActive()) {
            followerLookTarget = null;
            followerLookTicks = 0;
            followerLookCooldown = 0;
            return;
        }
        if (isJobActive()) {
            // On a delivery job: act like a worker, not a companion
            followerLookTarget = null;
            followerLookTicks = 0;
            return;
        }

        EntityPlayer owner = getFollowOwner();
        if (owner == null || owner.isDead) {
            followerLookTarget = null;
            followerLookTicks = 0;
            return;
        }
// If owner is idle and we're close, let FollowOwner AI handle position;
// here we only keep head-look behaviour.
        // --- NEW: only use idle behaviour when owner is basically not moving ---
        double mvX = owner.posX - owner.prevPosX;
        double mvZ = owner.posZ - owner.prevPosZ;
        double mvLenSq = mvX * mvX + mvZ * mvZ;
        boolean ownerMoving = mvLenSq > MOTION_EPSILON || owner.isSprinting();

        if (ownerMoving) {
            // Owner moving -> let follow AI control movement + yaw entirely.
            // We only keep soft separation (to avoid standing in the owner), no random look/wander.
            double d2 = this.getDistanceSq(owner);
            double minSeparation = 1.2D;
            boolean tooClose = d2 < (minSeparation * minSeparation);
            if (tooClose && !this.isInCombat()) {
                double dx = this.posX - owner.posX;
                double dz = this.posZ - owner.posZ;
                applySoftSeparation(dx, dz, 0.05D);
            }

            followerLookTarget = null;
            followerLookTicks = 0;
            followerLookCooldown = 0;
            return; // <--- KEY: no idle head/idle movement while owner is moving
        }

// Distance to owner
        double d2 = this.getDistanceSq(owner);
        double idleMaxSq = 16.0 * 16.0;

// If the owner is moving and we’re very close (< ~2 blocks), let FOLLOW AI control,
// do NOT run idle random look / wander here (prevents head fight).
        if (ownerMoving && d2 < 4.0D) { // 4.0D = 2 blocks squared
            followerLookTarget = null;
            followerLookTicks = 0;
            followerLookCooldown = 0;

            // still allow soft separation so we don’t stand inside the player
            double minSeparation = 1.2D;
            boolean tooClose = d2 < (minSeparation * minSeparation);
            if (tooClose && !this.isInCombat()) {
                double dx = this.posX - owner.posX;
                double dz = this.posZ - owner.posZ;
                applySoftSeparation(dx, dz, 0.05D);
            }

            // Don’t do the rest of idle behaviour (no random look, no random wander)
            return;
        }

// If too far from owner, let the follow AI handle movement; don't do idle stuff
        if (d2 > idleMaxSq) {
            followerLookTarget = null;
            followerLookTicks = 0;
            return;
        }

// "Personal space" radius around the player
        double minSeparation = 0.3D; // ~a bit less than 1 block
        boolean tooClose = d2 < (minSeparation * minSeparation);

// If we are too close, take a tiny step away to avoid ramming into the player
        if (tooClose && !this.isInCombat()) {
            double dx = this.posX - owner.posX;
            double dz = this.posZ - owner.posZ;
            double lenSq = dx * dx + dz * dz;

            if (lenSq > 0.0001D) {
                double len = Math.sqrt(lenSq);
                dx /= len;
                dz /= len;

                // Step back about 1.2 blocks away from current position
                double targetX = this.posX + dx * 1.6D;
                double targetZ = this.posZ + dz * 1.6D;

                applySoftSeparation(this.posX - owner.posX, this.posZ - owner.posZ, 0.05D);
            }
        }

//        // --- NEW: avoid crowding with other followers of the same owner ---
//        if (!this.isInCombat() && this.ticksExisted % 10 == 0) { // every 10 ticks
//            double repelRadius = 0.5D;
//            AxisAlignedBB box = this.getEntityBoundingBox().grow(repelRadius, 1.0D, repelRadius);
//
//            List<EntityTravellerWorker> mates = this.world.getEntitiesWithinAABB(
//                    EntityTravellerWorker.class,
//                    box,
//                    w -> w != null
//                            && w != this
//                            && w.isHiredFollower()
//                            && w.getFollowOwner() == owner // same owner
//            );
//
//            if (!mates.isEmpty()) {
//                // Compute average position of nearby followers
//                double avgX = 0.0D;
//                double avgZ = 0.0D;
//                for (EntityTravellerWorker w : mates) {
//                    avgX += w.posX;
//                    avgZ += w.posZ;
//                }
//                avgX /= mates.size();
//                avgZ /= mates.size();
//
//                // Move a bit away from the local center of followers
//                double dx = this.posX - avgX;
//                double dz = this.posZ - avgZ;
//                double lenSq = dx * dx + dz * dz;
//
//                if (lenSq > 0.0004D) { // avoid division by zero
//                    double len = Math.sqrt(lenSq);
//                    dx /= len;
//                    dz /= len;
//
//                    double targetX = this.posX + dx * 0.8D;
//                    double targetZ = this.posZ + dz * 0.8D;
//
//                    this.getNavigator().tryMoveToXYZ(
//                            targetX,
//                            this.posY,
//                            targetZ,
//                            TravellersModConfig.movementSpeedWhileFollowing * 0.35D // slow shuffle
//                    );
//                }
//            }
//        }


// 1) Gentle, slow random wander around owner when in idle band and not too close
        if (!tooClose && this.getNavigator().noPath() && !this.isInCombat()) {
            // Much rarer: on average every ~7s
            if (this.rand.nextInt(140) == 0) {
                // Small, lazy orbit around the owner (2–3.5 blocks)
                double angle = this.rand.nextDouble() * Math.PI * 2.0;
                double radius = 2.0 + this.rand.nextDouble() * 1.5D; // [2,3.5]
                double tx = owner.posX + Math.cos(angle) * radius;
                double tz = owner.posZ + Math.sin(angle) * radius;
                double ty = owner.posY;

                BlockPos targetPos = new BlockPos(tx, ty, tz);
                this.getNavigator().tryMoveToXYZ(
                        targetPos.getX() + 0.5,
                        targetPos.getY(),
                        targetPos.getZ() + 0.5,
                        TravellersModConfig.movementSpeedWhileFollowing * 0.01D // slower idle walk
                );
            }
        }

        // 2) Random look behaviour (including owner / nearby entities)
        if (this.isInCombat()) {
            // In combat: let combat AI fully control head
            followerLookTarget = null;
            followerLookTicks = 0;
            return;
        }

        if (followerLookTicks > 0) {
            // Keep looking at current target
            if (followerLookTarget != null && followerLookTarget.isEntityAlive()) {
                this.getLookHelper().setLookPositionWithEntity(
                        followerLookTarget,
                        10.0F,
                        (float) this.getVerticalFaceSpeed()
                );
            } else if (followerLookHasPoint) {
                this.getLookHelper().setLookPosition(
                        followerLookX,
                        followerLookY,
                        followerLookZ,
                        10.0F,
                        (float) this.getVerticalFaceSpeed()
                );
            }
            followerLookTicks--;
            return;
        }


        // Time to pick a new thing to look at
// Time to pick a new thing to look at
        followerLookCooldown = 40 + this.rand.nextInt(60); // ~2–5s between decisions
        followerLookTicks   = 40 + this.rand.nextInt(80);  // hold gaze ~2–6s

        followerLookTarget = null;
        followerLookHasPoint = false;

// Search for nearby entities to look at
        double lookRadius = 6.0;
        AxisAlignedBB box = this.getEntityBoundingBox().grow(lookRadius, 2.0, lookRadius);

        List<EntityLivingBase> candidates = this.world.getEntitiesWithinAABB(
                EntityLivingBase.class,
                box,
                e -> e != null
                        && e != this
                        && e != owner
                        && e.isEntityAlive()
        );

// 0: look at owner
// 1: look at some nearby entity
// 2: look at a random point / ground
        int mode = this.rand.nextInt(3);

        if (mode == 1 && !candidates.isEmpty()) {
            // Look at random nearby entity
            followerLookTarget = candidates.get(this.rand.nextInt(candidates.size()));
            this.getLookHelper().setLookPositionWithEntity(
                    followerLookTarget,
                    10.0F,
                    (float) this.getVerticalFaceSpeed()
            );
        } else if (mode == 0) {
            // Look at owner
            followerLookTarget = owner;
            this.getLookHelper().setLookPositionWithEntity(
                    followerLookTarget,
                    10.0F,
                    (float) this.getVerticalFaceSpeed()
            );
        } else {
            // Look at a random point in the distance, mostly at horizon level
            double r = 3.0D + this.rand.nextDouble() * 6.0D; // 3–9 blocks away
            double angle = this.rand.nextDouble() * Math.PI * 2.0;

            double px = this.posX + Math.cos(angle) * r;
            double pz = this.posZ + Math.sin(angle) * r;

            double py;

            // 80% of the time: look at horizon / eye-height
            if (this.rand.nextInt(5) != 0) {
                double eyeY = this.posY + this.getEyeHeight();
                // Slight vertical variation around eye level
                py = eyeY - 0.3D + this.rand.nextDouble() * 0.6D;
            } else {
                // 20% of the time: glance a bit lower (not hard-ground staring)
                double eyeY = this.posY + this.getEyeHeight();
                py = eyeY - 1.0D - this.rand.nextDouble() * 0.8D;
            }

            followerLookHasPoint = true;
            followerLookX = px;
            followerLookY = py;
            followerLookZ = pz;

            this.getLookHelper().setLookPosition(
                    followerLookX,
                    followerLookY,
                    followerLookZ,
                    4.0F,
                    (float) this.getVerticalFaceSpeed()
            );
        }


    }

    private void applySoftSeparation(double dx, double dz, double strength) {
        // Normalize
        double len = Math.sqrt(dx*dx + dz*dz);
        if (len < 0.0001) return;

        dx /= len;
        dz /= len;

        // Apply small nudge velocity (no pathfinding!)
        this.motionX += dx * strength;
        this.motionZ += dz * strength;
    }

    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        // Off-screen ghost mode: ignore non-player targets entirely
        if (TravellersModConfig.travellerNoAggroOffscreen
                && !this.isInPlayerViewDistance()
                && !(target instanceof EntityPlayer)) {
            super.setAttackTarget(null);
            return;
        }

        // Never attack the owner
        if (isOwner(target)) {
            super.setAttackTarget(null);
            return;
        }

        // Apply your blacklist (no creepers etc.)
        if (target != null) {
            TravellerBlacklist blacklist = TravellersModConfig.TRAVELLER_AGGRO_BLACKLIST;
            if (blacklist.isBlacklisted(target)) {
                super.setAttackTarget(null);
                return;
            }
        }

        super.setAttackTarget(target);
    }


    public boolean isOwner(@Nullable EntityLivingBase target) {
        if (target == null) return false;
        EntityPlayer owner = getFollowOwner();
        return owner != null && owner == target;
    }
    @Override
    public void setAIMoveSpeed(float speed) {
        double base = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();

        float cap;

        if (this.isAimingBow()) {
            // Keep this conservative so aiming looks right
            cap = (float) Math.min(base * 0.98D, 0.30D);
        } else if (this.getAttackTarget() != null) {
            cap = (float) Math.min(base * 1.22D, 0.38D); // same combat cap
        } else if (this.isHiredFollower() && !this.isJobActive()) {
            cap = (float) (base * 1.30D);
        } else {
            // Normal idle travellers behaviour
            cap = (float) (base * 0.7D);
        }

        super.setAIMoveSpeed(Math.min(speed, cap));
    }
    @Override
    protected void dropLoot(boolean wasRecentlyHit, int lootingModifier, DamageSource source) {
        // Let base travellers drop its random backpack stuff etc.
        super.dropLoot(wasRecentlyHit, lootingModifier, source);

        if (world.isRemote) return;

        // --- ALWAYS drop everything from the personal backpack ---
        for (int i = 0; i < personalInventory.getSlots(); i++) {
            ItemStack stack = personalInventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            EntityItem ei = new EntityItem(world, this.posX, this.posY + 0.5, this.posZ, stack.copy());
            ei.setDefaultPickupDelay();
            world.spawnEntity(ei);

            personalInventory.setStackInSlot(i, ItemStack.EMPTY);
        }

    for (int i = 0; i < travelInventory.getSlots(); i++) {
        ItemStack stack = travelInventory.getStackInSlot(i);
        if (stack.isEmpty()) continue;

        EntityItem ei = new EntityItem(world, this.posX, this.posY + 0.5, this.posZ, stack.copy());
        ei.setDefaultPickupDelay();
        world.spawnEntity(ei);

        travelInventory.setStackInSlot(i, ItemStack.EMPTY);
    }

    }
    /**
     * Long-range catch-up: plan a single-stop travel route to the owner,
     * using the same travel AI the worker uses when returning from jobs.
     */
    public void startOwnerCatchupTravel() {
        if (!isHiredFollower()) return;

        EntityPlayer owner = getFollowOwner();
        if (owner == null) return;

        BlockPos approx = getApproximateSurfacePosNear(owner);

        // Reuse the return route logic (now with readyToDepart = true)
        updateReturnRoute(approx);
    }
    public void setEscortLeader(@Nullable EntityTravellerWorker leader) {
        if (leader == null) {
            this.escortLeaderEntityId = null;
        } else if (leader != this) {
            this.escortLeaderEntityId = leader.getEntityId();
        }
    }

    /**
     * Effective follow target: either a leader worker, or the owner player.
     */
    @Nullable
    public EntityLivingBase getEffectiveFollowTarget() {
        // Leader takes precedence if valid
        if (escortLeaderEntityId != null) {
            Entity e = this.world.getEntityByID(escortLeaderEntityId);
            if (e instanceof EntityTravellerWorker) {
                EntityTravellerWorker leader = (EntityTravellerWorker) e;

                // Only valid if leader is alive and still ultimately hired by same owner
                if (!leader.isDead && leader.isHiredFollower()
                        && Objects.equals(leader.getFollowOwnerId(), this.getFollowOwnerId())) {
                    return leader;
                }
            }
            // Leader invalid: fall back to owner
            escortLeaderEntityId = null;
        }

        return getFollowOwner();
    }
    private void tickStayAtIdleAnchor() {
        if (!isHiredFollower()) return;
        if (isJobActive()) return;
        if (followOrderActive) return; // only care when follow is OFF
        if (idleAnchorPos == null) return;

        // If fighting, let combat AI do its thing, we’ll walk back afterwards
        if (this.getAttackTarget() != null) {
            return;
        }

        // After combat, or normal idle: stay near the anchor
        double maxRadius = 3.0D;
        double distSq = this.getDistanceSqToCenter(idleAnchorPos);

        if (distSq > maxRadius * maxRadius) {
            if (this.getNavigator().noPath()) {
                this.getNavigator().tryMoveToXYZ(
                        idleAnchorPos.getX() + 0.5D,
                        idleAnchorPos.getY(),
                        idleAnchorPos.getZ() + 0.5D,
                        TravellersModConfig.movementSpeedWhileFollowing * 0.4D
                );
            }
        } else {
            // Close enough: prevent random drifting
            this.getNavigator().clearPath();
        }
    }

    public boolean isEscortingLeader(EntityTravellerWorker leader) {
        return escortLeaderEntityId != null
                && escortLeaderEntityId == leader.getEntityId();
    }

    public boolean hasEscortLeader() {
        return escortLeaderEntityId != null;
    }

    public void clearEscortLeader() {
        this.escortLeaderEntityId = null;
    }

}