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
import com.jubitus.travellers.unit.culture.TravellerAppearance;
import com.jubitus.travellers.stay.EntityAIStay;
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
import com.jubitus.travellers.follow.group.WorkerGroupService;
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


    // --- escort leader watchdog ---
    private int escortLeaderCheckCooldown = 0;
    // -1 = uninitialised; set to entity-ID-based phase offset on first tick to spread load across ticks
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
    // A merchant's escort carries no backpack; the group link itself is server-only.
    private static final DataParameter<Boolean> MERCHANT_ESCORT_DM =
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

    public final PlayerHostility playerHostility = new PlayerHostility(this);
    public final RecruitMission recruit = new RecruitMission(this);
    public final WorkerPurse purse = new WorkerPurse();
    public final DeliveryJob delivery = new DeliveryJob(this);
    public final WorkerTrips trips = new WorkerTrips(this);
    // Gear kinds ticked at its last Get Equipment; null = never set (picks up any upgrade).
    @Nullable
    private com.jubitus.travellers.unit.equipment.EquipLoadout equipRole;
    // 18-slot cargo inventory used for deliveries (separate from personal backpack)
    private final ItemStackHandler travelInventory = new ItemStackHandler(18);
    // --- follower / hire state (existing) ---
    @Nullable
    public UUID followOwnerId = null;
    public boolean hiredFollower = false;
    // Client copy of the home village's name, sent with the hire state (village data is server-only).
    public String clientHomeVillageName = "";
    // Client copy of how many workers a hire takes (the merchant's whole group), sent with the hire state.
    public int clientHireGroupSize = 1;
    public int clientHirePrice = -1;
    // Client copies for the hire screen's hints, about the viewing player.
    public boolean clientHireNeedsChief = false;
    public int clientFreeBarrackBeds = 0;
    // Client copy: whether this hired worker has a bed (any zone assignment), for the hub's warning.
    public boolean clientHasBed = true;
    // --- the one zone assignment: a Barrack (lodging) or a Stable (lodging + duty) ---
    @Nullable
    ZoneRef zone = null;
    private int anchorScanCooldown = 0;
    private int leaderCountCooldown = 0;
    private int stuckAtVillageTicks = 0;
    boolean returnUsingFollow = false;
    // NEW: whether follow AI is currently active (player’s "Follow" order)
    boolean followOrderActive = false;
    // NEW: where the worker was told to stay when follow was toggled OFF
    @Nullable
    BlockPos idleAnchorPos = null;
    // NEW: leader logic – if non-null, this worker follows another worker instead of the player
    private boolean usingBoostedNav = false;
    WorkerMode mode = WorkerMode.IDLE;
    // When true, base EntityTraveller will NOT auto-plan routes
    boolean routeLocked = false;

    private FollowMode activeFollowMode = FollowMode.SURFACE;

    public EntityTravellerWorker(World worldIn) {
        super(worldIn);
        this.enablePersistence();
        this.forceSpawn = true;

        // Re-added below at a priority above the worker's own travel task.
        removeTaskOfType(EntityAIStay.class);
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
                e -> com.jubitus.travellers.combat.EngageRange.mayPick(this, e)
                        && com.jubitus.travellers.combat.Hostility.isWorkerEnemy(this, e)
                        && com.jubitus.travellers.combat.SharedThreatScan.heightAllows(this, e)
        ));

        // Worker travel AI (for jobs)
        this.aiTravel = new EntityAITravel(
                this,
                TravellersConfig.ATTRIBUTES.workerStepBlocks
        );
        this.tasks.addTask(11, aiTravel);

        // Wandering (not hired) workers visit villages like travellers; the donation happens on departure.
        if (getStayAI() != null) this.tasks.addTask(10, getStayAI());

        // Follow-leader AI (companion mode) - shared with plain traveller/raider groups, see
        // EntityAIFollowLeader's own class doc.
        this.tasks.addTask(9, new EntityAIFollowLeader(
                this,
                1.0F,
                1.0F
        ));

        // Duty zone work (Stable palfrenier, and any future duty) - no-ops unless actually on duty.
        this.tasks.addTask(13, new com.jubitus.travellers.zone.duty.EntityAIWorkerDuty(this));

        // Lowest priority on purpose - only ever runs when nothing above wants the move+look mutex,
        // i.e. the worker is genuinely idle.
        this.tasks.addTask(14, new com.jubitus.travellers.mount.EntityAIClaimLooseMount(this));
    }

    @Override
    public boolean isOwner(@Nullable EntityLivingBase target) {
        if (target == null) return false;
        EntityPlayer owner = getFollowOwner();
        return owner != null && owner == target;
    }




    public boolean isGoingHome() {
        return trips.isGoingHome();
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
     * move it - the assign messages call {@link WorkerTrips#startDutyTravel()} for a duty zone. */
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

    public boolean isOnDuty() {
        return mode == WorkerMode.ON_DUTY;
    }

    /** Drops the zone assignment. A worker that was working, staying or heading there goes back to
     * following its owner. Returns false if it had none. */
    public boolean releaseFromZone() {
        if (zone == null) return false;
        boolean boundHere = mode == WorkerMode.ON_DUTY || trips.isGoingToDuty() || trips.isGoingHome()
                || zone.center.equals(idleAnchorPos);
        this.zone = null;
        trips.onZoneReleased();
        if (this.mode == WorkerMode.ON_DUTY) this.mode = WorkerMode.IDLE;
        syncZoneDataParams();
        if (boundHere) {
            stopAllTravelNow();
            this.returnUsingFollow = false;
            delivery.awaitingPickup = false;
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
        Optional<UUID> o = this.dataManager.get(ESCORT_LEADER_DM);
        return o.isPresent() ? o.get() : null;
    }

    // A loaded worker in this world, or null.
    @Nullable
    public EntityTravellerWorker resolveEscortLeader(@Nullable UUID id) {
        if (id == null) return null;
        if (!(world instanceof WorldServer)) return null;
        Entity e = ((WorldServer) world).getEntityFromUuid(id);
        return (e instanceof EntityTravellerWorker) ? (EntityTravellerWorker) e : null;
    }

    @Nullable
    public EntityTravellerWorker getEscortLeaderEntity() {
        UUID id = getEscortLeaderUUID();
        if (id == null) return null;
        return resolveEscortLeader(id);
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
        return recruit.isOnMission() || delivery.isOnMission()
                || mode == WorkerMode.GET_EQUIPMENT
                || trips.isUnderWay();
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


    @Nullable
    public UUID getFollowOwnerUUID() {
        Optional<UUID> o = this.dataManager.get(FOLLOW_OWNER_DM);
        return o.isPresent() ? o.get() : null;
    }



    @Override
    protected void onCatchupBegin() {
        if (this.isHiredFollower()) {
            this.setFollowOrderActive(true);
        }
        this.setUsingTravelAI(true);
    }

    @Override
    protected void onCatchupEnd() {
        this.getNavigator().clearPath();
        this.setUsingTravelAI(false);
    }

    @Nullable
    public EntityPlayer getFollowOwner() {
        UUID id = getFollowOwnerUUID();
        return id == null ? null : this.world.getPlayerEntityByUUID(id);
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


    @Nullable
    public boolean isUsingTravelAI() {
        return this.dataManager.get(USING_TRAVEL_AI);
    }

    // Hired workers don't roam: they only get a route for a job, a catch-up or going home.
    @Override
    protected boolean shouldAutoPlanRoute() {
        return !isHiredFollower()
                || isJobActive()
                || isCatchupTravel()
                || isGoingHome();
    }

    @Override
    protected boolean rerollsAppearanceAtSpawn() {
        return true;
    }

    @Override
    protected TravellerAppearance pickAppearance(CultureDef c, int textureIndex) {
        return c.getWorkerAppearance(textureIndex);
    }

    @Override
    protected int groupAttackerCapFloor(int divisor) {
        return getCachedGroupSize() / divisor;
    }

    @Override
    public boolean isOwnerStillFightingAssistTarget(EntityLivingBase target) {
        EntityPlayer owner = getFollowOwner();
        if (owner == null || !owner.isEntityAlive()) return false;
        if (owner.getLastAttackedEntity() != target) return false;
        return (owner.ticksExisted - owner.getLastAttackedEntityTime()) <= TravellersConfig.PLAYER_AGGRO.playerFleeForgiveTicks;
    }

    @Override
    @Nullable
    public EntityPlayer getHiredOwner() {
        return isHiredFollower() ? getFollowOwner() : null;
    }

    @Override
    public boolean isHostileUnit(EntityTraveller other) {
        if (other instanceof EntityTravellerWorker) {
            return playerHostility.isEnemyHiredWorker((EntityTravellerWorker) other) || super.isHostileUnit(other);
        }
        return super.isHostileUnit(other);
    }

    // Hired workers only wear it on a mission (flag is synced; the mission fields are server-only)
    @Override
    public boolean shouldRenderBackpack() {
        if (isHiredFollower()) {
            return isMissionCarrying() && !isBackpackHiddenByCombat();
        }
        if (dataManager.get(MERCHANT_ESCORT_DM)) return false;
        return super.shouldRenderBackpack();
    }

    // Base hook: auto-planner will skip this entity when true
    @Override
    public boolean isRouteLocked() {
        // A merchant's escort has no trip of its own, like any group follower.
        return routeLocked || getGroupLeaderUUID() != null;
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
    private void applyFollowRange() {
        double range = configuredFollowRange();
        getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(usingBoostedNav ? range * 2.0D : range);
    }

    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        // Re-asserting the current target (an owner's repeated hits) must not re-run the checks below and drop it.
        if (target != null && target == getAttackTarget()) {
            super.setAttackTarget(target);
            return;
        }

        // Never attack the owner
        if (isOwner(target)) {
            if (combat.refusalKeepsCurrent(target)) return;
            combat.noteDrop("refused: owner");
            super.setAttackTarget(null);
            return;
        }

        // Blacklist
        if (target != null) {
            if (getAggroBlacklist().matches(target)) {
                if (combat.refusalKeepsCurrent(target)) return;
                combat.noteDrop("refused: blacklisted");
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
                if (combat.refusalKeepsCurrent(target)) return;
                combat.noteDrop("refused: unseen");
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
        this.dataManager.register(MERCHANT_ESCORT_DM, false);
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
    public void onLivingUpdate() {
        if (!world.isRemote && isEntityInsideOpaqueBlock()) {
            escapeFromSolid();
        }
        super.onLivingUpdate();
        if (world.isRemote) return;
        dataManager.set(MERCHANT_ESCORT_DM, !isHiredFollower() && getGroupLeaderUUID() != null);

        // Choir freeze: skip ALL worker navigation/movement logic.
        // Movement and look are handled by EntityAIChoirFreeze (priority 0, mutex 3).
        if (choirFreezeActive) {
            this.motionX = 0;
            this.motionZ = 0;
            getNavigator().clearPath();
            return;
        }

        recruit.tickReturnToOwner();
        if (this.ticksExisted % 20 == 0) {
            refreshCachedAttitude();
            dropTargetNoLongerEnemy();
        }
        tickFollowAndMaintenance();
        playerHostility.tickServer();

        if (isGuardingAnchor() && tickGuardLeash()) return; // keeps aggro: skip the rest of the tick

        clearAggroIfOffscreen();
        this.setUsingTravelAI(shouldBeUsingTravelAI());
        boolean carrying = computeMissionCarry();
        if (carrying != this.dataManager.get(MISSION_CARRY_DM)) this.dataManager.set(MISSION_CARRY_DM, carrying);

        TravelCommon tc = this.getTravelCommon();
        trips.tickArrival(tc);
        recruit.tickTour();
        delivery.tick(tc);
        tickVillageStuckWatchdog(tc);
    }

    // A target player / hired worker that is no longer an enemy is dropped.
    private void dropTargetNoLongerEnemy() {
        EntityLivingBase tgt = this.getAttackTarget();
        boolean stale = (tgt instanceof EntityPlayer && !playerHostility.isEnemyPlayer((EntityPlayer) tgt))
                || (tgt instanceof EntityTravellerWorker
                && !playerHostility.isEnemyHiredWorker((EntityTravellerWorker) tgt));
        if (stale) {
            combat.noteDrop("no longer an enemy");
            this.setAttackTarget(null);
            this.setRevengeTarget(null);
            this.setInCombat(false);
        }
    }

    // Escort watchdog, virtual-leader follow, follower count / equipment sync, navigation, idle behaviour.
    private void tickFollowAndMaintenance() {
        if (--escortLeaderCheckCooldown <= 0) {
            escortLeaderCheckCooldown = 20;
            tickEscortLeaderValidity();
        }
        tickVirtualLeaderFollow();

        // world.getEntities() is not cheap at 500 workers: every 5s.
        if (--leaderCountCooldown <= 0) {
            leaderCountCooldown = 100;
            updateLeaderFollowerCount();
            equipment.syncEquipmentFromInventory();
            resolveLegacyZoneRef();
        }

        updateDynamicNavigation();
        tickFollowerIdleBehaviour();
        tickStayAtIdleAnchor();
    }

    // Guard leash while Follow is OFF. Returns true while an enemy player is targeted (aggro kept).
    private boolean tickGuardLeash() {
        EntityLivingBase tgt = this.getAttackTarget();
        if (tgt instanceof EntityPlayer && playerHostility.isEnemyPlayer((EntityPlayer) tgt)) {
            return true; // declared OR engaged
        }

        // If WE got pulled too far from the anchor, stop fighting and go back.
        if (this.getDistanceSqToCenter(idleAnchorPos) > ANCHOR_HARD_LEASH * ANCHOR_HARD_LEASH) {
            combat.noteDrop("guard leash");
            this.setAttackTarget(null);
            this.setRevengeTarget(null);
            this.setInCombat(false);
            this.getNavigator().clearPath();
            tickStayAtIdleAnchor();
        }

        // Don't even ACCEPT targets that are far from the anchor.
        if (tgt != null && distSqFromAnchor(tgt) > ANCHOR_GUARD_RADIUS * ANCHOR_GUARD_RADIUS) {
            combat.noteDrop("guard leash");
            this.setAttackTarget(null);
            this.setRevengeTarget(null);
            this.setInCombat(false);
            this.getNavigator().clearPath();
        }
        return false;
    }

    // An idle worker parked at its target village for 2 minutes is sent on a new route.
    private void tickVillageStuckWatchdog(TravelCommon tc) {
        if (!(this.mode == WorkerMode.IDLE
                && !this.isJobActive()
                && !this.isHiredFollower()
                && tc.getTargetVillage() != null
                && tc.isAtTargetStop()
                && !isOnStay()
                && this.getNavigator().noPath()
                && !tc.isReadyToDepart())) {
            stuckAtVillageTicks = 0;
            return;
        }
        if (++stuckAtVillageTicks <= 20 * 120) return;

        BlockPos v = tc.getTargetVillage();
        MillLog.major(this, "[TravellersWorker] Watchdog: stuck at village " + v + " – forcing new route.");
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);
        tc.routeIndex = -1;
        this.getNavigator().clearPath();
        stuckAtVillageTicks = 0;
        tc.markVillageBad(v);
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
            moveAttr.setBaseValue(UnitSpeed.ATTRIBUTE);
        }

        // Equipment is initialised here (rather than by the base class) so it reads the worker's
        // own appearance pool/texture index above, not the plain-traveller one rolled by super.onInitialSpawn.
        if (!world.isRemote) {
            equipment.initSpawnEquipment(c, this.rand);
            assignCultureName();
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
        if (world.isRemote) return !isInCombatSynced();
        if (this.isInCombat()) {
            return false;
        }

        if (!this.isInCombat()) {
            // Choose hello vs talk
            if (voice.shouldHelloFor(player)) {
                voice.playHello();         // one hello variation
                voice.markHello(player);        // start the 5-minute window for THIS player
            } else {
                voice.playTalk();          // normal chatter
            }
        }

        // 1) Back from a job with goods: every click opens the pickup screen until the cargo is empty
        if (delivery.offerPickup(player)) return true;

        // Back from recruiting: talking to it opens the report (and the purse).
        if (recruit.isBack() && WorkerLookup.isOwnedBy(this, player.getUniqueID()) && !this.isInCombat()) {
            if (player instanceof EntityPlayerMP) recruit.openReport((EntityPlayerMP) player);
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
                        new com.jubitus.travellers.worker.net.MsgSyncWorkerHireState(this, player),
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
            if (recruit.getTour() != null) recruit.getTour().log().recruiterDied(getPosition());
            // Captured with the tour and the purse, which a successor inherits; both leave this body before the
            // bed-respawn snapshot below, or a worker back at its bed would carry the same tour and money.
            com.jubitus.travellers.follow.group.WorkerGroupService.LeaderOrder leaderOrder =
                    com.jubitus.travellers.follow.group.WorkerGroupService.captureOrder(this);
            int carried = purse.get();
            purse.set(0);
            recruit.abandon();
            setFollowOrderActive(false);

            if (getHomeVillage() != null && world instanceof WorldServer) {
                WorldServer ws = (WorldServer) world;
                TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);

// Clear only if this UUID is the registered one.
// Do NOT respawn here: the village may be unloaded.
// When the village chunks are loaded again, ensureWorkersForVillages() will respawn.
                reg.clearWorkerFor(getHomeVillage(), this.getUniqueID());
            }

            // Bed respawn is decided first: the death notice says whether the worker comes back.
            if (hiredFollower && zone != null && world.getMinecraftServer() != null) {
                NBTTagCompound nbt = new NBTTagCompound();
                writeToNBT(nbt);
                if (com.jubitus.travellers.zone.ZoneAssignments.onWorkerDied(
                        world.getMinecraftServer(), getUniqueID(), followOwnerId, zone, nbt)) {
                    com.jubitus.travellers.zone.RespawnSnapshot.takeKeptGearOff(this);
                }
            }

            // A battle participant's notice comes from VirtualBattleManager.onParticipantDeath (via super.onDeath).
            if (hiredFollower && followOwnerId != null && world instanceof WorldServer
                    && !com.jubitus.travellers.battle.VirtualBattleManager.get((WorldServer) world)
                            .isParticipant(this.getUniqueID())) {
                com.jubitus.travellers.notify.WorkerLifecycleNotifications.died(world.getMinecraftServer(),
                        followOwnerId, getUniqueID(), getName(),
                        com.jubitus.travellers.notify.NoticePlace.of(this),
                        com.jubitus.travellers.notify.VanillaDeathMessage.from(this, cause));
            }

            // After the death notice, so the owner reads "X died" before "Y has taken command".
            boolean inherited = hiredFollower && world.getMinecraftServer() != null
                    && com.jubitus.travellers.follow.group.WorkerGroupService.onLeaderDied(world.getMinecraftServer(),
                            followOwnerId, getUniqueID(), leaderOrder);
            // Nobody took the purse over: the coins fall where it died.
            if (!inherited && carried > 0) {
                purse.set(carried);
                purse.drop(this);
            }
        }

        super.onDeath(cause);
    }

    @Override
    public boolean shouldAttackExtraEntity(Entity e) {
        if (e instanceof EntityTravellerWorker) {
            return playerHostility.isEnemyHiredWorker((EntityTravellerWorker) e);
        }
        if (e instanceof EntityPlayer) {
            return playerHostility.isEnemyPlayer((EntityPlayer) e);
        }
        // Enemy-village Millénaire villagers (declared via the Behavior GUI's village relations)
        // must be reachable through this same hook, since every proactive target-scan task
        // (EntityAIDefendIfClose, EntityAIDefendOwnerProactively) calls shouldAttackExtraEntity()
        // rather than knowing about MillVillager specifically — without this, only the slow,
        // throttled fallback scan ever picked them up, making engagement look scattered/delayed
        // across a formation instead of the whole group reacting together.
        if (e instanceof org.millenaire.common.entity.MillVillager) {
            return playerHostility.isEnemyMillVillager((org.millenaire.common.entity.MillVillager) e);
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
        applyFollowRange();
        FollowRuntimeConfig.get().reloadFromTravellersConfig();
    }

    public void setEscortLeaderUUID(@Nullable UUID id) {
        // Prevent cycles / self-follow
        if (WorkerGroupService.wouldCreateLoadedEscortCycle(this, id)) {
            id = null;
        }

        if (!world.isRemote) {
            this.dataManager.set(ESCORT_LEADER_DM, id == null ? Optional.absent() : Optional.of(id));
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
        catchup.stop();
        trips.clearForBedRespawn();
        recruit.abandon();
        this.returnUsingFollow = false;
        if (this.mode != WorkerMode.ON_DUTY) finishJob();
        this.followOrderActive = false;
        stopAllTravelNow();
        this.idleAnchorPos = spot;
    }

    void finishJob() {
        this.mode = WorkerMode.IDLE;
        delivery.clear();

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

    public boolean usesStays() {
        return TravellersConfig.VILLAGE_VISIT.enabledWorkers && !isHiredFollower() && isIdleForRoaming();
    }

    public boolean isIdleForRoaming() {
        // free to behave like a normal traveller
        return this.mode == WorkerMode.IDLE && !delivery.isWaitingForPickup();
    }


    private void updateDynamicNavigation() {
        if (world.isRemote) return;

        boolean shouldBoost =
                isInPlayerViewDistance()
                        || isUsingTravelAI()      // << key
                        || isCatchupTravel();     // << key

        if (shouldBoost == usingBoostedNav) return;
        usingBoostedNav = shouldBoost;
        applyFollowRange();

        // Keep the travel step within the follow range
        if (aiTravel != null) {
            int fr = (int) Math.floor(getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getAttributeValue());
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
        if (trips.isGoOrderActive()) {
            trips.cancelGoOrder();
            if (!active) {
                this.idleAnchorPos = this.getPosition();
                return;
            }
        }
        if (active) {
            // Player re-engages follow: cancel any "going home" travel too
            if (trips.cancelGoHome()) {
                this.returnUsingFollow = false;
                delivery.awaitingPickup = false;
            }

            // Follow ON
            this.idleAnchorPos = null;
            this.getNavigator().clearPath();
            FollowFormationCoordinator.notifyWorkerRejoined(this.getUniqueID());
            return;
        }

        if (!active) {

            // ✅ NEW: If we were going home, cancel it NOW.
            if (trips.cancelGoHome()) {
                this.returnUsingFollow = false;
                delivery.awaitingPickup = false;

                // anchor here (player told us to stay)
                this.idleAnchorPos = this.getPosition();
                this.getNavigator().clearPath();
                return;
            }

            // Normal anchor behavior
            this.idleAnchorPos = this.getPosition();

            if (!this.isJobActive() && !isCatchupTravel() && !delivery.isWaitingForPickup()) {
                stopAllTravelNow();
                this.returnUsingFollow = false;
                delivery.awaitingPickup = false;
            } else {
                this.getNavigator().clearPath();
            }

        } else {
            // Follow ON
            this.idleAnchorPos = null;
            this.getNavigator().clearPath();
        }
    }

    // Approximate surface position above player's XZ
// Approximate surface position near any follow target (player OR leader worker)
    public BlockPos getApproximateSurfacePosNear(@Nullable EntityLivingBase target) {
        if (target == null) {
            return delivery.getEmployerReturnPos() != null ? delivery.getEmployerReturnPos() : this.getPosition();
        }

        return FollowCatchup.surfaceNear(this.world, target);
    }

    void stopAllTravelNow() {
        TravelCommon tc = this.getTravelCommon();

        tc.clearRoute(false);

        this.getNavigator().clearPath();
    }

    public boolean isJobActive() {
        return this.mode != WorkerMode.IDLE && !delivery.isWaitingForPickup();
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
        hireAsFollower(owner.getUniqueID());
    }

    /** Hired by an owner who may be offline (a recruiter hiring on their behalf). */
    public void hireAsFollower(UUID id) {
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
        delivery.jobVillageCenter = null;
        delivery.jobDelivered = false;
        delivery.awaitingPickup = false;
        this.routeLocked = true;  // companion mode

        this.followOrderActive = true; // <--- key line
        this.idleAnchorPos = null;

        this.getNavigator().clearPath();
    }

    public void dismissFollower() {
        if (!world.isRemote) recruit.onDismissed();
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
        // An unhired worker follows only its merchant (spawn group), like a traveller group follower.
        if (!isHiredFollower()) return super.getEffectiveFollowTarget();
        UUID leaderId = getEscortLeaderUUID();
        if (leaderId != null) {
            EntityTravellerWorker leader = resolveEscortLeader(leaderId);

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
                } else if (world.getMinecraftServer() != null) {
                    // Gone without a death hook: elect a successor (re-links this worker); the new link
                    // is followed from next tick, the owner meanwhile.
                    com.jubitus.travellers.follow.group.WorkerGroupService.onLeaderMissing(
                            world.getMinecraftServer(), getFollowOwnerId(), leaderId, getUniqueID());
                }
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
        if (this.mode == WorkerMode.RETURNING && delivery.jobDelivered) return;
        if (isJobActive()) return;
        if (trips.isGoingHome()) return;
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
                        UnitSpeed.nav(UnitSpeed.STROLL_PERCENT)
                );
            }
        } else {
            this.getNavigator().clearPath();
        }
    }

    public boolean isEscortingLeader(EntityTravellerWorker leader) {
        UUID id = getEscortLeaderUUID();
        return id != null && leader != null && id.equals(leader.getUniqueID());
    }

    public boolean hasEscortLeader() {
        return getEscortLeaderUUID() != null;
    }

    public void clearEscortLeader() {
        setEscortLeaderUUID(null);
    }

    public boolean isReturnUsingFollow() {
        // Recruit return: use follow AI to get back to player (jobDelivered is never set for recruit missions)
        if (recruit.isReturningToOwner()) return true;
        // Sale/transport return: allow follow AI even while awaitingPickup so worker stays close (3-4 blocks)
        return this.mode == WorkerMode.RETURNING
                && delivery.jobDelivered
                && this.returnUsingFollow;
    }

    // Last formation role reported by the follow AI, kept through a fight (the follow task does not run then).
    private boolean escortSlot = false;

    public void setEscortSlot(boolean escort) {
        this.escortSlot = escort;
    }

    /** The leader this worker escorts (an escort slot while following), or null: escorts fight only near it. */
    @Nullable
    public net.minecraft.entity.EntityLivingBase getEscortedLeader() {
        if (!escortSlot || !isHiredFollower() || !isFollowOrderActive()) return null;
        return getEffectiveFollowTarget();
    }

    /** Transport worker coming back to the player: tight escort instead of a formation slot. */
    public boolean isPickupEscortActive() {
        return this.mode == WorkerMode.RETURNING && delivery.jobDelivered && this.returnUsingFollow;
    }

    public static int dist2D(BlockPos a, BlockPos b) {
        double dx = (a.getX() + 0.5D) - (b.getX() + 0.5D);
        double dz = (a.getZ() + 0.5D) - (b.getZ() + 0.5D);
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
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
                || isCatchupTravel()
                || trips.isUnderWay();
    }

    @Nullable
    public BlockPos getTravelGoalPos() {
        // Catchup/return-to-owner uses dynamic goal
        BlockPos cg = catchup.dynamicGoal();
        if (cg != null) return cg;

        // Normal travel uses the village goal
        return getTravelCommon().getTargetVillage();
    }

    @Override
    public boolean isItineraryDriven() {
        if (isCatchupTravel()) return false;
        if (trips.getActivePersonalTravelGoal() != null) return true;
        if (isOnFixedGoalMission()) return true;
        return !hiredFollower && mode == WorkerMode.IDLE && getEscortLeaderUUID() == null;
    }

    /**
     * A mission toward a fixed place (sale/transport, a recruiting stop, a Go order, Get Equipment).
     * The return to the owner is not one: the owner moves. Mirrors VirtualTravellerManager.missionDest.
     */
    public boolean isOnFixedGoalMission() {
        if (!hiredFollower) return false;
        if (mode == WorkerMode.TO_VILLAGE) return delivery.jobVillageCenter != null;
        if (mode == WorkerMode.RECRUITING) return recruit.getCurrentStop() != null;
        return trips.isGoOrderActive() && trips.getGoOrderTargetPos() != null
                && (mode == WorkerMode.IDLE || mode == WorkerMode.GET_EQUIPMENT);
    }

    public WorkerMode getMode() {
        return mode;
    }

    @Nullable
    public BlockPos getJobGoal() {
        if (mode == WorkerMode.TO_VILLAGE) return delivery.jobVillageCenter;
        if (mode == WorkerMode.RECRUITING) return recruit.getCurrentStop();
        return null;
    }

    @Nullable
    public BlockPos getVirtualGoal() {
        // Catchup travel always takes priority
        if (isCatchupTravel() && catchup.getTargetId() != null && world instanceof net.minecraft.world.WorldServer) {
            Entity tgt = ((net.minecraft.world.WorldServer) world).getEntityFromUuid(catchup.getTargetId());
            if (tgt != null && !tgt.isDead) return tgt.getPosition();
        }
        // Personal self-travel (Go Home / Go To Duty): a concrete destination the worker is already
        // walking to. Must survive virtualization with a real goal, not null.
        BlockPos personal = trips.getActivePersonalTravelGoal();
        if (personal != null) return personal;
        // Active mission modes: go to mission destination, never override with owner pos
        if (mode == WorkerMode.TO_VILLAGE && delivery.jobVillageCenter != null) return delivery.jobVillageCenter;
        if (mode == WorkerMode.RETURNING) {
            // Recruit return: track player's CURRENT position (not the fixed original start point)
            if (recruit.isReturningToOwner() && isHiredFollower() && followOwnerId != null) {
                EntityPlayer owner = world.getPlayerEntityByUUID(followOwnerId);
                if (owner != null && !owner.isDead) return owner.getPosition();
            }
            if (delivery.getEmployerReturnPos() != null) return delivery.getEmployerReturnPos();
        }
        if (mode == WorkerMode.RECRUITING && recruit.getCurrentStop() != null) return recruit.getCurrentStop();
        // Go order (IDLE mode, specific target)
        if (trips.isGoOrderActive() && trips.getGoOrderTargetPos() != null) return trips.getGoOrderTargetPos();
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
            UUID merchant = getGroupLeaderUUID();
            if (merchant != null && world instanceof WorldServer) {
                Entity le = ((WorldServer) world).getEntityFromUuid(merchant);
                if (le != null && !le.isDead) return le.getPosition();
                return com.jubitus.travellers.virtual.VirtualTravellerManager
                        .get((WorldServer) world).getVirtualPosition(merchant);
            }
            BlockPos tv = getTravelCommon().getTargetVillage();
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
                && !(this.mode == WorkerMode.RETURNING && delivery.jobDelivered);
    }

    private double distSqFromAnchor(EntityLivingBase e) {
        if (idleAnchorPos == null || e == null) return 0.0D;
        double ax = idleAnchorPos.getX() + 0.5D;
        double az = idleAnchorPos.getZ() + 0.5D;
        double dx = e.posX - ax;
        double dz = e.posZ - az;
        return dx * dx + dz * dz;
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

        setLeaderFollowerCount(WorkerGroupService.countLoadedFollowers(this));
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

        AxisAlignedBB box = owner.getEntityBoundingBox().grow(r, com.jubitus.travellers.combat.SharedThreatScan.SCAN_HEIGHT, r);

        List<EntityLivingBase> list = world.getEntitiesWithinAABB(EntityLivingBase.class, box,
                e -> e != null
                        && e.isEntityAlive()
                        && e instanceof net.minecraft.entity.monster.IMob
                        // NEW: basic LoS gate (choose one or both)
                        && this.canEntityBeSeen(e)
                        && com.jubitus.travellers.combat.SharedThreatScan.heightAllows(this, e)
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



    private void tickEscortLeaderValidity() {
        if (world.isRemote) return;

        // Si pas hired follower, on ne doit jamais avoir de leader
        if (!this.isHiredFollower()) {
            if (this.hasEscortLeader()) this.clearEscortLeader();
            return;
        }

        UUID leaderId = this.getEscortLeaderUUID(); // via PlayerHostility / datamanager
        if (leaderId == null) return;

        EntityTravellerWorker leader = resolveEscortLeader(leaderId);

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
        if (!catchup.virtualRepathDue()) return;

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

        catchup.beginIfFarFromVirtualSlot(leaderId, virtualEscortSlot);
    }

    @Override
    @Nullable
    protected BlockPos getOffscreenLeaderSlot() {
        return isVirtualLeader(catchup.getTargetId()) ? virtualEscortSlot : null;
    }

    private boolean isVirtualLeader(@Nullable UUID id) {
        return id != null && world instanceof net.minecraft.world.WorldServer
                && com.jubitus.travellers.virtual.VirtualTravellerManager
                .get((net.minecraft.world.WorldServer) world).isVirtual(id);
    }

    @Nullable
    public com.jubitus.travellers.unit.equipment.EquipLoadout getEquipRole() {
        return equipRole;
    }

    public void setEquipRole(@Nullable com.jubitus.travellers.unit.equipment.EquipLoadout role) {
        this.equipRole = role;
    }

    // --- NBT save / load ---
    @Override
    public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);
        delivery.writeNbt(nbt);
        recruit.writeNbt(nbt);
        purse.write(nbt);
        nbt.setInteger("WorkerMode", mode.ordinal());
        trips.writeNbt(nbt);
        nbt.setBoolean("RouteLocked", routeLocked);
        nbt.setTag("TravelInventory", travelInventory.serializeNBT());
        nbt.setBoolean("FollowOrderActive", followOrderActive);
        if (idleAnchorPos != null) {
            nbt.setInteger("IdleAx", idleAnchorPos.getX());
            nbt.setInteger("IdleAy", idleAnchorPos.getY());
            nbt.setInteger("IdleAz", idleAnchorPos.getZ());
        }
        UUID leaderId = getEscortLeaderUUID(); // from DataManager
        if (leaderId != null) {
            nbt.setUniqueId("EscortLeaderUUID", leaderId);
        }
        nbt.setBoolean("HiredFollower", dataManager.get(HIRED_FOLLOWER_DM));
        Optional<UUID> o = dataManager.get(FOLLOW_OWNER_DM);
        if (o.isPresent()) nbt.setUniqueId("FollowOwnerId", o.get());
        if (zone != null) zone.write(nbt);
        if (equipRole != null) nbt.setByte(com.jubitus.travellers.unit.equipment.EquipLoadout.ROLE_NBT_KEY, (byte) equipRole.bits);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        forceSpawn = true;
        delivery.readNbt(nbt);
        recruit.readNbt(nbt);
        purse.read(nbt);

        int m = nbt.getInteger("WorkerMode");
        mode = (m >= 0 && m < WorkerMode.values().length) ? WorkerMode.values()[m] : WorkerMode.IDLE;
        routeLocked = nbt.getBoolean("RouteLocked");
        if (nbt.hasKey("TravelInventory")) {
            travelInventory.deserializeNBT(nbt.getCompoundTag("TravelInventory"));
        }

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

        // Hire state; the synced copies are set too, so everything below reads the restored owner.
        hiredFollower = nbt.getBoolean("HiredFollower");
        followOwnerId = nbt.hasUniqueId("FollowOwnerId") ? nbt.getUniqueId("FollowOwnerId") : null;
        dataManager.set(HIRED_FOLLOWER_DM, hiredFollower);
        dataManager.set(FOLLOW_OWNER_DM, Optional.fromNullable(followOwnerId));

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

        // Set the synced DataParameter on the server
        if (!world.isRemote) {
            setEscortLeaderUUID(leaderId); // this updates ESCORT_LEADER_DM
        }

        if (mode != WorkerMode.RETURNING || !delivery.jobDelivered) {
            delivery.awaitingPickup = false;
        }
        zone = ZoneRef.read(nbt);
        if (!world.isRemote) syncZoneDataParams();

        trips.readZoneTripsNbt(nbt);
        recruit.afterReadNbt();
        trips.readGoOrderNbt(nbt);
        String roleKey = com.jubitus.travellers.unit.equipment.EquipLoadout.ROLE_NBT_KEY;
        equipRole = nbt.hasKey(roleKey) ? com.jubitus.travellers.unit.equipment.EquipLoadout.of(nbt.getByte(roleKey)) : null;
        if (!world.isRemote) {
            refreshOwnerVisualsFromSavedData();
        }
    }

    /**
     * Called by EntityAIFollowLeader to tell this worker what its current formation/idle slot is.
     * Server-side only in practice (AI runs on server), but safe if called elsewhere.
     */
    private static boolean isMovingEnough(EntityLivingBase e) {
        if (e == null) return false;

        double dpX = EntityMotion.velX(e);
        double dpZ = EntityMotion.velZ(e);
        double mvSq = dpX * dpX + dpZ * dpZ;

        double moX = e.motionX;
        double moZ = e.motionZ;
        double moSq = moX * moX + moZ * moZ;

        return (Math.max(mvSq, moSq) > MOTION_EPSILON) || e.isSprinting() || e.isSneaking();
    }


}
