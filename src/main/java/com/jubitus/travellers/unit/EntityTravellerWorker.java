package com.jubitus.travellers.unit;

import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.google.common.base.Optional;
import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.gui.TravellersGuiIds;
import com.jubitus.travellers.marker.WorkerMarkStyle;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.village.visit.EntityAIVillageVisit;
import com.jubitus.travellers.gaze.EntityAIStopAndLookAtPlayer;
import com.jubitus.travellers.combat.ai.EntityAIDefendIfClose;
import com.jubitus.travellers.combat.ai.EntityAIDefendOwnerProactively;
import com.jubitus.travellers.combat.ai.EntityAIOwnerHurtByTargetTraveller;
import com.jubitus.travellers.combat.ai.EntityAIOwnerHurtTargetTraveller;
import com.jubitus.travellers.combat.ai.EntityAIWorkerHurtByTargetTraveller;
import com.jubitus.travellers.combat.ai.ThrottledAttackTargetAI;
import com.jubitus.travellers.combat.ai.TravellerHurtByTarget;
import net.minecraft.entity.ai.EntityAITasks;
import com.jubitus.travellers.follow.EntityAIFollowLeader;
import com.jubitus.travellers.follow.FollowFormationCoordinator;
import com.jubitus.travellers.follow.FollowMode;
import com.jubitus.travellers.follow.FollowRecoveryHelper;
import com.jubitus.travellers.follow.FollowRuntimeConfig;
import com.jubitus.travellers.travel.EntityAITravel;
import com.jubitus.travellers.travel.TravelCommon;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneRef;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.marker.WorkerOwnerVisualsSavedData;
import com.jubitus.travellers.worker.net.MsgOpenClientWorkerGui;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.combat.PlayerHostility;
import com.jubitus.travellers.combat.lists.EntityRuleList;
import com.jubitus.travellers.village.TravellerVillageRelations;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.village.MillGoods;
import com.jubitus.travellers.ground.chunk.TravellerChunkManager;
import com.jubitus.travellers.spawn.TravellerVillageRegistry;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.EntityAIHurtByTarget;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.*;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.EnumSkyBlock;
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
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.*;
import org.millenaire.common.utilities.MillCommonUtilities;

public class EntityTravellerWorker extends EntityTraveller {


    public static final int PVP_ENGAGE_TICKS = 20 * 120; // 2 minutes


    private int cachedGroupSize = 1;
    /**
     * Group combat posture, refreshed once/sec from WorkerFormationSavedData (see onLivingUpdate's
     * ticksExisted%20 block) — the single cheap read path for combat AI, never read the saved data
     * directly from per-tick code.
     */
    private com.jubitus.travellers.follow.formations.WorkerAttitude cachedAttitude =
            com.jubitus.travellers.follow.formations.WorkerAttitude.ATTACK;
    /** See {@link #cachedAttitude} - same refresh cadence, same reasoning. */
    private double cachedPlayerCohesion = 0.4D;


    // --- "Go..." order state ---
    private boolean goOrderActive = false;
    @Nullable private BlockPos goOrderTargetPos = null;
    private int goOrderTargetDim = 0; // for persistence (same dim usually)

    // --- escort leader watchdog ---
    private int escortLeaderCheckCooldown = 0;
    // -1 = uninitialised; set to entity-ID-based phase offset on first tick to spread load across ticks
    private int virtualLeaderRepathCooldown = -1;
    // The formation slot of an offscreen escort leader; catchup travel drives toward it.
    @Nullable private BlockPos virtualEscortSlot;

    // --- anchor guard tuning ---
    private static final double ANCHOR_GUARD_RADIUS = 192.0D;     // can fight within this radius of anchor
    private static final double ANCHOR_HARD_LEASH = 256.0D;     // if worker itself goes beyond this, disengage & return
    // --- anchor defense ---
    private static final double ANCHOR_DEFEND_RADIUS = 12.0D; // blocks
    private static final int ANCHOR_SCAN_INTERVAL_TICKS = 20; // 1 time per second
    private static final double MOTION_EPSILON = 0.0004D;
    private static final DataParameter<Boolean> USING_TRAVEL_AI =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Integer> LEADER_FOLLOWER_COUNT_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.VARINT);
    static final DataParameter<Boolean> HIRED_FOLLOWER_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.BOOLEAN);
    static final DataParameter<Optional<UUID>> FOLLOW_OWNER_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.OPTIONAL_UNIQUE_ID);
    // --- assigned home sync ---
    static final DataParameter<com.google.common.base.Optional<BlockPos>> HOME_POS_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.OPTIONAL_BLOCK_POS);
    static final DataParameter<Integer> HOME_DIM_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.VARINT);
    static final DataParameter<String> HOME_NAME_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.STRING);
    public static final DataParameter<Optional<UUID>> ESCORT_LEADER_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.OPTIONAL_UNIQUE_ID);

    private static final DataParameter<Integer> OWNER_MARK_COLOR_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.VARINT);
    private static final DataParameter<String> OWNER_MARK_STYLE_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.STRING);
    private static final DataParameter<Boolean> CHOIR_ACTIVE_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> MISSION_CARRY_DM =
            EntityDataManager.createKey(EntityTravellerWorker.class, DataSerializers.BOOLEAN);

    private boolean choirFreezeActive = false;
    // Public so EntityAIChoirFreeze (different package) can read them directly
    public double choirFreezeLookX, choirFreezeLookZ;

    private static final double RETURN_FOLLOW_SWITCH_DIST = TravellersConfig.WORKER_FORMATION.returnFollowSwitchDist; // tweak (24–40 works well)
    // How long to wait after loot pickup before saying goodbye & roaming again
    private static final int POST_JOB_FAREWELL_TICKS = 0; // 5 seconds (tweak)
    // How often (in ticks) to refresh target when returning
    private static final int RETURN_REPATH_INTERVAL_TICKS = 200; // ~10 seconds
    public final PlayerHostility playerHostility = new PlayerHostility(this);
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
    // Client copy of the home village's name, sent with the hire state (village data is server-only).
    public String clientHomeVillageName = "";
    // --- the one zone assignment: a Barrack (lodging) or a Stable (lodging + duty) ---
    @Nullable
    ZoneRef zone = null;
    boolean goingHome = false;
    boolean goingToDuty = false;
    private int anchorScanCooldown = 0;
    // --- dynamic owner catchup goal ---
    private BlockPos cachedCatchupGoal = null;
    private long nextCatchupRefreshTick = 0;
    private int leaderCountCooldown = 0;
    private int stuckAtVillageTicks = 0;
    private boolean returnUsingFollow = false;
    // recruit mission return phase: follow owner home
    boolean recruitReturningToOwner = false;
    // NEW: whether follow AI is currently active (player’s "Follow" order)
    private boolean followOrderActive = false;
    // NEW: where the worker was told to stay when follow was toggled OFF
    @Nullable
    BlockPos idleAnchorPos = null;
    // NEW: leader logic – if non-null, this worker follows another worker instead of the player
    int postJobFarewellTicks = 0;
    boolean postJobFarewellActive = false;
    private double baseFollowRange;
    private double boostedFollowRange;
    private boolean usingBoostedNav = false;
    // --- home village ---
    @Nullable
    BlockPos homeVillageCenter;
    private boolean catchupTravel;
    @Nullable
    private UUID catchupTargetUUID = null;
    boolean awaitingPickup = false;
    private boolean pickupNotified = false;
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
    // GET_EQUIPMENT mission flags
    private boolean getEquipStay = false;
    private com.jubitus.travellers.unit.equipment.EquipTargetKind getEquipTargetKind =
            com.jubitus.travellers.unit.equipment.EquipTargetKind.LONE_BARRACK;
    // "Employer" = player who hired this worker for the current/last job
    @Nullable
    UUID employerId;
    @Nullable
    BlockPos employerReturnPos;   // where to go back (captured when job starts)
    // When true, base EntityTraveller will NOT auto-plan routes
    boolean routeLocked = false;
    boolean hasUnsoldGoods = false;

    private FollowMode activeFollowMode = FollowMode.SURFACE;

    private static final int RECRUIT_STOP_RADIUS = 10;
    private static final int RECRUIT_VILLAGE_RADIUS = 24;
    private static final int RECRUIT_STALL_TICKS = 20 * 5;
    private static final double RECRUIT_PROGRESS_EPS_SQ = 2.0D * 2.0D;

    @Nullable
    private BlockPos recruitCurrentStopPos = null;

    private int recruitArrivalWatchdogTicks = 0;
    private double recruitBestDistSq = Double.MAX_VALUE;

    public EntityTravellerWorker(World worldIn) {
        super(worldIn);
        this.enablePersistence();
        this.forceSpawn = true;

        // Re-added below at a priority above the worker's own travel task.
        removeTaskOfType(EntityAIVillageVisit.class);
        removeTaskOfType(EntityAITravel.class);
        removeTaskOfType(EntityAIFollowLeader.class);
        removeTaskOfType(EntityAIStopAndLookAtPlayer.class);
        // Priority 0 = highest; mutex 3 blocks all other move+look AI when freeze is active
        this.tasks.addTask(0, new com.jubitus.travellers.choir.EntityAIChoirFreeze(this));
        this.tasks.addTask(10, new EntityAIStopAndLookAtPlayer(this));
        // Base EntityTraveller.initEntityAI() (run via super() above) already registered
        // EntityAIDefendIfClose at priority 1 and TravellerHurtByTarget at priority 2 — pull
        // them out and re-add below at their place in the worker's own priority order instead
        // of leaving duplicate priority numbers around (used to collide with the tasks added
        // here, making tie-break order an accident of insertion rather than a real priority).
        EntityAIDefendIfClose inheritedDefendIfClose = null;
        TravellerHurtByTarget inheritedHurtByTarget = null;
        for (Object obj : this.targetTasks.taskEntries.toArray()) {
            EntityAITasks.EntityAITaskEntry entry = (EntityAITasks.EntityAITaskEntry) obj;
            if (entry.action instanceof EntityAIDefendIfClose) inheritedDefendIfClose = (EntityAIDefendIfClose) entry.action;
            if (entry.action instanceof TravellerHurtByTarget) inheritedHurtByTarget = (TravellerHurtByTarget) entry.action;
        }
        removeTaskOfType(EntityAIDefendIfClose.class);
        removeTaskOfType(TravellerHurtByTarget.class);

        // Single, unified priority order (0 = most urgent). Every target-selector task shares
        // the vanilla target-AI mutex bit (1), so only one of these can actually claim a target
        // per tick — priority order below is what breaks ties deterministically.
        this.targetTasks.addTask(0, new EntityAIOwnerHurtByTargetTraveller(this));
        this.targetTasks.addTask(1, new EntityAIDefendOwnerProactively(this, 20));
        this.targetTasks.addTask(2, new EntityAIWorkerHurtByTargetTraveller(this));
        this.targetTasks.addTask(3, new EntityAIOwnerHurtTargetTraveller(this));
        if (inheritedDefendIfClose != null) this.targetTasks.addTask(4, inheritedDefendIfClose);
        if (inheritedHurtByTarget != null) this.targetTasks.addTask(5, inheritedHurtByTarget);

        // Single unified target scan (replaces the 5 separate ThrottledAttackTargetAI instances
        // that used to run here, each with its own narrower predicate) — modeled on
        // RaiderAttackTargetAI's single-predicate approach in EntityTravellerRaider. One scan,
        // one predicate covering every case, so there's no risk of two of these racing each
        // other and overwriting a target mid-fight, and the IMob/attack-list fallback now
        // actually consults shouldAttackExtraEntity() (it used to silently skip listed
        // non-IMob entities).
        this.targetTasks.addTask(6, new ThrottledAttackTargetAI<>(
                this,
                EntityLivingBase.class,
                10,
                true,
                false,
                e -> {
                    if (e == null || !e.isEntityAlive() || e.isDead) return false;
                    if (e == this || isOwner(e)) return false;

                    if (e instanceof EntityTravellerWorker) {
                        return this.isEnemyHiredWorker((EntityTravellerWorker) e);
                    }
                    if (e instanceof EntityPlayer) {
                        return this.isEnemyPlayer((EntityPlayer) e);
                    }
                    if (e instanceof EntityTraveller) {
                        EntityTraveller t = (EntityTraveller) e;
                        return t.isAggressiveToTravellers() && !this.isAggressiveToTravellers();
                    }
                    // MillVillager falls through to shouldAttackExtraEntity() below, same as
                    // the attack-list case — kept in one place instead of duplicated here.

                    EntityRuleList blacklist = TravellersDerivedConfig.TRAVELLER_AGGRO_BLACKLIST;
                    if (blacklist != null && blacklist.matches(e)) return false;

                    return (e instanceof net.minecraft.entity.monster.IMob) || this.shouldAttackExtraEntity(e);
                }
        ));

        // Worker travel AI (for jobs)
        this.aiTravel = new EntityAITravel(
                this,
                TravellersConfig.SPEED.movementSpeedWhileTravel,
                TravellersConfig.ATTRIBUTES.workerStepBlocks
        );
        this.tasks.addTask(11, aiTravel);

        // Wandering (not hired) workers visit villages like travellers; the donation happens on departure.
        if (getVillageVisitAI() != null) this.tasks.addTask(10, getVillageVisitAI());

        // Follow-leader AI (companion mode) - shared with plain traveller/raider groups, see
        // EntityAIFollowLeader's own class doc.
        this.tasks.addTask(9, new EntityAIFollowLeader(
                this,
                TravellersConfig.SPEED.movementSpeedWhileFollowingPlayer,
                1.0F,
                1.0F
        ));

        // Duty zone work (Stable palfrenier, and any future duty) - no-ops unless actually on duty.
        this.tasks.addTask(13, new com.jubitus.travellers.zone.duty.EntityAIWorkerDuty(this));

        // Lowest priority on purpose - only ever runs when nothing above wants the move+look mutex,
        // i.e. the worker is genuinely idle.
        this.tasks.addTask(14, new com.jubitus.travellers.mount.EntityAIClaimLooseMount(this));
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
        int flat = TravellersConfig.RECRUITMENT.recruitFeeFlat;
        int perVillage = TravellersConfig.RECRUITMENT.recruitFeePerVillage * villagesCount;
        int perDist = (int) Math.ceil(routeDistance * TravellersConfig.RECRUITMENT.recruitFeePerBlock);
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
        return inventory; // inventory (EntityTraveller base) is the single worker inventory
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

    /** Client side: the home shown in GUIs, from {@code MsgSyncWorkerHome}. */
    public void setClientHome(@Nullable BlockPos pos, int dim, String name) {
        this.dataManager.set(HOME_POS_DM, pos == null
                ? com.google.common.base.Optional.absent()
                : com.google.common.base.Optional.of(pos));
        this.dataManager.set(HOME_DIM_DM, dim);
        this.dataManager.set(HOME_NAME_DM, name == null ? "" : name);
    }

    @Nullable
    public ZoneRef getZoneRef() {
        return zone;
    }

    /** Binds this worker to {@code record} (its home, and its duty if the zone has one). Does not
     * move it - the assign messages call {@link #startDutyTravel()} for a duty zone. */
    public void assignZone(PlayerZoneRecord record, int dim) {
        this.zone = ZoneRef.of(record, dim);
        syncZoneDataParams();
    }

    private void syncZoneDataParams() {
        this.dataManager.set(HOME_POS_DM, zone == null
                ? com.google.common.base.Optional.absent()
                : com.google.common.base.Optional.of(zone.center));
        this.dataManager.set(HOME_DIM_DM, zone == null ? 0 : zone.dim);
        this.dataManager.set(HOME_NAME_DM, zone == null ? "" : zone.name);
    }

    /** Server tick: an assignment read from a save older than zone ids gets its id once the zone's
     * dimension is loaded. A zone that no longer exists is released by {@code ZoneAssignments.tickAudit}. */
    private void resolveLegacyZoneRef() {
        if (zone == null || zone.id != null || followOwnerId == null) return;
        if (zone.resolve(followOwnerId) != null) syncZoneDataParams();
    }

    // --- Duty zone assignment: the duty side of the one zone assignment ---

    @Nullable
    public BlockPos getDutyZoneCenter() {
        return zone != null && zone.isDuty() ? zone.center : null;
    }

    public int getDutyZoneDim() {
        return zone != null ? zone.dim : 0;
    }

    @Nullable
    public String getDutyKind() {
        return zone != null ? zone.dutyKind() : null;
    }

    public boolean isGoingToDuty() {
        return goingToDuty;
    }

    public boolean isOnDuty() {
        return mode == WorkerMode.ON_DUTY;
    }

    /**
     * The fixed self-chosen destination this worker is currently walking to under its own travel AI:
     * its duty zone ({@link #startDutyTravel()}) or its home barrack ({@link #startGoHome()}), or
     * null if it is doing neither. Both are "personal travel" - one concrete goal that must keep
     * progressing offscreen and survive virtualize/materialize regardless of {@code mode}, unlike a
     * follow/escort slot. Seeded into {@link #getVirtualGoal()} and mirrored onto the virtual record.
     */
    @Nullable
    public BlockPos getActivePersonalTravelGoal() {
        if (zone == null || zone.dim != this.dimension) return null;
        if (goingToDuty && zone.isDuty()) return zone.center;
        if (goingHome) return zone.center;
        return null;
    }

    /** Sends the worker to its assigned duty zone via the travel AI - mirrors {@link #startGoHome()}'s
     * shape, except {@code mode} becomes {@code ON_DUTY} and stays that way once arrived (the duty is
     * the ongoing state, not a one-shot destination). */
    public void startDutyTravel() {
        if (world.isRemote) return;
        BlockPos dutyCenter = getDutyZoneCenter();
        if (dutyCenter == null) return;
        if (zone.dim != this.dimension) return;

        this.mode = WorkerMode.ON_DUTY;
        this.goingToDuty = true;
        this.routeLocked = true;

        this.followOrderActive = false;
        this.idleAnchorPos = null;

        this.getNavigator().clearPath();
        this.setCurrentWaypoint(null);

        TravelCommon tc = this.getTravelCommon();
        tc.routeTo(dutyCenter);
        tc.setDepartCooldownTicks(0);
    }

    /** Drops the zone assignment. A worker that was working, staying or heading there goes back to
     * following its owner. Returns false if it had none. */
    public boolean releaseFromZone() {
        if (zone == null) return false;
        boolean boundHere = mode == WorkerMode.ON_DUTY || goingToDuty || goingHome
                || zone.center.equals(idleAnchorPos);
        this.zone = null;
        this.goingHome = false;
        this.goingToDuty = false;
        if (this.mode == WorkerMode.ON_DUTY) this.mode = WorkerMode.IDLE;
        syncZoneDataParams();
        if (boundHere) {
            stopAllTravelNow();
            this.returnUsingFollow = false;
            this.awaitingPickup = false;
            this.idleAnchorPos = null;
            if (isHiredFollower()) {
                this.followOrderActive = false;
                setFollowOrderActive(true);
            }
        }
        return true;
    }

    public FollowMode getActiveFollowMode() {
        return activeFollowMode;
    }

    public void setActiveFollowMode(@Nullable FollowMode mode) {
        this.activeFollowMode = mode == null ? FollowMode.SURFACE : mode;
    }

    @Nullable
    public UUID getEscortLeaderUUID() {
        return playerHostility.getEscortLeaderUUID();
    }

    @Nullable
    private EntityTravellerWorker resolveEscortLeader(UUID id) {

        return playerHostility.resolveEscortLeader(id);
    }

    @Nullable
    public EntityTravellerWorker getEscortLeaderEntity() {
        UUID id = getEscortLeaderUUID();
        if (id == null) return null;
        return resolveEscortLeader(id);
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

    /** Synced to the client: the renderer can't see mode/goOrder fields, which are server-only. */
    public boolean isMissionCarrying() {
        return this.dataManager.get(MISSION_CARRY_DM);
    }

    private boolean computeMissionCarry() {
        if (!isHiredFollower()) return false;
        return isOnRecruitMission() || isOnDeliveryMission()
                || mode == WorkerMode.GET_EQUIPMENT
                || goOrderActive || goingHome || goingToDuty;
    }

    public boolean isWorkerChoirActive() { return this.dataManager.get(CHOIR_ACTIVE_DM); }

    public void setWorkerChoirActive(boolean active) { this.dataManager.set(CHOIR_ACTIVE_DM, active); }

    public void setChoirFreeze(boolean active, double lookX, double lookZ) {
        this.choirFreezeActive = active;
        this.choirFreezeLookX = lookX;
        this.choirFreezeLookZ = lookZ;
    }

    public boolean isChoirFreezeActive() { return choirFreezeActive; }

    @Override
    public void stopChoir() {
        super.stopChoir();
        setWorkerChoirActive(false);
        choirFreezeActive = false;
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
    private EntityLivingBase resolveCatchupTarget() {
        if (catchupTargetUUID == null) return null;
        if (world instanceof WorldServer) {
            Entity e = ((WorldServer) world).getEntityFromUuid(catchupTargetUUID);
            if (e instanceof EntityLivingBase && !e.isDead) return (EntityLivingBase) e;
        }
        return null;
    }



    public void startCatchupTravelTo(@Nullable EntityLivingBase target) {
        if (target == null) return;
        if (target.dimension != this.dimension) return;
        beginCatchupToward(target.getUniqueID());
    }

    private void beginCatchupToward(UUID targetId) {
        this.catchupTargetUUID = targetId;
        this.cachedCatchupGoal = null;
        this.nextCatchupRefreshTick = 0;

        this.catchupTravel = true;

        if (this.isHiredFollower()) {
            this.setFollowOrderActive(true);
        }

        TravelCommon tc = this.getTravelCommon();
        tc.clearRoute(true);

//        this.getNavigator().clearPath();
        this.setUsingTravelAI(true);
    }

    @Nullable
    public EntityPlayer getFollowOwner() {
        UUID id = getFollowOwnerUUID();
        return id == null ? null : this.world.getPlayerEntityByUUID(id);
    }

    public void stopOwnerCatchupTravel() {
        stopCatchupTravel();
    }

    // Called when the entity is detected inside an opaque block, before vanilla suffocation damage.
    // Searches nearby for the nearest safe floor (same column first, then adjacent) and teleports there.
    private void escapeFromSolid() {
        int bx = MathHelper.floor(posX);
        int bz = MathHelper.floor(posZ);
        int startY = MathHelper.floor(posY);
        int maxY = world.getActualHeight() - 3;

        int[][] cols = {{0,0},{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}};
        for (int[] col : cols) {
            int ex = bx + col[0];
            int ez = bz + col[1];
            for (int offset = 0; offset <= 8; offset++) {
                int yDown = startY - offset;
                if (yDown >= 1) {
                    BlockPos floor = new BlockPos(ex, yDown, ez);
                    IBlockState st = world.getBlockState(floor);
                    if (!st.getBlock().isAir(st, world, floor)) {
                        IBlockState s1 = world.getBlockState(floor.up());
                        IBlockState s2 = world.getBlockState(floor.up(2));
                        if (s1.getBlock().isAir(s1, world, floor.up()) && s2.getBlock().isAir(s2, world, floor.up(2))) {
                            setPosition(ex + 0.5, yDown + 1.0, ez + 0.5);
                            return;
                        }
                    }
                }
                if (offset > 0) {
                    int yUp = startY + offset;
                    if (yUp <= maxY) {
                        BlockPos floor = new BlockPos(ex, yUp, ez);
                        IBlockState st = world.getBlockState(floor);
                        if (!st.getBlock().isAir(st, world, floor)) {
                            IBlockState s1 = world.getBlockState(floor.up());
                            IBlockState s2 = world.getBlockState(floor.up(2));
                            if (s1.getBlock().isAir(s1, world, floor.up()) && s2.getBlock().isAir(s2, world, floor.up(2))) {
                                setPosition(ex + 0.5, yUp + 1.0, ez + 0.5);
                                return;
                            }
                        }
                    }
                }
            }
        }
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

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        refreshFollowRanges();
    }

    @Override
    protected double configuredMaxHealth() {
        return TravellersConfig.ATTRIBUTES.workerMaxHealth;
    }

    @Override
    protected double configuredFollowRange() {
        return TravellersConfig.ATTRIBUTES.workerFollowRange;
    }

    // Boosted range bounds vanilla's A* chunk cache; keep it at 2x so a path search stays near loaded ground.
    private void refreshFollowRanges() {
        baseFollowRange = configuredFollowRange();
        boostedFollowRange = baseFollowRange * 2.0D;
        IAttributeInstance followAttr = getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE);
        if (followAttr != null) followAttr.setBaseValue(usingBoostedNav ? boostedFollowRange : baseFollowRange);
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
            EntityRuleList blacklist = TravellersDerivedConfig.TRAVELLER_AGGRO_BLACKLIST;
            if (blacklist != null && blacklist.matches(target)) {
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
        this.dataManager.register(OWNER_MARK_COLOR_DM, WorkerOwnerVisualsSavedData.DEFAULT_COLOR);
        this.dataManager.register(OWNER_MARK_STYLE_DM, WorkerOwnerVisualsSavedData.DEFAULT_STYLE);
        this.dataManager.register(CHOIR_ACTIVE_DM, false);
        this.dataManager.register(MISSION_CARRY_DM, false);
    }

    // NBT: persist employer + job so restart doesn’t lose the mission


    /**
     * Donate backpack contents to the target village & then mark job delivered.
     * Call this from your worker-specific delivery AI when at the town hall.
     */

    @Override
    protected void dropEquipment(boolean wasRecentlyHit, int lootingModifier) {
        // Consolidate real items from entity slots into inventory before drop. Ghost items were
        // already purged by EntityTraveller.onDeath, so every remaining stack here is real.
        for (net.minecraft.inventory.EntityEquipmentSlot slot : net.minecraft.inventory.EntityEquipmentSlot.values()) {
            ItemStack s = getItemStackFromSlot(slot);
            if (s.isEmpty()) continue;
            net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inventory, s.copy(), false);
            setItemStackToSlot(slot, ItemStack.EMPTY);
        }
        // Vanilla dropEquipment is suppressed; everything goes through dropLoot → dropInventoryFull
    }

    @Override
    protected void dropLoot(boolean wasRecentlyHit, int lootingModifier, DamageSource source) {
        // A hired worker always drops everything it's carrying (own stakes, no random gate) — skip
        // EntityTraveller.dropLoot's dropBackpackLoot() pass entirely, since dropInventoryFull below
        // would drop whatever survives it anyway.
        dropFewItems(wasRecentlyHit, lootingModifier);
        dropEquipment(wasRecentlyHit, lootingModifier);
        equipment.dropInventoryFull(inventory);
        equipment.dropInventoryFull(travelInventory);
    }

    @Override
    protected float computeMoveSpeedCap(double base) {
        if (this.isAimingBow()) return (float) Math.min(base * 0.98D, 0.30D);
        if (this.getAttackTarget() != null) {
            double mult = isGuardingAnchor() ? 1.18D : 1.12D;
            double max = isGuardingAnchor() ? 0.36D : 0.34D;
            return (float) Math.min(base * mult, max);
        }
        if (this.isHiredFollower() && !this.isJobActive()) {
            EntityLivingBase t = this.getEffectiveFollowTarget();
            double d = (t != null) ? this.getDistance(t) : 0.0D;
            double mult = d > 128.0D ? 1.80D : d > 64.0D ? 1.60D : d > 32.0D ? 1.40D : 1.35D;
            return (float) Math.min(base * mult, 0.46D);
        }
        return (float) (base * 0.7D);
    }

    @Override
    public void onLivingUpdate() {
        if (!world.isRemote && isEntityInsideOpaqueBlock()) {
            escapeFromSolid();
        }
        super.onLivingUpdate();

        // Choir freeze: skip ALL worker navigation/movement logic.
        // Movement and look are handled by EntityAIChoirFreeze (priority 0, mutex 3).
        if (!world.isRemote && choirFreezeActive) {
            this.motionX = 0;
            this.motionZ = 0;
            getNavigator().clearPath();
            return;
        }

        if (!world.isRemote && catchupTravel) {
            EntityLivingBase t = resolveCatchupTarget();
            if (t == null) t = getEffectiveFollowTarget();

            if (t != null && !t.isDead && t.dimension == this.dimension) {
                FollowRuntimeConfig cfg = FollowRuntimeConfig.get();

                boolean caveLike = FollowRecoveryHelper.isLeaderMeaningfullyUnderground(world, t, cfg)
                        || FollowRecoveryHelper.isEnclosedFollowSpace(world, t, cfg);

                // Check distance to SLOT, not to leader. The worker's goal is its
                // formation position — once it's close enough, normal follow AI takes over.
                double stopDist = caveLike ? cfg.catchup.stopDistanceCave : cfg.catchup.stopDistanceVisible;
                boolean stop;

                // Also check distance to leader directly — the slot goal can be
                // stale if the leader moved/teleported after catchup started.
                double distToLeader = this.getDistance(t);

                if (followerHasSlotGoal) {
                    double dx = this.posX - followerSlotGoalX;
                    double dy = this.posY - followerSlotGoalY;
                    double dz = this.posZ - followerSlotGoalZ;
                    double distToSlot = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double verticalGapSlot = Math.abs(dy);

                    boolean closeToSlot = caveLike
                            ? (distToSlot <= stopDist && verticalGapSlot <= cfg.cave.verticalTolerance + 1.5D)
                            : (distToSlot <= stopDist);
                    // Fallback: also stop if close enough to the leader itself
                    boolean closeToLeader = distToLeader <= stopDist;

                    stop = closeToSlot || closeToLeader;
                } else {
                    // No slot known — fall back to distance to leader
                    boolean visible = this.canEntityBeSeen(t) || t.canEntityBeSeen(this);
                    double verticalGap = Math.abs(this.posY - t.posY);

                    stop = caveLike
                            ? (distToLeader <= stopDist && verticalGap <= cfg.cave.verticalTolerance + 1.5D)
                            : (distToLeader <= stopDist && (visible || distToLeader <= stopDist * 0.75D));
                }

                // Near but trapped (a pit under the leader): the travel ladder must finish before follow resumes.
                if (stop && !getStuckRecord().isEscalating()) {
                    stopOwnerCatchupTravel();
                }
            } else if (virtualEscortSlot == null || !isVirtualLeader(catchupTargetUUID)
                    || (getDistanceSqToCenter(virtualEscortSlot)
                    <= FollowRuntimeConfig.get().catchup.stopDistanceVisible * FollowRuntimeConfig.get().catchup.stopDistanceVisible
                    && !getStuckRecord().isEscalating())) {
                // An offscreen escort leader is chased through its slot until close to it; a gone one ends catchup.
                stopOwnerCatchupTravel();
            }
        }


        if (!world.isRemote && recruitReturningToOwner) {
            tickRecruitReturnToOwner();
        }

        // Server-side: register/update this worker as a chunkloader
        if (!world.isRemote) {
            if ((this.ticksExisted % 20) == 0) { // once per second
                refreshCachedAttitude();

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
            tickVirtualLeaderFollow();

// Update leader follower count every 5s. world.getEntities() is not cheap at 500 workers.
            if (--leaderCountCooldown <= 0) {
                leaderCountCooldown = 100;
                updateLeaderFollowerCount();
                equipment.syncEquipmentFromInventory();
                resolveLegacyZoneRef();
            }

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
                    tickStayAtIdleAnchor();
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
            boolean carrying = computeMissionCarry();
            if (carrying != this.dataManager.get(MISSION_CARRY_DM)) this.dataManager.set(MISSION_CARRY_DM, carrying);
//            tickUndergroundFollowRescue();

        }

        if (world.isRemote) return;

        TravelCommon tc = this.getTravelCommon();

        if (!world.isRemote && goingHome && zone != null && zone.dim == this.dimension) {
            int r = tc.arrivalRadiusBlocks(zone.center);
            if (this.getDistanceSqToCenter(zone.center) <= (double) (r * r)) {

                goingHome = false;

                // 1) wipe travel completely (no route, no waypoint, no target)
                stopAllTravelNow();

                // 2) now anchor at home (follow OFF)
                this.followOrderActive = false;
                this.idleAnchorPos = zone.center;
                this.getNavigator().clearPath();

                EntityPlayer owner = getFollowOwner();
                if (owner != null) {
                    NotificationService.notify(owner, NotificationDefs.WORKER_ARRIVED_HOME);
                }
            }
        }

        // --- Duty zone arrival --- mirrors the Home arrival block above, except mode stays ON_DUTY:
        // the duty is the ongoing state once arrived, not a one-shot destination like Home is.
        BlockPos dutyCenter = getDutyZoneCenter();
        if (!world.isRemote && goingToDuty && dutyCenter != null && zone.dim == this.dimension) {
            int r = tc.arrivalRadiusBlocks(dutyCenter);
            if (this.getDistanceSqToCenter(dutyCenter) <= (double) (r * r)) {
                goingToDuty = false;
                stopAllTravelNow();
                this.followOrderActive = false;
                this.idleAnchorPos = dutyCenter;
                this.getNavigator().clearPath();
            }
        }
// --- "Go..." order arrival ---
        if (!world.isRemote && goOrderActive && goOrderTargetPos != null && goOrderTargetDim == this.dimension) {
            int r = mode == WorkerMode.GET_EQUIPMENT
                    ? TravellersConfig.WORKER.workerEquipArrivalRadius : TravellersConfig.WORKER.workerGoArrivalRadius;
            double dx = this.posX - (goOrderTargetPos.getX() + 0.5D);
            double dz = this.posZ - (goOrderTargetPos.getZ() + 0.5D);
            if (dx * dx + dz * dz <= (double) (r * r)) {

                goOrderActive = false;
                stopAllTravelNow();
                this.getNavigator().clearPath();

                if (mode == WorkerMode.GET_EQUIPMENT) {
                    if (getEquipTargetKind == com.jubitus.travellers.unit.equipment.EquipTargetKind.STABLE) {
                        com.jubitus.travellers.zone.PlayerZoneManager zoneMgr =
                                com.jubitus.travellers.zone.PlayerZoneManager.get(world);
                        com.jubitus.travellers.zone.PlayerZoneRecord zone =
                                zoneMgr.getByOwnerAndCenter(this.getFollowOwnerId(), goOrderTargetPos);
                        if (zone != null) {
                            boolean wantedHorse = this.getMountUUID() == null;
                            if (wantedHorse) {
                                NBTTagCompound horseNbt = com.jubitus.travellers.zone.stable.WorkerHorsePickup
                                        .claimReadyHorse((net.minecraft.world.WorldServer) world, zone);
                                if (horseNbt != null && !com.jubitus.travellers.mount.TravellerMountSupport
                                        .bondSpecificMount(this, horseNbt)) {
                                    // Spawn genuinely failed (a UUID collision this couldn't avoid) -
                                    // don't just lose the horse from the pool's own count.
                                    com.jubitus.travellers.zone.stable.WorkerHorsePickup.returnUnclaimedHorse(
                                            (net.minecraft.world.WorldServer) world, zone, horseNbt);
                                }
                            }
                            // Whoever this order was sent to arriving is what unlocks the whole group -
                            // same precedent WorkerEquipExchanger already set for chest gear: followers
                            // don't each need to travel here individually.
                            int followerHorses = com.jubitus.travellers.zone.stable.WorkerHorsePickup.claimForGroupFollowers(
                                    (net.minecraft.world.WorldServer) world, zone, this.getUniqueID());
                            if (wantedHorse && this.getMountUUID() == null) {
                                com.jubitus.travellers.zone.stable.WorkerHorsePickup.notifyNothingToEquip(
                                        world, zone, this.getFollowOwnerId());
                            }
                            int horses = followerHorses + (wantedHorse && this.getMountUUID() != null ? 1 : 0);
                            if (horses > 0) {
                                net.minecraft.server.MinecraftServer srv = world.getMinecraftServer();
                                UUID ownerId = this.getFollowOwnerId();
                                int total = 1 + (srv != null && ownerId != null
                                        ? com.jubitus.travellers.follow.group.WorkerGroupService
                                        .countFollowers(srv, ownerId, this.getUniqueID()) : 0);
                                com.jubitus.travellers.notify.MissionNotifications.equipped(srv, ownerId, horses, total);
                            }
                        }
                    } else {
                        boolean useWholeVillage = getEquipTargetKind
                                == com.jubitus.travellers.unit.equipment.EquipTargetKind.CONTROLLED_VILLAGE;
                        int[] equip = com.jubitus.travellers.unit.equipment.WorkerEquipExchanger
                                .exchangeWithFollowers(this, world, goOrderTargetPos, useWholeVillage);
                        com.jubitus.travellers.notify.MissionNotifications.equipped(
                                world.getMinecraftServer(), this.getFollowOwnerId(), equip[0], equip[1]);
                    }
                    if (getEquipStay) {
                        mode = WorkerMode.IDLE;
                        this.followOrderActive = false;
                        this.idleAnchorPos = goOrderTargetPos;
                    } else {
                        // Immediately become IDLE so follow AI brings the worker back naturally
                        finishJob();
                        if (this.isHiredFollower()) {
                            this.setFollowOrderActive(true);
                        }
                    }
                } else {
                    // Normal go order: stay here
                    this.followOrderActive = false;
                    this.idleAnchorPos = goOrderTargetPos;
                    com.jubitus.travellers.notify.MissionNotifications.arrived(
                            world.getMinecraftServer(), this.getFollowOwnerId(), this.getUniqueID());
                }
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
                            tc2.clearRoute(false);

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

                    tc.clearRoute(false);
                    this.getNavigator().clearPath();

                    awaitingPickup = true;
                    // Stay on the tight follow escort until the player takes the goods (no timeout)
                    returnUsingFollow = true;
                    if (this.isHiredFollower()) {
                        this.setFollowOrderActive(true);
                    }

                    if (!pickupNotified) {
                        pickupNotified = true;
                        this.playHelloSound();
                        NotificationService.notify(employer, this.hasUnsoldGoods
                                ? NotificationDefs.WORKER_CARGO_ARRIVED_PARTIAL
                                : NotificationDefs.WORKER_CARGO_ARRIVED_FULL);
                    }
                }

            } else if (employerReturnPos != null && !awaitingPickup) {
                // Fallback: if we lost the player entity, just go to last known target and wait there
                int r = tc.arrivalRadiusBlocks(employerReturnPos);
                if (this.getDistanceSqToCenter(employerReturnPos) <= (double) (r * r)) {
                    tc.clearRoute(false);
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

    public int getOwnerMarkColor() {
        return this.dataManager.get(OWNER_MARK_COLOR_DM) & 0xFFFFFF;
    }

    public String getOwnerMarkStyleId() {
        return this.dataManager.get(OWNER_MARK_STYLE_DM);
    }

    public WorkerMarkStyle getOwnerMarkStyle() {
        return WorkerMarkStyle.byId(getOwnerMarkStyleId());
    }

    public boolean shouldRenderOwnerMark() {
        return this.isHiredFollower() && this.getFollowOwnerId() != null;
    }

    public boolean isVisualLeader() {
        return this.getLeaderFollowerCount() > 0;
    }

    public void setOwnerVisuals(int color, String styleId) {
        if (!world.isRemote) {
            this.dataManager.set(OWNER_MARK_COLOR_DM, color & 0xFFFFFF);
            this.dataManager.set(OWNER_MARK_STYLE_DM, WorkerMarkStyle.byId(styleId).id);
        }
    }

    public void refreshOwnerVisualsFromSavedData() {
        if (world.isRemote) return;

        UUID ownerId = this.getFollowOwnerId();
        if (ownerId == null) {
            setOwnerVisuals(
                    WorkerOwnerVisualsSavedData.DEFAULT_COLOR,
                    WorkerOwnerVisualsSavedData.DEFAULT_STYLE
            );
            return;
        }

        WorkerOwnerVisualsSavedData.Entry entry = WorkerOwnerVisualsSavedData.get(this.world).getFor(ownerId);
        setOwnerVisuals(entry.color, entry.styleId);
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
            rollRenderScale(getActiveAppearance());
        }
        IAttributeInstance moveAttr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (moveAttr != null) {
            moveAttr.setBaseValue(TravellersConfig.SPEED.movementSpeed);
        }

        // Equipment is initialised here (rather than by the base class) so it reads the worker's
        // own appearance pool/texture index above, not the plain-traveller one rolled by super.onInitialSpawn.
        if (!world.isRemote) {
            equipment.initSpawnEquipment(c, this.rand);
        }

        // Move spawn weapon from MAINHAND into inventory so the GUI shows it and the ghost system manages display
        if (!world.isRemote) {
            ItemStack mainhand = getHeldItemMainhand();
            if (!mainhand.isEmpty() && !equipment.isGhostWeapon(mainhand) && !equipment.isGhostBow(mainhand)) {
                net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inventory, mainhand.copy(), false);
                setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }

        return livingdata;
    }

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;
        if (this.isInCombat()) {
            return false;
        }

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
            com.jubitus.travellers.debug.MissionDebugLog.loaded(this, "pickup-gui");
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
            NotificationService.notify(player, NotificationDefs.WORKER_ALREADY_ON_JOB);
            return true;
        }

        // 3) Normal idle interaction → open HUB GUI (Transport / Follow)
        if (!this.isInCombat()) {
            if (player instanceof EntityPlayerMP) {
                // Same two syncs as the HUB case of MsgOpenWorkerSubGui: the hub displays the hire
                // state and the authoritative follower count as soon as it opens.
                TravellersNetwork.CHANNEL.sendTo(
                        new com.jubitus.travellers.worker.net.MsgSyncWorkerHireState(this),
                        (EntityPlayerMP) player
                );
                TravellersNetwork.CHANNEL.sendTo(
                        new MsgOpenClientWorkerGui(this.getEntityId(), MsgOpenClientWorkerGui.Type.HUB),
                        (EntityPlayerMP) player
                );
                com.jubitus.travellers.follow.group.WorkerGroupService.syncLeaderFollowerCount(
                        (EntityPlayerMP) player, this.getUniqueID());
            }
        }
        return true;
    }

    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            clearLeaderFromLoadedEscortsOnDeath();
            setFollowOrderActive(false);

            if (homeVillageCenter != null && world instanceof WorldServer) {
                WorldServer ws = (WorldServer) world;
                TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);

// Clear only if this UUID is the registered one.
// Do NOT respawn here: the village may be unloaded.
// When the village chunks are loaded again, ensureWorkersForVillages() will respawn.
                reg.clearWorkerFor(homeVillageCenter, this.getUniqueID());
            }

            // Bed respawn is decided first: the death notice says whether the worker comes back.
            if (hiredFollower && zone != null && world.getMinecraftServer() != null) {
                NBTTagCompound nbt = new NBTTagCompound();
                writeToNBT(nbt);
                stripInventoryNbt(nbt);
                com.jubitus.travellers.zone.ZoneAssignments.onWorkerDied(
                        world.getMinecraftServer(), getUniqueID(), followOwnerId, zone, nbt);
            }

            // A battle participant's notice comes from VirtualBattleManager.onParticipantDeath (via super.onDeath).
            if (hiredFollower && followOwnerId != null && world instanceof WorldServer
                    && !com.jubitus.travellers.battle.VirtualBattleManager.get((WorldServer) world)
                            .isParticipant(this.getUniqueID())) {
                com.jubitus.travellers.notify.WorkerLifecycleNotifications.died(world.getMinecraftServer(),
                        followOwnerId, getUniqueID(), getName(),
                        com.jubitus.travellers.notify.NoticePlace.of(this),
                        com.jubitus.travellers.notify.VanillaDeathMessage.from(this, cause), null);
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
        // Enemy-village Millénaire villagers (declared via the Behavior GUI's village relations)
        // must be reachable through this same hook, since every proactive target-scan task
        // (EntityAIDefendIfClose, EntityAIDefendOwnerProactively) calls shouldAttackExtraEntity()
        // rather than knowing about MillVillager specifically — without this, only the slow,
        // throttled fallback scan ever picked them up, making engagement look scattered/delayed
        // across a formation instead of the whole group reacting together.
        if (e instanceof org.millenaire.common.entity.MillVillager) {
            return isEnemyMillVillager((org.millenaire.common.entity.MillVillager) e);
        }
        // Workers get their own configurable attack list; falls back to the traveller list
        // when no worker-specific rule was ever configured.
        if (com.jubitus.travellers.config.TravellersDerivedConfig.WORKER_ATTACK_LIST.hasRules()) {
            if (com.jubitus.travellers.config.TravellersDerivedConfig.WORKER_ATTACK_LIST.matches(e)) return true;
        } else if (com.jubitus.travellers.config.TravellersDerivedConfig.TRAVELLER_ATTACK_LIST.matches(e)) {
            return true;
        }
        // Ancient Warfare 2 - always consulted independently of the attack-list branch above.
        // Bug this fixes: this override used to call super.shouldAttackExtraEntity() (which
        // has the AW2 check) only as a fallback when WORKER_ATTACK_LIST had no rules of its
        // own - but WORKER_ATTACK_LIST ships with non-empty defaults (LavaCow entries), so
        // hasRules() was true and AW2 npcs never reached the AW2 check at all for any worker.
        return checkAncientWarfare2Hostility(e);
    }


    @Override
    public void onConfigReload() {
        super.onConfigReload();
        refreshFollowRanges();
        FollowRuntimeConfig.get().reloadFromTravellersConfig();
    }

    public void setEscortLeaderUUID(@Nullable UUID id) {
        // Prevent cycles / self-follow
        if (wouldCreateEscortCycle(id)) {
            id = null;
        }

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
        this.pickupNotified = false;
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
        com.jubitus.travellers.debug.MissionDebugLog.loaded(this, "delivery-start");
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
    /**
     * Back beside its bed after dying: it stands there on its own (follow off, out of any group, no
     * order or mission left over from before its death). Its zone - and a palfrenier's duty - is kept.
     */
    public void resetForBedRespawn(BlockPos spot) {
        clearEscortLeader();
        stopOwnerCatchupTravel();
        this.goOrderActive = false;
        this.goOrderTargetPos = null;
        this.goOrderTargetDim = 0;
        this.goingHome = false;
        this.goingToDuty = false;
        this.recruitActive = false;
        this.recruitReturningToOwner = false;
        this.returnUsingFollow = false;
        if (this.mode != WorkerMode.ON_DUTY) finishJob();
        this.followOrderActive = false;
        stopAllTravelNow();
        this.idleAnchorPos = spot;
    }

    private void finishJob() {
        this.mode = WorkerMode.IDLE;
        this.jobVillageCenter = null;
        this.jobDelivered = false;
        this.donationJob = false;
        this.hasUnsoldGoods = false;
        this.awaitingPickup = false;
        this.pickupNotified = false;
        this.returnRepathCooldown = 0;

        // KEEP companion mode locked if hired follower
        this.routeLocked = this.isHiredFollower();   // <-- change

        TravelCommon tc = this.getTravelCommon();
        tc.clearRoute(false);

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
        if (!(world instanceof net.minecraft.world.WorldServer)) return;
        EntityPlayer employer = getEmployer();

        com.jubitus.travellers.debug.MissionDebugLog.loaded(this, "trade-before");
        MillGoods.Delivery d = MillGoods.deliverCargo((net.minecraft.world.WorldServer) world, villageCenter,
                getTravelInventory(), employer, this.donationJob);
        if (!d.reached) return;
        com.jubitus.travellers.debug.MissionDebugLog.loaded(this, "trade-after");

        if (d.unsold) this.hasUnsoldGoods = true;
        if (d.refused) NotificationService.notify(employer, NotificationDefs.TRADE_REFUSED);
        if (d.reputation != 0 && employer != null && TravellersConfig.WORKER.workerNotifyOnVillageTrade) {
            notifyTradeSummary(d.villageName, employer, this.donationJob, d.items, d.money, d.reputation, d.traded);
        }
        if (!this.donationJob && d.money > 0) addMoneyToWorkerInventory(d.money);
        onJobDelivered();
    }

    /** One toast per transaction: totals plus the three most valuable items (2nd line), "(+N)" for the rest. */
    private static void notifyTradeSummary(String villageName, EntityPlayer employer, boolean donateMode,
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
            NotificationService.notify(employer, NotificationDefs.TRADE_DONATED_SUMMARY, villageName,
                    String.valueOf(totalItems), String.valueOf(totalRep), items.toString());
        } else {
            NotificationService.notify(employer, NotificationDefs.TRADE_SOLD_SUMMARY, villageName,
                    String.valueOf(totalItems), MillMoney.format(totalMoney),
                    String.valueOf(totalRep), items.toString());
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
        tc.routeTo(newReturnPos);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    public void onLootCollected(EntityPlayer player) {
        // Only finish if we were on a return job
        if (this.mode == WorkerMode.RETURNING && this.jobDelivered) {
            com.jubitus.travellers.debug.MissionDebugLog.loaded(this, "pickup-done");

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
            ITextComponent farewell = new TextComponentTranslation("travellers.dialogue.worker_farewell");
            farewell.getStyle().setColor(TextFormatting.WHITE);
            player.sendMessage(farewell);
        }
    }

    @Override
    public boolean usesVillageVisit() {
        return TravellersConfig.VILLAGE_VISIT.enabledWorkers && !isHiredFollower() && isIdleForRoaming();
    }

    public boolean isIdleForRoaming() {
        // free to behave like a normal traveller
        return this.mode == WorkerMode.IDLE && !this.isWaitingForPickup();
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
            aiTravel.setStepBlocks(Math.min(TravellersConfig.ATTRIBUTES.workerStepBlocks, Math.max(12, fr - 8)));
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
        // Following and a Go order are exclusive: either change ends the Go order along with its
        // travel route, or the travel AI keeps walking to the old destination against the follow AI.
        if (this.goOrderActive) {
            cancelGoOrderAndTravel();
            if (!active) {
                this.idleAnchorPos = this.getPosition();
                return;
            }
        }
        if (active) {
            // Player re-engages follow: cancel any "going home" travel too
            if (this.goingHome) {
                this.goingHome = false;
                stopAllTravelNow();            // clears TravelCommon target/route/waypoint + navigator
                this.returnUsingFollow = false;
                this.awaitingPickup = false;
            }

            // Follow ON
            this.idleAnchorPos = null;
            this.getNavigator().clearPath();
            FollowFormationCoordinator.notifyWorkerRejoined(this.getUniqueID());
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

        // Never "wait at stop" in catchup mode.
        tc.routeTo(newPos);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    // Approximate surface position above player's XZ
// Approximate surface position near any follow target (player OR leader worker)
    public BlockPos getApproximateSurfacePosNear(@Nullable EntityLivingBase target) {
        if (target == null) {
            return this.employerReturnPos != null ? this.employerReturnPos : this.getPosition();
        }

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        BlockPos actual = new BlockPos(target.posX, target.getEntityBoundingBox().minY, target.posZ);

        boolean underground = FollowRecoveryHelper.isLeaderMeaningfullyUnderground(this.world, target, cfg)
                || FollowRecoveryHelper.isEnclosedFollowSpace(this.world, target, cfg);

        if (underground) {
            return actual;
        }

        int groundY = ColumnGround.topLayer(this.world, actual.getX(), actual.getZ(), true);
        return groundY != ColumnGround.NONE ? new BlockPos(actual.getX(), groundY, actual.getZ()) : actual;
    }

    private void stopAllTravelNow() {
        TravelCommon tc = this.getTravelCommon();

        tc.clearRoute(false);

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
     * Hire/dismiss already exist – just update to default follow state.
     */
    public void hireAsFollower(EntityPlayer owner) {
        UUID id = owner.getUniqueID();
        this.followOwnerId = id;
        this.hiredFollower = true;
        if (!world.isRemote) {
            this.dataManager.set(HIRED_FOLLOWER_DM, true);
            this.dataManager.set(FOLLOW_OWNER_DM, Optional.of(id));
            this.refreshOwnerVisualsFromSavedData();
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
        this.routeLocked = false;
        if (!world.isRemote) {
            this.dataManager.set(HIRED_FOLLOWER_DM, false);
            this.dataManager.set(FOLLOW_OWNER_DM, Optional.absent());
            this.setUsingTravelAI(false);
        }
        this.followOrderActive = false;
        this.idleAnchorPos = null;
        if (!world.isRemote) {
            this.setOwnerVisuals(
                    WorkerOwnerVisualsSavedData.DEFAULT_COLOR,
                    WorkerOwnerVisualsSavedData.DEFAULT_STYLE
            );
        }
    }

    public void setUsingTravelAI(boolean using) {
        if (!world.isRemote) {
            this.dataManager.set(USING_TRAVEL_AI, using);
        }
    }

    /** Remove all inventory data from a worker NBT so the respawned entity starts empty. */
    public static void stripInventoryNbt(NBTTagCompound nbt) {
        // Vanilla equipment
        nbt.removeTag("HandItems");
        nbt.removeTag("ArmorItems");
        // EntityTraveller personal inventory
        nbt.removeTag("TravellerInv");
        // EntityTravellerWorker cargo
        nbt.removeTag("TravelInventory");
        // Legacy key (migrated during readFromNBT but strip it anyway)
        nbt.removeTag("PersonalInventory");
    }

    // Mark/unmark specific players as hostile


    // Keeps a hired follower from standing inside its owner. Where it looks is the gaze's job.
    private void tickFollowerIdleBehaviour() {
        if (!isHiredFollower() || !isFollowOrderActive() || isJobActive()) {
            followerHasSlotGoal = false; // only meaningful while following
            return;
        }
        if (isInCombat() || getLeaderFollowerCount() > 0) return;
        EntityPlayer owner = getFollowOwner();
        if (owner == null || owner.isDead) return;
        EntityLivingBase followTarget = getEffectiveFollowTarget();
        double minSeparation = followTarget != null && isMovingEnough(followTarget) ? 1.2D : 0.3D;
        double dx = this.posX - owner.posX;
        double dz = this.posZ - owner.posZ;
        if (dx * dx + dz * dz < minSeparation * minSeparation) applySoftSeparation(dx, dz, 0.05D);
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

            if (!world.isRemote && world instanceof net.minecraft.world.WorldServer) {
                com.jubitus.travellers.virtual.VirtualTravellerManager vwm =
                        com.jubitus.travellers.virtual.VirtualTravellerManager
                                .get((net.minecraft.world.WorldServer) world);
                if (vwm.isVirtual(leaderId)) {
                    // Leader is in the virtual registry (either still in entity list with isDead,
                    // or already removed). tickVirtualLeaderFollow() handles movement.
                    return null;
                }
                // Leader is loaded but invalid (bad state, wrong owner, etc.): clear
                if (leader != null) {
                    clearEscortLeader();
                }
                // Leader not in world and not virtual: truly gone -> fall through to player
            }
        }
        return getFollowOwner();
    }

    /** Refreshes {@link #cachedAttitude}/{@link #cachedPlayerCohesion} from the scope this worker
     * currently follows - see their field docs for why combat/follow AI reads the cache instead of
     * the saved data directly. */
    private void refreshCachedAttitude() {
        if (!isHiredFollower()) {
            cachedAttitude = com.jubitus.travellers.follow.formations.WorkerAttitude.ATTACK;
            cachedPlayerCohesion = 0.4D;
            return;
        }
        // A worker that leads its own group is scoped to ITSELF (its group's settings live under
        // its own UUID, set from the Hub entry for that leader) - resolving via
        // getEffectiveFollowTarget() would instead chase up to whoever THIS worker happens to be
        // following (usually the player, or another leader in a leader-follows-leader chain), which
        // is a different group's settings entirely. isVisualLeader() is the same cheap loaded-only
        // "do I have followers" signal already used for the armband texture - fine here too, this is
        // just an AI-behaviour heuristic, not authoritative state.
        com.jubitus.travellers.follow.formations.WorkerFormationScopeKey scope = isVisualLeader()
                ? new com.jubitus.travellers.follow.formations.WorkerFormationScopeKey(
                        com.jubitus.travellers.follow.formations.WorkerFormationScopeKey.Type.WORKER,
                        getUniqueID())
                : com.jubitus.travellers.follow.formations.WorkerFormationScopeResolver
                        .resolveRuntimeScope(getEffectiveFollowTarget());
        if (scope == null) {
            cachedAttitude = com.jubitus.travellers.follow.formations.WorkerAttitude.ATTACK;
            cachedPlayerCohesion = 0.4D;
            return;
        }
        com.jubitus.travellers.follow.formations.WorkerFormationSettings settings =
                com.jubitus.travellers.follow.formations.WorkerFormationSavedData
                        .get(world).getSettings(scope);
        cachedAttitude = settings.attitude;
        cachedPlayerCohesion = settings.playerCohesion;
    }

    private void tickStayAtIdleAnchor() {
        if (!isHiredFollower()) return;
        if (this.mode == WorkerMode.RETURNING && this.jobDelivered) return;
        if (isJobActive()) return;
        if (this.goingHome) return;
        if (followOrderActive) return;
        if (idleAnchorPos == null) return;

        if (this.getAttackTarget() != null) return;

        double maxRadius = TravellersConfig.WORKER.workerIdleAnchorRadius;
        double distSq = this.getDistanceSqToCenter(idleAnchorPos);

        if (distSq > maxRadius * maxRadius) {
            if (this.getNavigator().noPath()) {
                this.getNavigator().tryMoveToXYZ(
                        idleAnchorPos.getX() + 0.5D,
                        idleAnchorPos.getY(),
                        idleAnchorPos.getZ() + 0.5D,
                        TravellersConfig.SPEED.movementSpeedWhileFollowing * 0.85D  // was 0.4
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
        // Recruit return: use follow AI to get back to player (jobDelivered is never set for recruit missions)
        if (this.recruitReturningToOwner) return true;
        // Sale/transport return: allow follow AI even while awaitingPickup so worker stays close (3-4 blocks)
        return this.mode == WorkerMode.RETURNING
                && this.jobDelivered
                && this.returnUsingFollow;
    }

    /** Transport worker coming back to the player: tight escort instead of a formation slot. */
    public boolean isPickupEscortActive() {
        return this.mode == WorkerMode.RETURNING && this.jobDelivered && this.returnUsingFollow;
    }

    // Recruit mission (GuiWorkerRecruit): outbound village tour + follow-AI return leg to owner
    public boolean isOnRecruitMission() {
        return this.mode == WorkerMode.RECRUITING || this.recruitReturningToOwner;
    }

    // Sale/donation mission (GuiWorker): outbound trip to village + full return leg to owner
    public boolean isOnDeliveryMission() {
        return this.mode == WorkerMode.TO_VILLAGE
                || (this.mode == WorkerMode.RETURNING && this.jobDelivered);
    }

    public List<BlockPos> pickRecruitVillages(EntityPlayer player) {
        World world = this.world;
        BlockPos origin = player.getPosition();

        int maxRadius = TravellersConfig.RECRUITMENT.recruitMaxVillageRadius;
        int minN = TravellersConfig.RECRUITMENT.recruitVillagesMin;
        int maxN = TravellersConfig.RECRUITMENT.recruitVillagesMax;

        List<BlockPos> centers = MillSettlements.snapshot(world).villageCenters;
        List<BlockPos> inRange = new ArrayList<>();

        for (BlockPos c : centers) {
            int d = dist2D(origin, c);

            // NEW: enforce minimum distance for recruitment
            if (d < TravellersConfig.RECRUITMENT.recruitMinVillageDistance) {
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
        this.recruitCurrentStopPos = null;
        resetRecruitArrivalWatchdog();

        this.mode = WorkerMode.RECRUITING;
        this.routeLocked = true;

        if (this.isHiredFollower()) {
            this.setFollowOrderActive(true);
        }

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;

        for (BlockPos v : recruitVillages) {
            tc.route.add(resolveRecruitStopPos(v));
        }
        if (returnPos != null) {
            tc.route.add(returnPos);
        }

        tc.routeIndex = 0;
        setRecruitLegTarget(this.recruitVillages.get(0));
    }

    private void tickRecruitMission() {
        TravelCommon tc = this.getTravelCommon();

        if (recruitIndex < recruitVillages.size()) {
            BlockPos targetVillage = recruitVillages.get(recruitIndex);

            if (hasReachedRecruitVillage(targetVillage)) {
                if (!recruitSpawnedAt.contains(targetVillage)) {
                    recruitSpawnedAt.add(targetVillage);
                    spawnRecruitsAtVillage(targetVillage);
                }

                recruitIndex++;

                if (recruitIndex < recruitVillages.size()) {
                    tc.routeIndex = recruitIndex;
                    setRecruitLegTarget(recruitVillages.get(recruitIndex));
                } else {
                    this.recruitCurrentStopPos = null;
                    resetRecruitArrivalWatchdog();

                    beginRecruitReturnToOwner();

                    if (recruitReturnPos != null) {
                        tc.setTargetVillage(recruitReturnPos);
                        this.setTargetVillage(recruitReturnPos);
                        tc.setCurrentWaypoint(null);
                        tc.setReadyToDepart(true);
                        tc.setDepartCooldownTicks(0);
                        this.getNavigator().clearPath();
                    }
                }
            }

            return;
        }

        EntityPlayer owner = getFollowOwner();
        if (owner != null && !owner.isDead && owner.dimension == this.dimension) {
            double dx = this.posX - owner.posX;
            double dz = this.posZ - owner.posZ;
            double distHsq = dx * dx + dz * dz;

            if (distHsq <= 8.0D * 8.0D) {
                finishRecruitMission(owner);
            }
        } else {
            finishRecruitMission(null);
        }
    }

    private void spawnRecruitsAtVillage(BlockPos villageCenter) {
        if (!(this.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) this.world;

        EntityPlayer owner = getFollowOwner();
        if (owner == null) return;

        int min = TravellersConfig.RECRUITMENT.recruitSpawnMin;
        int max = TravellersConfig.RECRUITMENT.recruitSpawnMax;
        int count = min + this.rand.nextInt(Math.max(1, (max - min + 1)));

        for (int i = 0; i < count; i++) {
            BlockPos spawnPos = EntityTravellerWorker.findRecruitSpawnPos(world, villageCenter, this.rand);
            EntityTravellerWorker w = new EntityTravellerWorker(ws);

            w.setPosition(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D);
            w.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), null);

            // IMPORTANT: no home village, so no respawn-on-death
            w.setHomeVillage(null);

            // Make them hired followers of the SAME owner, but escort THIS recruiter as leader
            w.hireAsFollower(owner);
            w.setEscortLeader(this);

            ws.spawnEntity(w);
        }

        String villageName = resolveVillageName(villageCenter);
        NotificationService.notify(owner, NotificationDefs.RECRUIT_WORKERS_RECRUITED,
                String.valueOf(count), villageName);
    }

    /**
     * Best-effort village name lookup for player-facing recruit notifications.
     */
    private String resolveVillageName(BlockPos villageCenter) {
        Building th = MillSettlements.townHall(world, villageCenter);
        return th != null ? th.getVillageQualifiedName() : "a village";
    }

    private void finishRecruitMission(@Nullable EntityPlayer owner) {
        this.recruitActive = false;
        this.recruitVillages.clear();
        this.recruitSpawnedAt.clear();
        this.recruitIndex = 0;
        this.recruitReturnPos = null;
        this.recruitCurrentStopPos = null;
        resetRecruitArrivalWatchdog();

        // Clear travel route
        TravelCommon tc = this.getTravelCommon();
        tc.clearRoute(false);
        this.getNavigator().clearPath();

        // Back to companion idle
        this.mode = WorkerMode.IDLE;
        this.routeLocked = true; // keep companion mode
        this.setFollowOrderActive(true);

        if (owner != null) {
            NotificationService.notify(owner, NotificationDefs.RECRUIT_MISSION_COMPLETED);
        }
    }

    /**
     * Called when this worker is a temporary (non-spawned) entity loaded from virtual record NBT.
     * Marks the stop as done, advances recruit index, sets next leg or transitions to return.
     * Returns the number of recruits to create (0 if already done at this stop).
     */
    public int advanceRecruitStopVirtually(BlockPos stop) {
        if (stop == null || recruitSpawnedAt.contains(stop)) return 0;
        recruitSpawnedAt.add(stop);

        int count = TravellersConfig.RECRUITMENT.recruitSpawnMin
                + rand.nextInt(Math.max(1, TravellersConfig.RECRUITMENT.recruitSpawnMax - TravellersConfig.RECRUITMENT.recruitSpawnMin + 1));

        recruitIndex++;
        if (recruitIndex < recruitVillages.size()) {
            getTravelCommon().routeIndex = recruitIndex;
            setRecruitLegTarget(recruitVillages.get(recruitIndex));
        } else {
            // Last stop done — transition to returning via follow AI (mirrors beginRecruitReturnToOwner)
            recruitCurrentStopPos = null;
            recruitReturningToOwner = true;
            mode = WorkerMode.RETURNING;
            routeLocked = true;
            // Clear TravelAI entirely: getVirtualGoal() tracks the player's current position,
            // and once materialized the real-entity path uses follow AI (isReturnUsingFollow=true)
            TravelCommon tc = getTravelCommon();
            tc.clearRoute(false);
        }
        return count;
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
        tc.clearRoute(false);

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
                || this.goingToDuty
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

        // Refresh more frequently when in view distance — the worker is close
        // enough that a stale goal causes visible stuck behavior.
        boolean inView = isInPlayerViewDistance();
        long refreshInterval = FollowRuntimeConfig.get().catchup.repathIntervalTicks;
        if (inView) {
            refreshInterval = Math.min(refreshInterval, 20); // at most 1 second
        }

        if (cachedCatchupGoal == null || now >= nextCatchupRefreshTick) {
            Entity e = ((WorldServer) world).getEntityFromUuid(catchupTargetUUID);

            // When in view distance, always navigate toward the LEADER directly.
            // The slot goal may be stale (set before the player moved/teleported),
            // and the follow AI will assign the proper slot once catchup ends.
            if (inView && e instanceof EntityLivingBase && e.isEntityAlive() && e.dimension == this.dimension) {
                EntityLivingBase target = (EntityLivingBase) e;
                cachedCatchupGoal = getApproximateSurfacePosNear(target);
            } else if (followerHasSlotGoal) {
                // Navigate to the formation SLOT, not to the leader.
                // In large groups (100-500), the slot can be far from the leader.
                // The worker must reach its assigned position, not crowd the leader.
                cachedCatchupGoal = new BlockPos(
                        MathHelper.floor(followerSlotGoalX),
                        MathHelper.floor(followerSlotGoalY),
                        MathHelper.floor(followerSlotGoalZ)
                );
            } else if (e instanceof EntityLivingBase && e.isEntityAlive() && e.dimension == this.dimension) {
                // No slot known yet — fall back to leader position
                EntityLivingBase target = (EntityLivingBase) e;
                FollowRuntimeConfig cfg = FollowRuntimeConfig.get();

                boolean caveLike = FollowRecoveryHelper.isLeaderMeaningfullyUnderground(world, target, cfg)
                        || FollowRecoveryHelper.isEnclosedFollowSpace(world, target, cfg);

                BlockPos raw = caveLike
                        ? new BlockPos(target.posX, target.getEntityBoundingBox().minY, target.posZ)
                        : getApproximateSurfacePosNear(target);

                int q = caveLike ? cfg.catchup.dynamicGoalQuantizeCave : cfg.catchup.dynamicGoalQuantizeSurface;
                if (q < 1) q = 1;
                cachedCatchupGoal = new BlockPos(
                        MathHelper.floor(raw.getX() / (double) q) * q,
                        raw.getY(),
                        MathHelper.floor(raw.getZ() / (double) q) * q
                );
            }
            nextCatchupRefreshTick = now + refreshInterval;
        }

        return cachedCatchupGoal;
    }

    public boolean isCatchupTravel() {
        return catchupTravel;
    }

    @Override
    public boolean isItineraryDriven() {
        if (catchupTravel) return false;
        if (getActivePersonalTravelGoal() != null) return true;
        if (isOnFixedGoalMission()) return true;
        return !hiredFollower && mode == WorkerMode.IDLE && getEscortLeaderUUID() == null;
    }

    /**
     * A mission toward a fixed place (sale/transport, a recruiting stop, a Go order, Get Equipment).
     * The return to the owner is not one: the owner moves. Mirrors VirtualTravellerManager.missionDest.
     */
    public boolean isOnFixedGoalMission() {
        if (!hiredFollower) return false;
        if (mode == WorkerMode.TO_VILLAGE) return jobVillageCenter != null;
        if (mode == WorkerMode.RECRUITING) return recruitCurrentStopPos != null;
        return goOrderActive && goOrderTargetPos != null
                && (mode == WorkerMode.IDLE || mode == WorkerMode.GET_EQUIPMENT);
    }

    public void setCatchupTravel(boolean v) {
        this.catchupTravel = v;
    }

    @Nullable
    public UUID getCatchupTargetUUID() {
        return catchupTargetUUID;
    }

    public WorkerMode getMode() {
        return mode;
    }

    @Nullable
    public BlockPos getJobGoal() {
        if (mode == WorkerMode.TO_VILLAGE) return jobVillageCenter;
        if (mode == WorkerMode.RECRUITING) return recruitCurrentStopPos;
        return null;
    }

    @Nullable
    public BlockPos getVirtualGoal() {
        // Catchup travel always takes priority
        if (catchupTravel && catchupTargetUUID != null && world instanceof net.minecraft.world.WorldServer) {
            Entity tgt = ((net.minecraft.world.WorldServer) world).getEntityFromUuid(catchupTargetUUID);
            if (tgt != null && !tgt.isDead) return tgt.getPosition();
        }
        // Personal self-travel (Go Home / Go To Duty): a concrete destination the worker is already
        // walking to. Must survive virtualization with a real goal, not null.
        BlockPos personal = getActivePersonalTravelGoal();
        if (personal != null) return personal;
        // Active mission modes: go to mission destination, never override with owner pos
        if (mode == WorkerMode.TO_VILLAGE && jobVillageCenter != null) return jobVillageCenter;
        if (mode == WorkerMode.RETURNING) {
            // Recruit return: track player's CURRENT position (not the fixed original start point)
            if (recruitReturningToOwner && isHiredFollower() && followOwnerId != null) {
                EntityPlayer owner = world.getPlayerEntityByUUID(followOwnerId);
                if (owner != null && !owner.isDead) return owner.getPosition();
            }
            if (employerReturnPos != null) return employerReturnPos;
        }
        if (mode == WorkerMode.RECRUITING && recruitCurrentStopPos != null) return recruitCurrentStopPos;
        // Go order (IDLE mode, specific target)
        if (goOrderActive && goOrderTargetPos != null) return goOrderTargetPos;
        // Escort follower: head toward the leader (loaded or virtual), never the owner. getVirtualGoal
        // seeds a virtual record's initial goal, and a follower in a group must not be seeded toward
        // the player - the refreshGoal escort branch then refines this into the exact formation slot.
        UUID escortLeader = getEscortLeaderUUID();
        if (isHiredFollower() && escortLeader != null && world instanceof WorldServer) {
            WorldServer ws = (WorldServer) world;
            Entity le = ws.getEntityFromUuid(escortLeader);
            if (le != null && !le.isDead) return le.getPosition();
            BlockPos vp = com.jubitus.travellers.virtual.VirtualTravellerManager
                    .get(ws).getVirtualPosition(escortLeader);
            if (vp != null) return vp;
            return null; // leader unknown right now: stay put, refreshGoal sorts it out
        }
        // Hired follower, no active mission, follow ON: follow owner
        if (isHiredFollower() && followOwnerId != null && followOrderActive) {
            EntityPlayer owner = world.getPlayerEntityByUUID(followOwnerId);
            if (owner != null && !owner.isDead) return owner.getPosition();
        }
        // Non-hired roaming workers head to their target village. A hired worker with follow off and no
        // active order is anchored - it must stay put (return null), never chase a stale targetVillage
        // (its "ancien emplacement"), which would resurface on the next virtualize→materialize round-trip.
        if (!isHiredFollower()) {
            BlockPos tv = getTargetVillage();
            if (tv != null) return tv;
        }
        return null;
    }

    /**
     * Unlike {@link #getVirtualGoal()}, this deliberately covers only the escort-leader case - every
     * other mode {@code getVirtualGoal()} resolves (catchup, mission, Go order, recruiting, follow-owner)
     * ends up driven by {@code EntityAITravel}, which has its own, more reliable stuck detection (see
     * {@link EntityTraveller#tickEdgeVirtualizeProbe}'s class doc). Escorting a leader is the one mode
     * that never hands off to it, and so is the one mode this probe still needs to cover.
     */
    @Override
    @Nullable
    protected BlockPos getEdgeProbeGoal() {
        UUID escortLeader = getEscortLeaderUUID();
        if (!isHiredFollower() || escortLeader == null || !(world instanceof WorldServer)) return null;
        WorldServer ws = (WorldServer) world;
        Entity le = ws.getEntityFromUuid(escortLeader);
        if (le != null && !le.isDead) return le.getPosition();
        return com.jubitus.travellers.virtual.VirtualTravellerManager
                .get(ws).getVirtualPosition(escortLeader);
    }

    @Override
    public boolean canVirtualizeIgnoringCombat() {
        if (!super.canVirtualizeIgnoringCombat()) return false;
        if (awaitingPickup) return false;
        if (mode == WorkerMode.TO_VILLAGE && jobVillageCenter != null) {
            if (getDistanceSqToCenter(jobVillageCenter) < 48.0 * 48.0) return false;
        }
        if (mode == WorkerMode.RECRUITING && recruitCurrentStopPos != null) {
            if (getDistanceSqToCenter(recruitCurrentStopPos) < 24.0 * 24.0) return false;
        }
        if (mode == WorkerMode.GET_EQUIPMENT && goOrderTargetPos != null) {
            if (getDistanceSqToCenter(goOrderTargetPos) < 48.0 * 48.0) return false;
        }
        return true;
    }

    /** Called by VirtualTravellerManager when a Go order is reached offscreen (mirrors the loaded arrival). */
    public void finishGoOrderVirtually(BlockPos target) {
        goOrderActive = false;
        goOrderTargetPos = null;
        followOrderActive = false;
        idleAnchorPos = target;
    }

    /** Called by VirtualTravellerManager after a virtual equipment exchange. */
    public void finishGetEquipVirtually(boolean stay, BlockPos target) {
        if (stay) {
            mode = WorkerMode.IDLE;
            goOrderActive = false;
            goOrderTargetPos = null;
            followOrderActive = false;
            idleAnchorPos = target;
        } else {
            finishJob();
            if (isHiredFollower()) setFollowOrderActive(true);
        }
    }

    public void stopCatchupTravel() {
        this.catchupTravel = false;

        this.catchupTargetUUID = null;
        this.cachedCatchupGoal = null;
        this.nextCatchupRefreshTick = 0;

        TravelCommon tc = this.getTravelCommon();
        tc.clearRoute(false);

        this.getNavigator().clearPath();
        this.setUsingTravelAI(false);
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
        if (zone == null) return;
        if (zone.dim != this.dimension) return;
        BlockPos home = zone.center;

        this.goingHome = true;
        this.routeLocked = true;

        // disable follow, but DO NOT set an anchor yet
        this.followOrderActive = false;
        this.idleAnchorPos = null;

        this.setReturnUsingFollow(false);


        this.getNavigator().clearPath();
        this.setCurrentWaypoint(null);

        TravelCommon tc = this.getTravelCommon();
        tc.routeTo(home);
        tc.setDepartCooldownTicks(0);
    }

    public void setReturnUsingFollow(boolean v) {
        this.returnUsingFollow = v;
        // if using DataManager: dataManager.set(RETURN_USING_FOLLOW, v);
    }

    public int getLeaderFollowerCount() {
        return this.dataManager.get(LEADER_FOLLOWER_COUNT_DM);
    }

    /**
     * Client-only cache of the authoritative (loaded + virtual) follower count, pushed by
     * {@code MsgSyncLeaderFollowerCount}. -1 = never received (falls back to the loaded-only
     * {@link #getLeaderFollowerCount()} so a GUI never shows a bogus "0" before the server responds).
     * Deliberately not a DataParameter: {@link #LEADER_FOLLOWER_COUNT_DM} must stay loaded-only for
     * the AI/armband logic that reads it, so this is tracked separately for display purposes only.
     */
    private int clientAuthoritativeLeaderFollowerCount = -1;

    public void setClientAuthoritativeLeaderFollowerCount(int count) {
        this.clientAuthoritativeLeaderFollowerCount = count;
    }

    /** Best known total follower count (loaded + virtual) for display — see the field doc above. */
    public int getDisplayLeaderFollowerCount() {
        return clientAuthoritativeLeaderFollowerCount >= 0
                ? clientAuthoritativeLeaderFollowerCount
                : getLeaderFollowerCount();
    }

    public int getCachedGroupSize() { return cachedGroupSize; }
    public void setCachedGroupSize(int size) { cachedGroupSize = Math.max(1, size); }

    public com.jubitus.travellers.follow.formations.WorkerAttitude getCachedAttitude() {
        return cachedAttitude;
    }

    public double getCachedPlayerCohesion() {
        return cachedPlayerCohesion;
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

    public enum WorkerMode {
        IDLE,
        TO_VILLAGE,
        RETURNING,
        RECRUITING,
        GET_EQUIPMENT,
        /** Persistently assigned to a zone's duty (see {@link #getDutyKind}), same lifecycle shape as a
         * Barrack home but with an actual job to run once arrived - see EntityAIWorkerDuty. */
        ON_DUTY
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

        // Always check virtual registry first: setDead() leaves the entity in the loaded
        // list for 1+ ticks with isDead=true, which would incorrectly look like a dead leader.
        if (world instanceof net.minecraft.world.WorldServer) {
            com.jubitus.travellers.virtual.VirtualTravellerManager vwm =
                    com.jubitus.travellers.virtual.VirtualTravellerManager
                            .get((net.minecraft.world.WorldServer) world);
            if (vwm.isVirtual(leaderId)) {
                // Leader is virtual and alive; keep escort UUID
                return;
            }
        }

        // Leader in world but invalid (dead, dismissed, wrong owner) -> clear immediately
        boolean invalid =
                (leader != null)
                        && (leader.isDead
                        || !leader.isHiredFollower()
                        || !java.util.Objects.equals(leader.getFollowOwnerId(), this.getFollowOwnerId()));

        if (invalid) {
            this.clearEscortLeader();
            return;
        }

        // Leader not in world and not virtual: truly gone -> fall back to player
        if (leader == null) {
            this.clearEscortLeader();
        }

    }

    // A loaded escort follower whose leader is offscreen: resolve its formation slot next to the leader's
    // virtual position and hand it to catchup travel (EntityAITravel and its stuck ladder), the same way
    // a follower too far behind a loaded leader is.
    private void tickVirtualLeaderFollow() {
        if (world.isRemote) return;
        if (!isHiredFollower() || getAttackTarget() != null || !isFollowOrderActive()) return;

        UUID leaderId = getEscortLeaderUUID();
        if (leaderId == null || !(world instanceof net.minecraft.world.WorldServer)) {
            virtualEscortSlot = null;
            return;
        }
        net.minecraft.world.WorldServer serverWorld = (net.minecraft.world.WorldServer) world;
        com.jubitus.travellers.virtual.VirtualTravellerManager vwm =
                com.jubitus.travellers.virtual.VirtualTravellerManager.get(serverWorld);
        if (!vwm.isVirtual(leaderId)) {
            virtualEscortSlot = null;
            return;
        }

        // Staggered: resolvePhysicalFollowerGoal is O(followers of this leader).
        if (virtualLeaderRepathCooldown < 0) {
            virtualLeaderRepathCooldown = Math.floorMod(getEntityId(), 40);
        }
        if (--virtualLeaderRepathCooldown > 0) return;
        virtualLeaderRepathCooldown = 40;

        net.minecraft.util.math.BlockPos leaderPos = vwm.getVirtualPosition(leaderId);
        if (leaderPos == null) return;
        com.jubitus.travellers.virtual.VirtualTravellerRecord leaderRecord = vwm.getRecord(leaderId);

        boolean mounted = com.jubitus.travellers.pathing.PathBody.isMounted(this)
                || (leaderRecord != null && leaderRecord.mountNbt != null);
        net.minecraft.util.math.BlockPos slotPos =
                com.jubitus.travellers.virtual.VirtualFormationSimulator.resolvePhysicalFollowerGoal(
                        serverWorld, vwm.getAllRecords(), leaderId,
                        leaderPos.getX() + 0.5, leaderPos.getY(), leaderPos.getZ() + 0.5,
                        leaderRecord != null ? leaderRecord.rotYaw : 0.0f,
                        getUniqueID(), mounted, world.getTotalWorldTime());

        virtualEscortSlot = resolveVirtualSlot(slotPos.getX() + 0.5D, slotPos.getZ() + 0.5D);
        notifyFollowerSlotGoal(virtualEscortSlot.getX() + 0.5D, virtualEscortSlot.getY(), virtualEscortSlot.getZ() + 0.5D);

        double startDist = FollowRuntimeConfig.get().catchup.stopDistanceVisible * 2.0D;
        if (!catchupTravel && getDistanceSqToCenter(virtualEscortSlot) > startDist * startDist) {
            beginCatchupToward(leaderId);
        }
    }

    private boolean isVirtualLeader(@Nullable UUID id) {
        return id != null && world instanceof net.minecraft.world.WorldServer
                && com.jubitus.travellers.virtual.VirtualTravellerManager
                .get((net.minecraft.world.WorldServer) world).isVirtual(id);
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
        tc.routeTo(pos);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    public void startGetEquipMission(BlockPos target, boolean stay,
                                      com.jubitus.travellers.unit.equipment.EquipTargetKind kind) {
        if (world.isRemote || target == null) return;

        this.getEquipStay = stay;
        this.getEquipTargetKind = kind == null
                ? com.jubitus.travellers.unit.equipment.EquipTargetKind.LONE_BARRACK : kind;
        this.mode = WorkerMode.GET_EQUIPMENT;

        this.goingHome = false;
        this.catchupTravel = false;
        this.returnUsingFollow = false;
        this.awaitingPickup = false;
        this.jobDelivered = false;

        this.followOrderActive = false;
        this.idleAnchorPos = null;
        this.goOrderActive = true;
        this.goOrderTargetPos = target;
        this.goOrderTargetDim = this.dimension;
        this.routeLocked = true;

        TravelCommon tc = getTravelCommon();
        tc.routeTo(target);
        tc.setDepartCooldownTicks(0);

        getNavigator().clearPath();
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
            if (recruitCurrentStopPos != null) {
                nbt.setInteger("RecruitStopX", recruitCurrentStopPos.getX());
                nbt.setInteger("RecruitStopY", recruitCurrentStopPos.getY());
                nbt.setInteger("RecruitStopZ", recruitCurrentStopPos.getZ());
            }
            nbt.setInteger("RecruitArrivalWatchdogTicks", recruitArrivalWatchdogTicks);
            nbt.setDouble("RecruitBestDistSq", recruitBestDistSq);
            if (jobVillageCenter != null) {
                nbt.setInteger("JobVX", jobVillageCenter.getX());
                nbt.setInteger("JobVY", jobVillageCenter.getY());
                nbt.setInteger("JobVZ", jobVillageCenter.getZ());
            }
            nbt.setInteger("WorkerMode", mode.ordinal());
            nbt.setBoolean("GetEquipStay", getEquipStay);
            nbt.setString("GetEquipTargetKind", getEquipTargetKind.name());
            nbt.setBoolean("JobDelivered", jobDelivered);
            nbt.setBoolean("RouteLocked", routeLocked);
            nbt.setBoolean("DonationJob", donationJob);
            nbt.setTag("TravelInventory", travelInventory.serializeNBT());

            nbt.setBoolean("AwaitingPickup", awaitingPickup);
            nbt.setBoolean("HasUnsoldGoods", hasUnsoldGoods);
            nbt.setBoolean("PostJobFarewellActive", postJobFarewellActive);
            nbt.setInteger("PostJobFarewellTicks", postJobFarewellTicks);

            // home village (PersonalInventory removed — inventory is saved by EntityTraveller as "TravellerInv")
            if (homeVillageCenter != null) {
                nbt.setInteger("HomeVillageX", homeVillageCenter.getX());
                nbt.setInteger("HomeVillageY", homeVillageCenter.getY());
                nbt.setInteger("HomeVillageZ", homeVillageCenter.getZ());
            }
            if (followOwnerId != null) {
                nbt.setUniqueId("FollowOwnerId", followOwnerId);
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

            if (zone != null) zone.write(nbt);
            nbt.setBoolean("GoingHome", goingHome);
            nbt.setBoolean("GoingToDuty", goingToDuty);


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
            if (nbt.hasKey("RecruitStopX")) {
                recruitCurrentStopPos = new BlockPos(
                        nbt.getInteger("RecruitStopX"),
                        nbt.getInteger("RecruitStopY"),
                        nbt.getInteger("RecruitStopZ")
                );
            } else {
                recruitCurrentStopPos = null;
            }

            recruitArrivalWatchdogTicks = nbt.getInteger("RecruitArrivalWatchdogTicks");
            recruitBestDistSq = nbt.hasKey("RecruitBestDistSq") ? nbt.getDouble("RecruitBestDistSq") : Double.MAX_VALUE;

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
            getEquipStay = nbt.getBoolean("GetEquipStay");
            getEquipTargetKind = nbt.hasKey("GetEquipTargetKind")
                    ? com.jubitus.travellers.unit.equipment.EquipTargetKind.fromNbt(nbt.getString("GetEquipTargetKind"))
                    // Legacy saves stored a boolean instead - true meant CONTROLLED_VILLAGE, false LONE_BARRACK.
                    : (nbt.getBoolean("GetEquipWholeVillage")
                        ? com.jubitus.travellers.unit.equipment.EquipTargetKind.CONTROLLED_VILLAGE
                        : com.jubitus.travellers.unit.equipment.EquipTargetKind.LONE_BARRACK);
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


            // Migrate old PersonalInventory into the unified inventory (backward compat)
            if (nbt.hasKey("PersonalInventory")) {
                ItemStackHandler legacy = new ItemStackHandler(18);
                legacy.deserializeNBT(nbt.getCompoundTag("PersonalInventory"));
                for (int i = 0; i < legacy.getSlots(); i++) {
                    ItemStack s = legacy.getStackInSlot(i);
                    if (!s.isEmpty()) {
                        net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inventory, s.copy(), false);
                    }
                }
            }

            // home village
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

            zone = ZoneRef.read(nbt);
            if (!world.isRemote) syncZoneDataParams();

            goingHome = nbt.getBoolean("GoingHome");
            goingToDuty = nbt.getBoolean("GoingToDuty");
            BlockPos dutyCenter = getDutyZoneCenter();
            if (dutyCenter != null) {
                // Duty is a persistent state, not a one-shot mission - mode must be restored
                // unconditionally, whether the worker is still travelling there or already arrived.
                mode = WorkerMode.ON_DUTY;
                if (!world.isRemote && goingToDuty && zone.dim == dimension) {
                    TravelCommon dutyTc = getTravelCommon();
                    boolean dutyRouteMissing = dutyTc.route == null || dutyTc.route.isEmpty() || dutyTc.routeIndex < 0;
                    if (dutyRouteMissing) {
                        dutyTc.routeTo(dutyCenter);
                        dutyTc.setDepartCooldownTicks(0);

                        getNavigator().clearPath();
                    }
                }
            } else if (mode == WorkerMode.ON_DUTY) {
                mode = WorkerMode.IDLE;
            }
            if (!world.isRemote && goingHome && zone != null && zone.dim == dimension) {
                BlockPos assignedHomePos = zone.center;
                TravelCommon tc = getTravelCommon();

                boolean routeMissing = tc.route == null || tc.route.isEmpty() || tc.routeIndex < 0;
                if (routeMissing) {
                    tc.routeTo(assignedHomePos);
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
                    tc.routeTo(goOrderTargetPos);
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
        if (!world.isRemote) {
            refreshOwnerVisualsFromSavedData();
        }
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

        // If attacker is a player: only retaliate if that player has declared war on my owner
        if (attacker instanceof EntityPlayer) {
            EntityPlayer ap = (EntityPlayer) attacker;
            if (ap.isCreative() || ap.isSpectator()) return false;

            UUID attackerOwner = ap.getUniqueID();
            if (attackerOwner.equals(myOwner)) return false;
            if (!(world instanceof WorldServer)) return false;

            return com.jubitus.travellers.combat.PlayerEnemyRelations.get(world)
                    .isHostile(attackerOwner, myOwner);
        }

        // Ancient Warfare 2 faction NPC (Empire, Bandits, etc.) - reputation/engagement-aware,
        // see AncientWarfare2Compat. Covers both "I hit this one, it's now hostile to my owner"
        // and "this one joined the fight against my owner via AW2's own alert reaction".
        if (com.jubitus.travellers.combat.compat.AncientWarfare2Compat.isHostile(attacker, this.getFollowOwner())) {
            return true;
        }

        return false;
    }

    public void engageEnemyOwner(@Nullable UUID enemyOwner) {
        if (enemyOwner == null) return;
        this.playerHostility.engageOwner(enemyOwner, PVP_ENGAGE_TICKS);
    }

    private static final double GROUP_RALLY_RADIUS = 24.0D;

    /**
     * Nearby hired workers of {@code ownerId} around {@code center} - the group a single worker's
     * retaliation should pull in, shared by WorkerTeamPvPHandler (declared-war PvP) and
     * EntityAIWorkerHurtByTargetTraveller (everything else canRetaliateAgainst already allows:
     * mobs, AW2 hostiles, enemy hired workers). Loaded-side only, same as the offscreen side's
     * own group pull-in in BattleEncounterScanner.
     */
    public static List<EntityTravellerWorker> nearbyOwnedMates(World world, BlockPos center, UUID ownerId) {
        if (!(world instanceof WorldServer) || ownerId == null) return Collections.emptyList();
        net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(center)
                .grow(GROUP_RALLY_RADIUS, 8.0D, GROUP_RALLY_RADIUS);
        return world.getEntitiesWithinAABB(EntityTravellerWorker.class, box,
                w -> w != null && w.isEntityAlive() && w.isHiredFollower() && ownerId.equals(w.getFollowOwnerId()));
    }

    /**
     * Pulls every nearby same-owner hired worker into a fight against {@code attacker}, without
     * stealing a mate that's already busy with a live target. Callers are expected to have already
     * decided the single worker in {@code self} is allowed to fight {@code attacker} (this doesn't
     * re-check canRetaliateAgainst for the group - the whole point is that admission was already
     * granted once, at the individual level).
     */
    public static void rallyGroupAgainst(EntityTravellerWorker self, EntityLivingBase attacker, @Nullable UUID enemyOwner) {
        UUID ownerId = self.getFollowOwnerId();
        if (ownerId == null || attacker == null || !attacker.isEntityAlive()) return;
        for (EntityTravellerWorker mate : nearbyOwnedMates(self.world, self.getPosition(), ownerId)) {
            if (mate == self) continue;
            if (enemyOwner != null) mate.engageEnemyOwner(enemyOwner);
            EntityLivingBase cur = mate.getAttackTarget();
            if (cur == null || !cur.isEntityAlive()) {
                mate.setAttackTarget(attacker);
            }
        }
    }
    private void clearLeaderFromLoadedEscortsOnDeath() {
        if (world.isRemote) return;
        if (!(world instanceof WorldServer)) return;

        MinecraftServer server = ((WorldServer) world).getMinecraftServer();
        if (server == null) return;

        UUID ownerId = this.getFollowOwnerId();
        if (ownerId == null) return;

        UUID leaderId = this.getUniqueID();

        for (WorldServer ws : server.worlds) {
            List<EntityTravellerWorker> escorts = ws.getEntities(EntityTravellerWorker.class, other ->
                    other != null
                            && other.isEntityAlive()
                            && other.isHiredFollower()
                            && Objects.equals(other.getFollowOwnerId(), ownerId)
                            && leaderId.equals(other.getEscortLeaderUUID())
            );

            for (EntityTravellerWorker other : escorts) {
                // CLEAR the leader UUID so they can be assigned a new leader
                other.clearEscortLeader();

                // Optional but usually desired: make them follow the owner again
                other.setFollowOrderActive(true);
            }
        }
    }
    private boolean isEnemyMillVillager(org.millenaire.common.entity.MillVillager v) {
        if (v == null || !v.isEntityAlive()) return false;
        if (!(this.world instanceof net.minecraft.world.WorldServer)) return false;

        java.util.UUID ownerId = this.getFollowOwnerId();
        if (ownerId == null) return false;

        Building th = v.getTownHall();
        if (th == null) return false;

       Point p = th.getPos();
        if (p == null) return false;

        net.minecraft.util.math.BlockPos center = new net.minecraft.util.math.BlockPos(p.getiX(), p.getiY(), p.getiZ());

        TravellerVillageRelations rel =
                TravellerVillageRelations.get((net.minecraft.world.WorldServer) this.world);

        int dim = this.world.provider.getDimension();
        return rel.isEnemy(ownerId, dim, center);
    }
    /**
     * Called by EntityAIFollowLeader to tell this worker what its current formation/idle slot is.
     * Server-side only in practice (AI runs on server), but safe if called elsewhere.
     */
    private static boolean isMovingEnough(EntityLivingBase e) {
        if (e == null) return false;

        double dpX = e.posX - e.prevPosX;
        double dpZ = e.posZ - e.prevPosZ;
        double mvSq = dpX * dpX + dpZ * dpZ;

        double moX = e.motionX;
        double moZ = e.motionZ;
        double moSq = moX * moX + moZ * moZ;

        return (Math.max(mvSq, moSq) > MOTION_EPSILON) || e.isSprinting() || e.isSneaking();
    }

    public boolean isGoOrderActive() {
        return goOrderActive;
    }

    @Nullable
    public BlockPos getGoOrderTargetPos() {
        return goOrderTargetPos;
    }

    public void cancelGoOrderAndTravel() {
        goOrderActive = false;
        goOrderTargetPos = null;
        goOrderTargetDim = 0;
        stopAllTravelNow();
    }

    private double distSq2DTo(BlockPos pos) {
        if (pos == null) return Double.MAX_VALUE;
        double dx = (this.posX) - (pos.getX() + 0.5D);
        double dz = (this.posZ) - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private void resetRecruitArrivalWatchdog() {
        this.recruitArrivalWatchdogTicks = 0;
        this.recruitBestDistSq = Double.MAX_VALUE;
    }

    private BlockPos resolveRecruitTownHallAnchor(BlockPos villageCenter) {
        if (world == null || world.isRemote || villageCenter == null) return villageCenter;

        MillSettlements.Settlement s = MillSettlements.at(world, villageCenter);
        return s != null ? s.center : villageCenter;
    }

    private BlockPos resolveRecruitStopPos(BlockPos villageCenter) {
        BlockPos anchor = resolveRecruitTownHallAnchor(villageCenter);
        BlockPos safe = findRecruitSpawnPos(this.world, anchor, this.rand);
        return safe != null ? safe : anchor;
    }

    private void setRecruitLegTarget(BlockPos villageCenter) {
        BlockPos stop = resolveRecruitStopPos(villageCenter);

        this.recruitCurrentStopPos = stop;
        resetRecruitArrivalWatchdog();

        TravelCommon tc = this.getTravelCommon();
        tc.setTargetVillage(stop);
        this.setTargetVillage(stop);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        this.getNavigator().clearPath();
    }

    private boolean hasReachedRecruitVillage(BlockPos villageCenter) {
        if (villageCenter == null) return false;

        BlockPos stop = this.recruitCurrentStopPos != null
                ? this.recruitCurrentStopPos
                : resolveRecruitStopPos(villageCenter);

        double stopDistSq = distSq2DTo(stop);
        double villageDistSq = distSq2DTo(villageCenter);

        if (stopDistSq <= (double) (RECRUIT_STOP_RADIUS * RECRUIT_STOP_RADIUS)) {
            return true;
        }

        if (stopDistSq + RECRUIT_PROGRESS_EPS_SQ < this.recruitBestDistSq) {
            this.recruitBestDistSq = stopDistSq;
            this.recruitArrivalWatchdogTicks = 0;
            return false;
        }

        boolean nearVillage = villageDistSq <= (double) (RECRUIT_VILLAGE_RADIUS * RECRUIT_VILLAGE_RADIUS);
        if (!nearVillage) {
            this.recruitArrivalWatchdogTicks = 0;
            return false;
        }

        boolean stalled = this.getNavigator().noPath()
                || (this.motionX * this.motionX + this.motionZ * this.motionZ) <= MOTION_EPSILON;

        if (stalled) {
            this.recruitArrivalWatchdogTicks++;
        } else if (this.recruitArrivalWatchdogTicks > 0) {
            this.recruitArrivalWatchdogTicks--;
        }

        return this.recruitArrivalWatchdogTicks >= RECRUIT_STALL_TICKS;
    }


}
