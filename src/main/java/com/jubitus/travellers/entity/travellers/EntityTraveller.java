package com.jubitus.travellers.entity.travellers;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;


import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.client.render.backpack.BackpackRegistry;
import com.jubitus.travellers.client.render.backpack.IBackpackStyle;
import com.jubitus.travellers.client.sound.ModSounds;
import com.jubitus.travellers.client.sound.TravellerCrowdSoundLimiter;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.TravellerAppearance;
import com.jubitus.travellers.entity.culture.VoiceSet;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIAutoEat;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIFishWhileTravel;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIRoamInsideVillage;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIStopAndLookAtPlayer;
import com.jubitus.travellers.entity.entityAI.ambient.camp.EntityAICampJoinFire;
import com.jubitus.travellers.entity.entityAI.ambient.camp.EntityAICampPause;
import com.jubitus.travellers.entity.entityAI.combat.AttackTargetLimiter;
import com.jubitus.travellers.entity.entityAI.combat.TravellerCombatLogic;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIAttackMeleeCustom;
import com.jubitus.travellers.entity.entityAI.combat.TravellerItemDamage;
import com.jubitus.travellers.entity.equipment.TravellerEquipmentManager;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIDefendIfClose;
import com.jubitus.travellers.entity.entityAI.combat.EntityAIRangedBowTravellerSafe;
import com.jubitus.travellers.entity.entityAI.follow.EntityAIFollowGroupLeader;
import com.jubitus.travellers.entity.entityAI.follow.PathScheduler;
import com.jubitus.travellers.entity.entityAI.travel.EntityAIOpenFenceGate;
import com.jubitus.travellers.entity.entityAI.travel.EntityAITravel;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerCamp;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerDog;
import com.jubitus.travellers.utils.mobs.TravellerHurtByTarget;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.sounds.MsgChoirSingerMute;
import com.jubitus.travellers.server.net.message.sounds.MsgPlayTravellerVoice;
import com.jubitus.travellers.server.net.message.sounds.MsgStopTravellerVoice;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.pathing.TravellerNavigateGround;
import com.jubitus.travellers.utils.world.spawn.SpawnThrottles;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.pathfinding.*;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.millenaire.common.block.IBlockPath;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EntityTraveller extends EntityCreature {

    public static final String GHOST_TORCH_TAG = TravellerEquipmentManager.GHOST_TORCH_TAG;
    // FISHING ROD
    public static final String GHOST_ROD_TAG = TravellerEquipmentManager.GHOST_ROD_TAG;
    public static final double NEAR_HYS_DELTA_SQ = 4 * 4; // must be 4 blocks closer to switch
    // If something is close AND we currently have no target,
// we allow bypassing the limiter so nearby defenders react.
    public static final double EMERGENCY_ENGAGE_RANGE = 8.0D; // blocks
    static final UUID SWIM_BOOST_UUID =
            UUID.fromString("b3b1a5ee-9c12-4e6f-a7e4-1c4ef2f35d61");
    private static final DataParameter<String> BACKPACK_STYLE =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.STRING);
    private static final DataParameter<Integer> LAST_COMBAT_TICK =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.VARINT);
    private static final int COMBAT_HIDE_TICKS = 20 * 10; // 10s
    // --- Texture related fields ---
    private static final ResourceLocation TRAVELLER_LOOT =
            new ResourceLocation("travellers", "entities/traveller_pack");
    private static final DataParameter<Integer> TEXTURE_INDEX = EntityDataManager.createKey(EntityTraveller.class, DataSerializers.VARINT);
    // Per-entity render scale rolled at spawn from the active appearance's baseHeight, mirroring
    // Millenaire's own VillagerRecord scale roll (baseHeight * random 0.80-0.89 for adults).
    private static final DataParameter<Float> RENDER_SCALE = EntityDataManager.createKey(EntityTraveller.class, DataSerializers.FLOAT);
    // ---- GAP / CLIFF handling ----
    private static final String SWIM_BOOST_NAME = "TravellerSwimBoost";
    private static final int ARRIVE_IN = Math.max(8, TravellersConfig.DESTINATIONS.villageNear - 4); // e.g., 28 if near=32
    private static final int ARRIVE_OUT = TravellersConfig.DESTINATIONS.villageNear + 4;              // e.g., 36
    private static final DataParameter<Boolean> AIMING_BOW =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    // --- Camp sleep pose (synced for render only, never persisted to NBT: virtualization must
    // never rematerialize an entity mid-sleep, see EntityAICampStay) ---
    private static final DataParameter<Boolean> SLEEPING =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    private static final DataParameter<BlockPos> SLEEPING_BED_POS =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BLOCK_POS);
    // Server-only exact sleep spot, re-applied every tick via holdSleepPosition() so nearby-entity
    // collisions/pushes can never slide a sleeping traveller off its bed.
    private double sleepAnchorX, sleepAnchorY, sleepAnchorZ;
    // Configuration-ish knobs
    private static final DataParameter<String> CULTURE_ID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.STRING);

    // NEW: chosen voice personality (saved + synced)
    private static final DataParameter<String> VOICE_PERSONALITY_ID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.STRING);

    // NBT key (must match exactly in write/read)
    private static final String NBT_VOICE_PERSONALITY_ID = "VoicePersonalityId";


    private static final int HELLO_COOLDOWN_TICKS = 5 * 60 * 20; // 5 min
    private static final double EMERGENCY_ENGAGE_RANGE_SQ = EMERGENCY_ENGAGE_RANGE * EMERGENCY_ENGAGE_RANGE;
    public final TravellerCamp travellerCamp = new TravellerCamp(this);
    public final TravellerCombatLogic combat = new TravellerCombatLogic(this);
    public final TravellerEquipmentManager equipment = new TravellerEquipmentManager(this);
    public final ItemStackHandler inventory = new ItemStackHandler(18); // 18 slots example
    final TravelCommon travelCommon = new TravelCommon(this);
    private final int retargetPhase = this.rand.nextInt(10); // 0..9
    // --- Camp join cooldown (prevents placing a fire right after resting) ---
    private final TravellerCamp camp = new TravellerCamp(this);
    // per-player last hello tick (server-authoritative)
    private final Map<UUID, Integer> lastHelloTickByPlayer = new java.util.HashMap<>();
    // ---- GAP / CLIFF handling control ----
    private final boolean wasInWater = false;
    private final int noPathTicks = 0;
    private final float baseWaterPenalty = 6.0F;
    // NEW: cultural aggression toggle (per entity, persisted)
    private final boolean aggressiveToCultures = false;
    private final Map<UUID, Integer> ignoreTargetsUntil = new java.util.HashMap<>();
    public int nextTravelAmbientTick = 0;  // you already had this
    EntityAIAutoEat autoEatTask;
    EntityAIRangedBowTravellerSafe aiRanged;
    EntityAIAttackMeleeCustom aiMelee;
    EntityAIDefendIfClose aiDefendIfClose;
    EntityAITravel aiTravel;
    EntityAIFollowGroupLeader aiFollowGroupLeader;

    // UUID of the group leader this entity follows (null = leader or solo)
    @Nullable private UUID groupLeaderUUID;
    int spawnAmbientCooldownTicks = 0;                       // counts down server-side
    // server-side only
    private int trackingPlayers = 0;
    private long lastPathCheckTick = Long.MIN_VALUE;
    private int lastPathCheckTargetId = -1;
    private boolean lastPathCheckResult = false;
    private boolean wasInCombatPrev = false;
    private int combatSilenceUntilTick = 0;
    private final int debugNextTargetTraceTick = 0;
    private ForgeChunkManager.Ticket travelTicket;
    private int dogScanCooldown = 0;
    // --- Caravan / follow-other-travellers state ---
    @Nullable
    private UUID followLeaderId = null;
    private int followTimeoutTicks = 0;
    private int speakCooldown = 0;
    private int forcedSwingTicks = 0;
    private int hurtAnimCooldown = 0;
    private int travelRecoverTicks = 0;
    // --- Voice channel ---
    private int voiceLockUntilTick = 0;     // no new sounds until this tick (entity-local, server authoritative)
    private VoiceKind voiceKindNow = VoiceKind.NONE;
    private int attackVoiceCooldownUntilTick = 0;
    private int killVoiceCooldownUntilTick = 0;
    private int hurtSoundCooldownUntilTick = 0;
    // One shout per 64-block zone per cooldown window: dim*large_prime ^ zx ^ zz -> world time of last shout
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> ATTACK_ZONE_COOLDOWN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> KILL_ZONE_COOLDOWN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> DEATH_ZONE_COOLDOWN = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean choirSinging = false;   // managed by ChoirCoordinator, server-side only
    private int travelTorchCooldown = 0;
    // --- spawn helper flags ---
    private boolean checkedCompanionSpawn = false;   // only run logic once
    private boolean suppressCompanionSpawn = false;  // set for eggs/commands/etc.
    private int stuckTargetTicks = 0;
    private double lastTargetDistSq = -1.0D;
    @Nullable
    private UUID dogId = null;
    private int currentFollowers = 0;
    // Set/cleared each tick by EntityAICampStay while peacefully occupying a camp (building, wandering,
    // napping, sleeping). Never persisted: purely a same-tick "don't proactively engage" signal for
    // EntityAIDefendIfClose / RaiderAttackTargetAI. Retaliation (TravellerHurtByTarget) ignores this flag.
    private boolean campingPassive = false;
    public EntityTraveller(World worldIn) {
        super(worldIn);
        this.setPathPriority(PathNodeType.BLOCKED, -1.0F);
        this.setPathPriority(PathNodeType.WATER, 3.0F);     // mildly worse than WALKABLE

        // Make dangerous stuff REALLY undesirable
        this.setPathPriority(PathNodeType.DANGER_CACTUS, -1.0F);
        this.setPathPriority(PathNodeType.DAMAGE_CACTUS, -1.0F);
        this.setPathPriority(PathNodeType.DANGER_FIRE, -1.0F);
        this.setPathPriority(PathNodeType.DAMAGE_FIRE, -1.0F);
        this.setPathPriority(PathNodeType.DANGER_OTHER, -1.0F);

        // If you’re on 1.12 and these exist:
        this.setPathPriority(PathNodeType.LAVA, -1.0F);
        this.setPathPriority(PathNodeType.DAMAGE_OTHER, -1.0F);

        this.setSize(0.5F, 1.6F);
        this.enablePersistence(); // make them persist in world
        this.setCanPickUpLoot(true);
        this.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        this.setLeftHanded(false);
        this.stepHeight = 1.2F;

    }

    public boolean isGhostTorch(ItemStack s) {
        return equipment.isGhostTorch(s);
    }

    public static double pointToSegment2DSq(Vec3d P, Vec3d A, Vec3d B) {
        Vec3d AP = P.subtract(A);
        Vec3d AB = B.subtract(A);
        double ab2 = AB.lengthSquared();
        if (ab2 < 1e-9) return AP.lengthSquared();
        double t = AP.dotProduct(AB) / ab2;
        if (t < 0) return AP.lengthSquared();
        if (t > 1) return P.subtract(B).lengthSquared();
        Vec3d H = A.add(AB.scale(t));
        return P.subtract(H).lengthSquared();
    }

    @SubscribeEvent
    public static void onSpecialSpawn(LivingSpawnEvent.SpecialSpawn event) {
        if (event.getEntityLiving() instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) event.getEntityLiving();
            // This travellers came from spawn egg, command, structure, etc.
            t.setSuppressCompanionSpawn(true);
        }
    }

    public void setSuppressCompanionSpawn(boolean suppress) {
        this.suppressCompanionSpawn = suppress;
    }

    public boolean isTrackedByAnyPlayer() {
        if (world == null || world.isRemote) return false;

        if (trackingPlayers > 0) return true;

        // Fallback sûr : si un joueur est dans la zone de tracking probable,
        // on considère l'entité comme potentiellement trackée.
        return isInPlayerViewDistanceApprox();
    }

    public int getTrackingPlayerCount() {
        return trackingPlayers;
    }

    public void incTracking() {
        trackingPlayers++;
    }

    public void decTracking() {
        trackingPlayers = Math.max(0, trackingPlayers - 1);
    }

    private boolean shouldRunRetarget() {
        // every 10 ticks per entity, staggered
        return ((this.ticksExisted + retargetPhase) % 10) == 0;
    }

    public String getBackpackStyleId() {
        if (this.dataManager == null) return "auto";
        String v = this.dataManager.get(BACKPACK_STYLE);
        return (v == null || v.isEmpty()) ? "auto" : v;
    }

    public void setBackpackStyleId(String id) {
        if (!world.isRemote) this.dataManager.set(BACKPACK_STYLE, id);
    }

    public boolean isRouteLocked() {
        return false;
    }

    public void ignoreTarget(EntityLivingBase e, int ticks) {
        if (e == null) return;
        ignoreTargetsUntil.put(e.getUniqueID(), this.ticksExisted + Math.max(1, ticks));
    }

    public boolean isIgnoring(EntityLivingBase e) {
        if (e == null) return false;
        Integer until = ignoreTargetsUntil.get(e.getUniqueID());
        if (until == null) return false;
        if (this.ticksExisted > until) {
            ignoreTargetsUntil.remove(e.getUniqueID());
            return false;
        }
        return true;
    }

    // optional: prune sometimes
    private void pruneIgnoredTargets() {
        if ((this.ticksExisted & 0x3F) != 0) return; // every ~64 ticks
        int now = this.ticksExisted;
        ignoreTargetsUntil.entrySet().removeIf(en -> now > en.getValue());
    }

    private int retargetBoostUntilTick = 0;
    // Reference to the last dead attack target we already armed a retarget boost for — stops
    // onUpdate() from re-arming the boost every tick while the stale dead target is still
    // assigned (cleanup is deliberately deferred for the whole boost window).
    @Nullable private EntityLivingBase lastHandledDeadTarget = null;

    /**
     * Bypasses target-scan throttling (tick-rate gates, vanilla's random check-interval) for a
     * few ticks. Call once when a target is confirmed dead so the next target is acquired
     * immediately instead of waiting on the normal polling cadence.
     */
    public void requestImmediateRetarget() {
        retargetBoostUntilTick = this.ticksExisted + TravellersConfig.COMBAT.retargetBoostWindowTicks;
    }

    public boolean isRetargetBoosted() {
        return this.ticksExisted <= retargetBoostUntilTick;
    }

    /**
     * True while a hired worker's owner is still actively fighting the given target — used to
     * keep an owner-assist chase alive even though the worker itself was never personally
     * attacked by that target (so the direct-aggression "calm"/"threat" signals never trip).
     */
    /**
     * Effective melee-slot cap for this attacker on this target: the dynamic fight-duration cap
     * from AttackTargetLimiter, raised further for hired workers so a big formation can actually
     * bring enough attackers to kill something without everyone getting capped at a handful.
     * Must be used everywhere a cap decision is made for this attacker/target pair (both the
     * initial join in setAttackTarget() and the per-tick keepAlive refresh in onUpdate()) — using
     * a smaller cap in one of the two causes attackers that were let in under the bigger cap to
     * get evicted again on the very next tick's refresh (observed in practice: workers landing a
     * single hit then instantly returning to formation).
     */
    public int computeEffectiveAttackerCap(EntityLivingBase target) {
        int dynamicMax = AttackTargetLimiter.getEffectiveMax(target, world.getTotalWorldTime());
        if (this instanceof EntityTravellerWorker) {
            int groupBonus = ((EntityTravellerWorker) this).getCachedGroupSize()
                    / Math.max(1, TravellersConfig.COMBAT.groupSizeAttackerCapDivisor);
            return Math.max(dynamicMax, groupBonus);
        }
        return dynamicMax;
    }

    public boolean isOwnerStillFightingAssistTarget(EntityLivingBase target) {
        if (!(this instanceof EntityTravellerWorker)) return false;
        EntityPlayer owner = ((EntityTravellerWorker) this).getFollowOwner();
        if (owner == null || !owner.isEntityAlive()) return false;
        if (owner.getLastAttackedEntity() != target) return false;
        return (owner.ticksExisted - owner.getLastAttackedEntityTime()) <= TravellersConfig.PLAYER_AGGRO.playerFleeForgiveTicks;
    }

    @Override
    public float getBlockPathWeight(BlockPos pos) {
        float base = super.getBlockPathWeight(pos);

        // Check the block *under* the feet
        BlockPos belowPos = pos.down();
        IBlockState belowState = this.world.getBlockState(belowPos);
        Block below = belowState.getBlock();

        // Prefer any Millénaire path block (full or slab)
        if (below instanceof IBlockPath) {
            // Big bonus so paths beat plain ground
            base += 4.0F;  // tweak to taste (2–6)
        }

        return base;
    }

    @Override
    public boolean getCanSpawnHere() {
        if (world.isRemote) return false;
        // Global-per-dimension loaded cap
        int cap = TravellersConfig.SPAWN_TRAVELLERS.loadedTravellerCap;
        int loaded = this.world.getEntities(EntityTraveller.class, Entity::isEntityAlive).size();
        if (loaded >= cap) return false;

        // MUCH smaller “near a village”
        BlockPos pos = new BlockPos(this.posX, this.posY, this.posZ);
        if (!isNearAnyVillage(this.world, pos, TravellersConfig.SPAWN.villageSpawnRadius)) return false;

        // Light & ground sanity (creature-like)
        if (!world.getBlockState(pos.down()).isOpaqueCube()) return false;

        // Local density throttle (see next section)
        if (!SpawnThrottles.underLocalCap(this.world, pos, EntityTraveller.class,
                TravellersConfig.SPAWN_TRAVELLERS.travellerLocalCap, TravellersConfig.SPAWN_TRAVELLERS.travellerLocalCapRadius))
            return false;

        return super.getCanSpawnHere();
    }

    @Override
    public boolean isWithinHomeDistanceCurrentPosition() {
        return true;
    }

    @Override
    public void setHomePosAndDistance(BlockPos pos, int distance) {
    }

    // --- Home Override? Dunno if useful since I don't extend villager ---
    @Override
    public boolean hasHome() {
        return false;
    }

    public static boolean isNearAnyVillage(World world, BlockPos pos, int radius) {
        long r2 = (long) radius * (long) radius;
        for (BlockPos v : MillVillageIndex.getAllVillageCenters(world)) {
            if (v.distanceSq(pos) <= r2) return true;
        }
        return false;
    }


    /**
     * Mark a campcommon to avoid for N ticks.
     */
    public void avoidCamp(BlockPos pos, int ticks) {
        travellerCamp.avoidCamp(pos, ticks);
    }


// === Shared campcommon helpers ===

    /**
     * True if this campcommon is currently blacklisted for this travellers.
     */
    public boolean isAvoidingCamp(BlockPos pos) {
        return travellerCamp.isAvoidingCamp(pos);
    }

    public boolean isCampJoinOnCooldown() {
        return travellerCamp.isCampJoinOnCooldown();
    }

    public boolean hasCampfire() {
        return travellerCamp.hasCampfire();
    }

    public void markCampLeader(BlockPos pos, int durationTicks) {
        travellerCamp.markCampLeader(pos, durationTicks);
    }

    // joiner
    public void joinCampAt(BlockPos pos, int durationTicks) {
        travellerCamp.joinCampAt(pos, durationTicks);
    }

    public void clearCamp() {
        travellerCamp.clearCamp();
    }

    public void setCampJoinCooldown() {
        travellerCamp.setCampJoinCooldown();
    }

    public void setCampCooldown() {
        travellerCamp.setCampCooldown();
    }

    public boolean campOnCooldown() {
        return travellerCamp.campOnCooldown();
    }

    public boolean isGhostRod(ItemStack s) {
        return !s.isEmpty()
                && isFishingRodLike(s)
                && s.hasTagCompound()
                && s.getTagCompound().getBoolean(GHOST_ROD_TAG);
    }

    public boolean isFishingRodLike(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemFishingRod;
    }

    public ItemStack makeGhostRod(ItemStack src) {
        ItemStack copy = src.copy();
        copy.setCount(1);
        NBTTagCompound tag = copy.getTagCompound();
        if (tag == null) tag = new NBTTagCompound();
        tag.setBoolean(GHOST_ROD_TAG, true);
        if (tag.hasKey("AttributeModifiers")) tag.removeTag("AttributeModifiers");
        copy.setTagCompound(tag);
        return copy;
    }

    public TravellerCamp getCamp() {
        return camp;
    }

    @Nullable
    public CultureDef getCulture() {
        return CultureRegistry.get(getCultureId());
    }

    /**
     * Which appearance pool (traveller/raider/worker) this entity draws its texture index from.
     * Centralized here so rendering and scale both resolve the same TravellerAppearance -
     * subclasses used to be picked inconsistently between call sites (e.g. workers rendering
     * from the traveller pool despite rolling their index against the worker pool).
     */
    public TravellerAppearance getActiveAppearance() {
        CultureDef c = getCulture();
        if (c == null) {
            return new TravellerAppearance(new ResourceLocation("minecraft", "textures/entity/steve.png"));
        }
        if (this instanceof EntityTravellerRaider) {
            return c.getRaiderAppearance(getTextureIndex());
        }
        if (this instanceof EntityTravellerWorker) {
            return c.getWorkerAppearance(getTextureIndex());
        }
        return c.getAppearance(getTextureIndex());
    }

    public String getCultureId() {
        return this.dataManager.get(CULTURE_ID);
    }

    // Smoothly turn to look at a block (used for fire & social glances)
    public void faceTowards(BlockPos pos, float bodyStep, float headStep) {
        double dx = pos.getX() + 0.5 - this.posX;
        double dz = pos.getZ() + 0.5 - this.posZ;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        this.rotationYaw = rotlerp(this.rotationYaw, yaw, bodyStep);
        this.renderYawOffset = rotlerp(this.renderYawOffset, this.rotationYaw, bodyStep);
        this.rotationYawHead = rotlerp(this.rotationYawHead, this.renderYawOffset, headStep);
    }

    // Helper: clamp angular delta and step toward a target
    public static float rotlerp(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    // Convenience for the current campfire
    public void lookAtFire() {
        if (travellerCamp.isSocialLooking()) return; // don't override a chat glance
        BlockPos fp = travellerCamp.getCampfirePos();
        if (fp == null) return;
        double dx = fp.getX() + 0.5 - this.posX;
        double dz = fp.getZ() + 0.5 - this.posZ;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        this.rotationYaw = rotlerp(this.rotationYaw, yaw, 10.0F);
        this.renderYawOffset = rotlerp(this.renderYawOffset, this.rotationYaw, 10.0F);
        this.rotationYawHead = rotlerp(this.rotationYawHead, this.renderYawOffset, 12.0F);
    }

    // Choose a stand slot ~1.4 blocks in front of the fire (shared by both AIs)
    @Nullable
    public BlockPos pickFrontOfFire(BlockPos fire) {
        Vec3d dir = new Vec3d(this.posX - (fire.getX() + 0.5), 0, this.posZ - (fire.getZ() + 0.5));
        if (dir.lengthSquared() < 1.0E-4) dir = new Vec3d(1, 0, 0);
        dir = dir.normalize();

        int[] angles = new int[]{0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};
        for (int aDeg : angles) {
            double a = Math.toRadians(aDeg);
            double cx = Math.cos(a) * dir.x - Math.sin(a) * dir.z;
            double cz = Math.sin(a) * dir.x + Math.cos(a) * dir.z;
            int x = fire.getX() + (int) Math.round(cx * 1.4);
            int z = fire.getZ() + (int) Math.round(cz * 1.4);
            BlockPos top = travelCommon.topStandable(new BlockPos(x, fire.getY(), z));
            if (top != null && top.distanceSq(fire) <= (TravellersConfig.CAMP.campRadius + 2) * (TravellersConfig.CAMP.campRadius + 2))
                return top;
        }
        return null;
    }

    // Find a dry standable Y near XZ (no chunk loads)
    @Nullable
    public BlockPos topStandable(BlockPos guess) {

        return travelCommon.topStandable(guess);
    }

    // “Dry standable” check (single authoritative version)
    public boolean isDryStandable(BlockPos feet) {

        // never stand on Millénaire fire pit itself

        // forbid non-water liquids intersecting our AABB

        return travelCommon.isDryStandable(feet);
    }

    public boolean isAtTargetStopHys(boolean lastWasInside) {
        BlockPos tv = travelCommon.getTargetVillage();
        if (tv == null) return false;
        double d2 = this.getDistanceSqToCenter(tv);
        int r = travelCommon.arrivalRadiusBlocks(tv);
        int inR = Math.min(r, ARRIVE_IN);
        int outR = Math.max(r, ARRIVE_OUT);
        return lastWasInside ? d2 <= (double) outR * outR
                : d2 <= (double) inR * inR;
    }

    // --- Getters & Setters for AI Fields ---
    public BlockPos getTargetVillage() {
        return travelCommon.getTargetVillage();
    }

    public void setTargetVillage(@Nullable BlockPos pos) {
        travelCommon.setTargetVillage(pos);
    }

    // --- Arrival policy (ONE source of truth) ---
    public int arrivalRadiusBlocks(BlockPos target) {
        // Prefer Millénaire’s custom near radius if present, else your config
        return travelCommon.arrivalRadiusBlocks(target);
    }

    // Allow AI to check/do these without touching your privates:
    public boolean hasBowInBackpackPublic() {
        return equipment.hasBowInBackpack();
    }

    public ItemStack findBowInBackpackPublic() {
        ItemStack bow = equipment.findBowInBackpack();
        return bow != null ? bow : ItemStack.EMPTY;
    }

    public void delaySwordHideVisual() {
        equipment.delaySwordHideVisual();
    }

    public int getTravelRecoverTicks() {
        return this.travelRecoverTicks;
    }

    public void setTravelRecoverTicks(int t) {
        this.travelRecoverTicks = t;
    }

    public boolean isReadyToDepart() {
        return travelCommon.isReadyToDepart();
    }

    public void setReadyToDepart(boolean v) {
        travelCommon.setReadyToDepart(v);
    }

    public int getDepartCooldownTicks() {
        return travelCommon.getDepartCooldownTicks();
    }

    public void setDepartCooldownTicks(int t) {
        travelCommon.setDepartCooldownTicks(t);
    }

    public int getSpawnAmbientCooldownTicks() {
        return spawnAmbientCooldownTicks;
    }

    public void setNextTravelAmbientTick(int tick) {
        this.nextTravelAmbientTick = tick;
    }

    public int getVoiceLockUntilTick() {
        return voiceLockUntilTick;
    }

    // --- AI Init ---
    @Override
    protected void initEntityAI() {
        if (this.getNavigator() instanceof PathNavigateGround) {
            PathNavigateGround nav = (PathNavigateGround) this.getNavigator();
            nav.setCanSwim(true);           // on navigator
            nav.setBreakDoors(true);
            nav.setEnterDoors(true);

            // Java 8 style: separate cast
            if (nav.getNodeProcessor() instanceof WalkNodeProcessor) {
                WalkNodeProcessor processor = (WalkNodeProcessor) nav.getNodeProcessor();
                processor.setCanEnterDoors(true);
                processor.setCanSwim(true); // example
            }
            try {
                this.setPathPriority(PathNodeType.valueOf("ICE"), 0.0F);
            } catch (IllegalArgumentException ignored) {

            }
        }

        // Highest priority
        this.tasks.addTask(0, new EntityAISwimming(this));

        // Combat
        aiRanged = new EntityAIRangedBowTravellerSafe(
                this,
                TravellersConfig.COMBAT.rangedMinDist,
                TravellersConfig.COMBAT.rangedMaxDist,
                TravellersConfig.COMBAT.rangedCooldown,
                /* meleeHandOff */ Math.max(2.5D, TravellersConfig.COMBAT.rangedMinDist - 1.0D),
                /* ghostEquipBow */ true
        );

        this.tasks.addTask(3, aiRanged);


        aiMelee = new EntityAIAttackMeleeCustom(this, com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileAttacking, true, 0.30);
        this.tasks.addTask(4, aiMelee);

        aiFollowGroupLeader = new EntityAIFollowGroupLeader(this, com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileTravel);
        this.tasks.addTask(5, aiFollowGroupLeader);

        aiDefendIfClose = new EntityAIDefendIfClose(this, TravellersConfig.COMBAT.attackTriggerRange, true);

        this.targetTasks.addTask(1, aiDefendIfClose);

        this.targetTasks.addTask(2, new TravellerHurtByTarget(this, true, TravellersConfig.COMBAT.helpRange));
        autoEatTask = new EntityAIAutoEat(this, TravellersConfig.COMBAT.eatHealthThreshold);
        this.tasks.addTask(8, autoEatTask);

        if (com.jubitus.travellers.config.TravellersConfig.CAMP_EVENTS.enabled) {
            // New camp event system: keep occupants at their slot (build is handled by the manager).
            this.tasks.addTask(6, new com.jubitus.travellers.entity.entityAI.ambient.camp.EntityAICampStay(this));
        } else if (TravellersConfig.CAMP.campsEnabled) {
            this.tasks.addTask(6, new EntityAICampPause(this));                       // leader
            this.tasks.addTask(7, new EntityAICampJoinFire(this, 0.34D)); // joiner
        }

        this.tasks.addTask(9, new EntityAIStopAndLookAtPlayer(this, 3.2D, 30.0F, 30.0F));
        if (TravellersConfig.TRAVEL.fishWhileTravelEnabled) {
            this.tasks.addTask(22, new EntityAIFishWhileTravel(this));
        }

        // Utility / travel
        this.tasks.addTask(23, new EntityAIOpenFenceGate(this));

        this.tasks.addTask(24, new EntityAIOpenDoor(this, true));

        this.tasks.addTask(25, new EntityAIRoamInsideVillage(this, 0.34D, 3 * 20, 10 * 20, TravellersConfig.DESTINATIONS.villageRoamMinDuration * 20, TravellersConfig.DESTINATIONS.villageRoamMaxDuration * 20));// Optional: customize 2-minute duration or wordlist at runtime

        aiTravel = new EntityAITravel(this, com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileTravel, TravellersConfig.ATTRIBUTES.stepBlocks);
        this.tasks.addTask(27, aiTravel);

        this.tasks.addTask(28, new EntityAILookIdle(this));
    }

    // Entity Attributes
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();

        // Generic travellers stats
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH)
                .setBaseValue(TravellersConfig.ATTRIBUTES.travellerMaxHealth);

        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .setBaseValue(com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeed);

        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE)
                .setBaseValue(TravellersConfig.ATTRIBUTES.travellerFollowRange);

        // ATTACK_DAMAGE is not present by default for many entities
        if (this.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.ATTACK_DAMAGE) == null) {
            this.getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        }
        this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE)
                .setBaseValue(TravellersConfig.ATTRIBUTES.attackDamage);
    }

    @Override
    protected PathNavigate createNavigator(World worldIn) {
        return new TravellerNavigateGround(this, worldIn);
    }

    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        EntityLivingBase prev = this.getAttackTarget();
        if (!world.isRemote && prev != null && prev != target) {
            AttackTargetLimiter.leave(this, prev);
        }
        if (!world.isRemote && target != null) {
            double r = TravellersConfig.COMBAT.attackTriggerRange;
            double d2 = this.getDistanceSq(target);

            if (d2 > r * r) {
                // hard reject
                super.setAttackTarget(null);
                setInCombat(false);
                setRevengeTarget(null);
                getNavigator().clearPath();
                setSprinting(false);
                if (isAimingBow()) {
                    setAimingBow(false);
                    resetActiveHand();
                }
                return;
            }
        }
        if (world != null && world.getDifficulty() == EnumDifficulty.PEACEFUL) {
            super.setAttackTarget(null);
            setInCombat(false);
            return;
        }
        if (target != null) {
            double r = TravellersConfig.COMBAT.attackTriggerRange;
            if (this.getDistanceSq(target) > r * r) {
                // hard reject: never keep far targets
                super.setAttackTarget(null);
                if (!world.isRemote) {
                    setInCombat(false);
                    setRevengeTarget(null);
                    getNavigator().clearPath();
                    setSprinting(false);

                    // also cancel ranged pose if it was left on
                    if (isAimingBow()) {
                        setAimingBow(false);
                        resetActiveHand();
                    }
                }
                return;
            }
        }

        if (target != null) {
            TravellerBlacklist blacklist = getAggroBlacklist();
            if (blacklist.isBlacklisted(target)) {
                ignoreTarget(target, 20 * 60);
                rejectTarget();
                return;
            }
        }

        if (TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen
                && !this.isInPlayerViewDistance()
                && !(target instanceof EntityPlayer)) {
            rejectTarget();

            return;
        }

        if (!this.world.isRemote
                && TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen
                && !this.isInPlayerViewDistance()) {
            // Completely ignore combat targets while off-screen
            rejectTarget();
            return;
        }
        if (!this.isAggressiveToTravellers() && target instanceof net.minecraft.entity.IEntityOwnable) {
            net.minecraft.entity.IEntityOwnable ownable = (net.minecraft.entity.IEntityOwnable) target;
            if (ownable.getOwner() instanceof EntityPlayer) {
                rejectTarget();
                return;
            }
        }
        if (target instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) target;

            // ✅ Exception: hired worker vs hired worker can fight if owners are hostile
            if (this instanceof EntityTravellerWorker && other instanceof EntityTravellerWorker) {
                EntityTravellerWorker me = (EntityTravellerWorker) this;
                EntityTravellerWorker them = (EntityTravellerWorker) other;

                if (me.shouldAttackExtraEntity(them)) {
                    // allow, do NOT reject
                } else {
                    // fall through to normal faction rules
                    if (!combat.isCrossFaction(other)) {
                        rejectTarget();
                        return;
                    }
                }
            } else {
                // Normal travellers: block same-faction fights
                if (!combat.isCrossFaction(other)) {
                    rejectTarget();
                    return;
                }
            }
        }

// --- NEW: hard cap attackers per target ---
// --- hard cap attackers per target (with your 8-block bypass rule) ---
        if (!world.isRemote && target != null) {
            boolean isCombatTarget = !(target instanceof EntityPlayer &&
                    (((EntityPlayer) target).isCreative() || ((EntityPlayer) target).isSpectator()));

            if (isCombatTarget) {
                // If target is close (<=8 blocks) AND we have no current target,
                // ignore the limiter and allow engagement.
                boolean bypass = shouldBypassAttackTargetLimiter(target);

                int prio = AttackTargetLimiter.priorityFor(this);
                if (!bypass) {
                    int effectiveMax = computeEffectiveAttackerCap(target);

                    if (!AttackTargetLimiter.tryJoin(this, target, prio, effectiveMax)) {
                        ignoreTarget(target, 30);
                        startWatchingFight(target, 60);
                        if (this.getRevengeTarget() == target) this.setRevengeTarget(null);
                        setInCombat(false);
                        getNavigator().clearPath();
                        return;
                    }
                } else {
                    // Bypass (revenge / emergency): register an unlimited slot so keepAlive
                    // won't evict this entity on the next tick.
                    // Archers skip keepAlive entirely, so no slot needed for them.
                    boolean isArcher = this.hasBowAnywherePublic() || this.isAimingBow();
                    if (!isArcher) {
                        AttackTargetLimiter.tryJoin(this, target, prio, Integer.MAX_VALUE);
                    }
                }
            }
        }


        super.setAttackTarget(target);

        if (!world.isRemote) {
            if (target != null) setInCombat(true);
            if (target instanceof EntityPlayer) {
                combat.chasingPlayerId = target.getUniqueID();
                combat.lastLOSorCloseTick = this.ticksExisted; // we just “saw” them
            } else if (target == null) {
                combat.resetChaseMemory();
            }

            // Play attack shout when freshly engaging a target
            if (target != null && prev == null) {
                tryPlayAttackSound();
            }

            // Loaded-vs-loaded fight tracking: the offscreen scanner never sees entities that were
            // already loaded when the fight started, so notification/journal coverage needs this hook too.
            if (target instanceof EntityTraveller && prev != target) {
                com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get((net.minecraft.world.WorldServer) world)
                        .onLoadedEngagement((net.minecraft.world.WorldServer) world, this, (EntityTraveller) target);
            }
        }
    }

    // Traveller textures
    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(LAST_COMBAT_TICK, 0);
        this.dataManager.register(BACKPACK_STYLE, "auto");
        this.dataManager.register(CULTURE_ID, "en");
        this.dataManager.register(VOICE_PERSONALITY_ID, ""); // NEW: empty = “no personality chosen”

        this.dataManager.register(TEXTURE_INDEX, 0);
        this.dataManager.register(RENDER_SCALE, 1.0F);
        this.dataManager.register(AIMING_BOW, Boolean.FALSE); // NEW
        this.dataManager.register(SLEEPING, Boolean.FALSE);
        this.dataManager.register(SLEEPING_BED_POS, BlockPos.ORIGIN);
    }
    public static void dbg(String s) {
        System.out.println("[TravellersDBG] " + s);
    }

    @Override
    protected int getExperiencePoints(EntityPlayer player) {
        int base = 4; // baseline
        // small bonuses for gear
        int gear =
                (!getItemStackFromSlot(EntityEquipmentSlot.CHEST).isEmpty() ? 2 : 0) +
                        (!getItemStackFromSlot(EntityEquipmentSlot.LEGS).isEmpty() ? 1 : 0) +
                        (!getItemStackFromSlot(EntityEquipmentSlot.HEAD).isEmpty() ? 1 : 0);
        // bonus for max health above 20
        int hpBonus = Math.max(0, (int) Math.floor((getMaxHealth() - 20f) / 10f));
        return base + gear + hpBonus; // tweak to taste
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void handleStatusUpdate(byte id) {
        if (id == 2) { // hurt animation packet
            if (hurtAnimCooldown > 0) {
                return; // swallow repeated hurt flinches
            }
            hurtAnimCooldown = 8; // ~0.4s; tune (6–12 works well)
        }

        if (id == 4) {
            this.forcedSwingTicks = 6;
            this.swingArm(EnumHand.MAIN_HAND);
            return;
        }
        if (id == 9) return;

        super.handleStatusUpdate(id);
    }

    @Override
    public void onUpdate() {
        dropBoatIfOffscreen();
        super.onUpdate();
        if (!world.isRemote && voiceKindNow != VoiceKind.NONE && this.ticksExisted >= voiceLockUntilTick) {
            voiceKindNow = VoiceKind.NONE;
        }
        if (!world.isRemote) {
            EntityLivingBase at = this.getAttackTarget();
            if (at != null && at.isEntityAlive()) {
                // Archers bypass the limiter — no slot to maintain
                if (!shouldBypassAttackTargetLimiter(at)) {
                    int prio = AttackTargetLimiter.priorityFor(this);
                    // Must use the same effective cap as the initial join in setAttackTarget() —
                    // AttackTargetLimiter.keepAlive() alone only applies the plain default cap,
                    // which is smaller than the group-scaled cap workers actually joined under.
                    boolean ok = AttackTargetLimiter.tryJoin(this, at, prio, computeEffectiveAttackerCap(at));

                    if (!ok) {
                        // Evicted from the slot — back off briefly so we don't frantic-retry every tick
                        ignoreTarget(at, 30);
                        this.setAttackTarget(null);
                        this.setInCombat(false);
                        this.getNavigator().clearPath();
                        this.setSprinting(false);
                    }
                }
            }


            boolean inCombatNow = isInCombat();
            if (wasInCombatPrev && !inCombatNow) {
                // combat ended this tick -> clear once
                this.getNavigator().clearPath();
                this.setSprinting(false);

                double base = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
                this.setAIMoveSpeed((float) base);
                combatSilenceUntilTick = this.ticksExisted + 100; // 5 s post-combat voice silence
            }
            wasInCombatPrev = inCombatNow;
        }

        if (!world.isRemote) {
            EntityLivingBase tgt = this.getAttackTarget();
            if (tgt != null && tgt.isEntityAlive()) {
                double maxEngage = TravellersConfig.COMBAT.attackTriggerRange;

                boolean hasBow =
                        this.hasBowInBackpackPublic()
                                || (!this.getHeldItemMainhand().isEmpty() && this.getHeldItemMainhand().getItem() instanceof ItemBow);

// If ranged, allow ranged max
                if (hasBow) {
                    maxEngage = Math.max(maxEngage, TravellersConfig.COMBAT.rangedMaxDist * 1.25);
                }

// If you use helpRange for assistance chaining, also allow that
                maxEngage = Math.max(maxEngage, TravellersConfig.COMBAT.helpRange);
                double d2 = this.getDistanceSq(tgt);
                if (d2 > maxEngage * maxEngage) {
                    // Too far: disengage so AI can resume travelling
                    this.setAttackTarget(null);
                    this.setInCombat(false);
                    // also stop aiming if we were in ranged mode
                    if (isAimingBow()) {
                        setAimingBow(false);
                        resetActiveHand();
                    }
                }
            }
        }

        if (!world.isRemote) {
            EntityLivingBase stuckTgt = this.getAttackTarget();
            if (stuckTgt != null && stuckTgt.isEntityAlive()) {
                double d2tgt = this.getDistanceSq(stuckTgt);
                // Don't count as stuck when already in melee range: distance barely changes while trading blows
                final double MELEE_RANGE_SQ = 3.5 * 3.5;
                if (d2tgt > MELEE_RANGE_SQ && lastTargetDistSq >= 0 && d2tgt >= lastTargetDistSq - 2.0D) {
                    stuckTargetTicks++;
                } else {
                    stuckTargetTicks = 0;
                }
                lastTargetDistSq = d2tgt;
                if (stuckTargetTicks > 80) {
                    double dyTgt = Math.abs(stuckTgt.posY - this.posY);
                    boolean abandon = false;
                    if (dyTgt > 4.0D) {
                        abandon = true;
                    } else if (((this.ticksExisted + this.getEntityId()) % 20) == 0) {
                        abandon = !this.getEntitySenses().canSee(stuckTgt);
                    }
                    if (abandon) {
                        // Shorter ignore for revenge targets (they attacked us) — re-engage sooner
                        boolean isRevenge = (this.getRevengeTarget() == stuckTgt);
                        ignoreTarget(stuckTgt, isRevenge ? 40 : 200);
                        this.setAttackTarget(null);
                        this.setInCombat(false);
                        this.getNavigator().clearPath();
                        stuckTargetTicks = 0;
                        lastTargetDistSq = -1.0D;
                    }
                }
            } else {
                stuckTargetTicks = 0;
                lastTargetDistSq = -1.0D;
            }
        }

        if (!world.isRemote) {
            // Re-engage revenge target promptly when attack target was cleared (distance/stuck)
            // This avoids the 0-8 tick scan delay from EntityAIDefendIfClose
            if (this.getAttackTarget() == null) {
                EntityLivingBase rev = this.getRevengeTarget();
                if (rev != null && rev.isEntityAlive() && !this.isIgnoring(rev)) {
                    double r = TravellersConfig.COMBAT.attackTriggerRange;
                    if (this.getDistanceSq(rev) <= r * r) {
                        this.trySetAttackTargetLimited(rev);
                    }
                }
            }
        }

        if (!world.isRemote) {
            if (travelCommon.getDepartCooldownTicks() > 0)
                travelCommon.setDepartCooldownTicks(travelCommon.getDepartCooldownTicks() - 1);

            int rec = travelCommon.getTravelRecoverTicks();
            if (rec > 0) {
                travelCommon.setTravelRecoverTicks(rec - 1);
            }
            tickFollowTimer();

            EntityLivingBase tgtNow = this.getAttackTarget();
            if (tgtNow != null && !tgtNow.isEntityAlive()) {
                // Target just died — bypass target-scan throttling so the next enemy is
                // picked up within a tick or two instead of waiting on the normal poll cadence.
                // Only arm the boost once per death (reference identity): tgtNow stays assigned
                // to this same dead entity for the whole boost window below (cleanup is deferred
                // on purpose while boosted), so re-checking "did it just die" every tick would
                // otherwise re-arm the boost forever and never let the window actually expire.
                if (lastHandledDeadTarget != tgtNow) {
                    lastHandledDeadTarget = tgtNow;
                    requestImmediateRetarget();
                }
            }
            if (tgtNow == null || !tgtNow.isEntityAlive()) {
                EntityLivingBase rev = this.getRevengeTarget();
                boolean revAlive = rev != null && rev.isEntityAlive();

                // While the post-kill retarget boost is active, hold off on the full cleanup below
                // so target-scan tasks (EntityAIDefendIfClose etc.) get a real chance to pick up a
                // nearby threat before we give up and hand control back to travel/formation AI.
                if (!revAlive && !isRetargetBoosted()) {
                    // Target truly gone (dead/despawned) and no active revenge — full cleanup
                    if (isInCombat()) {
                        setInCombat(false);
                        this.setAttackTarget(null);
                        this.setRevengeTarget(null);
                        this.getNavigator().clearPath();
                        travelCommon.setTravelRecoverTicks(60);
                    } else if (tgtNow != null && !tgtNow.isEntityAlive()) {
                        this.setAttackTarget(null);
                        this.setRevengeTarget(null);
                        this.getNavigator().clearPath();
                    }
                }
                // else: attack target lost but revenge target is still alive (e.g. player moved out of range)
                // Don't clear revenge target — vanilla revengeTimer decays it naturally (~5s)
                // and the re-engage check above will re-lock when they return to range
            }

            if (TravellersConfig.SOUND.travellerAmbient && this.ticksExisted >= nextTravelAmbientTick) {
                travelCommon.maybePlayTravelAmbient();
            }
            equipment.handleWeaponVisibility();
            combat.decayAndPruneThreats();
            pruneIgnoredTargets();
        }
        if (!isInCombat()) {
            if (isAimingBow()) {
                // Only cancel if the thing we're using is a bow, so eating stays intact
                ItemStack hand = this.getHeldItemMainhand();
                if (!hand.isEmpty() && hand.getItem() instanceof ItemBow) {
                    resetActiveHand();
                }
                setAimingBow(false);
            }
        }
        EntityLivingBase tgt = this.getAttackTarget();
        if (tgt instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) tgt;

            // 1) Track “still engaging” signals: LOS or being near keeps chase fresh
            boolean see = this.getEntitySenses().canSee(p);
            double keepDist = TravellersConfig.PLAYER_AGGRO.playerFleeKeepDistance;
            boolean close = this.getDistanceSq(p) <= (keepDist * keepDist);
            if (see || close) {
                combat.lastLOSorCloseTick = this.ticksExisted; // still engaged; don’t forgive
            }

            // 2) Calm player? (your earlier memory updated by the hurt event)
            boolean playerCalm =
                    lastPlayerAggroAgeTicks(p) > TravellersConfig.PLAYER_AGGRO.playerCalmForgiveTicks;

            // Optional: don’t instantly forgive right after being damaged by them
            TravellerCombatLogic.Threat th = combat.getThreat(p.getUniqueID());
            boolean wasRecentlyThreat = (th != null) && (this.ticksExisted - th.lastSeenTick <= 40);

            // 3) Fleeing window: must be away/out of LOS for long enough
            boolean fleeingLongEnough =
                    (this.ticksExisted - combat.lastLOSorCloseTick) > TravellersConfig.PLAYER_AGGRO.playerFleeForgiveTicks;

            // 4) Assist chases (worker joining its owner against a player it was never
            // personally attacked by) must not be "forgiven" just because THIS worker was
            // never hit — playerCalm/wasRecentlyThreat only track direct aggression against
            // this entity. As long as the owner is still actively fighting the same player,
            // the assist stays alive regardless of those two signals.
            boolean assistStillDriven = isOwnerStillFightingAssistTarget(p);

            if (playerCalm && fleeingLongEnough && !wasRecentlyThreat && !assistStillDriven) {
                this.setAttackTarget(null);
                this.setInCombat(false);
                this.getNavigator().clearPath();
                if (isAimingBow()) {
                    setAimingBow(false);
                    resetActiveHand();
                }
                combat.resetChaseMemory();
                this.setRevengeTarget(null);
            }
        } else if (tgt == null) {
            combat.resetChaseMemory();
        }
        if (!world.isRemote) {
            if (isAimingBow()) {
                this.setSprinting(false); // belt-and-suspenders for ranged pose
                if ((this.ticksExisted & 0x3F) == 0) { // every ~64 ticks (~3.2s)
                    pruneGraceHits();
                }
            }
        }
        if (!world.isRemote && this.getAttackTarget() == null && combat.hasWatchFightTarget()) {
            if (combat.isWatchFightExpired()) {
                combat.clearWatchFight();
            } else if (world instanceof WorldServer) {
                Entity e = ((WorldServer) world).getEntityFromUuid(combat.getWatchFightTargetId());
                if (e instanceof EntityLivingBase && e.isEntityAlive()) {
                    this.getNavigator().clearPath();
                    this.getLookHelper().setLookPositionWithEntity(e, 30.0F, (float) this.getVerticalFaceSpeed());
                } else {
                    combat.clearWatchFight();
                }
            }
        }

    }

    // --- NBT Saving / Loading ---
    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        // --- Temporarily remove transient modifiers to avoid "already applied" during save ---
        IAttributeInstance move = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);

        // This avoids cross-chunk riding NBT weirdness without changing runtime state.
        if (this.isRiding() && this.getRidingEntity() instanceof EntityTravellerBoat) {
            compound.removeTag("Riding");
        }

        AttributeModifier savedSwim = null;
        if (move != null) {
            savedSwim = move.getModifier(SWIM_BOOST_UUID);
            if (savedSwim != null) {
                move.removeModifier(savedSwim);
            }
        }

        try {
            // Let vanilla do its save (this is where attribute maps are massaged)
            super.writeEntityToNBT(compound);

            // ---- your existing writes below ----
            if (dogId != null) compound.setUniqueId("DogId", dogId);
            compound.setTag("TravellerInv", inventory.serializeNBT());
            if (travelCommon.getTargetVillage() != null)
                compound.setTag("TargetVillage", NBTUtil.createPosTag(travelCommon.getTargetVillage()));
            if (travelCommon.getCurrentWaypoint() != null)
                compound.setIntArray("CurrentWaypoint", new int[]{travelCommon.getCurrentWaypoint().getX(), travelCommon.getCurrentWaypoint().getY(), travelCommon.getCurrentWaypoint().getZ()});
            if (travelCommon.getRoamTarget() != null)
                compound.setIntArray("RoamTarget", new int[]{travelCommon.getRoamTarget().getX(), travelCommon.getRoamTarget().getY(), travelCommon.getRoamTarget().getZ()});
            compound.setBoolean("CampLeader", this.travellerCamp.isCampLeader());
            compound.setLong("CampLeaveTime", this.travellerCamp.campLeaveTime);

            if (this.travellerCamp.getCampfirePos() != null) {
                compound.setInteger("CampX", this.travellerCamp.getCampfirePos().getX());
                compound.setInteger("CampY", this.travellerCamp.getCampfirePos().getY());
                compound.setInteger("CampZ", this.travellerCamp.getCampfirePos().getZ());
            }

            compound.setInteger("LastCombatTick", this.dataManager.get(LAST_COMBAT_TICK));
            compound.setInteger("PauseTicks", travelCommon.getPauseTicks());
            compound.setInteger("VillageIdleTicks", travelCommon.getVillageIdleTicks());
            compound.setBoolean("VillageIdling", travelCommon.isVillageIdling());
            compound.setInteger("RouteIndex", travelCommon.routeIndex);
            compound.setInteger("TextureIdx", getTextureIndex());
            compound.setFloat("RenderScale", getRenderScale());
            compound.setFloat("Wanderlust", travelCommon.wanderlust);
            compound.setFloat("ExploreChance", travelCommon.exploreChance);
            compound.setFloat("HopPreference", travelCommon.hopPreference);
            compound.setFloat("PathAngleBiasDeg", travelCommon.getPathAngleBiasDeg());
            compound.setDouble("PathLaneOffset", travelCommon.getPathLaneOffset());
            compound.setInteger("PathStepJitter", travelCommon.getPathStepJitter());
            compound.setInteger("RecalcPhase", travelCommon.getRecalcPhase());
            compound.setBoolean("ReadyToDepart", travelCommon.isReadyToDepart());
            compound.setInteger("DepartCooldown", travelCommon.getDepartCooldownTicks());
            compound.setString("CultureId", getCultureId());
            compound.setString(NBT_VOICE_PERSONALITY_ID, getVoicePersonalityId());
            if (groupLeaderUUID != null) compound.setUniqueId("GroupLeaderUUID", groupLeaderUUID);

            NBTTagList lst = new NBTTagList();
            for (BlockPos p : travelCommon.route) {
                lst.appendTag(new NBTTagIntArray(new int[]{p.getX(), p.getY(), p.getZ()}));
            }
            compound.setTag("RouteVillages", lst);

        } finally {
            // Restore the swim boost exactly as it was
            if (move != null && savedSwim != null && move.getModifier(SWIM_BOOST_UUID) == null) {
                move.applyModifier(savedSwim);
            }
        }
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        if (compound.getBoolean("LeftHanded")) this.setLeftHanded(false);
        this.travellerCamp.setCampLeader(compound.getBoolean("CampLeader"));
        this.travellerCamp.campLeaveTime = compound.getLong("CampLeaveTime");
        if (compound.hasKey("LastCombatTick")) {
            this.dataManager.set(LAST_COMBAT_TICK, compound.getInteger("LastCombatTick"));
        }
        if (compound.hasKey("CampX")) {
            int x = compound.getInteger("CampX");
            int y = compound.getInteger("CampY");
            int z = compound.getInteger("CampZ");
            this.travellerCamp.campfirePos = new BlockPos(x, y, z);
        } else {
            this.travellerCamp.campfirePos = null;
        }
        if (!this.world.isRemote) {
            this.spawnAmbientCooldownTicks = Math.max(this.spawnAmbientCooldownTicks, 4 * 20); // at least ~4s
        }
        dogId = compound.hasUniqueId("DogId") ? compound.getUniqueId("DogId") : null;
        if (compound.hasKey("TargetVillage", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            this.travelCommon.setTargetVillage(NBTUtil.getPosFromTag(
                    compound.getCompoundTag("TargetVillage")));
        } else {
            this.travelCommon.setTargetVillage(null);
        }

        // CurrentWaypoint (only once)
        if (compound.hasKey("CurrentWaypoint")) {
            int[] a = compound.getIntArray("CurrentWaypoint");
            if (a.length == 3) travelCommon.setCurrentWaypoint(new BlockPos(a[0], a[1], a[2]));
        } else {
            travelCommon.setCurrentWaypoint(null);
        }

        if (compound.hasKey("RoamTarget")) {
            int[] arr = compound.getIntArray("RoamTarget");
            travelCommon.setRoamTarget(new BlockPos(arr[0], arr[1], arr[2]));
        } else {
            travelCommon.setRoamTarget(null);
        }
        if (compound.hasKey("TravellerInv", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            inventory.deserializeNBT(compound.getCompoundTag("TravellerInv"));
        }
        if (compound.hasKey("TextureIdx")) setTextureIndex(compound.getInteger("TextureIdx"));
        if (compound.hasKey("RenderScale")) setRenderScale(compound.getFloat("RenderScale"));
        if (compound.hasKey("Wanderlust")) travelCommon.wanderlust = compound.getFloat("Wanderlust");
        if (compound.hasKey("ExploreChance")) travelCommon.exploreChance = compound.getFloat("ExploreChance");
        if (compound.hasKey("HopPreference")) travelCommon.hopPreference = compound.getFloat("HopPreference");
        if (compound.hasKey("PathAngleBiasDeg")) travelCommon.pathAngleBiasDeg = compound.getFloat("PathAngleBiasDeg");
        if (compound.hasKey("PathLaneOffset")) travelCommon.pathLaneOffset = compound.getDouble("PathLaneOffset");
        if (compound.hasKey("PathStepJitter")) travelCommon.pathStepJitter = compound.getInteger("PathStepJitter");
        if (compound.hasKey("RecalcPhase")) travelCommon.recalcPhase = compound.getInteger("RecalcPhase");
        if (compound.hasKey("CultureId")) setCultureId(compound.getString("CultureId"));

        if (!this.world.isRemote) {
            if (compound.hasKey(NBT_VOICE_PERSONALITY_ID)) {
                setVoicePersonalityId(compound.getString(NBT_VOICE_PERSONALITY_ID));
            } else {
                setVoicePersonalityId(""); // older worlds: keep empty -> old fallback behavior
            }
        }


        travelCommon.setPauseTicks(compound.getInteger("PauseTicks"));
        travelCommon.setVillageIdleTicks(compound.getInteger("VillageIdleTicks"));
        travelCommon.setVillageIdling(compound.getBoolean("VillageIdling"));
        travelCommon.route.clear();
        if (compound.hasKey("RouteVillages", net.minecraftforge.common.util.Constants.NBT.TAG_LIST)) {
            NBTTagList lst = compound.getTagList("RouteVillages", net.minecraftforge.common.util.Constants.NBT.TAG_INT_ARRAY);
            for (int i = 0; i < lst.tagCount(); i++) {
                int[] a = lst.getIntArrayAt(i);
                if (a.length == 3) travelCommon.route.add(new BlockPos(a[0], a[1], a[2]));
            }
        }
        travelCommon.routeIndex = compound.getInteger("RouteIndex");
        travelCommon.setReadyToDepart(compound.getBoolean("ReadyToDepart"));
        travelCommon.setDepartCooldownTicks(compound.getInteger("DepartCooldown"));
        groupLeaderUUID = compound.hasUniqueId("GroupLeaderUUID") ? compound.getUniqueId("GroupLeaderUUID") : null;
    }

    @Override
    protected void dropLoot(boolean wasRecentlyHit, int lootingModifier, DamageSource source) {
        super.dropLoot(wasRecentlyHit, lootingModifier, source);
        equipment.dropBackpackLoot();
    }

    @Override
    public void setAIMoveSpeed(float speed) {
        double base = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        super.setAIMoveSpeed(Math.min(speed, computeMoveSpeedCap(base)));
    }

    @Override
    public void onLivingUpdate() {

        if (!world.isRemote) {
            chooseVoicePersonalityIfNeeded();
            if (!this.world.isRemote) {
                ensureDismountIfInCombat();
            }

            if (spawnAmbientCooldownTicks > 0) spawnAmbientCooldownTicks--;
            if (travellerCamp.campGlobalCooldown > 0) travellerCamp.campGlobalCooldown--;
            if (travelTorchCooldown > 0) travelTorchCooldown--;  // NEW
        }

        super.onLivingUpdate();

        // Undo any drift super.onLivingUpdate() may have caused (nearby-entity collision, stray AI
        // movement) after it's already run, so a sleeping traveller never visibly slides.
        if (!world.isRemote && isSleepingInBed()) {
            holdSleepPosition();
        }

        // Clear revenge target immediately if the attacker is blacklisted
        if (!world.isRemote) {
            EntityLivingBase revenge = this.getRevengeTarget();
            if (revenge != null) {
                TravellerBlacklist aggroBl = getAggroBlacklist();
                if (aggroBl != null && aggroBl.isBlacklisted(revenge)) {
                    setRevengeTarget(null);
                }
            }
        }

        boolean inCombat =
                this.getAttackTarget() != null
                        || this.getRevengeTarget() != null
                        || this.hurtTime > 0;

        if (inCombat) {
            markCombatNow();
        }

        if (travellerCamp.campJoinCooldown > 0) travellerCamp.campJoinCooldown--;
        if (world.isRemote && hurtAnimCooldown > 0) hurtAnimCooldown--;
        if (speakCooldown > 0) speakCooldown--;
        if (world.isRemote) {
            if (forcedSwingTicks > 0) forcedSwingTicks--;
        }
        if (!world.isRemote) {
            equipment.tickPickupCooldown();
        }
        travellerCamp.tickCampAvoid();

        // Pick an initial random target only once
        if (!world.isRemote) {
            travelCommon.updateNearestVillage();

            // 1) Ensure we have a plan (unless subclass has locked routing)
            if (!isRouteLocked()
                    && (travelCommon.routeIndex < 0 || travelCommon.routeIndex >= travelCommon.route.size())) {

                // ✅ stop random roaming routes ONLY for hired follower workers
                if (this instanceof EntityTravellerWorker) {
                    EntityTravellerWorker w = (EntityTravellerWorker) this;

                    boolean shouldAutoPlan =
                            !w.isHiredFollower()
                                    || w.isJobActive()
                                    || w.isCatchupTravel()
                                    || w.isGoingHome();

                    if (shouldAutoPlan) {
                        travelCommon.ensureRoutePlanned();
                    }
                } else {
                    travelCommon.ensureRoutePlanned();
                }
            }


            // 2) Drive the current target from the route
            if (travelCommon.routeIndex >= 0 && travelCommon.routeIndex < travelCommon.route.size()) {
                BlockPos curStop = travelCommon.route.get(travelCommon.routeIndex);
                travelCommon.setTargetVillage(curStop);

                boolean nearCurrentStop = travelCommon.isAtTargetStop();

                if (nearCurrentStop && !travelCommon.isVillageIdling() && travelCommon.isReadyToDepart()) {
                    travelCommon.routeIndex++;
                    travelCommon.setReadyToDepart(false);
                    travelCommon.setDepartCooldownTicks(80);
                    if (travelCommon.routeIndex < travelCommon.route.size()) {
                        travelCommon.setTargetVillage(travelCommon.route.get(travelCommon.routeIndex));
                        this.getNavigator().clearPath();
                    } else {
                        // Trip finished
                        travelCommon.routeIndex = -1;
                        travelCommon.route.clear();
                        travelCommon.setTargetVillage(null);
                    }
                }

            }
            if (this.canPickUpLoot() && !isInCombat() && equipment.getPickupCooldown() == 0) {
                equipment.tryScanAndPickupGroundItemsThrottled();
            }
            if (travellerCamp.getCampfirePos() != null && this.isEntityAlive()) {

                if (this instanceof EntityTravellerWorker) {
                    EntityTravellerWorker w = (EntityTravellerWorker) this;
                    if (w.isHiredFollower()) {
                        // Followers should not care about camps / fire look
                        // while they are serving a player.
                        // So skip all campcommon social look logic here.
                    } else {
                        travellerCamp.tickCampSocial();
                        if (!travellerCamp.isSocialLooking()) {
                            this.lookAtFire();
                        }
                    }
                } else {
                    travellerCamp.tickCampSocial();
                    if (!travellerCamp.isSocialLooking()) {
                        this.lookAtFire();
                    }
                }
            }
        }


    }

    // Equipment
    @Override
    protected void updateEquipmentIfNeeded(EntityItem itemEntity) {
        equipment.handleGroundPickup(itemEntity);
    }

    @Override
    public void setItemStackToSlot(EntityEquipmentSlot slot, ItemStack stack) {
        if (slot == EntityEquipmentSlot.OFFHAND) {
            // Ghost torch: purely visual – don't run backpack logic for it
            if (!stack.isEmpty() && isGhostTorch(stack)) {
                super.setItemStackToSlot(slot, stack);
                return;
            }

            // Real items still go through your backpack handling
            if (!stack.isEmpty()) {
                ItemStack rem = ItemHandlerHelper.insertItemStacked(inventory, stack, false);
                if (rem.isEmpty()) {
                    super.setItemStackToSlot(slot, ItemStack.EMPTY);
                } else {
                    super.setItemStackToSlot(slot, rem);
                }
            } else {
                super.setItemStackToSlot(slot, ItemStack.EMPTY);
            }
            return;
        }
        super.setItemStackToSlot(slot, stack);
    }

    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        this.spawnAmbientCooldownTicks = TravellersConfig.SOUND.spawnAmbientDelayTicks + this.rand.nextInt(5 * 20);

        livingdata = super.onInitialSpawn(difficulty, livingdata);

        // --- culture pick/propagate ---

        String cid = null;

        // 1) If the spawner (soft-spawner or vanilla group leader) already gave us a culture, honour it.
        if (livingdata instanceof SpawnData) {
            cid = ((SpawnData) livingdata).cultureId;
        }

        // 2) If we still don't have a culture, try village-based pick around our spawn position.
        if (cid == null || cid.isEmpty()) {
            // Uses the same logic as your soft spawner (weighted by nearby Millénaire mill).
            String villageCid = MillVillageIndex.pickCultureForTravellerSpawn(this.world, this.getPosition(), this.rand);
            if (villageCid != null && !villageCid.isEmpty()) {
                cid = villageCid;
                // Make sure vanilla group spawns copy this culture as SpawnData.
                livingdata = new SpawnData(cid);
            }
        }

        // 3) Fallback: biome-based culture (old behaviour) if no village influence / no village data.
        if (cid == null || cid.isEmpty()) {
            net.minecraft.world.biome.Biome biome = this.world.getBiome(this.getPosition());
            cid = CultureRegistry.pickCultureIdForBiome(biome, this.rand);
            livingdata = new SpawnData(cid);
        }

        setCultureId(cid);


// NEW: pick personality once per entity (server decides, clients will be synced)
        chooseVoicePersonalityIfNeeded();


        CultureDef c = CultureRegistry.get(cid);
        int tCount = (c != null) ? c.travellerAppearanceCount() : 0;
        setTextureIndex(this.rand.nextInt(Math.max(1, tCount <= 0 ? 1 : tCount)));
        rollRenderScale(getActiveAppearance());

        // Reset move attr base and make sure no swim boost lingers
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.setBaseValue(com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeed);
            AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
            if (m != null) attr.removeModifier(m);
        }

        if (!world.isRemote) {
            equipment.initSpawnEquipment(c, this.rand);

            // 3) Seed ambient window if needed
            if (nextTravelAmbientTick <= 0 || nextTravelAmbientTick == Integer.MAX_VALUE) {
                scheduleNextTravelAmbient();
            }
            // 4) Assign culture-based name from Millénaire namelists
            String fullName = CultureRegistry.makeTravellerNameForCulture(cid, this.rand);
            if (fullName != null && !fullName.isEmpty()) {
                this.setCustomNameTag(fullName);
                this.setAlwaysRenderNameTag(false);
            }
        }

        // Personality seeds
        this.setHealth(this.getMaxHealth());
        java.util.Random r = new java.util.Random(this.getUniqueID().getMostSignificantBits()
                ^ this.getUniqueID().getLeastSignificantBits());
        this.travelCommon.wanderlust = 0.25f + r.nextFloat() * 0.60f;
        this.travelCommon.exploreChance = 0.08f + r.nextFloat() * 0.06f;
        this.travelCommon.hopPreference = 0.45f + r.nextFloat() * 0.25f;
        this.travelCommon.pathAngleBiasDeg = 0;
        this.travelCommon.pathLaneOffset = 0;
        this.travelCommon.pathStepJitter = 0;
        this.travelCommon.recalcPhase = r.nextInt(12);

        return livingdata;
    }

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;
        if (speakCooldown > 0) return true;

        speakCooldown = 20; // ~1s between barks
        doInteractSwing(hand);

        // ✅ Only greet/chat when NOT in combat
        if (!this.isInCombat()) {
            // Choose hello vs talk
            if (shouldHelloFor(player)) {
                playHelloSound();         // one hello variation
                markHello(player);        // start the 5-minute window for THIS player
            } else {
                playTalkSound();          // normal chatter
            }
        }

        // Keep your existing “I’m following / village idle / announce heading” messages:
        if (isFollowingSomeone()) {
            ITextComponent travellingMsg = new TextComponentTranslation("travellers.dialogue.travelling_with_someone");
            travellingMsg.getStyle().setColor(TextFormatting.WHITE);
            player.sendMessage(travellingMsg);
            return true;
        }
        if (travelCommon.isVillageIdling()) {
            String stayName = travelCommon.lookupNearestVillageNameOrCoords();
            ITextComponent stayNameComp = new TextComponentString(stayName)
                    .setStyle(new Style().setColor(TextFormatting.GOLD));
            ITextComponent stayMsg = new TextComponentTranslation("travellers.dialogue.staying_in_village", stayNameComp);
            stayMsg.getStyle().setColor(TextFormatting.WHITE);
            player.sendMessage(stayMsg);
            return true;
        }

        world.setEntityState(this, (byte) 4); // swing animation
        announceHeadingTo(player);
        return true;
    }

    public void scheduleNextTravelAmbient() {
        // spreads entities out and respects config
        if (!TravellersConfig.SOUND.travellerAmbient) {
            nextTravelAmbientTick = Integer.MAX_VALUE;
            return;
        }
        int min = Math.max(40, TravellersConfig.SOUND.travellerAmbientMinDelay);
        int max = Math.max(min, TravellersConfig.SOUND.travellerAmbientMaxDelay);
        int delta = min + this.rand.nextInt(Math.max(1, max - min + 1));
        nextTravelAmbientTick = this.ticksExisted + delta;
    }

    public int getTextureIndex() {
        return this.dataManager.get(TEXTURE_INDEX);
    }

    public void setTextureIndex(int index) {
        this.dataManager.set(TEXTURE_INDEX, index);
    }

    public float getRenderScale() {
        return this.dataManager.get(RENDER_SCALE);
    }

    public void setRenderScale(float scale) {
        this.dataManager.set(RENDER_SCALE, scale);
    }

    /**
     * Replicates Millenaire's own VillagerRecord scale roll: baseHeight * random 0.80-0.89,
     * rolled once at spawn (per-instance jitter, like real villagers) then persisted.
     */
    public void rollRenderScale(TravellerAppearance appearance) {
        float baseHeight = appearance != null ? appearance.baseHeight : 1.0f;
        setRenderScale(baseHeight * (0.80f + this.rand.nextInt(10) / 100f));
    }

    public void setCultureId(String id) {
        this.dataManager.set(CULTURE_ID, id);
    }

    public boolean isAimingBow() {
        return this.dataManager.get(AIMING_BOW);
    }

    public void setAimingBow(boolean v) {
        if (!world.isRemote) this.dataManager.set(AIMING_BOW, v);
    }

    public boolean isCampingPassive() {
        return campingPassive;
    }

    public void setCampingPassive(boolean v) {
        this.campingPassive = v;
    }

    public boolean isSleepingInBed() {
        return this.dataManager.get(SLEEPING);
    }

    public BlockPos getSleepingBedPos() {
        return this.dataManager.get(SLEEPING_BED_POS);
    }

    /**
     * Lies down in the given bed block. No distance/safety checks (the calling AI already
     * guarantees proximity and camp-only context) — this is a cosmetic pose, not vanilla
     * player-sleeping (no time skip, no spawn point).
     *
     * <p>Mirrors {@link net.minecraft.entity.player.EntityPlayer#trySleep} exactly: the entity is
     * anchored near the HEAD half (nudged 0.4 blocks further along {@code FACING}, away from the
     * foot), and the render is separately shifted back toward the foot by
     * {@link #getSleepRenderOffset()} (mirroring {@code EntityPlayer#setRenderOffsetForSleep}) so
     * the body visually spans the full two-block bed regardless of {@code FACING}/bed type. An
     * earlier FOOT-anchored attempt without that render shift is why this needs the comment: the
     * two pieces (anchor position and render offset) only look right together.
     */
    public boolean trySleepInBed(BlockPos bedPos) {
        if (world.isRemote || !isEntityAlive() || isSleepingInBed()) return false;
        IBlockState state = world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BlockBed)) return false;

        BlockBed.EnumPartType part = state.getValue(BlockBed.PART);
        BlockPos headPos = bedPos;
        if (part != BlockBed.EnumPartType.HEAD) {
            BlockPos candidate = bedPos.offset(state.getValue(BlockHorizontal.FACING));
            IBlockState candidateState = world.getBlockState(candidate);
            if (candidateState.getBlock() instanceof BlockBed
                    && candidateState.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
                headPos = candidate;
                state = candidateState;
            } // else: single/broken bed fallback, sleep on the one block found
        }

        EnumFacing facing = state.getValue(BlockHorizontal.FACING);
        double x = headPos.getX() + 0.5D + facing.getXOffset() * 0.4D;
        double z = headPos.getZ() + 0.5D + facing.getZOffset() * 0.4D;
        // Rest on the bed's OWN collision box, not vanilla's 0.6875 constant — Millénaire beds
        // (BlockMillBed) have custom, often lower collision heights and the constant floats above them.
        double y = headPos.getY() + state.getBoundingBox(world, headPos).maxY;

        this.setSize(0.2F, 0.2F);
        this.motionX = 0.0D;
        this.motionY = 0.0D;
        this.motionZ = 0.0D;
        this.setPosition(x, y, z);
        this.sleepAnchorX = x;
        this.sleepAnchorY = y;
        this.sleepAnchorZ = z;
        this.dataManager.set(SLEEPING_BED_POS, headPos);
        this.dataManager.set(SLEEPING, true);
        return true;
    }

    public void wakeUpTraveller() {
        if (world.isRemote || !isSleepingInBed()) return;
        this.setSize(0.5F, 1.6F);
        this.dataManager.set(SLEEPING, false);
    }

    /**
     * Re-pins the entity to its exact sleep anchor every tick, undoing any drift from nearby
     * entity collisions (also blocked outright via {@link #canBePushed()}) or stray AI movement —
     * a sleeping entity must never visibly slide out of its bed.
     */
    private void holdSleepPosition() {
        this.motionX = 0.0D;
        this.motionY = 0.0D;
        this.motionZ = 0.0D;
        if (this.posX != sleepAnchorX || this.posY != sleepAnchorY || this.posZ != sleepAnchorZ) {
            this.setPosition(sleepAnchorX, sleepAnchorY, sleepAnchorZ);
        }
    }

    @Override
    public boolean canBePushed() {
        if (isSleepingInBed()) return false;
        return super.canBePushed();
    }

    /**
     * Sleeping intentionally rests the entity on the bed's own (sometimes tall) collision box,
     * which can otherwise overlap a low ceiling/wall and trigger vanilla's "in wall" suffocation
     * damage ({@code EntityLivingBase#onEntityUpdate}) — never appropriate for a cosmetic pose.
     */
    @Override
    public boolean isEntityInsideOpaqueBlock() {
        // campingPassive also covers block-by-block shelter build/teardown: a block placed
        // against the mob's own bounding box must never suffocate the builder.
        if (isSleepingInBed() || campingPassive) return false;
        return super.isEntityInsideOpaqueBlock();
    }

    /**
     * Client-only: bed facing in degrees, mirroring {@link EntityPlayer#getBedOrientationInDegrees()}
     * exactly — vanilla uses its own hardcoded mapping, NOT {@link EnumFacing#getHorizontalAngle()}
     * (which is 90° off and shifts the sleeping body sideways depending on bed orientation).
     */
    @SideOnly(Side.CLIENT)
    public float getBedOrientationInDegrees() {
        BlockPos bedPos = getSleepingBedPos();
        IBlockState state = world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BlockBed)) return 0.0F;
        switch (state.getValue(BlockHorizontal.FACING)) {
            case SOUTH: return 90.0F;
            case WEST: return 0.0F;
            case NORTH: return 270.0F;
            case EAST: return 180.0F;
            default: return 0.0F;
        }
    }

    /**
     * Client-only: how far to shift the rendered model away from the anchor (which sits near the
     * HEAD half, see {@link #trySleepInBed}), mirroring {@code EntityPlayer#renderOffsetX/Z}. Without
     * this the body renders bunched up near the headboard instead of spanning the full bed.
     */
    @SideOnly(Side.CLIENT)
    public Vec3d getSleepRenderOffset() {
        BlockPos bedPos = getSleepingBedPos();
        IBlockState state = world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BlockBed)) return Vec3d.ZERO;
        EnumFacing facing = state.getValue(BlockHorizontal.FACING);
        // Vanilla uses -1.8 for a full-size player; the model renders at getRenderScale()
        // (RenderTraveller.preRenderCallback), so shrink the shift to keep the shorter
        // body centered in the 2-block bed: -0.9 * (1 + scale).
        double d = -0.9D * (1.0D + getRenderScale());
        return new Vec3d(d * facing.getXOffset(), 0.0D, d * facing.getZOffset());
    }

    /**
     * Client-only: while asleep, sample render light at the midpoint of the VISIBLE body (shifted
     * toward the bed's foot by {@link #getSleepRenderOffset}) instead of the anchor cell near the
     * headboard — a shelter block directly above the bed head otherwise darkens the whole body even
     * though most of it lies in a lit cell.
     */
    @Override
    @SideOnly(Side.CLIENT)
    public int getBrightnessForRender() {
        if (isSleepingInBed()) {
            Vec3d off = getSleepRenderOffset();
            BlockPos p = new BlockPos(posX + off.x * 0.5D, posY + 0.5D, posZ + off.z * 0.5D);
            if (world.isBlockLoaded(p)) {
                return world.getCombinedLight(p, 0);
            }
        }
        return super.getBrightnessForRender();
    }

    void tryPlayAttackSound() {
        if (!TravellersConfig.SOUND.attackSoundEnabled) return;
        if (world.isRemote) return;
        if (ticksExisted < attackVoiceCooldownUntilTick) return;
        if (voiceKindNow == VoiceKind.HURT && ticksExisted < voiceLockUntilTick) return;

        long worldTime = world.getTotalWorldTime();
        long zoneKey = attackZoneKey();
        Long lastZone = ATTACK_ZONE_COOLDOWN.get(zoneKey);
        if (lastZone != null && worldTime - lastZone < TravellersConfig.SOUND.attackSoundZoneCooldownTicks) return;

        VoiceSet v = resolveVoiceSetForAttack();
        if (v == null) return;
        SoundEvent snd = v.pickAttack(this.rand, null);
        if (snd == null) return;

        float vol = TravellersConfig.SOUND.attackSoundVolume;
        float pitch = 1.0f + (rand.nextFloat() - 0.5f) * 0.04f;
        int dur = 60 + rand.nextInt(40);

        if (playVoiceMoving(snd, vol, pitch, dur, true, VoiceKind.TALK)) {
            attackVoiceCooldownUntilTick = ticksExisted + TravellersConfig.SOUND.attackSoundEntityCooldownTicks;
            ATTACK_ZONE_COOLDOWN.put(zoneKey, worldTime);
        }
    }

    public void playCreeperWarningSound() {
        if (!TravellersConfig.SOUND.creeperWarnEnabled) return;
        if (world.isRemote) return;

        chooseVoicePersonalityIfNeeded();
        VoiceSet v = getActiveTravellerVoiceSet();
        if (v == null) return;
        SoundEvent snd = v.pickCreeper(this.rand, null);
        if (snd == null) return;

        float vol = TravellersConfig.SOUND.creeperWarnVolume;
        float pitch = 1.0f + (rand.nextFloat() - 0.5f) * 0.04f;
        int dur = 60 + rand.nextInt(40);
        playVoiceMoving(snd, vol, pitch, dur, true, VoiceKind.TALK);
    }

    protected VoiceSet resolveVoiceSetForAttack() {
        chooseVoicePersonalityIfNeeded();
        return getActiveTravellerVoiceSet();
    }

    protected VoiceSet resolveVoiceSetForKillDeath() {
        chooseVoicePersonalityIfNeeded();
        return getActiveTravellerVoiceSet();
    }

    public void tryPlayKillSound() {
        if (!TravellersConfig.SOUND.killSoundEnabled) return;
        if (world.isRemote || isDead) return;
        if (ticksExisted < killVoiceCooldownUntilTick) return;

        long worldTime = world.getTotalWorldTime();
        long zoneKey = attackZoneKey();
        Long lastZone = KILL_ZONE_COOLDOWN.get(zoneKey);
        if (lastZone != null && worldTime - lastZone < TravellersConfig.SOUND.killSoundZoneCooldownTicks) return;

        VoiceSet v = resolveVoiceSetForKillDeath();
        if (v == null) return;
        SoundEvent snd = v.pickKill(this.rand, null);
        if (snd == null) return;

        float vol = TravellersConfig.SOUND.killSoundVolume;
        float pitch = 1.0f + (rand.nextFloat() - 0.5f) * 0.04f;
        int dur = 60 + rand.nextInt(40);

        if (playVoiceMoving(snd, vol, pitch, dur, true, VoiceKind.TALK)) {
            killVoiceCooldownUntilTick = ticksExisted + TravellersConfig.SOUND.killSoundEntityCooldownTicks;
            KILL_ZONE_COOLDOWN.put(zoneKey, worldTime);
        }
    }

    void tryPlayDeathSound() {
        if (!TravellersConfig.SOUND.deathSoundEnabled) return;
        if (world.isRemote) return;

        long worldTime = world.getTotalWorldTime();
        long zoneKey = attackZoneKey();
        Long lastZone = DEATH_ZONE_COOLDOWN.get(zoneKey);
        if (lastZone != null && worldTime - lastZone < TravellersConfig.SOUND.deathSoundZoneCooldownTicks) return;

        chooseVoicePersonalityIfNeeded();
        VoiceSet v = resolveVoiceSetForKillDeath();
        if (v == null) return;
        SoundEvent snd = v.pickDeath(this.rand, null);
        if (snd == null) return;

        float vol = TravellersConfig.SOUND.deathSoundVolume;
        float pitch = 1.0f + (rand.nextFloat() - 0.5f) * 0.04f;
        int dur = 60;

        playVoiceMoving(snd, vol, pitch, dur, true, VoiceKind.TALK);
        DEATH_ZONE_COOLDOWN.put(zoneKey, worldTime);
    }

    private long attackZoneKey() {
        int zx = (int) Math.floor(posX / 32.0);
        int zz = (int) Math.floor(posZ / 32.0);
        return ((long) dimension * 1_000_003L) ^ ((long) (zx & 0xFFFF) << 16) ^ (long) (zz & 0xFFFF);
    }

    void playTalkSound() {
        chooseVoicePersonalityIfNeeded();
        VoiceSet v = getActiveTravellerVoiceSet();

        SoundEvent talk = (v != null)
                ? v.pickTalk(this.rand, ModSounds.TRAVELLER_TALK)
                : ModSounds.TRAVELLER_TALK;

        float vol = TravellersConfig.SOUND.travellerTalkVolume;
        float pitch = 1.0F + (this.rand.nextFloat() - 0.5F) * 0.02F;
        int dur = 40 + this.rand.nextInt(20);

        playVoiceMoving(talk, vol, pitch, dur, true, VoiceKind.TALK);
        scheduleNextTravelAmbient();
    }


    public void doInteractSwing(EnumHand hand) {
        // Tell all nearby clients to render a swing (4 = main hand, 5 = offhand)
        world.setEntityState(this, (byte) (hand == EnumHand.OFF_HAND ? 5 : 4));
        // Also update server-side state so other logic that checks swing progresses is consistent
        this.swingArm(hand);
        // If you use your forcedSwingTicks visual, give it a nudge
        this.forcedSwingTicks = 6; // matches your handleStatusUpdate()
    }

    public boolean isFollowingSomeone() {
        return this.hasFollowLeader() && this.getFollowLeader() != null;
    }

    public String debugId() {
        return "[Traveller " + this.getEntityId() + " " +
                (world != null && world.isRemote ? "CLIENT" : "SERVER") + "]";
    }

    // was: lookupNearestVillageNameOrCoords()
    private String lookupNearestVillageNameOrCoords() {
        return travelCommon.lookupNearestVillageNameOrCoords();
    }

    private void announceHeadingTo(EntityPlayer player) {
        // original target (whatever you stored there)
        BlockPos target = travelCommon.getTargetVillage();
        if (target == null) {
            ITextComponent noDestMsg = new TextComponentTranslation("travellers.dialogue.no_destination");
            noDestMsg.getStyle().setColor(TextFormatting.WHITE);
            player.sendMessage(noDestMsg);
            return;
        }

        // Resolve via MillVillageIndex only
        MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(this.world, target);
        if (vi == null) vi = MillVillageIndex.findNearest3D(this.world, target, 128);
        if (vi == null) vi = MillVillageIndex.findNearest2D(this.world, target, 160);

        // Use the resolved center if we have one, otherwise keep the raw target
        BlockPos center = (vi != null) ? vi.center : target;

        // Good name if possible, x/y/z only as final fallback
        String name;
        if (vi != null && vi.name != null && !vi.name.isEmpty()) {
            name = vi.name;
        } else {
            name = center.getX() + "/" + center.getY() + "/" + center.getZ();
        }

        String dir = cardinalDirectionTo(center); // or whatever your direction helper is

        ITextComponent nameComp = new TextComponentString(name).setStyle(new Style().setColor(TextFormatting.GOLD));
        ITextComponent dirComp = new TextComponentString(dir).setStyle(new Style().setColor(TextFormatting.GOLD));
        ITextComponent msg = new TextComponentTranslation("travellers.dialogue.heading_to", nameComp, dirComp);
        msg.getStyle().setColor(TextFormatting.WHITE);
        player.sendMessage(msg);
    }

    public boolean playVoiceMoving(SoundEvent evt, float vol, float pitch, int durationTicks, boolean preempt, VoiceKind kind) {
        if (!preempt && this.ticksExisted < voiceLockUntilTick) return false;

        // Don’t also call playSound here; we only want the moving instance on clients.
        sendFollowSound(evt, vol, pitch, 48.0);

        voiceLockUntilTick = Math.max(voiceLockUntilTick, this.ticksExisted + Math.max(1, durationTicks));
        voiceKindNow = kind;
        return true;
    }

    @Nullable
    public EntityTraveller getFollowLeader() {
        if (followLeaderId == null) return null;
        List<EntityTraveller> list = this.world.getEntities(EntityTraveller.class, e ->
                e != null && e.isEntityAlive() && e.getUniqueID().equals(followLeaderId));
        return list.isEmpty() ? null : list.get(0);
    }

    @Nullable
    public BlockPos getNearestVillage() {
        return travelCommon.getNearestVillage();
    }

    private String cardinalDirectionTo(BlockPos target) {
        double dx = target.getX() + 0.5 - this.posX;
        double dz = target.getZ() + 0.5 - this.posZ;

        double adx = Math.abs(dx), adz = Math.abs(dz);
        if (adx < 0.75 && adz < 0.75) return "here";

        // Minecraft axis: +X = east, +Z = south, -Z = north
        String ns = (dz < 0) ? "north" : "south";
        String ew = (dx < 0) ? "west" : "east";

        // bias toward cardinal if one axis dominates
        if (adx < adz * 0.5) return ns;       // mostly N/S
        if (adz < adx * 0.5) return ew;       // mostly E/W
        return ns + "-" + ew;                 // e.g., "north-east"
    }

    private void sendFollowSound(SoundEvent evt, float vol, float pitch, double radius) {
        if (world.isRemote || evt == null) return;

        // If we’re currently “voice locked”, use the remaining lock as the clip’s duration hint.
        // Otherwise just fall back to ~2s so short barks still work.
        int remaining = Math.max(1, this.voiceLockUntilTick - this.ticksExisted);
        int durationHint = (remaining > 0 ? remaining : 40); // 40 ticks = 2s

        MsgPlayTravellerVoice msg = new MsgPlayTravellerVoice(this, evt, vol, pitch, durationHint);

        NetworkRegistry.TargetPoint tp =
                new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, radius);
        TravellerFollowNet.CHANNEL.sendToAllAround(msg, tp);
    }

    // was: updateTargetVillage()
    private void updateNearestVillage() {

        travelCommon.updateNearestVillage();
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {

        if (source == DamageSource.CACTUS || "cactus".equals(source.getDamageType())) {
            return false;
        }

        boolean tookDamage = super.attackEntityFrom(source, amount);
        if (!tookDamage || world.isRemote) return tookDamage;

        TravellerItemDamage.damageArmorOnHit(this, amount);

        if (choirSinging) {
            // Keep the OGG stream alive; just mute the sound on clients for 8 seconds so
            // when unmuted the singer is back at the correct position in the song.
            TravellerFollowNet.CHANNEL.sendToAllAround(
                    new MsgChoirSingerMute(getEntityId(), 8000),
                    new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64.0));
            // Don't touch voiceLockUntilTick so the coordinator keeps the session alive.
        } else {
            TravellerFollowNet.CHANNEL.sendToAllAround(
                    new MsgStopTravellerVoice(this),
                    new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64.0));
            beginVoiceLock(20, VoiceKind.HURT);
        }

        Entity src = source.getTrueSource();

        if (src instanceof EntityPlayer && ((EntityPlayer) src).isCreative()) {
            setInCombat(false);
            return true;
        }

        // NEW: if worker is hit by its owner, never aggro back
        if (this instanceof EntityTravellerWorker && src instanceof EntityPlayer) {
            EntityTravellerWorker w = (EntityTravellerWorker) this;
            EntityPlayer owner = w.getFollowOwner();
            if (owner != null && owner == src) {
                return true;
            }
        }



        // NEW: if worker is hit by its owner, never aggro back
        if (this instanceof EntityTravellerWorker && src instanceof EntityPlayer) {
            EntityTravellerWorker w = (EntityTravellerWorker) this;
            EntityPlayer owner = w.getFollowOwner();
            if (owner != null && owner == src) {
                // Take damage, but do not treat as a threat
                return true;
            }
        }

        if (src instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) src;
            if (!combat.isCrossFaction(other)) {
                // same faction -> no revenge target, no aggro
                this.setRevengeTarget(null);
                return true;
            }
        }

        if (src instanceof org.millenaire.common.entity.MillVillager) {
            org.millenaire.common.entity.MillVillager mv = (org.millenaire.common.entity.MillVillager) src;
            if (!com.jubitus.travellers.utils.world.mill.MillVillagerTravellerGuard.shouldFightMillVillager(this, mv)) {
                // Absorb hit from a non-enemy millager without retaliating
                return true;
            }
        }

        if (src instanceof EntityLivingBase) {
            this.setRevengeTarget((EntityLivingBase) src);
        }

        if (world.getDifficulty() != EnumDifficulty.PEACEFUL && src instanceof EntityLivingBase) {
            EntityLivingBase le = (EntityLivingBase) src;

            // Always remember threats – we saw them / they hit us
            combat.rememberThreat(le, TravellerCombatLogic.THREAT_FOR_HIT);

            boolean canSee = this.getEntitySenses().canSee(le);
            double d2 = this.getDistanceSq(le);
            boolean near = d2 <= 9.0D; // 3 blocks radius; tweak as you like

            // **Key: require a path before we actually mark them as attack target**
            boolean hasPath = (le != null) && this.hasPathToThrottled(le, 2); // urgent


            // Special rules for players:
            if (le instanceof EntityPlayer) {
                EntityPlayer p = (EntityPlayer) le;

                // Only aggro on player if seen OR nearby AND pathable
                if ((canSee || near) && hasPath) {
                    setInCombat(true);
                    this.setAttackTarget(le);
                } else {
                    // Take the hit and remember threat, but don't start active combat
                    setInCombat(false);
                }
            } else {
                // Non-player: must be pathable to become an active target
                if (hasPath) {
                    setInCombat(true);
                    this.setAttackTarget(le);
                } else {
                    setInCombat(false);
                }
            }
        }

        return true;
    }

@Override
public void fall(float distance, float damageMultiplier) {
    // Fall damage starts at 6 blocks instead of vanilla 3 (subtract 2 on top of Minecraft's own 3-block safe distance).
    super.fall(Math.max(0.0F, distance - 2.0F), damageMultiplier);
}

    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            EntityLivingBase at = this.getAttackTarget();
            if (at != null) {
                AttackTargetLimiter.notifyAttackerDied(at); // signals target is dangerous → raises cap
                AttackTargetLimiter.leave(this, at);
            }
            TravellerFollowNet.CHANNEL.sendToAllAround(
                    new MsgStopTravellerVoice(this),
                    new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64.0));
            travellerCamp.cleanupOwnedCampfireIfAny();
            // Camp event system: free this traveller's slot so the camp updates / can be reused.
            com.jubitus.travellers.utils.world.camp.CampsiteManager.get((net.minecraft.world.WorldServer) world)
                    .releaseOccupant(this.getUniqueID());
            // Virtual battles: mark this pawn dead so the offscreen sim stays consistent.
            com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get((net.minecraft.world.WorldServer) world)
                    .onParticipantDeath((net.minecraft.world.WorldServer) world, this);
        }
        tryPlayDeathSound();
        if (!this.world.isRemote && this.isRiding() && this.getRidingEntity() instanceof net.minecraft.entity.item.EntityBoat) {
            net.minecraft.entity.item.EntityBoat boat = (net.minecraft.entity.item.EntityBoat) this.getRidingEntity();
            this.dismountRidingEntity();
            boat.setDead();
        }
        if (equipment.isGhostWeapon(getHeldItemMainhand())) setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        EntityTravellerDog dog = getDog();

        super.onDeath(cause);
    }

    @Nullable
    public EntityTravellerDog getDog() {
        if (dogId == null) return null;
        List<EntityTravellerDog> list = world.getEntities(EntityTravellerDog.class,
                e -> e != null && e.isEntityAlive() && dogId.equals(e.getUniqueID()));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    @javax.annotation.Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        if (this.ticksExisted < hurtSoundCooldownUntilTick) return null;
        hurtSoundCooldownUntilTick = this.ticksExisted + 15; // max ~1.3 hurt sounds/sec per entity
        VoiceSet v = getActiveTravellerVoiceSet();
        SoundEvent hurt = (v != null) ? v.pickHurt(this.rand, ModSounds.TRAVELLER_HURT) : ModSounds.TRAVELLER_HURT;
        return hurt;
    }


    @Override
    protected float getSoundVolume() {
        return TravellersConfig.SOUND.travellerHurtVolume;
    }

    @Override
    protected float getSoundPitch() {
        // small per-hit pitch variance so repeats don’t sound robotic
        return 0.95F + (this.rand.nextFloat() - 0.5F) * 0.08F;
    }

    @Override
    public void travel(float strafe, float vertical, float forward) {
        if (this.isInWater()) {
            float boost = (float) TravellersConfig.COMBAT.swimSpeedMultiplier;
            super.travel(strafe * boost, vertical, forward * boost);
        } else {
            super.travel(strafe, vertical, forward);
        }
    }

    // --- Combat Handling ---
    @Override
    public boolean attackEntityAsMob(Entity target) {
        boolean hit = combat.performMeleeAttack(target);
        if (hit && !world.isRemote) {
            world.playSound(null, target.posX, target.posY, target.posZ,
                    SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS,
                    0.7f, 0.85f + rand.nextFloat() * 0.3f);
        }
        return hit;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
            return (T) inventory;
        return super.getCapability(capability, facing);
    }

    // ---- capability plumbing ----
    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return true;
        return super.hasCapability(capability, facing);
    }

    public void rememberThreat(EntityLivingBase e, int addScore) {
        combat.rememberThreat(e, addScore);
    }

    public TravellerBlacklist getAggroBlacklist() {
        return (this instanceof EntityTravellerRaider)
                ? TravellersDerivedConfig.RAIDER_AGGRO_BLACKLIST
                : TravellersDerivedConfig.TRAVELLER_AGGRO_BLACKLIST;
    }

    public boolean shouldAttackExtraEntity(Entity e) {
        if (e == null || e == this) return false;

        // Never treat players in creative/spectator as "extra hostiles"
        if (e instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) e;
            if (p.isCreative() || p.isSpectator()) return false;
        }

        // First, global per-faction config
        if (this.isAggressiveToTravellers()) {
            // Raiders
            return TravellersDerivedConfig.RAIDER_ATTACK_LIST.isListed(e);
        } else {
            // Normal/friendly travellers
            return TravellersDerivedConfig.TRAVELLER_ATTACK_LIST.isListed(e);
        }

        // Put any hard-coded extra rules here if you need them later
        // (e.g. always attack some specific boss entity)
    }

    // Default: friendlies are NOT aggressive to other travellers.
    public boolean isAggressiveToTravellers() {
        return false;
    }

    public void cleanupOwnedCampfireIfAny() {
        travellerCamp.cleanupOwnedCampfireIfAny();
    }


    @Override
    public void setDead() {
        if (!this.world.isRemote) {
            travellerCamp.cleanupOwnedCampfireIfAny();
            // If we're in a boat, despawn the boat with us
            if (this.isRiding() && this.getRidingEntity() instanceof net.minecraft.entity.item.EntityBoat) {
                net.minecraft.entity.item.EntityBoat boat =
                        (net.minecraft.entity.item.EntityBoat) this.getRidingEntity();
                this.dismountRidingEntity();
                boat.setDead(); // no drops, just remove
            }
        }
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (!this.world.isRemote && this.isRiding() && this.getRidingEntity() instanceof net.minecraft.entity.item.EntityBoat) {
            net.minecraft.entity.item.EntityBoat boat = (net.minecraft.entity.item.EntityBoat) this.getRidingEntity();
            this.dismountRidingEntity();
            boat.setDead(); // kill the boat, no leftovers
        }
        if (attr != null) {
            AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
            if (m != null) attr.removeModifier(m);
        }

        super.setDead();
    }

    // Keep the rider centered on the Traveller. No steering hand-off.
    @Override
    public void updatePassenger(Entity passenger) {
        super.updatePassenger(passenger);
        if (this.isPassenger(passenger)) {
            double x = this.posX;
            double y = this.posY + this.getMountedYOffset() + passenger.getYOffset();
            double z = this.posZ;
            passenger.setPosition(x, y, z);
        }
    }

    // Seat height so the player doesn't clip the head.
    @Override
    public double getMountedYOffset() {
        return this.height * getRenderScale();
    }

    // Allow exactly one passenger.
    @Override
    protected boolean canFitPassenger(Entity passenger) {
        return this.getPassengers().size() < 1;
    }

    @Override
    public boolean getAlwaysRenderNameTag() {
        // Hard disable via config: no name tag, even if custom name exists.
        if (!TravellersConfig.RENDERING.showTravellerNames) {
            return false;
        }

        // Otherwise, behave like a custom-named entity that always shows its tag.
        // (You can tweak this if you want vanilla distance behaviour instead.)
        return this.hasCustomName();
    }

    @Override
    public SoundCategory getSoundCategory() {
        return SoundCategory.VOICE;
    }

    @SideOnly(Side.CLIENT)
    public float getForcedSwingProgress() {
        return (forcedSwingTicks <= 0) ? 0f : (1f - (forcedSwingTicks / 6.0f));
    }

    public BlockPos getRoamTarget() {
        return travelCommon.getRoamTarget();
    }

    public void setRoamTarget(BlockPos roamTarget) {
        travelCommon.setRoamTarget(roamTarget);
    }

    public int getPauseTicks() {
        return travelCommon.getPauseTicks();
    }

    public void setPauseTicks(int pauseTicks) {
        travelCommon.setPauseTicks(pauseTicks);
    }

    public int getVillageIdleTicks() {
        return travelCommon.getVillageIdleTicks();
    }

    public void setVillageIdleTicks(int villageIdleTicks) {
        travelCommon.setVillageIdleTicks(villageIdleTicks);
    }

    public void startFollowing(EntityTraveller leader, int durationTicks) {
        this.followLeaderId = leader.getUniqueID();
        this.followTimeoutTicks = durationTicks;
    }

    public void stopFollowing() {
        this.followLeaderId = null;
        this.followTimeoutTicks = 0;
        this.getNavigator().clearPath();
    }

    public void tickFollowTimer() {
        if (followTimeoutTicks > 0) followTimeoutTicks--;
        else if (hasFollowLeader()) stopFollowing();
    }

    // Some path related getters
    public float getPathAngleBiasDeg() {
        return travelCommon.getPathAngleBiasDeg();
    }

    public double getPathLaneOffset() {
        return travelCommon.getPathLaneOffset();
    }

    public int getPathStepJitter() {
        return travelCommon.getPathStepJitter();
    }

    public int getRecalcPhase() {
        return travelCommon.getRecalcPhase();
    }

    public BlockPos weightedPickInRange(List<BlockPos> list, BlockPos origin,
                                        int from, int to, double bias) {
        if (from >= to) return null;
        double total = 0;
        double[] w = new double[to - from];
        for (int i = from; i < to; i++) {
            double d = Math.sqrt(origin.distanceSq(list.get(i))); // linear distance
            double weight;
            if (bias > 0) {        // near band: bias toward higher d within band
                weight = Math.max(0.001, d);
            } else if (bias < 0) { // far band: bias toward highest d within band
                weight = Math.max(0.001, (i - from + 1));
            } else {               // mid band: flat
                weight = 1.0;
            }
            w[i - from] = weight;
            total += weight;
        }
        double r = this.rand.nextDouble() * total;
        for (int k = 0; k < w.length; k++) {
            r -= w[k];
            if (r <= 0) return list.get(from + k);
        }
        return list.get(to - 1);
    }

    public boolean isAtTargetStop() { return travelCommon.isAtTargetStop(); }

    /**
     * Called by config system after a /travellerreload or GUI change.
     */
    public void onConfigReload() {
        // 1) Attributes
        getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(TravellersConfig.ATTRIBUTES.travellerMaxHealth);
        if (getHealth() > getMaxHealth()) setHealth(getMaxHealth());
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeed);
        getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(TravellersConfig.ATTRIBUTES.travellerFollowRange);
        if (getAttributeMap().getAttributeInstance(SharedMonsterAttributes.ATTACK_DAMAGE) == null) {
            getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        }
        getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(TravellersConfig.ATTRIBUTES.attackDamage);

        // 2) AI tunables
        if (aiDefendIfClose != null) aiDefendIfClose.setAssistRadius(TravellersConfig.COMBAT.helpRange);
        if (aiTravel != null) aiTravel.setSpeed(com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileTravel);
        if (aiFollowGroupLeader != null) aiFollowGroupLeader.setSpeed(com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileTravel);

    }

    public ItemStack makeGhostBow(ItemStack src) {
        return equipment.makeGhostBow(src);
    }

    public ItemStack makeGhostBlock(net.minecraft.block.state.IBlockState state) {
        return equipment.makeGhostBlock(state);
    }

    public boolean isGhostBlock(ItemStack s) {
        return equipment.isGhostBlock(s);
    }

    public boolean hasBowAnywherePublic() {
        return equipment.hasBowAnywhere();
    }

    public boolean notePlayerHitAndIsStillGrace(EntityPlayer p) {
        return combat.notePlayerHitAndIsStillGrace(p);
    }

    public void pruneGraceHits() {
        combat.pruneGraceHits();
    }

    public void maybePlayTravelAmbient() {

        travelCommon.maybePlayTravelAmbient();
    }

    public BlockPos getCurrentWaypoint() {
        return travelCommon.getCurrentWaypoint();
    }

    public void setCurrentWaypoint(BlockPos currentWaypoint) {
        travelCommon.setCurrentWaypoint(currentWaypoint);
    }

    /** Returns the immutable village-stop list for diagnostics. */
    public java.util.List<BlockPos> getVillageRoute() {
        return java.util.Collections.unmodifiableList(travelCommon.route);
    }

    /** Returns the current village-route cursor index, or -1 if inactive. */
    public int getVillageRouteIndex() {
        return travelCommon.routeIndex;
    }

    /** Returns a snapshot of the current travel route waypoints (read-only copy). */
    @Nullable
    public UUID getGroupLeaderUUID() {
        return groupLeaderUUID;
    }

    public void setGroupLeaderUUID(@Nullable UUID uuid) {
        this.groupLeaderUUID = uuid;
    }

    public java.util.List<BlockPos> getTravelRoute() {
        return java.util.Collections.unmodifiableList(travelCommon.route);
    }

    /**
     * Re-applies route state that advanced while the entity was virtualized.
     * Called by VirtualTravellerManager immediately after readFromNBT during materialization.
     */
    public void restoreVirtualRoute(java.util.List<BlockPos> route, int routeIndex) {
        travelCommon.route.clear();
        travelCommon.route.addAll(route);
        travelCommon.routeIndex = routeIndex;
        if (routeIndex >= 0 && routeIndex < route.size()) {
            travelCommon.setTargetVillage(route.get(routeIndex));
        }
        travelCommon.setReadyToDepart(true);
        travelCommon.setVillageIdleTicks(0);
        travelCommon.setVillageIdling(false);
        // Clear stale AI state from virtualization time
        travelCommon.setCurrentWaypoint(null);
        travelCommon.setDepartCooldownTicks(0);
    }

    /**
     * Returns true if this entity can safely be virtualized (removed from world and simulated offscreen).
     * Does NOT check player distance — that is the caller's responsibility.
     */
    public boolean canVirtualize() {
        return !isInCombat() && canVirtualizeIgnoringCombat();
    }

    /** Combat-agnostic eligibility — virtual battle participants may virtualize mid-fight. */
    public boolean canVirtualizeIgnoringCombat() {
        if (isDead) return false;
        if (isHandActive()) return false;
        if (isRiding()) return false;
        return true;
    }

    /**
     * Attempts to virtualize this entity via VirtualTravellerManager instead of despawning.
     * Returns true if successfully virtualized (entity is now dead and managed offscreen).
     * Returns false if virtualization is not possible (no valid world, not eligible, etc.).
     */
    private boolean tryVirtualize() {
        if (!(world instanceof net.minecraft.world.WorldServer)) return false;
        // Edge/distance despawn paths ignore combat state on purpose: a mob that merely has a
        // proximity-acquired target (e.g. a raider eyeing a stationary far-away player) must still
        // be able to leave the world instead of getting stuck forever at the view-distance border.
        if (!canVirtualizeIgnoringCombat()) return false;
        com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager mgr =
                com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager.get(
                        (net.minecraft.world.WorldServer) world);
        mgr.virtualizeTraveller((net.minecraft.world.WorldServer) world, this, world.getTotalWorldTime());
        return true;
    }


    public boolean isAmbientPlayingNow() {
        return isVoiceLockedNow() && voiceKindNow == VoiceKind.AMBIENT;
    }

    public boolean isChoirSinging() { return choirSinging; }

    public void setChoirSinging(boolean singing) { this.choirSinging = singing; }

    public void stopChoir() {
        choirSinging = false;
        voiceLockUntilTick = ticksExisted;
        voiceKindNow = VoiceKind.NONE;
        if (!world.isRemote) {
            com.jubitus.travellers.server.net.message.sounds.MsgStopTravellerVoice stop =
                    new com.jubitus.travellers.server.net.message.sounds.MsgStopTravellerVoice(this);
            NetworkRegistry.TargetPoint tp = new NetworkRegistry.TargetPoint(
                    dimension, posX, posY, posZ, 96.0);
            TravellerFollowNet.CHANNEL.sendToAllAround(stop, tp);
        }
    }

    public boolean isVoiceLockedNow() {
        return this.ticksExisted < voiceLockUntilTick;
    }

    void beginVoiceLock(int durationTicks, VoiceKind kind) {
        voiceLockUntilTick = Math.max(voiceLockUntilTick, this.ticksExisted + Math.max(1, durationTicks));
        voiceKindNow = kind;
    }

    // Called by server when client signals a choir sound finished (natural end or pause desync).
    // Resets the voice lock so the coordinator can detect the interruption and schedule a rejoin.
    public void interruptChoirVoiceLock() {
        if (!choirSinging) return;
        voiceLockUntilTick = ticksExisted;
        voiceKindNow = VoiceKind.NONE;
    }

    public void markPlayerRecentAggro(EntityPlayer p) {
        combat.markPlayerRecentAggro(p);
    }

    public int lastPlayerAggroAgeTicks(EntityPlayer p) {
        return combat.lastPlayerAggroAgeTicks(p);
    }

    boolean shouldHelloFor(EntityPlayer p) {
        Integer t = lastHelloTickByPlayer.get(p.getUniqueID());
        return t == null || (this.ticksExisted - t) >= HELLO_COOLDOWN_TICKS;
    }

    void markHello(EntityPlayer p) {
        lastHelloTickByPlayer.put(p.getUniqueID(), this.ticksExisted);
        // prune very old entries opportunistically
        if ((this.ticksExisted & 0xFF) == 0) {
            int now = this.ticksExisted;
            lastHelloTickByPlayer.entrySet().removeIf(e -> now - e.getValue() > (HELLO_COOLDOWN_TICKS * 4));
        }
    }

    void playHelloSound() {
        chooseVoicePersonalityIfNeeded();
        VoiceSet v = getActiveTravellerVoiceSet();

        SoundEvent hello = (v != null)
                ? v.pickHello(this.rand, ModSounds.TRAVELLER_HELLO)
                : ModSounds.TRAVELLER_HELLO;

        float vol = TravellersConfig.SOUND.travellerTalkVolume;
        float pitch = 1.0F + (this.rand.nextFloat() - 0.5F) * 0.02F;
        int dur = 30 + this.rand.nextInt(20);

        playVoiceMoving(hello, vol, pitch, dur, true, VoiceKind.TALK);
    }


    private void maybeRecruitDog() {
        if (dogScanCooldown > 0) {
            dogScanCooldown--;
            return;
        }
        dogScanCooldown = TravellersConfig.ATTRIBUTES.dogScanCooldownTicks;

        if (this.isInCombat()) return;
        if (getDog() != null) return; // already have one

        // Find a nearby *wild* wolf
        AxisAlignedBB box = this.getEntityBoundingBox().grow(6.0, 2.0, 6.0);
        List<EntityWolf> wolves = this.world.getEntitiesWithinAABB(EntityWolf.class, box,
                w -> w != null
                        && w.isEntityAlive()
                        && !w.isTamed()
                        && !w.isAngry()
                        && w.getLeashHolder() == null
        );

        if (wolves.isEmpty()) return;

        EntityWolf wild = wolves.get(0);

        // Convert wild wolf -> travellers dog
        EntityTravellerDog dog = new EntityTravellerDog(this.world);
        dog.setLocationAndAngles(wild.posX, wild.posY, wild.posZ, wild.rotationYaw, wild.rotationPitch);
        dog.enablePersistence();
        dog.setTamed(true);
        dog.setSitting(false);
        dog.setHealth(dog.getMaxHealth());
        dog.setOwnerId(this.getUniqueID()); // crucial: owner is the travellers
        dog.setCollarColor(collarForCulture(this.getCultureId())); // customize aesthetic

        // Optional: give it a name
        dog.setAlwaysRenderNameTag(false);

        // Spawn & remove the wild one
        if (!world.isRemote) {
            world.spawnEntity(dog);
            wild.setDead();
        }

        this.dogId = dog.getUniqueID();
    }

    private EnumDyeColor collarForCulture(String cultureId) {
        // quick mapping (tweak to taste or pull from your CultureRegistry)
        switch (cultureId) {
            case "fr":
                return EnumDyeColor.RED;
            case "en":
                return EnumDyeColor.BLUE;
            default:
                return EnumDyeColor.ORANGE;
        }
    }

    /**
     * Called by {@code EntityAITravel} when its own stuck-rescue escalation has repeatedly
     * failed to find any loaded destination to teleport/detour to — a precise signal that the
     * mob is pinned against the edge of the loaded/view area (rather than merely stuck on
     * terrain), since every rescue candidate ahead lives in a chunk that {@code isAreaLoaded}
     * refuses to path or teleport into. Distance-gated so this can never fire near a player:
     * only allowed once the mob is already past most of the server's view distance from every
     * player, mirroring the same "never force-load chunks" contract as the rescue calls that
     * led here. Workers are excluded — they rely on TravellerChunkManager to keep their route
     * loaded and must never virtualize this way.
     */
    public boolean tryVirtualizeStuckAtEdge() {
        if (this instanceof EntityTravellerMerchant || this instanceof EntityTravellerWorker) return false;
        if (this.world.isRemote || !(this.world instanceof WorldServer)) return false;
        if (this.isAutoEating() || this.isHandActive()) return false;

        final WorldServer ws = (WorldServer) this.world;
        int viewChunks;
        try {
            viewChunks = ws.getMinecraftServer().getPlayerList().getViewDistance();
        } catch (Throwable t) {
            viewChunks = TravellersConfig.DESPAWN.serverViewDistanceFallbackChunks;
        }
        // Require being close to the real edge (75% of view distance) before allowing this —
        // a mob merely stuck on terrain near a player must never vanish.
        double minDistBlocks = Math.max(TravellersConfig.DESPAWN.farMinDistanceBlocks, viewChunks * 16.0 * 0.75);
        if (ws.getClosestPlayer(this.posX, this.posY, this.posZ, minDistBlocks, false) != null) return false;

        if (tryVirtualize()) return true;

        // Fallback: clean fade (virtualization not available for some reason)
        EntityTravellerDog dog = getDog();
        if (dog != null && !dog.world.isRemote) {
            if (!(dog.isTamed() && dog.getOwner() instanceof EntityPlayer)) {
                dog.setDead();
            }
        }
        this.setDead();
        return true;
    }

    public boolean hasPlayerPassenger() {
        if (this.getPassengers().isEmpty()) return false;
        for (Entity e : this.getPassengers()) {
            if (e instanceof EntityPlayer) return true;
        }
        return false;
    }

    public boolean isSocialLooking() {
        return travellerCamp.isSocialLooking();
    }

    public void beginSocialLook(EntityTraveller peer, int ticks) {
        if (peer == null || peer == this) return;
        this.travellerCamp.socialPeer = peer;
        this.travellerCamp.socialTicks = Math.max(1, ticks);
        // small desync so they don't snap in perfect lockstep
        this.travellerCamp.socialCooldown = 40 + rand.nextInt(60);
    }

    public void endSocialLook() {
        travellerCamp.endSocialLook();
    }

    public void tickCampSocial() {

        // maintain active glance

        // (idle) occasionally start a new glance

        // ~2% per second: adjust to taste

        // don't yank someone who's already looking at another travellers (unless it's at us)

        // make it mutual most of the time

        // cooldown before starting another new glance
        travellerCamp.tickCampSocial();
    }

    public void setTravelTorchVisible(boolean on) {
        if (world.isRemote) return;

        ItemStack off = getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);

        // === HARD GLOBAL OFF SWITCH ===
        if (!TravellersConfig.TRAVEL.travelTorchEnabled) {
            // If a ghost torch is currently showing, kill it and clear cooldown
            if (!off.isEmpty() && isGhostTorch(off)) {
                // note: call super so we don't trigger backpack logic
                super.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
            travelTorchCooldown = 0;
            return; // absolutely no more torch logic when disabled
        }

        // === NORMAL BEHAVIOR WHEN ENABLED ===

        if (on) {
            // Don't show while fighting or doing something hand-active
            if (this.isInCombat() || this.isAutoEating()) return;
            // Don't show when idling at village or at a campfire
            if (travelCommon.isVillageIdling() || travellerCamp.getCampfirePos() != null) return;
            // Cooldown: don't re-enable immediately after we just turned it off
            int cd = TravellersConfig.TRAVEL.travelTorchCooldownTicks > 0
                    ? TravellersConfig.TRAVEL.travelTorchCooldownTicks
                    : TravellersConfig.TRAVEL.travelTorchCooldownTicksDefault;
            if (travelTorchCooldown > 0) return;

            // If another travellers nearby already has a torch, skip
            double rad = TravellersConfig.TRAVEL.travelTorchNearbyRadius > 0
                    ? TravellersConfig.TRAVEL.travelTorchNearbyRadius
                    : TravellersConfig.TRAVEL.travelTorchNearbyRadiusDefault;
            if (hasNearbyTravellerTorch(rad)) return;

            // Only equip if offhand is empty or already ghost torch
            if (off.isEmpty() || isGhostTorch(off)) {
                setItemStackToSlot(EntityEquipmentSlot.OFFHAND, makeGhostTorch());
            }
        } else {
            // Turning OFF: clear ghost torch and start cooldown
            if (!off.isEmpty() && isGhostTorch(off)) {
                setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);

                int cd = TravellersConfig.TRAVEL.travelTorchCooldownTicks > 0
                        ? TravellersConfig.TRAVEL.travelTorchCooldownTicks
                        : TravellersConfig.TRAVEL.travelTorchCooldownTicksDefault;
                travelTorchCooldown = cd;
            }
        }
    }

    public boolean isInCombat() {
        return combat.isInCombat();
    }

    public boolean isInPostCombatSilence() {
        return this.ticksExisted < combatSilenceUntilTick;
    }

    public void setInCombat(boolean inCombat) {
        this.combat.setInCombat(inCombat);
    }

    public void onCombatEntered() {
        if (autoEatTask != null) autoEatTask.cancel();
        equipment.ensureBestWeaponNow();
    }

    public void markCombatTickNow() {
        if (!world.isRemote) {
            this.dataManager.set(LAST_COMBAT_TICK, (int) world.getTotalWorldTime());
        }
    }

    protected float computeMoveSpeedCap(double base) {
        return combat.computeMoveSpeedCap(base);
    }

    public boolean isAutoEating() {
        return autoEatTask != null && autoEatTask.isEating();
    }

    public boolean isLowHealth() {
        float max = getMaxHealth();
        return max > 0 && getHealth() / max < TravellersConfig.COMBAT.lowHealthCombatFrac;
    }

    public boolean hasNearbyTravellerTorch(double radius) {
        if (world == null) return false;

        AxisAlignedBB box = this.getEntityBoundingBox().grow(radius, 2.0, radius);
        List<EntityTraveller> peers = world.getEntitiesWithinAABB(
                EntityTraveller.class,
                box,
                e -> e != null
                        && e != this
                        && e.isEntityAlive()
                        && e.hasTravelTorchVisible()
        );
        return !peers.isEmpty();
    }

    public ItemStack makeGhostTorch() {
        return equipment.makeGhostTorch();
    }

// --- companion dog spawn helpers ---

    public boolean hasTravelTorchVisible() {
        return isGhostTorch(getItemStackFromSlot(EntityEquipmentSlot.OFFHAND));
    }

    public boolean isVillageIdling() {  // you already store this
        return travelCommon.isVillageIdling();
    }

    public void setVillageIdling(boolean villageIdling) {
        travelCommon.setVillageIdling(villageIdling);
    }

    @Nullable
    public BlockPos getCampfirePos() {
        return travellerCamp.getCampfirePos();
    }

    private void maybeSpawnCompanionDog() {
        if (world.isRemote) return;
        if (suppressCompanionSpawn) return;                    // egg/command/etc.
        if (!TravellersConfig.SPAWN.spawnTravellerWithDog) return;
        if (this.dogId != null) return;                        // already has one?

        double chance = TravellersConfig.SPAWN.travellerDogSpawnChance; // or hard-code 0.35
        if (this.rand.nextDouble() >= chance) return;

        WorldServer ws = (WorldServer) this.world;
        BlockPos origin = this.getPosition();

        BlockPos dogPos = findSafeDogSpawnPos(ws, origin);
        if (dogPos == null) {
            // No safe place found nearby, just give up silently
            return;
        }

        EntityTravellerDog dog = new EntityTravellerDog(ws);
        dog.setPosition(dogPos.getX() + 0.5, dogPos.getY(), dogPos.getZ() + 0.5);

        dog.onInitialSpawn(ws.getDifficultyForLocation(dogPos), null);

// ✅ IMPORTANT: make it a real companion dog
        dog.enablePersistence();
        dog.setTamed(true);
        dog.setOwnerId(this.getUniqueID());
        dog.setSitting(false);
        dog.setHealth(dog.getMaxHealth());

        this.dogId = dog.getUniqueID();
        ws.spawnEntity(dog);

    }

    /**
     * Find a nearby, non-solid, ground-supported position where the dog
     * won’t spawn inside a block.
     */
    @Nullable
    private BlockPos findSafeDogSpawnPos(World world, BlockPos center) {
        int radius = 2;

        // Prefer around the travellers at same Y, then one block up
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (isSafeDogPos(world, pos)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSafeDogPos(World world, BlockPos pos) {
        BlockPos below = pos.down();

        IBlockState belowState = world.getBlockState(below);
        // Needs solid-ish floor to stand on
        if (!belowState.isSideSolid(world, below, EnumFacing.UP)) return false;

        IBlockState feet = world.getBlockState(pos);
        IBlockState head = world.getBlockState(pos.up());

        // Feet or head space must not be solid full blocks
        if (feet.getMaterial().isSolid() && feet.isFullCube()) return false;
        return !head.getMaterial().isSolid() || !head.isFullCube();
    }

    public int getTicksExisted() {
        return this.ticksExisted;
    }

    public int getFollowerCount() {
        return currentFollowers;
    }

    protected void onFollowerStarted() {
        currentFollowers++;
    }

    protected void onFollowerStopped() {
        if (currentFollowers > 0) currentFollowers--;
    }

    /**
     * Number of travellers currently following this one.
     * Uses followLeaderId directly (cheap) instead of calling getFollowLeader().
     */
    public int getCurrentFollowerCount() {
        UUID myId = this.getUniqueID();
        List<EntityTraveller> followers = this.world.getEntities(EntityTraveller.class, e ->
                e != null
                        && e.isEntityAlive()
                        && e.hasFollowLeader()
                        && myId.equals(e.followLeaderId)      // access is OK here: same class
        );
        return followers.size();
    }

    // Follower things
    public boolean hasFollowLeader() {
        return followLeaderId != null;
    }

    public boolean isDifferentCulture(EntityTraveller other) {
        return !isSameCulture(other);
    }

    public boolean isSameCulture(EntityTraveller other) {
        if (other == null) return false;
        String a = this.getCultureId();
        String b = other.getCultureId();
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equalsIgnoreCase(b);
    }

    private void ensureDismountIfInCombat() {
        if (this.world.isRemote) return;
        if (!this.isInCombat()) return;

        if (this.isRiding() && this.getRidingEntity() instanceof net.minecraft.entity.item.EntityBoat) {
            net.minecraft.entity.item.EntityBoat boat =
                    (net.minecraft.entity.item.EntityBoat) this.getRidingEntity();

            this.dismountRidingEntity();

            // If it's our travellers boat, it'll auto-despawn when empty
            // thanks to onUpdate() above. No need to force kill here,
            // but you can be explicit if you want:
            // if (boat instanceof EntityTravellerBoat) {
            //     boat.setDead();
            // }
        }
    }

    protected void removeTaskOfType(Class<? extends EntityAIBase> clazz) {
        for (Object obj : this.tasks.taskEntries.toArray()) {
            if (obj instanceof EntityAITasks.EntityAITaskEntry) {
                EntityAITasks.EntityAITaskEntry entry = (EntityAITasks.EntityAITaskEntry) obj;
                if (clazz.isInstance(entry.action)) {
                    this.tasks.removeTask(entry.action);
                }
            }
        }

        for (Object obj : this.targetTasks.taskEntries.toArray()) {
            if (obj instanceof EntityAITasks.EntityAITaskEntry) {
                EntityAITasks.EntityAITaskEntry entry = (EntityAITasks.EntityAITaskEntry) obj;
                if (clazz.isInstance(entry.action)) {
                    this.targetTasks.removeTask(entry.action);
                }
            }
        }
    }

    public boolean isInPlayerViewDistance() {
        return isInPlayerViewDistanceApprox();
    }

    public boolean isInPlayerViewDistanceApprox() {
        if (world == null) return true;
        if (world.isRemote) return true;

        if (!(world instanceof net.minecraft.world.WorldServer)) return true;
        net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;

        int viewChunks;
        try {
            viewChunks = ws.getMinecraftServer().getPlayerList().getViewDistance(); // server setting
        } catch (Throwable t) {
            viewChunks = 8; // fallback
        }

        // Add a small safety margin so you don't flap at the border
        int radiusBlocks = (viewChunks + 1) * 16;

        // true = some player is within the range where they'd normally be tracking
        return ws.getClosestPlayer(this.posX, this.posY, this.posZ, radiusBlocks, false) != null;
    }

    protected void clearAggroIfOffscreen() {
        if (!TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen) return;
        if (this.world.isRemote) return;
        if (this.isInPlayerViewDistance()) return;

        if ((this.ticksExisted & 15) != 0) { // 1 fois / 16 ticks
            if (this.getAttackTarget() != null) this.setAttackTarget(null);
            if (this.getRevengeTarget() != null) this.setRevengeTarget(null);
            this.setTravelRecoverTicks(0);
            return;
        }

        if (this.getAttackTarget() != null) this.setAttackTarget(null);
        if (this.getRevengeTarget() != null) this.setRevengeTarget(null);
        this.setTravelRecoverTicks(0);

        final double r = 24.0D; // réduit de 48 à 24
        AxisAlignedBB box = this.getEntityBoundingBox().grow(r, 8.0D, r);

        List<EntityLiving> attackers = this.world.getEntitiesWithinAABB(
                EntityLiving.class,
                box,
                e -> e != null && e.isEntityAlive() && e != this && e.getAttackTarget() == this
        );

        for (EntityLiving e : attackers) {
            if (e.getAttackTarget() == this) e.setAttackTarget(null);
            if (e.getRevengeTarget() == this) e.setRevengeTarget(null);
            e.getNavigator().clearPath();
        }
    }

    public ForgeChunkManager.Ticket getOrCreateTravelTicket() {
        if (world.isRemote) return null;
        if (travelTicket == null) {
            travelTicket = ForgeChunkManager.requestTicket(Travellers.INSTANCE, world, ForgeChunkManager.Type.ENTITY);
            if (travelTicket != null) travelTicket.bindEntity(this);
        }
        return travelTicket;
    }

    private void rejectTarget() {
        EntityLivingBase cur = this.getAttackTarget();
        if (cur != null) {
            AttackTargetLimiter.leave(this, cur);
            ignoreTarget(cur, 20 * 60);
        }

        super.setAttackTarget(null);
        if (!world.isRemote) {
            setInCombat(false);
            combat.resetChaseMemory();
            setRevengeTarget(null);
            getNavigator().clearPath();
        }
    }

    protected void markCombatNow() {
        combat.markCombatNow();
    }

    public boolean shouldRenderBackpack() {
        // Catchup travel: never show backpack (checked first, before style override)
        if (this instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this;
            if (w.isCatchupTravel()) return false;
        }

        IBackpackStyle style = BackpackRegistry.resolve(this);
        if (style != null && !style.isHideable()) return true;

        if (isBackpackHiddenByCombat()) return false;

        if (this instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this;

            // Hired: backpack UNIQUEMENT quand il "voyage"
            if (w.isHiredFollower()) {

                // Missions / déplacements réels
                if (w.isCatchupTravel()) return false;      // catchup quand trop loin
                if (w.isGoingHome()) return true;          // go home
                if (w.isGoOrderActive()) return true;      // go order
                if (w.isReturnUsingFollow()) return true;
                if (w.isWaitingForPickup()) return true;

                // Cas "travel AI" autonome, mais surtout PAS quand il est hired
                return w.isUsingTravelAI() && !w.isHiredFollower();
            }
        }

        // Non-hired: comportement normal (toujours visible sauf combat hide)
        return true;
    }

    public boolean isBackpackHiddenByCombat() {
        int last = this.dataManager.get(LAST_COMBAT_TICK);
        int now = (int) world.getTotalWorldTime();
        return (now - last) < COMBAT_HIDE_TICKS;
    }

    public EntityAITravel getTravelAI() {
        return this.aiTravel;
    }

    public boolean offscreenHopTowardPos(@Nullable BlockPos goal, int stepBlocks) {
        TravelCommon tc = this.getTravelCommon();

        if (this.isRiding() && this.getRidingEntity() instanceof EntityTravellerBoat) {
            Entity ride = this.getRidingEntity();
            this.dismountRidingEntity();
            if (ride instanceof EntityTravellerBoat) ((EntityTravellerBoat) ride).requestKill();
            else if (ride.getPassengers().isEmpty()) ride.setDead();
        }


        if (tc == null) return false;

        if (goal == null) return false;

        EntityAITravel ai = this.aiTravel;
        if (ai == null) return false;

        // 1) normal toward-goal hop
        if (ai.teleportToward(goal, stepBlocks)) return true;

        // 3) general surface rescue (bigger scan)
        if (ai.teleportToSurfaceRescue(24, 6)) return true;

        // 4) last-ditch: smaller step sometimes succeeds when big step doesn't
        return ai.teleportToward(goal, Math.max(8, stepBlocks / 2));
    }

    public TravelCommon getTravelCommon() {
        return this.travelCommon;
    }

    @Nullable
    public EntityLivingBase getSchedulerOwnerForPathing() {
        // For hired workers: bucket by the player owner (so 50 workers share one budget)
        if (this instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this;
            EntityPlayer owner = w.getFollowOwner();
            if (owner != null) return owner;
        }
        // Fallback: bucket by this entity
        return this;
    }

    /**
     * Throttled path build. Returns a Path (or null) but only if scheduler allows it.
     */
    @Nullable
    public Path getPathToEntityThrottled(EntityLivingBase target, int priority) {
        if (target == null || world == null) return null;

        // Emergency close range: never throttle
        if (this.getDistanceSq(target) <= EMERGENCY_ENGAGE_RANGE_SQ) {
            return this.getNavigator().getPathToEntityLiving(target);
        }

        EntityLivingBase schedOwner = getSchedulerOwnerForPathing();
        if (!PathScheduler.tryAcquire(world, schedOwner, this.getEntityId(), priority)) {
            return null;
        }
        return this.getNavigator().getPathToEntityLiving(target);
    }


    @Override
    protected void playStepSound(BlockPos pos, Block blockIn) {
        if (!TravellerCrowdSoundLimiter.allowStep(this)) {
            return;
        }
        super.playStepSound(pos, blockIn);
    }

    @Override
    protected void playHurtSound(DamageSource source) {
        Entity trueSrc = (source == null) ? null : source.getTrueSource();
        Entity immediateSrc = (source == null) ? null : source.getImmediateSource();

        if (trueSrc instanceof EntityPlayer || immediateSrc instanceof EntityPlayer) {
            super.playHurtSound(source);
            return;
        }

        if (!TravellerCrowdSoundLimiter.allowHurt(this)) {
            return;
        }

        super.playHurtSound(source);
    }

    /**
     * Throttled boolean path existence check with tiny per-tick cache.
     */
    public boolean hasPathToThrottled(EntityLivingBase target, int priority) {
        if (target == null || world == null) return false;

        long now = world.getTotalWorldTime();
        int tid = target.getEntityId();

        // Cheap cache: avoid recomputing multiple times in the same tick
        if (now == lastPathCheckTick && tid == lastPathCheckTargetId) {
            return lastPathCheckResult;
        }

        Path p = getPathToEntityThrottled(target, priority);
        boolean ok = (p != null);

        lastPathCheckTick = now;
        lastPathCheckTargetId = tid;
        lastPathCheckResult = ok;
        return ok;
    }
    // =======================
// Emergency engage bypass
// =======================

    public boolean shouldBypassAttackTargetLimiter(@javax.annotation.Nullable EntityLivingBase e) {
        return combat.shouldBypassAttackTargetLimiter(e);
    }



    /**
     * One call site for “set target, but obey limiter unless emergency”.
     * <p>
     * priority suggestion:
     * 0 = normal
     * 1 = “prefer” (ranged / ms emory)
     * 2 = urgent (but emergency bypass ignores limiter anyway)
     */
    public boolean trySetAttackTargetLimited(@javax.annotation.Nullable EntityLivingBase target, int priority) {
        if (target == null || !target.isEntityAlive()) return false;

        // Emergency / urgent priority bypasses limiter
        if (priority >= 2 || shouldBypassAttackTargetLimiter(target)) {
            this.setAttackTarget(target);
            this.setInCombat(true);
            return true;
        }
        int prio = AttackTargetLimiter.priorityFor(this);
        int dynamicMax = AttackTargetLimiter.getEffectiveMax(target, world.getTotalWorldTime());
        int effectiveMax = (this instanceof EntityTravellerWorker)
                ? Math.max(dynamicMax, ((EntityTravellerWorker) this).getCachedGroupSize() / 5)
                : dynamicMax;
        if (!AttackTargetLimiter.tryJoin(this, target, prio, effectiveMax)) {
            ignoreTarget(target, 30); // brief backoff — prevents frantic retry every scan tick
            return false;
        }

        this.setAttackTarget(target);
        this.setInCombat(true);
        return true;
    }

    // Keep the old signature too (so existing callers still compile)
    public boolean trySetAttackTargetLimited(@javax.annotation.Nullable EntityLivingBase target) {
        return trySetAttackTargetLimited(target, 0);
    }

    /**
     * Weighted “don’t shoot into a crowd” score around the *target*.
     * - Friendly travellers near the target add +1 each
     * - Players near the target add +2 each (stronger avoid)
     * Only used when the target is NOT a player.
     */
    public int rangedCrowdScoreAround(@Nullable EntityLivingBase target, double radius) {
        return combat.rangedCrowdScoreAround(target, radius);
    }

    protected void startWatchingFight(EntityLivingBase target, int ticks) {
        combat.startWatchingFight(target, ticks);
    }


// =======================
// Ranged target de-crowding
// =======================

    private void dropBoatIfOffscreen() {
        if (world.isRemote) return;

        if (!this.isInPlayerViewDistance() && this.isRiding()) {
            Entity ride = this.getRidingEntity();
            if (ride instanceof EntityTravellerBoat) {
                // 1) dismount FIRST (travellers side)
                this.dismountRidingEntity();

                // 2) kill boat safely + immediately
                ((EntityTravellerBoat) ride).killNow();
            }
        }
    }

    public enum VoiceKind {NONE, AMBIENT, TALK, HURT, CHOIR}

    public static final class SpawnData implements IEntityLivingData {
        public final String cultureId;

        public SpawnData(String id) {
            this.cultureId = id;
        }
    }

    public String getVoicePersonalityId() {
        if (this.dataManager == null) return "";
        String v = this.dataManager.get(VOICE_PERSONALITY_ID);
        return (v == null) ? "" : v;
    }

    private void setVoicePersonalityId(String id) {
        if (world.isRemote) return;
        String v = (id == null) ? "" : id;
        this.dataManager.set(VOICE_PERSONALITY_ID, v);
    }

    protected String pickVoicePersonalityId(String cultureId) {
        return CultureRegistry.pickRandomTravellerPersonalityId(cultureId, this.rand);
    }

    /**
     * Server-only. Choose ONE personality at spawn (and never change it again).
     * If no variants exist, we keep “” and fall back to old culture-based voices.
     */
    private void chooseVoicePersonalityIfNeeded() {
        if (world.isRemote) return;

        // Already chosen? Keep it.
        if (!getVoicePersonalityId().isEmpty()) return;

        String cid = getCultureId();
        String picked = pickVoicePersonalityId(cid);

        // picked == “” means “no variants” -> keep empty to use fallback behavior
        setVoicePersonalityId(picked);
    }

    /**
     * Resolve the VoiceSet to use:
     * - personality (if present and valid)
     * - else your old CultureRegistry.TRAVELLER_VOICES fallback
     */
    @javax.annotation.Nullable
    private VoiceSet getActiveTravellerVoiceSet() {
        return CultureRegistry.resolveTravellerVoiceSet(getCultureId(), getVoicePersonalityId());

    }


}