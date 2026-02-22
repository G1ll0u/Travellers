package com.jubitus.travellers.entity.travellers;

import com.google.common.base.Optional;
import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIRoamInsideVillage;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIStopAndLookAtPlayer;
import com.jubitus.travellers.entity.entityAI.ambient.camp.EntityAICampJoinFire;
import com.jubitus.travellers.entity.entityAI.ambient.camp.EntityAICampPause;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIDefendOwnerProactively;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIOwnerHurtByTargetTraveller;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIOwnerHurtTargetTraveller;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIWorkerHurtByTargetTraveller;
import com.jubitus.travellers.entity.entityAI.follow.EntityAIFollowOwnerTraveller;
import com.jubitus.travellers.entity.entityAI.follow.EntityAIFollowTraveller;
import com.jubitus.travellers.entity.entityAI.travel.EntityAIMerchantDeliverGoods;
import com.jubitus.travellers.entity.entityAI.travel.EntityAITravel;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.utils.mobs.PlayerHostility;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager;
import com.jubitus.travellers.utils.world.spawn.TravellerVillageRegistry;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.EntityAIHurtByTarget;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
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
import net.minecraft.server.MinecraftServer;
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
import java.util.*;

public class EntityTravellerWorker extends EntityTraveller {

    public static final int PVP_ENGAGE_TICKS = 20 * 120; // 2 minutes
    public static final double PVP_DECLARE_CHECK_RADIUS = 64.0D;


    // --- "Go..." order state ---
    private boolean goOrderActive = false;
    @Nullable private BlockPos goOrderTargetPos = null;
    private int goOrderTargetDim = 0; // for persistence (same dim usually)

    // --- escort leader watchdog ---
    private int escortLeaderCheckCooldown = 0;

    private static final int MIN_REP_TO_TRADE = -1024;
    // --- anchor guard tuning ---
    private static final double ANCHOR_GUARD_RADIUS = 192.0D;     // can fight within this radius of anchor
    private static final double ANCHOR_HARD_LEASH = 256.0D;     // if worker itself goes beyond this, disengage & return
    // --- anchor defense ---
    private static final double ANCHOR_DEFEND_RADIUS = 12.0D; // blocks
    private static final int ANCHOR_SCAN_INTERVAL_TICKS = 20; // 1 time per second
    private static final int CATCHUP_REFRESH_TICKS = 20 * 10; // 10 seconds
    private static final double MOTION_EPSILON = 0.0004D;
    private static final DataParameter<Boolean> USING_TRAVEL_AI =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Integer> LEADER_FOLLOWER_COUNT_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.VARINT);
    static final DataParameter<Boolean> HIRED_FOLLOWER_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.BOOLEAN);
    static final DataParameter<Optional<UUID>> FOLLOW_OWNER_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.OPTIONAL_UNIQUE_ID);
    // --- assigned home sync (server -> client) ---
    private static final long NO_HOME = Long.MIN_VALUE;
    // --- assigned home sync ---
    static final DataParameter<com.google.common.base.Optional<BlockPos>> HOME_POS_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.OPTIONAL_BLOCK_POS);
    static final DataParameter<Integer> HOME_DIM_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.VARINT);
    static final DataParameter<String> HOME_NAME_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.STRING);
    public static final DataParameter<Optional<UUID>> ESCORT_LEADER_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.OPTIONAL_UNIQUE_ID);
    private static final double RETURN_FOLLOW_SWITCH_DIST = TravellersModConfig.returnFollowSwitchDist; // tweak (24–40 works well)
    // How often to refresh the catchup target (ticks)
    private static final int CATCHUP_REPATH_INTERVAL_TICKS = 40; // 2s
    // --- home chest idle behaviour (unemployed roaming) ---
    private static final int HOME_CHEST_WAIT_MAX_TICKS = 5 * 60 * 20; // 5 minutes
    // How long to wait after loot pickup before saying goodbye & roaming again
    private static final int POST_JOB_FAREWELL_TICKS = 0; // 5 seconds (tweak)
    // How often (in ticks) to refresh target when returning
    private static final int RETURN_REPATH_INTERVAL_TICKS = 200; // ~10 seconds
    private static final int WORKER_SLOTS = 18; // cargo slots
    public final PlayerHostility playerHostility = new PlayerHostility(this);
    private final Set<ChunkPos> forced = new java.util.HashSet<>();
    // NEW: 18-slot personal inventory (like a small chest on the worker)
    private final ItemStackHandler personalInventory = new ItemStackHandler(18);
    private final int lastTravelTick = 0;
    // 18-slot cargo inventory used for deliveries (separate from personal backpack)
    private final ItemStackHandler travelInventory = new ItemStackHandler(18);
    // --- recruit mission state ---
    final List<BlockPos> recruitVillages = new ArrayList<>();
    // track which villages already spawned recruits (avoid double spawn)
    final Set<BlockPos> recruitSpawnedAt = new HashSet<>();
    // --- follower / hire state (existing) ---
    @Nullable
    public UUID followOwnerId = null;
    public boolean hiredFollower = false;
    @Nullable
    BlockPos assignedHomePos = null;
    int assignedHomeDim = 0;
    String assignedHomeName = ""; // optional but handy
    boolean goingHome = false; // travel-in-progress
    private int anchorScanCooldown = 0;
    // --- dynamic owner catchup goal ---
    private BlockPos cachedCatchupGoal = null;
    private long nextCatchupRefreshTick = 0;
    private int leaderCountCooldown = 0;
    private int stuckAtVillageTicks = 0;
    private boolean returnUsingFollow = false;
    private int catchupRepathCooldown = 0;
    // recruit mission return phase: follow owner home
    boolean recruitReturningToOwner = false;
    // NEW: whether follow AI is currently active (player’s "Follow" order)
    private boolean followOrderActive = false;
    // NEW: where the worker was told to stay when follow was toggled OFF
    @Nullable
    BlockPos idleAnchorPos = null;
    // NEW: leader logic – if non-null, this worker follows another worker instead of the player
    @Nullable
    private UUID escortLeaderUUID = null;
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
    private boolean atHomeChest = false;
    private int homeChestWaitTicks = 0;
    int postJobFarewellTicks = 0;
    boolean postJobFarewellActive = false;
    private double baseFollowRange;
    private double boostedFollowRange;
    private boolean usingBoostedNav = false;
    // --- home village (like merchants) ---
    @Nullable
    BlockPos homeVillageCenter;
    private boolean catchupTravel;
    @Nullable
    private UUID catchupTargetUUID = null;
    boolean awaitingPickup = false;
    // Cooldown counter for re-pathing
    private int returnRepathCooldown = 0;
    // If true, cargo is treated as a donation (extra rep, no money)
    boolean donationJob = false;
    int recruitIndex = 0;
    @Nullable
    private BlockPos recruitReturnPos = null;
    boolean recruitActive = false;
    @Nullable
    BlockPos jobVillageCenter = null;
    WorkerMode mode = WorkerMode.IDLE;
    boolean jobDelivered = false;
    // "Employer" = player who hired this worker for the current/last job
    @Nullable
    UUID employerId;
    @Nullable
    BlockPos employerReturnPos;   // where to go back (captured when job starts)
    // When true, base EntityTraveller will NOT auto-plan routes
    boolean routeLocked = false;
    boolean hasUnsoldGoods = false;

    public EntityTravellerWorker(World worldIn) {
        super(worldIn);
        this.enablePersistence();
        this.forceSpawn = true;

        removeTaskOfType(EntityAIRoamInsideVillage.class);
        removeTaskOfType(EntityAITravel.class);
        removeTaskOfType(EntityAIFollowTraveller.class);
        removeTaskOfType(EntityAICampJoinFire.class);
        removeTaskOfType(EntityAICampPause.class);
        removeTaskOfType(EntityAIStopAndLookAtPlayer.class);
        this.tasks.addTask(10, new EntityAIStopAndLookAtPlayer(this, 3.2D, 30.0F, 30.0F));
        this.targetTasks.addTask(0, new EntityAIOwnerHurtByTargetTraveller(this));
        this.targetTasks.addTask(1, new EntityAIOwnerHurtTargetTraveller(this));
        this.targetTasks.addTask(2,
                new EntityAINearestAttackableTarget<>(
                        this,
                        EntityTraveller.class,
                        10,
                        true,
                        false,
                        t -> t != null
                                && t.isEntityAlive()
                                && t.isAggressiveToTravellers()   // raiders override true
                                && !this.isAggressiveToTravellers()
                )
        );

// ✅ NEW: proactive defense before owner gets hit + spreads workers across targets
        this.targetTasks.addTask(0, new EntityAIDefendOwnerProactively(this, 5)); // every 5 ticks

// React if WE get hit
// React if WE get hit (but only when retaliation is allowed)
        this.targetTasks.addTask(3, new EntityAIWorkerHurtByTargetTraveller(this));

// Target hostile-marked players (owner-defined enemy list)
        // 4) Target ENEMY hired workers (followers) as well
        this.targetTasks.addTask(4, new EntityAINearestAttackableTarget<>(
                this,
                EntityTravellerWorker.class,
                10,
                true,
                false,
                other -> this.isEnemyHiredWorker(other)
        ));


// 5) Target hostile-marked players (owner-defined enemy list)
        this.targetTasks.addTask(5, new EntityAINearestAttackableTarget<>(
                this,
                EntityPlayer.class,
                10,
                true,
                false,
                p -> this.isEnemyPlayer(p)
        ));

// 6) Fallback: pick nearby IMob if nothing else is happening
        this.targetTasks.addTask(6, new EntityAINearestAttackableTarget<>(
                this,
                EntityLivingBase.class,
                10,
                true,
                false,
                e -> {
                    if (e == null || e.isDead) return false;
                    if (e == this) return false;
                    if (isOwner(e)) return false;
                    if (!(e instanceof net.minecraft.entity.monster.IMob)) return false;

                    TravellerBlacklist blacklist = TravellersModConfig.TRAVELLER_AGGRO_BLACKLIST;
                    return blacklist == null || !blacklist.isBlacklisted(e);
                }
        ));


        // Worker travel AI (for jobs)
        this.aiTravel = new EntityAITravel(
                this,
                TravellersModConfig.movementSpeedWhileTravel,
                TravellersModConfig.workerStepBlocks
        );
        this.tasks.addTask(11, aiTravel);

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

    public boolean isOwner(@Nullable EntityLivingBase target) {
        if (target == null) return false;
        EntityPlayer owner = getFollowOwner();
        return owner != null && owner == target;
    }

    public boolean isPlayerMarkedHostile(EntityPlayer p) {
        return playerHostility.isPlayerMarkedHostile(p);
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

    public static int computeRouteDistance2D(BlockPos start, List<BlockPos> villages, BlockPos end) {
        if (villages == null || villages.isEmpty()) {
            return dist2D(start, end);
        }
        int total = 0;
        BlockPos prev = start;
        for (BlockPos v : villages) {
            total += dist2D(prev, v);
            prev = v;
        }
        total += dist2D(prev, end);
        return total;
    }

    public static int computeRecruitFee(int routeDistance, int villagesCount) {
        int flat = TravellersModConfig.recruitFeeFlat;
        int perVillage = TravellersModConfig.recruitFeePerVillage * villagesCount;
        int perDist = (int) Math.ceil(routeDistance * TravellersModConfig.recruitFeePerBlock);
        return Math.max(0, flat + perVillage + perDist);
    }

    /**
     * Find a safe spawn pos near a village center: prefers ground, can fall back to inside-safe.
     */
    public static BlockPos findRecruitSpawnPos(World world, BlockPos villageCenter, Random rand) {
        if (world == null || villageCenter == null) return villageCenter;

        // 1) Try some random offsets around the center, pick best safe spot
        final int tries = 24;
        final int radius = 10; // tweak: how far from center we allow

        for (int i = 0; i < tries; i++) {
            int dx = rand.nextInt(radius * 2 + 1) - radius;
            int dz = rand.nextInt(radius * 2 + 1) - radius;

            BlockPos col = new BlockPos(villageCenter.getX() + dx, 0, villageCenter.getZ() + dz);

            // top block at that XZ
            BlockPos top = world.getTopSolidOrLiquidBlock(col);

            // Spawn on top block (usually top is the air block above ground)
            BlockPos spawn = top;

            // If top is still too high (roof), we want the *ground*.
            // Simple heuristic: if top is higher than villageCenter by a lot, search downward for first solid with air above.
            if (spawn.getY() > villageCenter.getY() + 3) {
                BlockPos downFixed = findGroundBelow(world, new BlockPos(spawn.getX(), spawn.getY(), spawn.getZ()), 20);
                if (downFixed != null) spawn = downFixed;
            }

            if (isSafeSpawn(world, spawn)) return spawn;
        }

        // 2) Spiral search around center (deterministic fallback)
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    BlockPos col = new BlockPos(villageCenter.getX() + dx, 0, villageCenter.getZ() + dz);
                    BlockPos top = world.getTopSolidOrLiquidBlock(col);
                    BlockPos spawn = top;

                    if (spawn.getY() > villageCenter.getY() + 3) {
                        BlockPos downFixed = findGroundBelow(world, new BlockPos(spawn.getX(), spawn.getY(), spawn.getZ()), 24);
                        if (downFixed != null) spawn = downFixed;
                    }

                    if (isSafeSpawn(world, spawn)) {
                        int d = dx * dx + dz * dz;
                        if (d < bestDist) {
                            bestDist = d;
                            best = spawn;
                        }
                    }
                }
            }
            if (best != null) return best;
        }

        // 3) Last resort: whatever we had
        return villageCenter;
    }

    private static BlockPos findGroundBelow(World world, BlockPos start, int maxDown) {
        BlockPos p = start;
        for (int i = 0; i < maxDown && p.getY() > 2; i++) {
            BlockPos below = p.down();
            if (world.getBlockState(below).getMaterial().isSolid()
                    && world.isAirBlock(p)
                    && world.isAirBlock(p.up())) {
                return p;
            }
            p = below;
        }
        return null;
    }

    /**
     * Checks 2-block-high collision free + not inside liquids.
     */
    private static boolean isSafeSpawn(World world, BlockPos pos) {
        if (pos == null) return false;

        // Need air for feet + head
        if (!world.isAirBlock(pos)) return false;
        if (!world.isAirBlock(pos.up())) return false;

        // Block below must be solid
        IBlockState below = world.getBlockState(pos.down());
        if (!below.getMaterial().isSolid()) return false;

        // Avoid water/lava
        IBlockState feet = world.getBlockState(pos);
        if (feet.getMaterial().isLiquid()) return false;

        // Collision check (optional but nice)
        AxisAlignedBB bb = new AxisAlignedBB(pos).expand(0, 1, 0); // 2 blocks tall
        return world.getCollisionBoxes(null, bb).isEmpty();
    }

    public boolean isGoingHome() {
        return goingHome;
    }

    public ItemStackHandler getPersonalInventory() {
        return personalInventory;
    }

    public boolean hasAssignedHomeClient() {
        com.google.common.base.Optional<BlockPos> o = this.dataManager.get(HOME_POS_DM);
        return o != null && o.isPresent();
    }

    @Nullable
    public BlockPos getAssignedHomePos() {
        com.google.common.base.Optional<BlockPos> o = this.dataManager.get(HOME_POS_DM);
        return (o != null && o.isPresent()) ? o.get() : null;
    }

    public int getAssignedHomeDim() {
        return this.dataManager.get(HOME_DIM_DM);
    }

    public String getAssignedHomeName() {
        return this.dataManager.get(HOME_NAME_DM);
    }

    public void setAssignedHome(@Nullable BlockPos pos, int dim, String name) {
        this.assignedHomePos = pos;
        this.assignedHomeDim = dim;
        this.assignedHomeName = (name == null ? "" : name);

        if (!world.isRemote) {
            this.dataManager.set(HOME_POS_DM, pos == null
                    ? com.google.common.base.Optional.absent()
                    : com.google.common.base.Optional.of(pos));
            this.dataManager.set(HOME_DIM_DM, dim);
            this.dataManager.set(HOME_NAME_DM, this.assignedHomeName);
        }
    }

    public void addHostilePlayer(UUID id) {
        playerHostility.addHostilePlayer(id);
    }

    @Nullable
    private EntityTravellerWorker getHostileListOwner() {
        return playerHostility.getHostileListOwner();
    }

    @Nullable
    public UUID getEscortLeaderUUID() {
        return playerHostility.getEscortLeaderUUID();
    }

    @Nullable
    private EntityTravellerWorker resolveEscortLeader(UUID id) {

        return playerHostility.resolveEscortLeader(id);
    }

    public void removeHostilePlayer(UUID id) {
        playerHostility.removeHostilePlayer(id);
    }

    // =====================================================
// Enemy hired-worker logic (shared helper)
// =====================================================


    public boolean isHiredFollower() {
        return this.dataManager.get(HIRED_FOLLOWER_DM) && getFollowOwnerUUID() != null;
    }

    // --- follower / hire state ---
    @Nullable
    public UUID getFollowOwnerId() {
        Optional<UUID> o = this.dataManager.get(FOLLOW_OWNER_DM);
        return o.isPresent() ? o.get() : null;
    }

    public Set<UUID> getEffectiveHostilePlayers() {
        return playerHostility.getEffectiveHostilePlayers();
    }

    @Nullable
    public UUID getFollowOwnerUUID() {
        Optional<UUID> o = this.dataManager.get(FOLLOW_OWNER_DM);
        return o.isPresent() ? o.get() : null;
    }

    @Nullable
    public EntityTravellerWorker getGroupLeaderOrSelf() {
        return playerHostility.getGroupLeaderOrSelf();
    }

    /**
     * Copy THIS worker's hostile list to all loaded hired workers of the same owner.
     * Call this on the leader right after editing hostilePlayers.
     */
    public void syncHostileListToEscorts() {
        playerHostility.syncHostileListToEscorts();
    }


    @Nullable
    private EntityLivingBase resolveCatchupTarget() {
        if (catchupTargetUUID == null) return null;
        if (world instanceof WorldServer) {
            Entity e = ((WorldServer) world).getEntityFromUuid(catchupTargetUUID);
            if (e instanceof EntityLivingBase && !e.isDead) return (EntityLivingBase) e;
        }
        return null;
    }

    public void startOwnerCatchupTravel() {
        startCatchupTravelTo(getFollowOwner());
    }

    public void startCatchupTravelTo(@Nullable EntityLivingBase target) {
        if (target == null) return;
        if (target.dimension != this.dimension) return;

        this.catchupTargetUUID = target.getUniqueID();
        this.cachedCatchupGoal = null;
        this.nextCatchupRefreshTick = 0;

        this.catchupTravel = true;
        this.catchupRepathCooldown = 0;

        if (this.isHiredFollower()) this.setFollowOrderActive(true);

        updateCatchupRoute(getApproximateSurfacePosNear(target));
    }

    @Nullable
    public EntityPlayer getFollowOwner() {
        UUID id = getFollowOwnerUUID();
        return id == null ? null : this.world.getPlayerEntityByUUID(id);
    }

    public void stopOwnerCatchupTravel() {
        stopCatchupTravel();
    }

    public void setHostile(UUID target, boolean hostile) {
        playerHostility.setHostile(target, hostile);
    }

    @Nullable
    public BlockPos getHomeVillage() {
        return this.homeVillageCenter;
    }

    public void setHomeVillage(@Nullable BlockPos pos) {
        this.homeVillageCenter = pos;
    }

    public boolean canStartRecruitMission() {
        return this.mode == WorkerMode.IDLE
                && !this.postJobFarewellActive
                && !this.isWaitingForPickup()
                && !this.recruitActive;
    }

    public boolean isUsingTravelAI() {
        return this.dataManager.get(USING_TRAVEL_AI);
    }

    // Base hook: auto-planner will skip this entity when true
    @Override
    public boolean isRouteLocked() {
        return routeLocked;
    }

    // Use worker stats if you want them different
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();

        baseFollowRange = TravellersModConfig.workerFollowRange;
        boostedFollowRange = baseFollowRange * 2.0D;

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
    public void setAttackTarget(@Nullable EntityLivingBase target) {

        // Never attack the owner
        if (isOwner(target)) {
            super.setAttackTarget(null);
            return;
        }

        // Blacklist
        if (target != null) {
            TravellerBlacklist blacklist = TravellersModConfig.TRAVELLER_AGGRO_BLACKLIST;
            if (blacklist != null && blacklist.isBlacklisted(target)) {
                super.setAttackTarget(null);
                return;
            }
        }

        // NEW: line-of-sight sanity (don’t hard-block “revenge” at point blank)
        if (target != null) {
            double d2 = this.getDistanceSq(target);
            boolean sees = this.canEntityBeSeen(target);

            // Allow unseen only if very close OR it’s directly involved in combat with us/owner
            boolean isDirectCombat =
                    target == this.getRevengeTarget()
                            || target == this.getLastAttackedEntity()
                            || (getFollowOwner() != null &&
                            (target == getFollowOwner().getRevengeTarget()
                                    || (target instanceof EntityLiving && ((EntityLiving) target).getAttackTarget() == getFollowOwner())));

            if (!sees && d2 > (8.0D * 8.0D) && !isDirectCombat) {
                super.setAttackTarget(null);
                return;
            }
        }

        // (keep your offscreen rules, etc.)
        super.setAttackTarget(target);
    }

    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(USING_TRAVEL_AI, false);
        this.dataManager.register(HIRED_FOLLOWER_DM, false);
        this.dataManager.register(FOLLOW_OWNER_DM, Optional.absent());
        this.dataManager.register(ESCORT_LEADER_DM, Optional.absent());
        this.dataManager.register(LEADER_FOLLOWER_COUNT_DM, 0);
        this.dataManager.register(HOME_POS_DM, com.google.common.base.Optional.absent());
        this.dataManager.register(HOME_DIM_DM, 0);
        this.dataManager.register(HOME_NAME_DM, "");

    }

    // NBT: persist employer + job so restart doesn’t lose the mission


    /**
     * Donate backpack contents to the target village & then mark job delivered.
     * Call this from your worker-specific delivery AI when at the town hall.
     */

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

    @Override
    public void setAIMoveSpeed(float speed) {
        double base = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();

        float cap;

        if (this.isAimingBow()) {
            // Keep this conservative so aiming looks right
            cap = (float) Math.min(base * 0.98D, 0.30D);
        } else if (this.getAttackTarget() != null) {
            // Slightly faster when guarding an anchor so they don't look sluggish.
            double mult = isGuardingAnchor() ? 1.35D : 1.22D;
            double max = isGuardingAnchor() ? 0.87D : 0.80D;
            cap = (float) Math.min(base * mult, max);
        } else if (this.isHiredFollower() && !this.isJobActive()) {
            double d = 0.0D;
            EntityLivingBase t = this.getEffectiveFollowTarget();
            if (t != null) d = this.getDistance(t);

            double mult = 1.35D;
            if (d > 32.0D) mult = 1.40D;
            if (d > 64.0D) mult = 1.60D;
            if (d > 128.0D) mult = 1.80D;

            cap = (float) (base * mult);
        } else {
            // Normal idle travellers behaviour
            cap = (float) (base * 0.7D);
        }

        super.setAIMoveSpeed(Math.min(speed, cap));
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if (!world.isRemote && catchupTravel) {
            EntityLivingBase t = resolveCatchupTarget();

            // fallback: if target not loaded, try effective follow target
            if (t == null) t = getEffectiveFollowTarget();

            if (t != null && !t.isDead && t.dimension == this.dimension) {
                double dx = this.posX - t.posX;
                double dz = this.posZ - t.posZ;
                double distHsq = dx * dx + dz * dz;

                double switchSq = RETURN_FOLLOW_SWITCH_DIST * RETURN_FOLLOW_SWITCH_DIST;

                if (this.canEntityBeSeen(t) && distHsq <= switchSq) {
                    stopOwnerCatchupTravel(); // rename if you want
                } else {
                    if (--catchupRepathCooldown <= 0) {
                        catchupRepathCooldown = CATCHUP_REPATH_INTERVAL_TICKS;

                        BlockPos approx = getApproximateSurfacePosNear(t);

                        if (this.getTravelCommon().getTargetVillage() == null
                                || this.getTravelCommon().getTargetVillage().distanceSq(approx) > (16 * 16)) {
                            updateCatchupRoute(approx);
                        }
                    }
                }
            } else {
                stopOwnerCatchupTravel();
            }
        }


        if (!world.isRemote && recruitReturningToOwner) {
            tickRecruitReturnToOwner();
        }

        // Server-side: register/update this worker as a chunkloader
        if (!world.isRemote) {
            if ((this.ticksExisted % 20) == 0) { // once per second
                EntityLivingBase tgt = this.getAttackTarget();
                if (tgt instanceof EntityPlayer) {
                    if (!this.isEnemyPlayer((EntityPlayer) tgt)) {
                        this.setAttackTarget(null);
                        this.setRevengeTarget(null);
                        this.setInCombat(false);
                    }
                } else if (tgt instanceof EntityTravellerWorker) {
                    if (!this.isEnemyHiredWorker((EntityTravellerWorker) tgt)) {
                        this.setAttackTarget(null);
                        this.setRevengeTarget(null);
                        this.setInCombat(false);
                    }
                }
            }

            if (--escortLeaderCheckCooldown <= 0) {
                escortLeaderCheckCooldown = 20;
                tickEscortLeaderValidity();
            }
// Update leader follower count every 1s (cheap, helps GUI)
            if (--leaderCountCooldown <= 0) {
                leaderCountCooldown = 20;
                updateLeaderFollowerCount();
            }

            TravellerChunkManager.INSTANCE.onChunkloaderTick(
                    this,
                    TravellersModConfig.workerChunkRadius

            );
            updateDynamicNavigation();
            tickFollowerIdleBehaviour();
            tickStayAtIdleAnchor(); // NEW
            playerHostility.tickServer();

            // --- NEW: guard leash when Follow is OFF ---
            if (!world.isRemote && isGuardingAnchor()) {
                EntityLivingBase tgt = this.getAttackTarget();
                if (tgt instanceof EntityPlayer && this.isEnemyPlayer((EntityPlayer) tgt)) {
                    return; // keep aggro (declared OR engaged)
                }

                double hardLeashSq = ANCHOR_HARD_LEASH * ANCHOR_HARD_LEASH;

                // If WE got pulled too far from the anchor, stop fighting and go back.
                if (this.getDistanceSqToCenter(idleAnchorPos) > hardLeashSq) {
                    this.setAttackTarget(null);
                    this.setRevengeTarget(null);
                    this.setInCombat(false);
                    this.getNavigator().clearPath();
                    // tickStayAtIdleAnchor() will pull us back to idleAnchorPos after this.
                }

                // Optional: don't even ACCEPT targets that are far from the anchor.
                if (tgt != null) {
                    double guardSq = ANCHOR_GUARD_RADIUS * ANCHOR_GUARD_RADIUS;
                    if (distSqFromAnchor(tgt) > guardSq) {
                        this.setAttackTarget(null);
                        this.setRevengeTarget(null);
                        this.setInCombat(false);
                        this.getNavigator().clearPath();
                    }
                }
            }

            clearAggroIfOffscreen();
            this.setUsingTravelAI(shouldBeUsingTravelAI());


        }

        if (world.isRemote) return;

        TravelCommon tc = this.getTravelCommon();

        if (!world.isRemote && goingHome && assignedHomePos != null && assignedHomeDim == this.dimension) {
            int r = tc.arrivalRadiusBlocks(assignedHomePos);
            if (this.getDistanceSqToCenter(assignedHomePos) <= (double) (r * r)) {

                goingHome = false;

                // 1) wipe travel completely (no route, no waypoint, no target)
                stopAllTravelNow();

                // 2) now anchor at home (follow OFF)
                this.followOrderActive = false;
                this.idleAnchorPos = assignedHomePos;
                this.getNavigator().clearPath();

                // OPTIONAL: if you truly need tc.targetVillage=home for chest-waiting logic,
                // set it AFTER stopAllTravelNow(), and be sure AITravel won't run:
                // tc.setTargetVillage(assignedHomePos);
                // this.setTargetVillage(assignedHomePos);
                // tc.setReadyToDepart(false);

                EntityPlayer owner = getFollowOwner();
                if (owner != null) {
                    owner.sendMessage(new TextComponentString(TextFormatting.GREEN + "Worker is now at home."));
                }
            }
        }
// --- "Go..." order arrival ---
        if (!world.isRemote && goOrderActive && goOrderTargetPos != null && goOrderTargetDim == this.dimension) {
            int r = TravellersModConfig.workerGoArrivalRadius;
            if (this.getDistanceSqToCenter(goOrderTargetPos) <= (double) (r * r)) {

                // Stop travel completely
                goOrderActive = false;

                stopAllTravelNow();

                // Stay here: follow OFF + anchor at destination
                this.followOrderActive = false;
                this.idleAnchorPos = goOrderTargetPos;
                this.getNavigator().clearPath();

                // Clear target info (optional)
                // goOrderTargetPos = goOrderTargetPos; // keep it if you want display, or clear:
                // goOrderTargetPos = null;
            }
        }


        if (!world.isRemote && this.mode == WorkerMode.RECRUITING && recruitActive) {
            tickRecruitMission();
        }
        // 1) Going TO the village (unchanged)
        if (mode == WorkerMode.TO_VILLAGE && jobVillageCenter != null && !jobDelivered) {
            int r = tc.arrivalRadiusBlocks(jobVillageCenter);
            if (this.getDistanceSqToCenter(jobVillageCenter) <= (double) (r * r)) {
                donateInventoryToVillage(jobVillageCenter);
                // donateInventoryToVillage calls onJobDelivered() already
            }
        }

        // 2) RETURNING: re-path, walk to player, then wait for pickup
// RETURNING: re-path with travel AI (long range), vanilla path only at close range
        if (mode == WorkerMode.RETURNING && jobDelivered) {
            EntityPlayer employer = getEmployer();

            if (employer != null) {

                // Use horizontal distance (better for caves / Y differences)
                double dx = this.posX - employer.posX;
                double dz = this.posZ - employer.posZ;
                double distHsq = dx * dx + dz * dz;

// --- Switch from Travel AI -> Follow AI when we can SEE the employer ---
                if (!awaitingPickup) {
                    // horizontal distance is better than 3D (caves / Y offsets)

                    double switchSq = RETURN_FOLLOW_SWITCH_DIST * RETURN_FOLLOW_SWITCH_DIST;

                    boolean sees = this.canEntityBeSeen(employer);

                    // Use a little hysteresis so it doesn't flicker on/off
                    double switchOffSq = (RETURN_FOLLOW_SWITCH_DIST + 10.0D);
                    switchOffSq *= switchOffSq;

                    if (!returnUsingFollow) {
                        if (sees && distHsq <= switchSq) {
                            returnUsingFollow = true;

                            // Kill Travel AI state so it can't teleport when path drops
                            TravelCommon tc2 = this.getTravelCommon();
                            tc2.route.clear();
                            tc2.routeIndex = -1;
                            tc2.setTargetVillage(null);
                            this.setTargetVillage(null); // IMPORTANT
                            tc2.setCurrentWaypoint(null);
                            tc2.setReadyToDepart(false);
                            tc2.setDepartCooldownTicks(0);

                            this.getNavigator().clearPath();

                            // Ensure follow isn't off (clears idleAnchor)
                            if (this.isHiredFollower()) {
                                this.setFollowOrderActive(true);
                            }
                        }
                    } else {
                        // If we lost sight / got far again, let Travel AI take over again
                        if (!sees || distHsq > switchOffSq) {
                            returnUsingFollow = false;
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
                        if (this.isHiredFollower()) {
                            this.setFollowOrderActive(true);
                        } else {
                            updateReturnRoute(getApproximateSurfacePosNear(employer));
                        }
                    }
                }

                // Keep long-range travel AI updated
                if (!awaitingPickup && !returnUsingFollow) {
                    // ONLY travel AI logic when we are NOT using follow
                    if (returnRepathCooldown-- <= 0) {
                        returnRepathCooldown = RETURN_REPATH_INTERVAL_TICKS;
                        BlockPos newApprox = getApproximateSurfacePosNear(employer);

                        if (this.employerReturnPos == null
                                || this.employerReturnPos.distanceSq(newApprox) > (32 * 32)) {
                            updateReturnRoute(newApprox);
                        }
                    }
                }


                // Arrival + message
                if (!awaitingPickup && distHsq <= nearSq) {

                    tc.route.clear();
                    tc.routeIndex = -1;
                    tc.setTargetVillage(null);
                    this.setTargetVillage(null); // IMPORTANT
                    tc.setCurrentWaypoint(null);
                    tc.setReadyToDepart(false);
                    tc.setDepartCooldownTicks(0);
                    this.getNavigator().clearPath();

                    awaitingPickup = true;

                    this.playHelloSound();

                    String msg = this.hasUnsoldGoods
                            ? TextFormatting.WHITE + "I'm here. I couldn't sell everything, some goods are still in my pack."
                            : TextFormatting.WHITE + "I'm here with your goods. Check my pack.";
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

                // Now fully clear job state and unlock auto-roaming
                finishJob();
            } else {
                // During farewell, keep him from wandering off:
                this.getNavigator().clearPath();
            }
        }
        if (!world.isRemote && this.isHiredFollower() && this.isFollowOrderActive() && !this.isJobActive()) {
            EntityLivingBase follow = getEffectiveFollowTarget(); // leader worker OR owner player
            if (follow != null && !follow.isDead && follow.dimension == this.dimension) {
                double dx = follow.posX - this.posX;
                double dz = follow.posZ - this.posZ;
                double distH = Math.sqrt(dx * dx + dz * dz);

                if (distH > 200.0D && !this.catchupTravel) {
                    this.startCatchupTravelTo(follow); // IMPORTANT: catch up to leader if escorting
                }
            }
            if ((this.ticksExisted % 5) == (this.getEntityId() & 4)) { // staggered, every ~5 ticks
                if (this.getAttackTarget() == null && !this.isInCombat()) {
                    EntityLivingBase owner = this.getEffectiveFollowTarget();
                    EntityLivingBase t = findThreatNearOwner(owner, 14.0D);
                    if (t != null) {
                        this.trySetAttackTargetLimited(t, 2); // your limiter-aware setter
                    }
                }
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

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;

        if (!this.isInCombat()) {
            // Choose hello vs talk
            if (shouldHelloFor(player)) {
                playHelloSound();         // one hello variation
                markHello(player);        // start the 5-minute window for THIS player
            } else {
                playTalkSound();          // normal chatter
            }
        }

        // 1) If we are back from a job and waiting for pickup, ALWAYS open the cargo GUI
        if (this.mode == WorkerMode.RETURNING && this.jobDelivered && this.awaitingPickup && !this.isInCombat()) {
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
        if (!this.isInCombat()) {
            player.openGui(
                    Travellers.INSTANCE,
                    TravellersGuiIds.WORKER_HUB,
                    world,
                    this.getEntityId(),
                    0, 0
            );
        }
        return true;
    }

    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            clearLeaderFromLoadedEscortsOnDeath();
            TravellerChunkManager.INSTANCE.untrackChunkloader(this);

            if (homeVillageCenter != null && world instanceof WorldServer) {
                WorldServer ws = (WorldServer) world;
                TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);

// Clear only if this UUID is the registered one.
// Do NOT respawn here: the village may be unloaded.
// When the village chunks are loaded again, ensureWorkersForVillages() will respawn.
                reg.clearWorkerFor(homeVillageCenter, this.getUniqueID());
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

    @Nullable
    private EntityPlayer getEmployer() {
        if (employerId == null) return null;
        return this.world.getPlayerEntityByUUID(employerId);
    }

    @Override
    public boolean shouldAttackExtraEntity(Entity e) {
        if (e instanceof EntityTravellerWorker) {
            return this.isEnemyHiredWorker((EntityTravellerWorker) e);
        }
        if (e instanceof EntityPlayer) {
            return this.isEnemyPlayer((EntityPlayer) e);
        }
        return super.shouldAttackExtraEntity(e);
    }


    @Override
    public void onConfigReload() {
        super.onConfigReload();

        // Sync worker-specific ranges from config
        this.baseFollowRange = TravellersModConfig.workerFollowRange;
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

    public void setEscortLeaderUUID(@Nullable UUID id) {
        // Prevent cycles / self-follow
        if (wouldCreateEscortCycle(id)) {
            id = null;
        }

        this.escortLeaderUUID = id; // optional local cache

        if (!world.isRemote) {
            this.dataManager.set(ESCORT_LEADER_DM, id == null ? Optional.absent() : Optional.of(id));
        }
    }

    private boolean wouldCreateEscortCycle(@Nullable UUID newLeaderId) {
        if (newLeaderId == null) return false;

        // can't follow self
        if (newLeaderId.equals(this.getUniqueID())) return true;

        // Walk up the leader chain: newLeader -> its leader -> ...
        // If we ever reach "this", it would create a cycle.
        UUID cur = newLeaderId;

        // safety cap (prevents infinite loops if data is corrupted)
        for (int i = 0; i < 32 && cur != null; i++) {
            EntityTravellerWorker curLeader = playerHostility.resolveEscortLeader(cur);
            if (curLeader == null || curLeader.isDead) return false;

            UUID next = curLeader.getEscortLeaderUUID();

            if (next == null) return false;
            if (next.equals(this.getUniqueID())) return true;

            cur = next;
        }

        // If chain is absurdly long / weird, treat as unsafe
        return true;
    }

    // called by server when player confirms job in GUI
    // Old signature, gilles to non-donation job
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
        if (this.jobDelivered) return;
        this.jobDelivered = true;
        this.mode = WorkerMode.RETURNING;

        if (this.isHiredFollower()) {
            this.setFollowOrderActive(true); // clears idleAnchorPos
        }

        EntityPlayer employer = getEmployer();
        if (employer != null) {
            updateReturnRoute(getApproximateSurfacePosNear(employer));
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

        // KEEP companion mode locked if hired follower
        this.routeLocked = this.isHiredFollower();   // <-- change

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;
        tc.setTargetVillage(null);
        this.setTargetVillage(null);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    @Override
    protected boolean canDespawn() {
        // Never allow vanilla despawn logic to remove workers
        return false;
    }

    @Override
    protected void despawnEntity() {
        // NO-OP: workers never despawn due to distance / idle checks
        // (they can still die from damage via setDead/onDeath)
    }

    @Override
    public boolean isNoDespawnRequired() {
        // Tell vanilla "this entity must never despawn"
        return true;
    }

    @Nullable
    private EntityPlayer getEmployerPlayer() {
        if (employerId == null) return null;
        return this.world.getPlayerEntityByUUID(employerId);
    }

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
            simpleStoreCargoToVillage(th);
            onJobDelivered();
            return;
        }

        // ✅ NEW: seller-style gate (rep/day/attack/chestLocked)
        if (!canWorkerTradeWithVillage(th, employer)) {
            this.hasUnsoldGoods = true; // ensures “I couldn’t sell everything” message
            employer.sendMessage(new TextComponentString(
                    TextFormatting.RED +
                            "The village refuses to trade with you (reputation too low or village is not trading right now)."
            ));

            // Return to employer with all cargo untouched
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
     * <p>
     * SELL MODE (donateMode == false):
     * - Village buys items at TradeGood.getCalculatedBuyingPrice().
     * - Reputation: price * qty.
     * - Money: converted to coins and placed in the worker's cargo inventory.
     * <p>
     * DONATE MODE (donateMode == true):
     * - Village receives items.
     * - Reputation: price * qty * DONATION_REP_MULTIPLIER.
     * - No money is given.
     */
    private void processCargoTradeWithVillage(Building building, EntityPlayer employer, boolean donateMode) {
        ItemStackHandler inv = this.getTravelInventory();


        if (!canWorkerTradeWithVillage(building, employer)) {
            this.hasUnsoldGoods = true;
            MillLog.major(this, "[TravellersWorker] Trade refused by village (rep/day/attack/chestLocked gate).");
            return;
        }

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
        int totalRep = 0; // reputation points

        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            int meta = stack.getMetadata();

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
                int unitPriceCalc;
                try {
                    unitPriceCalc = good.getCalculatedBuyingPrice(building, employer);
                } catch (NullPointerException npe) {
                    // Millénaire can NPE if shopBuys entry exists but has no price for this TradeGood
                    unitPriceCalc = 0;
                    MillLog.major(this, "[TravellersWorker] getCalculatedBuyingPrice NPE for good="
                            + good.key + " item=" + invItem + " building=" + building + " player=" + employer.getName());
                }

                // Basic price, using villageType overrides if any
                int unitPriceBasicWithBuilding = good.getBasicBuyingPrice(townHall);

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
                repGain = baseValue; // same as normal selling rep
                moneyGain = baseValue;
            } else if (donateMode && good != null && unitPrice > 0) {
                int baseValue = unitPrice * stored;
                int repMult = ContainerTrade.DONATION_REP_MULTIPLIER;
                repGain = baseValue * repMult;
            }

            totalRep += repGain;
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

    public ItemStackHandler getTravelInventory() {
        return travelInventory;
    }

    public boolean hasUnsoldGoods() {
        return hasUnsoldGoods;
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

// Look for any nearby player (owner or not)
        final double playerRadius = 8.0D;
        AxisAlignedBB box = this.getEntityBoundingBox().grow(playerRadius, 4.0D, playerRadius);
        List<EntityPlayer> players = world.getEntitiesWithinAABB(EntityPlayer.class, box);
        boolean playerNearby = !players.isEmpty();

// --- Social behavior while waiting ---
        if (playerNearby) {
            // Look at the closest player
            EntityPlayer p = players.get(0);
            this.getLookHelper().setLookPositionWithEntity(
                    p,
                    10.0F,
                    (float) this.getVerticalFaceSpeed()
            );

            // Say hello once shortly after arriving
            if (homeChestWaitTicks == 20) {
                this.playHelloSound();
            }

            // IMPORTANT: don't count waiting time while someone is nearby
            // so he won't leave while being "discovered".
            return;
        }

// No player nearby -> count down the "leave home" timer
        homeChestWaitTicks++;

// Leave only after timeout AND no player nearby
        if (homeChestWaitTicks >= HOME_CHEST_WAIT_MAX_TICKS) {
            homeChestWaitTicks = 0;
            atHomeChest = false;

            // Allow travel AI to plan next hop
            tc.setReadyToDepart(true);
            tc.setDepartCooldownTicks(0);
        }

    }

    private void updateDynamicNavigation() {
        if (world.isRemote) return;

        boolean shouldBoost =
                isInPlayerViewDistance()
                        || isUsingTravelAI()      // << key
                        || catchupTravel;         // << key

        if (shouldBoost == usingBoostedNav) return;
        usingBoostedNav = shouldBoost;

        IAttributeInstance followAttr =
                this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE);
        if (followAttr != null) {
            followAttr.setBaseValue(shouldBoost ? boostedFollowRange : baseFollowRange);
        }

        // Optional but strongly recommended: also keep travel step sane vs follow range
        if (aiTravel != null) {
            int fr = (int) Math.floor(followAttr.getAttributeValue());
            aiTravel.setStepBlocks(Math.min(TravellersModConfig.workerStepBlocks, Math.max(12, fr - 8)));
        }
    }

    public boolean isFollowOrderActive() {
        return followOrderActive;
    }

    /**
     * Set whether this worker should actively follow its owner/leader.
     * When turned off, we remember the current position as an anchor.
     */
    public void setFollowOrderActive(boolean active) {
        if (this.followOrderActive == active && active) return;

        this.followOrderActive = active;
        if (active) {
            this.goOrderActive = false;
            this.goOrderTargetPos = null;
            this.goOrderTargetDim = 0;
            // ✅ PLACE IT HERE: player re-engages follow -> cancel any "going home" travel
            if (this.goingHome) {
                this.goingHome = false;
                stopAllTravelNow();            // clears TravelCommon target/route/waypoint + navigator
                this.returnUsingFollow = false;
                this.awaitingPickup = false;
            }

            // Follow ON
            this.idleAnchorPos = null;
            this.getNavigator().clearPath();
            return;
        }

        if (!active) {

            // ✅ NEW: If we were going home, cancel it NOW.
            if (this.goingHome) {
                this.goingHome = false;

                // kill all travel state so nothing keeps pulling toward home
                stopAllTravelNow();
                this.returnUsingFollow = false;
                this.awaitingPickup = false;

                // anchor here (player told us to stay)
                this.idleAnchorPos = this.getPosition();
                this.getNavigator().clearPath();
                return;
            }

            // Normal anchor behavior
            this.idleAnchorPos = this.getPosition();

            if (!this.isJobActive() && !this.catchupTravel && !this.isWaitingForPickup()) {
                stopAllTravelNow();
                this.returnUsingFollow = false;
                this.awaitingPickup = false;
            } else {
                this.getNavigator().clearPath();
            }

        } else {
            // Follow ON
            this.idleAnchorPos = null;
            this.getNavigator().clearPath();
        }
    }

    /**
     * Catchup route: one waypoint that is constantly refreshed
     */
    private void updateCatchupRoute(BlockPos newPos) {
        if (newPos == null) return;

        TravelCommon tc = this.getTravelCommon();

        tc.route.clear();
        tc.routeIndex = -1;

        tc.route.add(newPos);
        tc.routeIndex = 0;

        tc.setTargetVillage(newPos);
        this.setTargetVillage(newPos);

        tc.setCurrentWaypoint(null);

        // IMPORTANT: do NOT "wait at stop" in catchup mode.
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    // Approximate surface position above player's XZ
// Approximate surface position near any follow target (player OR leader worker)
    public BlockPos getApproximateSurfacePosNear(@Nullable EntityLivingBase target) {
        if (target == null) {
            return this.employerReturnPos != null ? this.employerReturnPos : this.getPosition();
        }

        BlockPos pos = target.getPosition();

        BlockPos columnTop;
        try {
            columnTop = this.world.getTopSolidOrLiquidBlock(new BlockPos(pos.getX(), 0, pos.getZ()));
        } catch (Exception e) {
            columnTop = new BlockPos(pos.getX(), (int) this.posY, pos.getZ());
        }

        // Only do "underground" behavior for players
        if (target instanceof EntityPlayer) {
            boolean underground =
                    pos.getY() + 2 < columnTop.getY() &&
                            !this.world.canSeeSky(pos);

            return underground ? pos : columnTop;
        }

        // Non-player target (leader worker etc.): surface at their XZ
        return columnTop;
    }

    private void stopAllTravelNow() {
        TravelCommon tc = this.getTravelCommon();

        tc.route.clear();
        tc.routeIndex = -1;

        tc.setTargetVillage(null);
        this.setTargetVillage(null);

        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    public boolean isJobActive() {
        return this.mode != WorkerMode.IDLE && !this.isWaitingForPickup();
    }

    public boolean isWaitingForPickup() {
        return this.mode == WorkerMode.RETURNING && this.jobDelivered && this.awaitingPickup;
    }

    @Nullable
    public BlockPos getIdleAnchorPos() {
        return idleAnchorPos;
    }

    public void clearIdleAnchor() {
        this.idleAnchorPos = null;
    }

    /**
     * Hire/dismiss already exist – just update to gilles follow state.
     */
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
        this.playerHostility.hostilePlayers.clear();
        this.routeLocked = false;
        if (!world.isRemote) {
            this.dataManager.set(HIRED_FOLLOWER_DM, false);
            this.dataManager.set(FOLLOW_OWNER_DM, Optional.absent());
            this.setUsingTravelAI(false);
        }
        this.followOrderActive = false;
        this.idleAnchorPos = null;
    }

    public void setUsingTravelAI(boolean using) {
        if (!world.isRemote) {
            this.dataManager.set(USING_TRAVEL_AI, using);
        }
    }

    // Mark/unmark specific players as hostile


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
        followerLookTicks = 40 + this.rand.nextInt(80);  // hold gaze ~2–6s

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
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.0001) return;

        dx /= len;
        dz /= len;

        // Apply small nudge velocity (no pathfinding!)
        this.motionX += dx * strength;
        this.motionZ += dz * strength;
    }

    public void setEscortLeader(@Nullable EntityTravellerWorker leader) {
        if (leader == null || leader == this) {
            setEscortLeaderUUID(null);
        } else {
            setEscortLeaderUUID(leader.getUniqueID());
        }
    }

    /**
     * Effective follow target: either a leader worker, or the owner player.
     */
    @Nullable
    public EntityLivingBase getEffectiveFollowTarget() {
        UUID leaderId = playerHostility.getEscortLeaderUUID();
        if (leaderId != null) {
            EntityTravellerWorker leader = playerHostility.resolveEscortLeader(leaderId);

            if (leader != null
                    && !leader.isDead
                    && leader.isHiredFollower()
                    && Objects.equals(leader.getFollowOwnerId(), this.getFollowOwnerId())) {
                return leader;
            }

            // invalid leader (loaded but wrong) -> clear on server
            if (!world.isRemote && leader != null) {
                clearEscortLeader();
            }
        }
        return getFollowOwner();
    }

    private void tickStayAtIdleAnchor() {
        if (!isHiredFollower()) return;
        if (this.mode == WorkerMode.RETURNING && this.jobDelivered) return;
        if (isJobActive()) return;
        if (this.goingHome) return;
        if (followOrderActive) return;
        if (idleAnchorPos == null) return;

        if (this.getAttackTarget() != null) return;

        double maxRadius = TravellersModConfig.workerIdleAnchorRadius;
        double distSq = this.getDistanceSqToCenter(idleAnchorPos);

        if (distSq > maxRadius * maxRadius) {
            if (this.getNavigator().noPath()) {
                this.getNavigator().tryMoveToXYZ(
                        idleAnchorPos.getX() + 0.5D,
                        idleAnchorPos.getY(),
                        idleAnchorPos.getZ() + 0.5D,
                        TravellersModConfig.movementSpeedWhileFollowing * 0.85D  // was 0.4
                );
            }
        } else {
            this.getNavigator().clearPath();
        }
    }

    public boolean isEscortingLeader(EntityTravellerWorker leader) {
        UUID id = playerHostility.getEscortLeaderUUID();
        return id != null && leader != null && id.equals(leader.getUniqueID());
    }

    public boolean hasEscortLeader() {
        return playerHostility.getEscortLeaderUUID() != null;
    }

    public void clearEscortLeader() {
        setEscortLeaderUUID(null);
    }

    public boolean isReturnUsingFollow() {
        return this.mode == WorkerMode.RETURNING
                && this.jobDelivered
                && this.returnUsingFollow
                && !this.awaitingPickup;
    }

    public List<BlockPos> pickRecruitVillages(EntityPlayer player) {
        World world = this.world;
        BlockPos origin = player.getPosition();

        int maxRadius = TravellersModConfig.recruitMaxVillageRadius;
        int minN = TravellersModConfig.recruitVillagesMin;
        int maxN = TravellersModConfig.recruitVillagesMax;

        List<BlockPos> centers = MillVillageIndex.getAllVillageCenters(world);
        List<BlockPos> inRange = new ArrayList<>();

        for (BlockPos c : centers) {
            int d = dist2D(origin, c);

            // NEW: enforce minimum distance for recruitment
            if (d < TravellersModConfig.recruitMinVillageDistance) {
                continue;
            }

            if (d <= maxRadius) {
                inRange.add(c);
            }
        }


        inRange.sort(Comparator.comparingInt(c -> dist2D(origin, c)));

        if (inRange.isEmpty()) return Collections.emptyList();

        int n = minN + this.rand.nextInt(Math.max(1, (maxN - minN + 1)));
        n = Math.min(n, inRange.size());

        return new ArrayList<>(inRange.subList(0, n));
    }

    public static int dist2D(BlockPos a, BlockPos b) {
        double dx = (a.getX() + 0.5D) - (b.getX() + 0.5D);
        double dz = (a.getZ() + 0.5D) - (b.getZ() + 0.5D);
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    public void startRecruitMission(EntityPlayer player, List<BlockPos> villages, BlockPos returnPos) {
        if (world.isRemote) return;
        if (villages == null || villages.isEmpty()) return;

        this.recruitVillages.clear();
        this.recruitVillages.addAll(villages);

        this.recruitIndex = 0;
        this.recruitReturnPos = returnPos;
        this.recruitActive = true;
        this.recruitSpawnedAt.clear();

        // Recruiter becomes a "job entity" temporarily
        this.mode = WorkerMode.RECRUITING;
        this.routeLocked = true;

        // Make sure he’s not anchored
        if (this.isHiredFollower()) {
            this.setFollowOrderActive(true);
        }

        // Build travel route: villages then return
        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;

        for (BlockPos v : recruitVillages) tc.route.add(v);
        if (returnPos != null) tc.route.add(returnPos);

        tc.routeIndex = 0;
        tc.setTargetVillage(tc.route.get(0));
        this.setTargetVillage(tc.route.get(0));
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    private void tickRecruitMission() {
        TravelCommon tc = this.getTravelCommon();

        // 1) Still visiting villages
        if (recruitIndex < recruitVillages.size()) {
            BlockPos targetVillage = recruitVillages.get(recruitIndex);

            int r = tc.arrivalRadiusBlocks(targetVillage);
            if (this.getDistanceSqToCenter(targetVillage) <= (double) (r * r)) {

                // Spawn recruits once per village
                if (!recruitSpawnedAt.contains(targetVillage)) {
                    recruitSpawnedAt.add(targetVillage);
                    spawnRecruitsAtVillage(targetVillage);
                }

                // Advance to next village
                recruitIndex++;

                if (recruitIndex < recruitVillages.size()) {
                    // go next village (keep Travel AI)
                    BlockPos next = recruitVillages.get(recruitIndex);
                    tc.setTargetVillage(next);
                    this.setTargetVillage(next);
                    tc.setReadyToDepart(true);
                    tc.setDepartCooldownTicks(0);
                    this.getNavigator().clearPath();
                } else {
                    beginRecruitReturnToOwner();
                    // Switch to return point (last point in tc.route is return)
                    if (recruitReturnPos != null) {
                        tc.setTargetVillage(recruitReturnPos);
                        this.setTargetVillage(recruitReturnPos);
                        tc.setReadyToDepart(true);
                        tc.setDepartCooldownTicks(0);
                        this.getNavigator().clearPath();
                    }
                }
            }
            return;
        }

        // 2) Returning to player (simple: once close to owner, finish)
        EntityPlayer owner = getFollowOwner();
        if (owner != null && !owner.isDead && owner.dimension == this.dimension) {
            double dx = this.posX - owner.posX;
            double dz = this.posZ - owner.posZ;
            double distHsq = dx * dx + dz * dz;

            if (distHsq <= 8.0D * 8.0D) {
                finishRecruitMission(owner);
            }
        } else {
            // If owner not found, just stop recruiting and go idle-travelling again
            finishRecruitMission(null);
        }
    }

    private void spawnRecruitsAtVillage(BlockPos villageCenter) {
        if (!(this.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) this.world;

        EntityPlayer owner = getFollowOwner();
        if (owner == null) return;

        int min = TravellersModConfig.recruitSpawnMin;
        int max = TravellersModConfig.recruitSpawnMax;
        int count = min + this.rand.nextInt(Math.max(1, (max - min + 1)));

        for (int i = 0; i < count; i++) {
            BlockPos spawnPos = EntityTravellerWorker.findRecruitSpawnPos(world, villageCenter, this.rand);
            EntityTravellerWorker w = new EntityTravellerWorker(ws);

            // Spawn near village center, on surface
            BlockPos top = ws.getTopSolidOrLiquidBlock(new BlockPos(villageCenter.getX(), 0, villageCenter.getZ()));


            w.setPosition(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D);
            w.onInitialSpawn(ws.getDifficultyForLocation(top), null);

            // IMPORTANT: no home village, so no respawn-on-death
            w.setHomeVillage(null);

            // Make them hired followers of the SAME owner, but escort THIS recruiter as leader
            w.hireAsFollower(owner);
            w.setEscortLeader(this);

            ws.spawnEntity(w);
        }

        owner.sendMessage(new TextComponentString(
                TextFormatting.WHITE + "Recruited " + count + " workers at a village."
        ));
    }

    private void finishRecruitMission(@Nullable EntityPlayer owner) {
        this.recruitActive = false;
        this.recruitVillages.clear();
        this.recruitSpawnedAt.clear();
        this.recruitIndex = 0;
        this.recruitReturnPos = null;

        // Clear travel route
        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;
        tc.setTargetVillage(null);
        this.setTargetVillage(null);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);
        this.getNavigator().clearPath();

        // Back to companion idle
        this.mode = WorkerMode.IDLE;
        this.routeLocked = true; // keep companion mode
        this.setFollowOrderActive(true);

        if (owner != null) {
            owner.sendMessage(new TextComponentString(TextFormatting.GREEN + "Recruit mission completed."));
        }
    }

    private void beginRecruitReturnToOwner() {
        // We want Follow AI + catchupTravel to do the long-range work
        this.recruitReturningToOwner = true;

        // Make sure Follow order is ON
        if (this.isHiredFollower()) {
            this.setFollowOrderActive(true); // clears idleAnchorPos
        }

        // Stop Travel AI route completely
        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;
        tc.setTargetVillage(null);
        this.setTargetVillage(null);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();

        // IMPORTANT:
        // If your Follow AI is gated by "job active", we must NOT be "job active" anymore.
        // So drop to IDLE companion mode (but keep routeLocked=true so he doesn't auto-roam).
        this.mode = WorkerMode.IDLE;
        this.routeLocked = true;

        // Also ensure we are not in catchupTravel stale state
        this.catchupTravel = false;
    }

    private void tickRecruitReturnToOwner() {
        EntityPlayer owner = getFollowOwner();
        if (owner == null || owner.isDead || owner.dimension != this.dimension) {
            // Owner missing → abort safely
            recruitReturningToOwner = false;
            finishRecruitMission(null);
            return;
        }

        double dx = this.posX - owner.posX;
        double dz = this.posZ - owner.posZ;
        double distHsq = dx * dx + dz * dz;

        // When close enough, finish
        if (distHsq <= 8.0D * 8.0D) {
            recruitReturningToOwner = false;
            finishRecruitMission(owner);
        }

        // No navigation commands here:
        // Follow AI will pull him in, and your existing "dist > 200 → startOwnerCatchupTravel()"
        // will kick in automatically because mode is IDLE and followOrderActive is true.
    }


    // CLIENT ONLY cache for GUI display (UUID + last known name)

    public void startFollowCatchupTravel() {
        startCatchupTravelTo(getEffectiveFollowTarget());
    }

    public java.util.List<String> getHostileNamesClient() {
        return playerHostility.getHostileNamesClient();
    }

    public void setHostileNamesClient(java.util.List<String> names) {
        playerHostility.setHostileNamesClient(names);
    }
    private boolean shouldBeUsingTravelAI() {
        TravelCommon tc = this.getTravelCommon();
        if (tc == null) return false;

        boolean hasRoute = tc.route != null && !tc.route.isEmpty() && tc.routeIndex >= 0;
        boolean hasTarget = tc.getTargetVillage() != null;
        boolean wantsDepart = tc.isReadyToDepart();  // ou ton flag équivalent

        // Le travel "normal" existe dès qu'on a une cible + une route ou qu'on est en mode départ.
        boolean normalTravel = hasTarget && (hasRoute || wantsDepart);

        return normalTravel
                || this.isJobActive()
                || this.catchupTravel
                || this.goingHome
                || this.goOrderActive;
    }

    @Nullable
    public BlockPos getTravelGoalPos() {
        // Catchup/return-to-owner uses dynamic goal
        BlockPos cg = getDynamicCatchupGoal();
        if (cg != null) return cg;

        // Normal travel uses the village goal
        return getTargetVillage();
    }

    @Nullable
    public BlockPos getDynamicCatchupGoal() {
        if (!isCatchupTravel()) return null;
        if (!(world instanceof WorldServer)) return cachedCatchupGoal;
        if (catchupTargetUUID == null) return cachedCatchupGoal;

        long now = world.getTotalWorldTime();
        if (cachedCatchupGoal == null || now >= nextCatchupRefreshTick) {
            Entity e = ((WorldServer) world).getEntityFromUuid(catchupTargetUUID);
            if (e != null && e.isEntityAlive()) {
                BlockPos p = new BlockPos(e.posX, e.posY, e.posZ);
                int q = 4;
                p = new BlockPos((p.getX() / q) * q, p.getY(), (p.getZ() / q) * q);
                cachedCatchupGoal = p;
            }
            nextCatchupRefreshTick = now + CATCHUP_REFRESH_TICKS;
        }
        return cachedCatchupGoal;
    }

    public boolean isCatchupTravel() {
        return catchupTravel;
    }

    public void setCatchupTravel(boolean v) {
        this.catchupTravel = v;
    }

    public void stopCatchupTravel() {
        this.catchupTravel = false;
        this.catchupRepathCooldown = 0;

        this.catchupTargetUUID = null;
        this.cachedCatchupGoal = null;
        this.nextCatchupRefreshTick = 0;

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;
        tc.setTargetVillage(null);
        this.setTargetVillage(null);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(false);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    private void tickAnchorDefense() {
        if (world.isRemote) return;

        // Only when hired + follow OFF + we actually have an anchor
        if (!isHiredFollower()) return;
        if (followOrderActive) return;
        if (idleAnchorPos == null) return;

        // Already fighting
        if (this.getAttackTarget() != null) return;

        if (anchorScanCooldown-- > 0) return;
        anchorScanCooldown = ANCHOR_SCAN_INTERVAL_TICKS;

        AxisAlignedBB box = new AxisAlignedBB(idleAnchorPos).grow(ANCHOR_DEFEND_RADIUS, 4.0D, ANCHOR_DEFEND_RADIUS);

        // 1) hostile-marked players first (your existing hostile list)
        java.util.List<EntityPlayer> hostilePs = world.getEntitiesWithinAABB(EntityPlayer.class, box,
                p -> p != null
                        && !p.isDead
                        && !p.isCreative()
                        && !p.isSpectator()
                        && !isOwner(p)
                        && playerHostility.isPlayerMarkedHostile(p));

        if (!hostilePs.isEmpty()) {
            this.setAttackTarget(hostilePs.get(0));
            return;
        }

        // 2) hostile mobs (IMob = vanilla hostile mobs)
        java.util.List<EntityLivingBase> mobs = world.getEntitiesWithinAABB(EntityLivingBase.class, box,
                e -> e != null
                        && e.isEntityAlive()
                        && e != this
                        && (e instanceof net.minecraft.entity.monster.IMob));

        if (!mobs.isEmpty()) {
            this.setAttackTarget(mobs.get(0)); // your setAttackTarget() already respects blacklist/owner
        }
    }

    private boolean isGuardingAnchor() {
        return this.isHiredFollower()
                && !this.isFollowOrderActive()
                && this.idleAnchorPos != null
                && !this.isJobActive()
                && !(this.mode == WorkerMode.RETURNING && this.jobDelivered);
    }

    private double distSqFromAnchor(EntityLivingBase e) {
        if (idleAnchorPos == null || e == null) return 0.0D;
        double ax = idleAnchorPos.getX() + 0.5D;
        double az = idleAnchorPos.getZ() + 0.5D;
        double dx = e.posX - ax;
        double dz = e.posZ - az;
        return dx * dx + dz * dz;
    }

    public void startGoHome() {
        if (world.isRemote) return;
        if (assignedHomePos == null) return;
        if (assignedHomeDim != this.dimension) return;

        this.goingHome = true;
        this.routeLocked = true;

        // disable follow, but DO NOT set an anchor yet
        this.followOrderActive = false;
        this.idleAnchorPos = null;

        this.setReturnUsingFollow(false);


        this.getNavigator().clearPath();
        this.setCurrentWaypoint(null);

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = 0;
        tc.route.add(assignedHomePos);

        tc.setTargetVillage(assignedHomePos);
        this.setTargetVillage(assignedHomePos);

        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);
    }

    public void setReturnUsingFollow(boolean v) {
        this.returnUsingFollow = v;
        // if using DataManager: dataManager.set(RETURN_USING_FOLLOW, v);
    }

    public int getLeaderFollowerCount() {
        return this.dataManager.get(LEADER_FOLLOWER_COUNT_DM);
    }

    // Optional: call this from packets after assign/clear for instant UI update
    public void forceRefreshLeaderCount() {
        updateLeaderFollowerCount();
    }

    /**
     * Recompute how many hired workers currently escort THIS worker.
     */
    private void updateLeaderFollowerCount() {
        if (world.isRemote) return;
        if (!(world instanceof WorldServer)) return;

        // If not hired, we consider it "not a leader"
        if (!this.isHiredFollower() || this.getFollowOwnerId() == null) {
            setLeaderFollowerCount(0);
            return;
        }

        UUID ownerId = this.getFollowOwnerId();
        UUID myId = this.getUniqueID();

        // Count loaded hired workers whose escort leader == me
        List<EntityTravellerWorker> all = world.getEntities(EntityTravellerWorker.class, w ->
                w != null
                        && !w.isDead
                        && w != this
                        && w.isHiredFollower()
                        && Objects.equals(w.getFollowOwnerId(), ownerId)
                        && myId.equals(w.getEscortLeaderUUID())
        );

        setLeaderFollowerCount(all.size());
    }

    private void setLeaderFollowerCount(int v) {
        if (!world.isRemote) {
            this.dataManager.set(LEADER_FOLLOWER_COUNT_DM, Math.max(0, v));
        }
    }

    // ✅ Count how many allied workers are already attacking this target (to spread across multiple mobs)
    public int countAlliedAttackers(EntityLivingBase target, double radius) {
        if (target == null) return 0;

        AxisAlignedBB box = target.getEntityBoundingBox().grow(radius, 6.0D, radius);
        UUID ownerId = this.getFollowOwnerId();

        List<EntityTravellerWorker> list = this.world.getEntitiesWithinAABB(EntityTravellerWorker.class, box,
                w -> w != null && w.isEntityAlive()
                        && w.isHiredFollower()
                        && Objects.equals(w.getFollowOwnerId(), ownerId)
                        && w.getAttackTarget() == target);

        return list.size();
    }

    // ✅ True if THIS worker is one of the K nearest NON-BOW workers to the target
    public boolean isTopKNonBowToTarget(EntityLivingBase target, int k) {
        if (target == null) return false;
        if (this.hasBowAnywherePublic()) return false;

        AxisAlignedBB box = target.getEntityBoundingBox().grow(24.0D, 6.0D, 24.0D);
        UUID ownerId = this.getFollowOwnerId();

        List<EntityTravellerWorker> list = this.world.getEntitiesWithinAABB(EntityTravellerWorker.class, box,
                w -> w != null && w.isEntityAlive()
                        && w.isHiredFollower()
                        && Objects.equals(w.getFollowOwnerId(), ownerId)
                        && !w.hasBowAnywherePublic());

        if (list.size() <= k) return true; // not enough melee candidates -> allow

        list.sort(java.util.Comparator.comparingDouble(w -> w.getDistanceSq(target)));

        for (int i = 0; i < Math.min(k, list.size()); i++) {
            if (list.get(i) == this) return true;
        }
        return false;
    }

    @Nullable
    private EntityLivingBase findThreatNearOwner(@Nullable EntityLivingBase owner, double r) {
        if (owner == null) return null;

        AxisAlignedBB box = owner.getEntityBoundingBox().grow(r, 4.0D, r);

        List<EntityLivingBase> list = world.getEntitiesWithinAABB(EntityLivingBase.class, box,
                e -> e != null
                        && e.isEntityAlive()
                        && e instanceof net.minecraft.entity.monster.IMob
                        // NEW: basic LoS gate (choose one or both)
                        && this.canEntityBeSeen(e)
                        // optional: avoid “underground below me” targets by Y band
                        && Math.abs(e.posY - owner.posY) <= 6.0D
        );

        EntityLivingBase best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (EntityLivingBase e : list) {
            boolean targetsOwner = false;
            if (e instanceof EntityLiving) {
                targetsOwner = ((EntityLiving) e).getAttackTarget() == owner;
            }
            if (e.getRevengeTarget() == owner) targetsOwner = true;

            boolean sees = this.canEntityBeSeen(e);
            double d2 = owner.getDistanceSq(e);
            boolean allow = sees || d2 < (6.0D * 6.0D) || targetsOwner;
            if (!allow) continue;
            double score = d2 + (targetsOwner ? -200.0 : 0.0);

            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    @Override
    public boolean isOnSameTeam(Entity entityIn) {
        // Allow worker-vs-worker combat when enemy by owner hostility rules
        if (entityIn instanceof EntityTravellerWorker) {
            EntityTravellerWorker other = (EntityTravellerWorker) entityIn;
            if (this.shouldAttackExtraEntity(other)) {
                return false; // NOT same team (so targeting can happen)
            }
        }
        return super.isOnSameTeam(entityIn);
    }

    private boolean canWorkerTradeWithVillage(Building building, @Nullable EntityPlayer player) {
        if (building == null || player == null) return false;

        // checkSeller(): only runs during daytime and not under attack
        if (building.underAttack) return false;

        // checkSeller(): requires locked chests
        if (!building.chestLocked) return false;

        // checkSeller(): rep gate
        return building.getReputation(player) >= MIN_REP_TO_TRADE;
    }

    public enum WorkerMode {
        IDLE,
        TO_VILLAGE,
        RETURNING,
        RECRUITING
    }
    private final WorkerNbtComponent nbtComponent = new WorkerNbtComponent();


    public PlayerHostility getHostility() {
        return playerHostility;
    }

    private void tickEscortLeaderValidity() {
        if (world.isRemote) return;

        // Si pas hired follower, on ne doit jamais avoir de leader
        if (!this.isHiredFollower()) {
            if (this.hasEscortLeader()) this.clearEscortLeader();
            return;
        }

        UUID leaderId = this.getEscortLeaderUUID(); // via PlayerHostility / datamanager
        if (leaderId == null) return;

        EntityTravellerWorker leader = this.playerHostility.resolveEscortLeader(leaderId);

        // Leader mort, absent (chunk unload), ou invalide -> clear
        boolean invalid =
                (leader != null) // IMPORTANT: don't clear just because leader is unloaded
                        && (leader.isDead
                        || !leader.isHiredFollower()
                        || !java.util.Objects.equals(leader.getFollowOwnerId(), this.getFollowOwnerId()));

        if (invalid) {
            // ensure we have leader hostile list persisted locally before detaching
            this.playerHostility.hostilePlayers.clear();
            this.playerHostility.hostilePlayers.addAll(leader.getHostility().hostilePlayers);

            this.clearEscortLeader();
        }

    }

    public void startGoToDestination(BlockPos pos, int dim) {
        if (world.isRemote) return;
        if (pos == null) return;
        if (dim != this.dimension) return;

        // Cancel anything that could fight with this travel order
        this.goingHome = false;
        this.catchupTravel = false;
        this.returnUsingFollow = false;
        this.awaitingPickup = false;

        // Stop job if you want to force the order (optional)
        // If you prefer to block when job active, do that in the packet handler.
        if (this.isJobActive()) {
            // Keep it simple: cancel job travel state but keep inventory
            this.mode = WorkerMode.IDLE;
            this.jobDelivered = false;
        }

        // Follow OFF, no anchor yet (anchor will be set once arrived)
        this.followOrderActive = false;
        this.idleAnchorPos = null;

        // Mark "go order" active for NBT persistence
        this.goOrderActive = true;
        this.goOrderTargetPos = pos;
        this.goOrderTargetDim = dim;

        // Use TravelAI exactly like GoHome: build a 1-point route
        this.routeLocked = true;

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = 0;
        tc.route.add(pos);

        tc.setTargetVillage(pos);
        this.setTargetVillage(pos);

        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }






    // NBT SAVE/LOAD
    private class WorkerNbtComponent {
        public void write(NBTTagCompound nbt) {
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
            nbt.setTag("TravelInventory", travelInventory.serializeNBT());

            nbt.setBoolean("AwaitingPickup", awaitingPickup);
            nbt.setBoolean("HasUnsoldGoods", hasUnsoldGoods);
            nbt.setBoolean("PostJobFarewellActive", postJobFarewellActive);
            nbt.setInteger("PostJobFarewellTicks", postJobFarewellTicks);

            // NEW: personal inventory
            nbt.setTag("PersonalInventory", personalInventory.serializeNBT());
            // NEW: home village
            if (homeVillageCenter != null) {
                nbt.setInteger("HomeVillageX", homeVillageCenter.getX());
                nbt.setInteger("HomeVillageY", homeVillageCenter.getY());
                nbt.setInteger("HomeVillageZ", homeVillageCenter.getZ());
            }
            if (followOwnerId != null) {
                nbt.setUniqueId("FollowOwnerId", followOwnerId);
            }

            if (!playerHostility.hostilePlayers.isEmpty()) {
                NBTTagList list = new NBTTagList();
                for (UUID id : playerHostility.hostilePlayers) {
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
            UUID leaderId = playerHostility.getEscortLeaderUUID(); // from DataManager
            if (leaderId != null) {
                nbt.setUniqueId("EscortLeaderUUID", leaderId);
            }
            nbt.setBoolean("HiredFollower", dataManager.get(HIRED_FOLLOWER_DM));
            Optional<UUID> o = dataManager.get(FOLLOW_OWNER_DM);
            if (o.isPresent()) nbt.setUniqueId("FollowOwnerId", o.get());
            nbt.setBoolean("RecruitActive", recruitActive);
            nbt.setBoolean("RecruitReturning", recruitReturningToOwner);
            nbt.setInteger("RecruitIndex", recruitIndex);

            NBTTagList vlist = new NBTTagList();
            for (BlockPos p : recruitVillages) {
                NBTTagCompound c = new NBTTagCompound();
                c.setInteger("x", p.getX());
                c.setInteger("y", p.getY());
                c.setInteger("z", p.getZ());
                vlist.appendTag(c);
            }
            nbt.setTag("RecruitVillages", vlist);

            if (assignedHomePos != null) {
                nbt.setInteger("HomeX", assignedHomePos.getX());
                nbt.setInteger("HomeY", assignedHomePos.getY());
                nbt.setInteger("HomeZ", assignedHomePos.getZ());
                nbt.setInteger("HomeDim", assignedHomeDim);
                nbt.setString("HomeName", assignedHomeName);
            }
            nbt.setBoolean("GoingHome", goingHome);


            NBTTagList spawned = new NBTTagList();
            for (BlockPos p : recruitSpawnedAt) {
                NBTTagCompound c = new NBTTagCompound();
                c.setInteger("x", p.getX());
                c.setInteger("y", p.getY());
                c.setInteger("z", p.getZ());
                spawned.appendTag(c);
            }
            nbt.setTag("RecruitSpawnedAt", spawned);
            // --- "Go..." order persistence ---
            nbt.setBoolean("GoOrderActive", goOrderActive);
            if (goOrderTargetPos != null) {
                nbt.setInteger("GoX", goOrderTargetPos.getX());
                nbt.setInteger("GoY", goOrderTargetPos.getY());
                nbt.setInteger("GoZ", goOrderTargetPos.getZ());
                nbt.setInteger("GoDim", goOrderTargetDim);
            }
// NEW: local hostile players (auto retaliation)
            if (!playerHostility.localHostilePlayers.isEmpty()) {
                NBTTagList list = new NBTTagList();
                for (UUID id : playerHostility.localHostilePlayers) {
                    NBTTagCompound c = new NBTTagCompound();
                    c.setUniqueId("Player", id);
                    list.appendTag(c);
                }
                nbt.setTag("LocalHostilePlayers", list);
            }

        }

        public void read(NBTTagCompound nbt) {
            forceSpawn = true;
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
                travelInventory.deserializeNBT(nbt.getCompoundTag("TravelInventory"));
            }

            awaitingPickup = nbt.getBoolean("AwaitingPickup");
            hasUnsoldGoods = nbt.getBoolean("HasUnsoldGoods");
            postJobFarewellActive = nbt.getBoolean("PostJobFarewellActive");
            postJobFarewellTicks = nbt.getInteger("PostJobFarewellTicks");


            // NEW: personal inventory
            if (nbt.hasKey("PersonalInventory")) {
                personalInventory.deserializeNBT(nbt.getCompoundTag("PersonalInventory"));
            }

            // NEW: home village
            if (nbt.hasKey("HomeVillageX")) {
                int x = nbt.getInteger("HomeVillageX");
                int y = nbt.getInteger("HomeVillageY");
                int z = nbt.getInteger("HomeVillageZ");
                homeVillageCenter = new BlockPos(x, y, z);
            } else {
                homeVillageCenter = null;
            }
            if (nbt.hasUniqueId("FollowOwnerId")) {
                followOwnerId = nbt.getUniqueId("FollowOwnerId");
            } else {
                followOwnerId = null;
            }
            hiredFollower = nbt.getBoolean("HiredFollower");

            playerHostility.hostilePlayers.clear();
            if (nbt.hasKey("HostilePlayers", Constants.NBT.TAG_LIST)) {
                NBTTagList list = nbt.getTagList("HostilePlayers", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound c = list.getCompoundTagAt(i);
                    if (c.hasUniqueId("Player")) {
                        playerHostility.hostilePlayers.add(c.getUniqueId("Player"));
                    }
                }
            }
            followOrderActive = nbt.getBoolean("FollowOrderActive");
            if (nbt.hasKey("IdleAx")) {
                idleAnchorPos = new BlockPos(
                        nbt.getInteger("IdleAx"),
                        nbt.getInteger("IdleAy"),
                        nbt.getInteger("IdleAz")
                );
            } else {
                idleAnchorPos = null;
            }

            UUID leaderId = nbt.hasUniqueId("EscortLeaderUUID")
                    ? nbt.getUniqueId("EscortLeaderUUID")
                    : null;

// Keep local field in sync (optional, but fine)
            escortLeaderUUID = leaderId;

// IMPORTANT: set the synced DataParameter on the server
            if (!world.isRemote) {
                setEscortLeaderUUID(leaderId); // this updates ESCORT_LEADER_DM
            }

            if (mode != WorkerMode.RETURNING || !jobDelivered) {
                awaitingPickup = false;
            }
            recruitActive = nbt.getBoolean("RecruitActive");
            recruitReturningToOwner = nbt.getBoolean("RecruitReturning");
            recruitIndex = nbt.getInteger("RecruitIndex");

            recruitVillages.clear();
            if (nbt.hasKey("RecruitVillages", Constants.NBT.TAG_LIST)) {
                NBTTagList vlist = nbt.getTagList("RecruitVillages", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < vlist.tagCount(); i++) {
                    NBTTagCompound c = vlist.getCompoundTagAt(i);
                    recruitVillages.add(new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z")));
                }
            }

            recruitSpawnedAt.clear();
            if (nbt.hasKey("RecruitSpawnedAt", Constants.NBT.TAG_LIST)) {
                NBTTagList slist = nbt.getTagList("RecruitSpawnedAt", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < slist.tagCount(); i++) {
                    NBTTagCompound c = slist.getCompoundTagAt(i);
                    recruitSpawnedAt.add(new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z")));
                }
            }

            if (nbt.hasKey("HomeX")) {
                assignedHomePos = new BlockPos(nbt.getInteger("HomeX"), nbt.getInteger("HomeY"), nbt.getInteger("HomeZ"));
                assignedHomeDim = nbt.getInteger("HomeDim");
                assignedHomeName = nbt.getString("HomeName");
            } else {
                assignedHomePos = null;
                assignedHomeDim = 0;
                assignedHomeName = "";
            }

            if (!world.isRemote) {
                dataManager.set(HOME_POS_DM, assignedHomePos == null
                        ? com.google.common.base.Optional.absent()
                        : com.google.common.base.Optional.of(assignedHomePos));
                dataManager.set(HOME_DIM_DM, assignedHomeDim);
                dataManager.set(HOME_NAME_DM, assignedHomeName == null ? "" : assignedHomeName);
            }


            goingHome = nbt.getBoolean("GoingHome");
            if (!world.isRemote && goingHome && assignedHomePos != null && assignedHomeDim == dimension) {
                TravelCommon tc = getTravelCommon();

                boolean routeMissing = tc.route == null || tc.route.isEmpty() || tc.routeIndex < 0;
                if (routeMissing) {
                    tc.route.clear();
                    tc.routeIndex = 0;
                    tc.route.add(assignedHomePos);

                    tc.setTargetVillage(assignedHomePos);
                    setTargetVillage(assignedHomePos);

                    tc.setCurrentWaypoint(null);
                    tc.setReadyToDepart(true);
                    tc.setDepartCooldownTicks(0);

                    getNavigator().clearPath();
                }
            }

// If farewell isn't active, no need to keep a non-zero counter
            if (!postJobFarewellActive) {
                postJobFarewellTicks = 0;
            }
            boolean hired = nbt.getBoolean("HiredFollower");
            Optional<UUID> owner = nbt.hasUniqueId("FollowOwnerId")
                    ? Optional.of(nbt.getUniqueId("FollowOwnerId"))
                    : Optional.absent();

            hiredFollower = hired;
            followOwnerId = owner.isPresent() ? owner.get() : null;

            dataManager.set(HIRED_FOLLOWER_DM, hired);
            dataManager.set(FOLLOW_OWNER_DM, owner);
            if (recruitReturningToOwner && isHiredFollower()) {
                setFollowOrderActive(true);
            }
// --- "Go..." order restore ---
            goOrderActive = nbt.getBoolean("GoOrderActive");
            if (nbt.hasKey("GoX")) {
                goOrderTargetPos = new BlockPos(
                        nbt.getInteger("GoX"),
                        nbt.getInteger("GoY"),
                        nbt.getInteger("GoZ")
                );
                goOrderTargetDim = nbt.getInteger("GoDim");
            } else {
                goOrderTargetPos = null;
                goOrderTargetDim = 0;
            }

// If active, rebuild TravelCommon route so TravelAI continues after reload
            if (!world.isRemote && goOrderActive && goOrderTargetPos != null) {
                TravelCommon tc = getTravelCommon();

                boolean routeMissing = tc.route == null || tc.route.isEmpty() || tc.routeIndex < 0;
                if (routeMissing) {
                    tc.route.clear();
                    tc.routeIndex = 0;
                    tc.route.add(goOrderTargetPos);

                    tc.setTargetVillage(goOrderTargetPos);
                    setTargetVillage(goOrderTargetPos);

                    tc.setCurrentWaypoint(null);
                    tc.setReadyToDepart(true);
                    tc.setDepartCooldownTicks(0);

                    getNavigator().clearPath();
                }
            }
            playerHostility.localHostilePlayers.clear();
            if (nbt.hasKey("LocalHostilePlayers", Constants.NBT.TAG_LIST)) {
                NBTTagList list = nbt.getTagList("LocalHostilePlayers", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound c = list.getCompoundTagAt(i);
                    if (c.hasUniqueId("Player")) {
                        playerHostility.localHostilePlayers.add(c.getUniqueId("Player"));
                    }
                }
            }

        }

    }

    @Override
    public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);
        nbtComponent.write(nbt);
    }
    @Override
    public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        nbtComponent.read(nbt);
    }
    @Nullable
    public static UUID getOwnerIdOf(@Nullable EntityLivingBase e) {
        if (e == null) return null;
        if (e instanceof EntityPlayer) return e.getUniqueID();
        if (e instanceof EntityTravellerWorker) return ((EntityTravellerWorker) e).getFollowOwnerId();
        return null;
    }

    public boolean isEnemyOwner(@Nullable UUID otherOwner) {
        UUID myOwner = this.getFollowOwnerId();
        if (myOwner == null || otherOwner == null) return false;
        if (myOwner.equals(otherOwner)) return false;

        // declared hostility OR currently engaged (retaliation window)
        return this.playerHostility.isOwnerEffectivelyHostile(otherOwner)
                || this.playerHostility.isEngagedOwner(otherOwner);

    }

    public boolean isEnemyPlayer(@Nullable EntityPlayer p) {
        if (p == null || p.isDead) return false;
        if (p.isCreative() || p.isSpectator()) return false;
        if (isOwner(p)) return false;
        return isEnemyOwner(p.getUniqueID());
    }

    public boolean isEnemyHiredWorker(@Nullable EntityTravellerWorker other) {
        if (other == null || other == this) return false;
        if (!other.isEntityAlive()) return false;

        if (!this.isHiredFollower() || !other.isHiredFollower()) return false;

        UUID otherOwner = other.getFollowOwnerId();
        if (otherOwner == null) return false;

        return isEnemyOwner(otherOwner);
    }

    /**
     * Retaliation permission (your critical constraint):
     * We only retaliate if the ATTACKER has declared the victim owner hostile.
     */
    public boolean canRetaliateAgainst(@Nullable EntityLivingBase attacker) {
        if (attacker == null || !attacker.isEntityAlive()) return false;

        UUID myOwner = this.getFollowOwnerId();
        if (myOwner == null) return false;

        // Always retaliate vs mobs
        if (attacker instanceof net.minecraft.entity.monster.IMob) return true;

        // Never retaliate vs owner
        if (attacker == this.getFollowOwner()) return false;

        // If attacker is an enemy worker: easy check (their declared list includes my owner)
        if (attacker instanceof EntityTravellerWorker) {
            EntityTravellerWorker aw = (EntityTravellerWorker) attacker;
            UUID ao = aw.getFollowOwnerId();
            if (ao == null || ao.equals(myOwner)) return false;
            return aw.getHostility().isOwnerDeclaredHostile(myOwner);
        }

        // If attacker is a player: only retaliate if that player's nearby workers have declared war on my owner
        if (attacker instanceof EntityPlayer) {
            EntityPlayer ap = (EntityPlayer) attacker;
            if (ap.isCreative() || ap.isSpectator()) return false;

            UUID attackerOwner = ap.getUniqueID();
            if (attackerOwner.equals(myOwner)) return false;

            AxisAlignedBB box = ap.getEntityBoundingBox().grow(PVP_DECLARE_CHECK_RADIUS, 16.0D, PVP_DECLARE_CHECK_RADIUS);
            java.util.List<EntityTravellerWorker> list = ap.world.getEntitiesWithinAABB(
                    EntityTravellerWorker.class,
                    box,
                    w -> w != null
                            && w.isEntityAlive()
                            && w.isHiredFollower()
                            && attackerOwner.equals(w.getFollowOwnerId())
                            && w.getHostility().isOwnerDeclaredHostile(myOwner)

            );
            return !list.isEmpty();
        }

        return false;
    }

    public void engageEnemyOwner(@Nullable UUID enemyOwner) {
        if (enemyOwner == null) return;
        this.playerHostility.engageOwner(enemyOwner, PVP_ENGAGE_TICKS);
    }
    private void clearLeaderFromLoadedEscortsOnDeath() {
        if (world.isRemote) return;
        if (!(world instanceof WorldServer)) return;

        MinecraftServer server = ((WorldServer) world).getMinecraftServer();
        if (server == null) return;

        UUID ownerId = this.getFollowOwnerId();
        if (ownerId == null) return;

        UUID leaderId = this.getUniqueID();

        // Snapshot the leader's hostile list so followers keep fighting consistently after leader dies
        Set<UUID> snapshot = new HashSet<>(this.getHostility().hostilePlayers);

        for (WorldServer ws : server.worlds) {
            List<EntityTravellerWorker> escorts = ws.getEntities(EntityTravellerWorker.class, other ->
                    other != null
                            && other.isEntityAlive()
                            && other.isHiredFollower()
                            && Objects.equals(other.getFollowOwnerId(), ownerId)
                            && leaderId.equals(other.getEscortLeaderUUID())
            );

            for (EntityTravellerWorker other : escorts) {
                // Persist the leader list locally on the follower (NBT already saves hostilePlayers)
                other.getHostility().hostilePlayers.clear();
                other.getHostility().hostilePlayers.addAll(snapshot);

                // CLEAR the leader UUID so they can be assigned a new leader
                other.clearEscortLeader();

                // Optional but usually desired: make them follow the owner again
                other.setFollowOrderActive(true);
            }
        }
    }

}
