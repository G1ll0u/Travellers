package com.jubitus.travellers.entity.travellers;

import com.jubitus.travellers.world.ground.BlockRules;
import com.jubitus.travellers.world.visit.VillageVisitState;

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
import com.jubitus.travellers.entity.entityai.ambient.EntityAIAutoEat;
import com.jubitus.travellers.entity.entityai.ambient.EntityAIFishWhileTravel;
import com.jubitus.travellers.entity.entityai.ambient.EntityAIVillageVisit;
import com.jubitus.travellers.entity.entityai.ambient.EntityAIStopAndLookAtPlayer;
import com.jubitus.travellers.entity.entityai.combat.AttackTargetLimiter;
import com.jubitus.travellers.entity.entityai.combat.TravellerCombatLogic;
import com.jubitus.travellers.entity.entityai.combat.EntityAIAttackMeleeCustom;
import com.jubitus.travellers.entity.entityai.combat.TravellerItemDamage;
import com.jubitus.travellers.entity.equipment.TravellerEquipmentManager;
import com.jubitus.travellers.entity.entityai.combat.EntityAIDefendIfClose;
import com.jubitus.travellers.entity.entityai.combat.EntityAIRangedBowTravellerSafe;
import com.jubitus.travellers.entity.entityai.flight.ElytraFlightController;
import com.jubitus.travellers.entity.entityai.follow.FollowRecoveryHelper;
import com.jubitus.travellers.entity.entityai.follow.FollowRuntimeConfig;
import com.jubitus.travellers.entity.entityai.follow.PathScheduler;
import com.jubitus.travellers.entity.entityai.travel.EntityAIPassDoor;
import com.jubitus.travellers.entity.entityai.travel.TravellerDoorways;
import com.jubitus.travellers.entity.entityai.travel.EntityAITravel;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.entity.travellers.EntityTravellerBoat;
import net.minecraft.entity.passive.EntityHorse;
import com.jubitus.travellers.combat.TravellerHurtByTarget;
import com.jubitus.travellers.combat.compat.AncientWarfare2Compat;
import com.jubitus.travellers.combat.lists.TravellerBlacklist;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.sounds.MsgChoirSingerMute;
import com.jubitus.travellers.server.net.message.sounds.MsgPlayTravellerVoice;
import com.jubitus.travellers.server.net.message.sounds.MsgStopTravellerVoice;
import com.jubitus.travellers.world.mill.MillSettlements;
import com.jubitus.travellers.world.pathing.GapJumpEvaluator;
import com.jubitus.travellers.world.pathing.GapJumpResult;
import com.jubitus.travellers.world.pathing.PathBody;
import com.jubitus.travellers.world.pathing.TravellerNavigateGround;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.*;
import com.jubitus.travellers.entity.entityai.gaze.TravellerBodyHelper;
import com.jubitus.travellers.entity.entityai.gaze.Greetings;
import com.jubitus.travellers.entity.entityai.gaze.TravellerGaze;
import com.jubitus.travellers.entity.entityai.gaze.TravellerGreeter;
import com.jubitus.travellers.entity.entityai.gaze.TravellerLookHelper;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
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
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.millenaire.common.block.IBlockPath;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class EntityTraveller extends EntityCreature {

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
    // Hitbox at render scale 1.0 (config-driven); the live box is base * getRenderScale().
    // The biped model spans ~2.01 blocks from feet to top of head at scale 1.0, hence the default
    // height being taller than vanilla's 1.8 - it must contain the head, not just the body.
    // ---- GAP / CLIFF handling ----
    private static final String SWIM_BOOST_NAME = "TravellerSwimBoost";
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
    // Set only by /travellersdebug path test: a throwaway entity for comparing a mounted route
    // against a hand-marked one. Never persisted - it exists for one test run and is deleted by its
    // own watchdog, never handed off to the offscreen simulation.
    private boolean debugPathTest = false;
    // Configuration-ish knobs
    private static final DataParameter<String> CULTURE_ID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.STRING);

    // NEW: chosen voice personality (saved + synced)
    private static final DataParameter<String> VOICE_PERSONALITY_ID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.STRING);

    // UUID of this entity's bonded horse companion - must be synced (not just server-side), since the
    // client reads isMountedOnBondedMount() for its own local seat-position calculation every tick
    // (see getYOffset()), independent of whatever the last position sync packet said.
    private static final DataParameter<com.google.common.base.Optional<UUID>> MOUNT_UUID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.OPTIONAL_UNIQUE_ID);

    // NBT key (must match exactly in write/read)
    private static final String NBT_VOICE_PERSONALITY_ID = "VoicePersonalityId";


    private static final double EMERGENCY_ENGAGE_RANGE_SQ = EMERGENCY_ENGAGE_RANGE * EMERGENCY_ENGAGE_RANGE;
    public final TravellerCombatLogic combat = new TravellerCombatLogic(this);
    public final TravellerEquipmentManager equipment = new TravellerEquipmentManager(this);
    public final ElytraFlightController elytraFlight = new ElytraFlightController(this);
    public final ItemStackHandler inventory = new ItemStackHandler(18); // 18 slots example
    final TravelCommon travelCommon = new TravelCommon(this);
    public static final String ITINERARY_NBT_KEY = "Itin";
    // Current leg's itinerary + cursor, shared with the virtual record at virtualize/materialize.
    private com.jubitus.travellers.world.pathing.itinerary.ItineraryState itineraryState =
            new com.jubitus.travellers.world.pathing.itinerary.ItineraryState();
    public final com.jubitus.travellers.server.dialogue.memory.TravellerMemoryStore dialogueMemory =
            new com.jubitus.travellers.server.dialogue.memory.TravellerMemoryStore();
    private final int retargetPhase = this.rand.nextInt(10); // 0..9
    // per-player last hello tick (server-authoritative)
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
    com.jubitus.travellers.entity.entityai.follow.EntityAIFollowLeader aiFollowGroupLeader;

    // UUID of the group leader this entity follows (null = leader or solo)
    @Nullable private UUID groupLeaderUUID;

    /**
     * Chosen once, at group creation, for whichever entity is the group's leader - followers read
     * it off their resolved leader (loaded or virtual), never their own. Meaningless on a solo
     * traveller/raider or a follower (kept only so it round-trips cleanly through NBT/virtualize
     * regardless of role - simpler than a nullable field with role-dependent meaning).
     */
    private com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle groupFormationStyle =
            com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle.RING;

    public com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle getGroupFormationStyle() {
        return groupFormationStyle;
    }

    public void setGroupFormationStyle(com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle style) {
        this.groupFormationStyle = style == null
                ? com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle.RING : style;
    }

    /**
     * A grounded, deterministic reason for being on the road (identity-memory plan, Part L). Rolled
     * once, normally at {@link #onInitialSpawn}; {@code null} is the "unset" sentinel for an entity
     * loaded from a save predating this field, lazy-rolled on first read below (same pattern as
     * {@code TravellerMemoryStore.PlayerMemory.lastMetTick == -1}) - no world-wide migration pass.
     */
    @Nullable
    private TravelMotive travelMotive;

    public TravelMotive getTravelMotive() {
        if (travelMotive == null && !world.isRemote) {
            travelMotive = com.jubitus.travellers.server.dialogue.TravelMotiveDialogueContextProvider.rollMotive(this);
        }
        return travelMotive;
    }

    // UUID of this entity's bonded horse companion (null = on foot). A permanent 1:1 pairing, not a
    // group resource - see TravellerMountSupport for spawn/bond/anti-theft and MountedNavigator for
    // steering. Backed entirely by the MOUNT_UUID DataParameter (synced) - no separate server-only
    // field, so there is only one source of truth to keep consistent between the two sides.
    private int mountJumpCooldownTicks;

    // The bonded horse held as data (tied at an unloaded post) - see getPendingMountNbt().
    private NBTTagCompound pendingMountNbt;

    // What this rider currently wants to do with its bonded horse (ride it, lead it, leave it
    // standing). Persisted: an AI task that put the horse down must find it in the same state after a
    // reload, or the reconciler would silently re-mount a rider its own AI had deliberately put on
    // foot. Server-only - the client derives everything it needs from isRiding().
    private MountMode mountMode = MountMode.MOUNTED;

    // Last known full NBT of the bonded horse, refreshed while it's around. This is what makes
    // "a rider never loses its horse except by death" actually true rather than nearly true: if the
    // horse ever disappears without dying, it is rebuilt from here, identical, UUID included.
    @Nullable private NBTTagCompound mountSnapshotNbt;

    // Transient reconciler counters (see TravellerMountSupport.tickMount): consecutive ticks the
    // bonded horse could not be found in the world, and ticks spent trying to walk back to it.
    private int mountMissingTicks;
    private int remountTicks;

    // Transient (never persisted): mirrors isMountedOnBondedMount() from the previous tick, purely so
    // TravellerMountSupport.tickMount can detect the exact tick a bonded mount stops being
    // ridden and check, once, whether that was because the horse actually died (clear mountUUID so a
    // future mount roll/GUI assignment isn't blocked by a dangling reference) versus a deliberate
    // dismount that keeps the bond (planned future feature) - never inferred from "not riding" alone.
    private boolean wasRidingBondedMountLastTick;

    public boolean wasRidingBondedMountLastTick() {
        return wasRidingBondedMountLastTick;
    }

    public void setWasRidingBondedMountLastTick(boolean value) {
        wasRidingBondedMountLastTick = value;
    }

    @Nullable
    public UUID getMountUUID() {
        return this.dataManager.get(MOUNT_UUID).orNull();
    }

    public void setMountUUID(@Nullable UUID mountUUID) {
        this.dataManager.set(MOUNT_UUID, com.google.common.base.Optional.fromNullable(mountUUID));
    }

    public int getMountJumpCooldownTicks() {
        return this.mountJumpCooldownTicks;
    }

    public void setMountJumpCooldownTicks(int ticks) {
        this.mountJumpCooldownTicks = ticks;
    }

    /** The bonded horse held as data while no live copy exists (tied at a post whose chunk unloaded);
     * written only through {@link #setParkedMount}. Moved into the record when the rider goes offscreen. */
    @Nullable
    public NBTTagCompound getPendingMountNbt() {
        return pendingMountNbt;
    }

    /**
     * Never set this directly from an AI task - go through
     * {@code TravellerMountSupport.setMountMode}, which also performs the transition (mounting,
     * leashing, stepping off onto solid ground) instead of just recording the wish.
     */
    public MountMode getMountMode() {
        return mountMode;
    }

    public void setMountModeRaw(MountMode mode) {
        this.mountMode = mode;
    }

    // Where the horse held as data in pendingMountNbt stands (tied at a village post); null = beside
    // the rider. See TravellerMountSupport.tickParkedMount.
    @Nullable
    private BlockPos pendingMountPos;

    @Nullable
    public BlockPos getPendingMountPos() {
        return pendingMountPos;
    }

    /** Holds the horse as data, parked at {@code pos} (null = with the rider), or clears it. */
    public void setParkedMount(@Nullable NBTTagCompound nbt, @Nullable BlockPos pos) {
        this.pendingMountNbt = nbt;
        this.pendingMountPos = nbt == null ? null : pos;
    }

    // The current village stay (see VillageVisitState); moved to/from the virtual record at hand-off.
    @Nullable
    private VillageVisitState villageVisitState;

    @Nullable
    public VillageVisitState getVillageVisitState() {
        return villageVisitState;
    }

    public void setVillageVisitState(@Nullable VillageVisitState state) {
        this.villageVisitState = state;
    }

    // Bumped every time this rider's horse is (re)created from data: any physical copy carrying an older
    // number is a stale twin (see MountBondLoadHandler).
    private int mountGeneration;

    public int getMountGeneration() {
        return mountGeneration;
    }

    public void setMountGeneration(int generation) {
        this.mountGeneration = generation;
    }

    @Nullable
    public NBTTagCompound getMountSnapshotNbt() {
        return mountSnapshotNbt;
    }

    public void setMountSnapshotNbt(@Nullable NBTTagCompound nbt) {
        this.mountSnapshotNbt = nbt;
    }

    public int getMountMissingTicks() {
        return mountMissingTicks;
    }

    public void setMountMissingTicks(int ticks) {
        this.mountMissingTicks = ticks;
    }

    /** True while EntityAITravel is executing - the one discriminator
     * {@code TravellerMountSupport.applyMountedSpeed} needs to pick between the travel and follow
     * mounted speed multipliers (a rider crossing the world alone rides faster than one holding
     * formation). Transient, maintained by EntityAITravel's startExecuting/resetTask. */
    private boolean travelSpeedContext;

    public boolean isTravelSpeedContext() {
        return travelSpeedContext;
    }

    public void setTravelSpeedContext(boolean value) {
        this.travelSpeedContext = value;
    }

    /** Yaw the current mounted jump was launched along, or NaN when not mid-jump. Transient: a jump
     * never outlives a tick boundary that matters. See TravellerMoveHelper. */
    private float mountJumpYaw = Float.NaN;

    public float getMountJumpYaw() {
        return mountJumpYaw;
    }

    public void setMountJumpYaw(float yaw) {
        this.mountJumpYaw = yaw;
    }

    /** Consecutive ticks {@code TravellerMountSupport.isMountWedged} has read true in a row. Transient
     * (never persisted, reset the instant the horse is no longer wedged) - see
     * TravellerMountSupport.trySidestepIfWedged. */
    private int mountWedgedTicks = 0;

    public int getMountWedgedTicks() {
        return mountWedgedTicks;
    }

    public void setMountWedgedTicks(int ticks) {
        this.mountWedgedTicks = ticks;
    }

    /**
     * Yaw captured at the moment the current wedge episode began, {@code NaN} while not wedged.
     * Transient, never persisted.
     *
     * <p>The sidestep and reactive-jump direction used to be recomputed every tick from the horse's
     * live {@code rotationYaw} - but that yaw is itself derived from the bearing to the steering
     * target, which swings noticeably from just the small position jitter a stuck horse still has
     * (a trace caught a ~19 degree swing from under a block of z-jitter). Chasing that live bearing
     * made the escalation reverse itself mid-attempt instead of committing to one side. Freezing the
     * yaw for the whole episode fixes that; see TravellerMountSupport.trySidestepIfWedged.
     */
    private float mountWedgeAnchorYaw = Float.NaN;

    public float getMountWedgeAnchorYaw() {
        return mountWedgeAnchorYaw;
    }

    public void setMountWedgeAnchorYaw(float yaw) {
        this.mountWedgeAnchorYaw = yaw;
    }

    /**
     * Tick {@code TravellerMountSupport} last put this rider on foot for a "the horse genuinely
     * cannot make it through here" reason - {@code -1} while not. Transient, never
     * persisted. Because it doesn't survive a save, {@code writeEntityToNBT} deliberately never
     * persists {@code MountMode.LED} either (it writes {@code MOUNTED}) - otherwise a rider reloaded
     * as LED with this timer reset to {@code -1} would be stuck on foot forever, since the
     * time+distance heal below only runs while the timer is armed.
     *
     * <p>Two separate call sites land in this same fallback ({@code MountMode.LED}): a
     * footprint-blocked route, and giving up on sidesteps against a wedge. Both need the same
     * time+distance self-heal back to {@code MOUNTED}, done once in {@code TravellerMountSupport}'s
     * {@code reconcileOnFoot} rather than duplicated per call site - see
     * {@code TravellerMountSupport.markTerrainFallbackLed}.
     */
    private int ledFallbackSinceTick = -1;
    private double ledFallbackX, ledFallbackZ;

    public int getLedFallbackSinceTick() {
        return ledFallbackSinceTick;
    }

    public void setLedFallbackSinceTick(int tick) {
        this.ledFallbackSinceTick = tick;
    }

    public double getLedFallbackX() {
        return ledFallbackX;
    }

    public double getLedFallbackZ() {
        return ledFallbackZ;
    }

    public void setLedFallbackPos(double x, double z) {
        this.ledFallbackX = x;
        this.ledFallbackZ = z;
    }

    /**
     * Consecutive ticks the dismounted rider and its bonded horse have <em>both</em> been on dry
     * solid ground and close enough to remount (see {@code TravellerMountSupport.reconcileOnFoot}).
     * The remount only fires once this reaches {@code MOUNTS.remountStableTicks} - a shelving bank
     * where the horse touches bottom for a tick or two never reaches it. Transient, never persisted.
     */
    private int remountReadyTicks = 0;

    public int getRemountReadyTicks() {
        return remountReadyTicks;
    }

    public void setRemountReadyTicks(int ticks) {
        this.remountReadyTicks = ticks;
    }

    /** While this rider leads its horse on foot: consecutive ticks the horse has stayed past
     * {@code MOUNTS.ledStuckRecallDistance} without closing the gap. Past
     * {@code MOUNTS.ledStuckRecallTicks} the horse is brought to the rider - see
     * {@code TravellerMountSupport.reconcileOnFoot}. Transient, reset whenever the mode leaves LED. */
    private int ledHorseStuckTicks = 0;
    /** Smallest rider-horse gap (squared) seen this led-stuck episode, to tell "closing" from
     * "snagged". Transient. */
    private double ledHorseBestGapSq = Double.MAX_VALUE;

    public int getLedHorseStuckTicks() {
        return ledHorseStuckTicks;
    }

    public void setLedHorseStuckTicks(int ticks) {
        this.ledHorseStuckTicks = ticks;
    }

    public double getLedHorseBestGapSq() {
        return ledHorseBestGapSq;
    }

    public void setLedHorseBestGapSq(double gapSq) {
        this.ledHorseBestGapSq = gapSq;
    }

    /** Throttles the near-village LED-trigger scan (see {@code TravellerMountSupport.tickMount}) so
     * a bonded rider doesn't rescan the settlement list every single tick; {@link #cachedNearVillage}
     * holds the answer between rescans. Both transient. */
    private int nextVillageProximityCheckTick = 0;
    private boolean cachedNearVillage = false;

    public int getNextVillageProximityCheckTick() {
        return nextVillageProximityCheckTick;
    }

    public void setNextVillageProximityCheckTick(int tick) {
        this.nextVillageProximityCheckTick = tick;
    }

    public boolean isCachedNearVillage() {
        return cachedNearVillage;
    }

    public void setCachedNearVillage(boolean nearVillage) {
        this.cachedNearVillage = nearVillage;
    }

    // --- Lead trail: this rider's recent ground positions - a led horse follows in single file
    // instead of cutting straight at the rider and weaving (TravellerMountSupport.driveLedHorse), and
    // a mounted group's followers retrace it when their own straight-line steer fails on rough terrain
    // instead of guessing blind (EntityAIFollowLeader.tryFollowLeaderTrail, MountedFollowPacer) - the
    // leader already proved that ground passable a few ticks ago. Transient (loaded-only), maintained
    // once per tick whenever this rider has a bonded horse, mounted or leading it on foot alike;
    // cleared on a teleport hop or losing the bond.
    private static final int LEAD_TRAIL_MAX = 64;
    private final java.util.ArrayDeque<Vec3d> leadTrail = new java.util.ArrayDeque<>();
    private int ledPacerObstacleTicks = 0;
    private int ledPacerNavUntilTick = 0;
    private double ledPacerSmoothedRiderSpeed = 0.0;

    /** Appends the rider's current position to the lead trail (deduped, capped). */
    public void recordLeadTrailPoint() {
        Vec3d head = leadTrail.peekFirst();
        if (head != null) {
            double dx = head.x - posX, dz = head.z - posZ;
            if (dx * dx + dz * dz < 0.0036D) return; // < 0.06 blocks moved: nothing new
        }
        leadTrail.addFirst(new Vec3d(posX, posY, posZ));
        while (leadTrail.size() > LEAD_TRAIL_MAX) leadTrail.removeLast();
    }

    public void clearLeadTrail() {
        leadTrail.clear();
    }

    public int getLeadTrailSize() {
        return leadTrail.size();
    }

    /**
     * The point on the lead trail roughly {@code blocks} of walked distance behind the rider - what a
     * led horse aims at so it treads the same ground the rider just did. Returns the oldest recorded
     * point when the trail is shorter than that, or {@code null} when it is empty.
     */
    @Nullable
    public Vec3d leadTrailPointBehind(double blocks) {
        if (leadTrail.isEmpty()) return null;
        double acc = 0.0D;
        Vec3d prev = null;
        Vec3d last = null;
        for (Vec3d p : leadTrail) { // iterates newest -> oldest
            last = p;
            if (prev != null) {
                double dx = p.x - prev.x, dz = p.z - prev.z;
                acc += Math.sqrt(dx * dx + dz * dz);
                if (acc >= blocks) return p;
            }
            prev = p;
        }
        return last;
    }

    public int getLedPacerObstacleTicks() {
        return ledPacerObstacleTicks;
    }

    public void setLedPacerObstacleTicks(int ticks) {
        this.ledPacerObstacleTicks = ticks;
    }

    public int getLedPacerNavUntilTick() {
        return ledPacerNavUntilTick;
    }

    public void setLedPacerNavUntilTick(int tick) {
        this.ledPacerNavUntilTick = tick;
    }

    public double getLedPacerSmoothedRiderSpeed() {
        return ledPacerSmoothedRiderSpeed;
    }

    public void setLedPacerSmoothedRiderSpeed(double v) {
        this.ledPacerSmoothedRiderSpeed = v;
    }

    /** How many mount sidesteps have fired close together in time - see
     * TravellerMountSupport.trySidestepIfWedged. Transient, never persisted. */
    private int sidestepStreak = 0;
    /** {@code ticksExisted} the last sidestep fired at, or 0 before the first one. Transient. */
    private int lastSidestepTick = 0;

    public int getSidestepStreak() {
        return sidestepStreak;
    }

    public void setSidestepStreak(int streak) {
        this.sidestepStreak = streak;
    }

    public int getLastSidestepTick() {
        return lastSidestepTick;
    }

    public void setLastSidestepTick(int tick) {
        this.lastSidestepTick = tick;
    }

    public int getRemountTicks() {
        return remountTicks;
    }

    public void setRemountTicks(int ticks) {
        this.remountTicks = ticks;
    }

    /** True while actually riding the horse this entity is bonded to (not some other vehicle/horse). */
    public boolean isMountedOnBondedMount() {
        UUID mountUUID = getMountUUID();
        if (mountUUID == null || !isRiding()) return false;
        Entity ride = getRidingEntity();
        return ride instanceof EntityHorse && ride.getUniqueID().equals(mountUUID);
    }

    /**
     * Sets the body yaw for a purely cosmetic turn (looking at a fire, facing a fishing spot, keeping
     * the body aligned with the direction of travel).
     *
     * <p><b>Refuses to do anything while mounted, and that is the whole point.</b> On a horse,
     * {@code rotationYaw} is not decoration - {@code AbstractHorse.travel()} reads the rider's
     * {@code rotationYaw} and steers by it, so it is the steering wheel. Any code writing it outside
     * the move helper is driving the horse somewhere the path never asked for. That is exactly what
     * {@code TravelCommon.syncBodyYawWhileTravelling()} was doing: mounted, {@code Entity.updateRidden()}
     * zeroes the rider's motion every tick, so that method always fell through to its "aim at the raw
     * travel waypoint" branch - and since the little motion the rider regenerates points along its
     * current yaw, the result was a self-reinforcing lock that never turned at all. Measured in a real
     * log: the horse pointed 30 degrees away from the path node it was being steered toward on average,
     * more than 30 degrees off half the time, and stalled dead against terrain 8% of all ticks.
     *
     * <p>Aiming a bow is deliberately NOT routed through here: a mounted archer turning its horse to
     * face a target is exactly what a player does, and vanilla propagates it the same way.
     */
    public void setBodyYaw(float yaw) {
        if (isMountedOnBondedMount()) return;
        this.rotationYaw = yaw;
    }

    /**
     * Every follow/travel rescue teleport in this mod goes through {@code setPositionAndUpdate}
     * (see {@code FollowRecoveryHelper.teleportWorker}, {@code EntityAIFollowLeader.tryHopTowardTarget},
     * {@code EntityAITravel}'s surface-rescue hops, {@code MaterializePositionResolver.unstickEntitiesInVolume}...).
     * None of them know about mounts - left alone, a rescue would move only the rider, and
     * {@code AbstractHorse.updatePassenger()} would snap it straight back onto the horse's own
     * position the very next tick (the horse's position always wins - it's the vehicle, the rider is
     * just carried). Overriding here once, centrally, fixes every call site instead of auditing each
     * one: the horse gets the same teleport, the rider gets it as always, and they land in the same
     * place together.
     */
    private String buildingLabel(double x, double y, double z) {
        com.jubitus.travellers.world.mill.MillFootprints.Footprint f =
                com.jubitus.travellers.world.mill.MillFootprints.at(world, x, y, z, 0);
        return f == null ? "-" : f.planKey();
    }

    @Override
    public void setPositionAndUpdate(double x, double y, double z) {
        // Every rescue/catch-up hop in the mod funnels through here, so this one line records them
        // all with both ends - which is what makes "it teleported a block underground" or "it
        // teleported and came straight back" answerable from a trace instead of from memory.
        if (!world.isRemote) {
            if (com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(getUniqueID())) {
                com.jubitus.travellers.debug.trace.TravellerTracer.event(this, "teleport",
                        String.format(java.util.Locale.ROOT, "from=%.2f,%.2f,%.2f to=%.2f,%.2f,%.2f dist=%.1f bldgFrom=%s bldgTo=%s",
                                posX, posY, posZ, x, y, z,
                                Math.sqrt((x - posX) * (x - posX) + (y - posY) * (y - posY) + (z - posZ) * (z - posZ)),
                                buildingLabel(posX, posY, posZ), buildingLabel(x, y, z)));
            }
            // A hop breaks the continuity of the lead trail: a led horse must not try to walk the
            // straight line across the gap. Drop it and let it re-form from here.
            double hop = (x - posX) * (x - posX) + (z - posZ) * (z - posZ);
            if (hop > 16.0D) clearLeadTrail();
        }
        // A horse being led on a leash must come along for the same reason a ridden one must: every
        // one of those rescue hops exists to get this entity out of somewhere it was stuck, and
        // leaving its horse behind is how a rider ends up permanently separated from it. A hitched
        // horse is deliberately left alone - staying put is the entire point of that mode.
        if (!isMountedOnBondedMount() && getMountMode() == MountMode.LED) {
            TravellerMountSupport.teleportLedMountWith(this, x, y, z);
        }
        if (isMountedOnBondedMount()) {
            Entity ride = getRidingEntity();
            ride.setPositionAndUpdate(x, y, z);
            // A teleport must not carry over whatever momentum the horse had built up right before
            // it fired (most often a stuck/stagnation rescue, i.e. exactly when the horse was
            // fighting an obstacle and had a large motion vector). Left alone, physics applies that
            // leftover motion on the very next tick on top of the new position - reading as a huge,
            // erratic one-tick jump right after what should have been a clean, short hop.
            ride.motionX = 0;
            ride.motionY = 0;
            ride.motionZ = 0;
            this.motionX = 0;
            this.motionY = 0;
            this.motionZ = 0;
        }
        super.setPositionAndUpdate(x, y, z);
    }

    /**
     * Vanilla's own seat-height hook (read by {@code AbstractHorse}/{@code Entity#updatePassenger}) -
     * matches the value a mounted player uses (-0.35) by default, scaled by this individual's own
     * render scale roll so a shorter-rolled traveller doesn't float above a horse a taller one would
     * sit correctly on. 0 the rest of the time, same as vanilla's default for every other entity.
     *
     * <p>Applies to riding any {@code EntityHorse}, not just a bonded one - this is a visual seat
     * correction, not a bonding concern. A palfrenier's cosmetic taming ride ({@code StableHandTask},
     * plain {@code startRiding}, never bonded) needs the exact same correction or it sits floating
     * above the horse.
     */
    @Override
    public double getYOffset() {
        if (getRidingEntity() instanceof EntityHorse) {
            return TravellersConfig.MOUNTS.mountedSeatYOffset * getRenderScale();
        }
        return super.getYOffset();
    }
    // Which village/lone building this traveller/raider "belongs to" for population accounting —
    // mirrors EntityTravellerWorker.homeVillageCenter. Deliberately never used for AI/steering, only
    // so TravellerSoftSpawner can tell a village's spawned population apart from a neighbour's once
    // it has wandered off and materialized far from home (see population-overshoot fix, 2026-08-05).
    @Nullable private BlockPos homeVillage;

    @Nullable
    public BlockPos getHomeVillage() {
        return this.homeVillage;
    }

    public void setHomeVillage(@Nullable BlockPos pos) {
        this.homeVillage = pos;
    }
    // How far ahead tickEdgeVirtualizeProbe looks for loaded ground, clamped to the remaining distance.
    private static final int EDGE_PROBE_MIN_BLOCKS = 16;
    private static final int EDGE_PROBE_MAX_BLOCKS = 32;
    // Consecutive ticks spent facing unloaded terrain, and the tick the probe last ran (it is driven
    // from both the travel AI and the generic per-tick path, and must only count once per tick).
    private int edgeBlockedTicks = 0;
    private long edgeProbeLastTick = -1L;
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
    private int stuckTargetTicks = 0;
    private double lastTargetDistSq = -1.0D;
    @Nullable
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

        updateSizeForRenderScale();
        this.enablePersistence(); // make them persist in world
        this.setCanPickUpLoot(true);
        this.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        this.setLeftHanded(false);
        this.stepHeight = 1.0F;
        // Vanilla's move helper turns up to 90 degrees per tick, which AbstractHorse.travel() copies
        // straight onto a horse's body. See TravellerMoveHelper - identical to vanilla on foot.
        this.moveHelper = new com.jubitus.travellers.world.pathing.TravellerMoveHelper(this);
        this.gaze = new TravellerGaze(this);
        this.greeter = new TravellerGreeter(this);
        ObfuscationReflectionHelper.setPrivateValue(EntityLiving.class, this, new TravellerLookHelper(this, gaze), "field_70749_g");
    }

    private TravellerGaze gaze;
    private TravellerGreeter greeter;

    public TravellerGreeter getGreeter() {
        return greeter;
    }

    /** What the head looks at when no AI task asks for something; see {@link TravellerLookHelper}. */
    public TravellerGaze getGaze() {
        return gaze;
    }

    @Override
    protected EntityBodyHelper createBodyHelper() {
        return new TravellerBodyHelper(this);
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

    // A group follower has no trip of its own: it follows its leader, or catches up to it.
    public boolean isRouteLocked() {
        return groupLeaderUUID != null || catchupTravel;
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

    // getCanSpawnHere() override removed — travellers no longer register a vanilla biome spawn entry,
    // they spawn exclusively through the virtual population controller (TravellerSoftSpawner), which
    // never calls this. See CLAUDE.md / Travellers.java preInit for the removal.

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



    public boolean isGhostRod(ItemStack s) {
        return isFishingRodLike(s) && equipment.isGhostRod(s);
    }

    public boolean isFishingRodLike(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemFishingRod;
    }

    public ItemStack makeGhostRod(ItemStack src) {
        return equipment.makeGhostRod(src);
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

    // Helper: clamp angular delta and step toward a target
    public static float rotlerp(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    // Find a dry standable Y near XZ (no chunk loads)
    @Nullable
    public BlockPos topStandable(BlockPos guess) {

        return travelCommon.topStandable(guess);
    }

    public boolean isAtTargetStopHys(boolean lastWasInside) {
        return travelCommon.isAtTargetStopHys(lastWasInside);
    }

    /** Whether this unit stops to visit each village of its trip ({@link EntityAIVillageVisit}). */
    public boolean usesVillageVisit() {
        return TravellersConfig.VILLAGE_VISIT.enabledTravellers;
    }

    @Nullable
    private EntityAIVillageVisit villageVisitAI;

    @Nullable
    public EntityAIVillageVisit getVillageVisitAI() {
        return villageVisitAI;
    }

    protected void setVillageVisitAI(@Nullable EntityAIVillageVisit ai) {
        this.villageVisitAI = ai;
    }

    // Who turned the head during the last tick, for the trace ("gaze", "syncBody"...). Debug only.
    private String headWriters = "";

    public void noteHeadWriter(String who) {
        headWriters = headWriters.isEmpty() ? who : headWriters + "+" + who;
    }

    public String getHeadWriters() {
        return headWriters.isEmpty() ? "-" : headWriters;
    }

    /** The running AI task holding the movement bit, or null when nothing moves the traveller. */
    @Nullable
    public EntityAIBase getRunningMovementTask() {
        for (EntityAITasks.EntityAITaskEntry entry : tasks.taskEntries) {
            if (entry.using && (entry.action.getMutexBits() & 1) != 0) return entry.action;
        }
        return null;
    }

    public boolean hasHeadWriterThisTick() {
        return !headWriters.isEmpty();
    }

    /** What the gaze is on, for the trace. */
    public String describeGaze() {
        return gaze != null ? gaze.describe() : "-";
    }

    /** True while the village visit task is actually running (not merely the persisted idling flag). */
    public boolean isVisitingVillage() {
        return villageVisitAI != null && villageVisitAI.isRunning();
    }

    @Nullable
    public EntityTraveller getLoadedGroupLeader() {
        UUID id = getGroupLeaderUUID();
        if (id == null || !(world instanceof WorldServer)) return null;
        Entity e = ((WorldServer) world).getEntityFromUuid(id);
        return e instanceof EntityTraveller && e.isEntityAlive() ? (EntityTraveller) e : null;
    }

    /** A group follower standing in the village its loaded leader is currently staying in: it visits
     * on its own instead of following, until the leader starts to leave. */
    public boolean canDisperseWithLeader() {
        if (!TravellersConfig.VILLAGE_VISIT.followersDisperse || !usesVillageVisit()) return false;
        BlockPos stop = getLeaderVisitStop();
        if (stop == null) return false;
        com.jubitus.travellers.world.mill.VillageVisitIndex.Settlement s =
                com.jubitus.travellers.world.mill.VillageVisitIndex.get(world, stop);
        if (s == null) return false;
        double dx = posX - (s.center.getX() + 0.5D);
        double dz = posZ - (s.center.getZ() + 0.5D);
        double r = Math.max(s.radius, TravellersConfig.VILLAGE_VISIT.arrivalRadius);
        return dx * dx + dz * dz <= r * r;
    }

    /** The village the group leader is staying in right now, loaded or offscreen; null otherwise. */
    @Nullable
    public BlockPos getLeaderVisitStop() {
        UUID id = getGroupLeaderUUID();
        if (id == null) return null;
        EntityTraveller leader = getLoadedGroupLeader();
        if (leader != null) {
            EntityAIVillageVisit visit = leader.getVillageVisitAI();
            return visit != null && visit.isStaying() ? visit.getVisitStop() : null;
        }
        if (!(world instanceof WorldServer)) return null;
        com.jubitus.travellers.world.virtualization.VirtualTravellerRecord r =
                com.jubitus.travellers.world.virtualization.VirtualTravellerManager.get((WorldServer) world).getRecord(id);
        VillageVisitState st = r == null ? null : r.visit;
        return st != null && !st.isLeaving() && !st.asFollower ? st.stop : null;
    }

    /** Group followers leave their follow AI alone while dispersed in a village (or finishing that visit). */
    public boolean isDispersedInVillage() {
        if (getGroupLeaderUUID() == null) return false;
        return isVisitingVillage() || canDisperseWithLeader();
    }

    /** The settlement this unit is about to visit (its own next stop, or its leader's), or null. */
    @Nullable
    public BlockPos getUpcomingVisitStop() {
        if (!usesVillageVisit()) return null;
        EntityTraveller ref = this;
        if (getGroupLeaderUUID() != null) {
            ref = getLoadedGroupLeader();
            if (ref == null || !ref.usesVillageVisit()) return null;
        }
        BlockPos stop = ref.getTargetVillage();
        if (stop == null || stop.equals(ref.getTravelCommon().getVisitedStop())) return null;
        return stop;
    }

    // The settlement whose edge this rider hitched its horse at, to visit it on foot. A HITCHED horse
    // carrying this marker is never recalled; see TravellerMountSupport.reconcileOnFoot.
    @Nullable
    private BlockPos villageHitchStop;

    @Nullable
    public BlockPos getVillageHitchStop() {
        return villageHitchStop;
    }

    public void setVillageHitchStop(@Nullable BlockPos stop) {
        this.villageHitchStop = stop;
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


        aiMelee = new EntityAIAttackMeleeCustom(this, com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileAttacking, true, 0.00);
        this.tasks.addTask(4, aiMelee);

        aiFollowGroupLeader = new com.jubitus.travellers.entity.entityai.follow.EntityAIFollowLeader(
                this, com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileTravel, 1.0F, 1.0F);
        this.tasks.addTask(5, aiFollowGroupLeader);

        aiDefendIfClose = new EntityAIDefendIfClose(this, TravellersConfig.COMBAT.attackTriggerRange, true);

        this.targetTasks.addTask(1, aiDefendIfClose);

        this.targetTasks.addTask(2, new TravellerHurtByTarget(this, true, TravellersConfig.COMBAT.helpRange));
        autoEatTask = new EntityAIAutoEat(this, TravellersConfig.COMBAT.eatHealthThreshold);
        this.tasks.addTask(8, autoEatTask);

        if (com.jubitus.travellers.config.TravellersConfig.CAMP_EVENTS.enabled) {
            // New camp event system: keep occupants at their slot (build is handled by the manager).
            this.tasks.addTask(6, new com.jubitus.travellers.entity.entityai.ambient.camp.EntityAICampStay(this));
        }

        this.tasks.addTask(9, new EntityAIStopAndLookAtPlayer(this));
        if (TravellersConfig.TRAVEL.fishWhileTravelEnabled) {
            this.tasks.addTask(22, new EntityAIFishWhileTravel(this));
        }

        // Utility / travel
        this.tasks.addTask(23, new EntityAIPassDoor(this));

        villageVisitAI = new EntityAIVillageVisit(this);
        this.tasks.addTask(25, villageVisitAI);

        aiTravel = new EntityAITravel(this, com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileTravel, TravellersConfig.ATTRIBUTES.stepBlocks);
        this.tasks.addTask(27, aiTravel);
    }

    // Entity Attributes
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        applyConfiguredAttributes();
    }

    // INVARIANT: the only place unit attributes come from config; subclasses override the configured* values, never this.
    private void applyConfiguredAttributes() {
        getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(configuredMaxHealth());
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(TravellersConfig.SPEED.movementSpeed);
        getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(configuredFollowRange());
        if (getAttributeMap().getAttributeInstance(SharedMonsterAttributes.ATTACK_DAMAGE) == null) {
            getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        }
        getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(TravellersConfig.ATTRIBUTES.attackDamage);
    }

    // Called from the super constructor: overrides must only read config, never instance fields.
    protected double configuredMaxHealth() {
        return TravellersConfig.ATTRIBUTES.travellerMaxHealth;
    }

    protected double configuredFollowRange() {
        return TravellersConfig.ATTRIBUTES.travellerFollowRange;
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
        if (world != null && TravellersDerivedConfig.isPeacefulBlocked(world)) {
            super.setAttackTarget(null);
            setInCombat(false);
            return;
        }
        // A creative/spectator player is never a valid target, regardless of what triggered this
        // call (revenge, call-for-help, proactive scan...) - this is the single choke point every
        // setAttackTarget caller goes through via virtual dispatch, so checking it here once covers
        // all of them instead of every individual target-selector task re-deriving it.
        if (target instanceof EntityPlayer) {
            EntityPlayer tp = (EntityPlayer) target;
            if (tp.isCreative() || tp.isSpectator()) {
                super.setAttackTarget(null);
                setInCombat(false);
                return;
            }
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

        // Never target a friendly unit's bonded horse (see isFriendlyBondedMount): stops owner-assist
        // / defend AIs from mobbing a mount they can't damage.
        if (target != null && !world.isRemote && isFriendlyBondedMount(target)) {
            ignoreTarget(target, 20 * 30);
            rejectTarget();
            return;
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
            // target is never a creative/spectator player here - rejected earlier above.
            {
                // If target is close (<=8 blocks) AND we have no current target,
                // ignore the limiter and allow engagement.
                boolean bypass = shouldBypassAttackTargetLimiter(target);

                int prio = AttackTargetLimiter.priorityFor(this);
                if (!bypass) {
                    int effectiveMax = computeEffectiveAttackerCap(target);

                    if (!AttackTargetLimiter.tryJoin(this, target, prio, effectiveMax)) {
                        if (TravellersConfig.COMPAT.ancientWarfare2DebugLog
                                && AncientWarfare2Compat.isFactionNpc(target)) {
                            net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                                    "[Travellers/AW2] %s FAILED to join attacker slot on %s#%s (cap=%d, current=%d)",
                                    this.getName(), target.getName(), target.getUniqueID(),
                                    effectiveMax, AttackTargetLimiter.getAttackerCount(target));
                        }
                        ignoreTarget(target, 30);
                        startWatchingFight(target, 60);
                        if (this.getRevengeTarget() == target) this.setRevengeTarget(null);
                        setInCombat(false);
                        getNavigator().clearPath();
                        return;
                    } else if (TravellersConfig.COMPAT.ancientWarfare2DebugLog
                            && AncientWarfare2Compat.isFactionNpc(target)) {
                        net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                                "[Travellers/AW2] %s joined attacker slot on %s#%s (cap=%d, now=%d)",
                                this.getName(), target.getName(), target.getUniqueID(),
                                effectiveMax, AttackTargetLimiter.getAttackerCount(target));
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
                com.jubitus.travellers.world.battle.VirtualBattleManager.get((net.minecraft.world.WorldServer) world)
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
        this.dataManager.register(MOUNT_UUID, com.google.common.base.Optional.absent());
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
        // Edge probe for EntityAIFollowLeader followers only - see tickEdgeVirtualizeProbe's class
        // doc. getEdgeProbeGoal() returns null for everyone else, making this a no-op for them.
        if (!world.isRemote && tickEdgeVirtualizeProbe(getEdgeProbeGoal())) return;
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
        // isInCombat() reads TravellerCombatLogic.inCombat, a plain unsynced field that is only ever
        // set true server-side - on the client it reads permanently false. Without this guard, this
        // block ran on EVERY client tick whenever isAimingBow() was true (the client's own local,
        // always-"not in combat" view), calling resetActiveHand() and wiping the vanilla bow-draw
        // state (activeItemStack/activeItemStackUseCount) right back to empty every tick - undoing
        // updateActiveHand()'s progress (and its empty->non-empty heal above) before it could ever
        // stick, which is why the bow never visibly showed as drawn. setAimingBow(false) itself is a
        // no-op on the client anyway (guarded); the client must only ever mirror the server's synced
        // AIMING_BOW/HAND_STATES, never decide independently to reset them.
        if (!world.isRemote && !isInCombat()) {
            if (isAimingBow()) {
                // Only cancel if the thing we're using is a bow, so eating stays intact
                ItemStack hand = this.getHeldItemMainhand();
                if (!hand.isEmpty() && hand.getItem() instanceof ItemBow) {
                    resetActiveHand();
                }
                setAimingBow(false);
            }
        }
        // Same class of bug as above: this is server-authoritative chase-forgiveness bookkeeping
        // (LOS/proximity timers, threat memory, owner-assist state) with no valid client-side reading -
        // left unguarded, it ran every client tick too and could just as easily call resetActiveHand().
        if (!world.isRemote) {
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
        if (this.isRiding() && (this.getRidingEntity() instanceof EntityTravellerBoat
                || isMountedOnBondedMount())) {
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
            compound.setTag("TravellerInv", inventory.serializeNBT());
            if (travelCommon.getTargetVillage() != null)
                compound.setTag("TargetVillage", NBTUtil.createPosTag(travelCommon.getTargetVillage()));
            if (travelCommon.getCurrentWaypoint() != null)
                compound.setIntArray("CurrentWaypoint", new int[]{travelCommon.getCurrentWaypoint().getX(), travelCommon.getCurrentWaypoint().getY(), travelCommon.getCurrentWaypoint().getZ()});
            if (travelCommon.getRoamTarget() != null)
                compound.setIntArray("RoamTarget", new int[]{travelCommon.getRoamTarget().getX(), travelCommon.getRoamTarget().getY(), travelCommon.getRoamTarget().getZ()});

            compound.setInteger("LastCombatTick", this.dataManager.get(LAST_COMBAT_TICK));
            compound.setInteger("PauseTicks", travelCommon.getPauseTicks());
            compound.setInteger("VillageIdleTicks", travelCommon.getVillageIdleTicks());
            compound.setBoolean("VillageIdling", travelCommon.isVillageIdling());
            compound.setInteger("RouteIndex", travelCommon.routeIndex);
            itineraryState.writeNbt(compound, ITINERARY_NBT_KEY);
            compound.setInteger("TextureIdx", getTextureIndex());
            compound.setFloat("RenderScale", getRenderScale());
            compound.setFloat("Wanderlust", travelCommon.wanderlust);
            compound.setFloat("ExploreChance", travelCommon.exploreChance);
            if (travelCommon.recentDestinations.length > 0) {
                compound.setIntArray("RecentDests",
                        com.jubitus.travellers.entity.entityai.travel.travelcommon.RecentDestinationRing
                                .toIntPairs(travelCommon.recentDestinations));
            }
            compound.setFloat("HopPreference", travelCommon.hopPreference);
            compound.setFloat("PathAngleBiasDeg", travelCommon.getPathAngleBiasDeg());
            compound.setDouble("PathLaneOffset", travelCommon.getPathLaneOffset());
            compound.setInteger("PathStepJitter", travelCommon.getPathStepJitter());
            compound.setInteger("RecalcPhase", travelCommon.getRecalcPhase());
            compound.setBoolean("ReadyToDepart", travelCommon.isReadyToDepart());
            compound.setInteger("DepartCooldown", travelCommon.getDepartCooldownTicks());
            if (travelCommon.getVisitedStop() != null) {
                compound.setLong("VisitedStop", travelCommon.getVisitedStop().toLong());
            }
            if (villageHitchStop != null) compound.setLong("VillageHitchStop", villageHitchStop.toLong());
            compound.setString("CultureId", getCultureId());
            compound.setString(NBT_VOICE_PERSONALITY_ID, getVoicePersonalityId());
            if (groupLeaderUUID != null) compound.setUniqueId("GroupLeaderUUID", groupLeaderUUID);
            compound.setString("GroupFormationStyle", groupFormationStyle.name());
            if (travelMotive != null) compound.setString("TravelMotive", travelMotive.name());
            if (getMountUUID() != null) compound.setUniqueId("MountUUID", getMountUUID());
            compound.setInteger("MountGen", mountGeneration);
            if (pendingMountPos != null) compound.setLong("PendingMountPos", pendingMountPos.toLong());
            if (villageVisitState != null) compound.setTag(VillageVisitState.NBT_KEY, villageVisitState.write());
            if (pendingMountNbt != null) compound.setTag("PendingMountNbt", pendingMountNbt);
            // LED is a purely transient reconciler state (terrain fallback, water dismount) whose own
            // recovery tracking (ledFallbackSinceTick) is NOT persisted - so a rider saved as LED
            // would reload with the mode stuck and nothing left to heal it back (seen in the field: a
            // traveller leading its horse forever with the horse standing right next to it). Persist
            // MOUNTED instead; if the ground here really is still impassable, the next failed path
            // re-arms LED properly on load. HITCHED is a stable intent with its own distance-based
            // recall and is kept.
            // INVARIANT: LED is never persisted.
            MountMode savedMode = (mountMode == MountMode.LED) ? MountMode.MOUNTED : mountMode;
            compound.setString("MountMode", savedMode.name());
            if (mountSnapshotNbt != null) compound.setTag("MountSnapshot", mountSnapshotNbt);
            if (homeVillage != null) {
                compound.setInteger("HomeVillageX", homeVillage.getX());
                compound.setInteger("HomeVillageY", homeVillage.getY());
                compound.setInteger("HomeVillageZ", homeVillage.getZ());
            }

            NBTTagList lst = new NBTTagList();
            for (BlockPos p : travelCommon.route) {
                lst.appendTag(new NBTTagIntArray(new int[]{p.getX(), p.getY(), p.getZ()}));
            }
            compound.setTag("RouteVillages", lst);

            compound.setTag("DialogueMemory", dialogueMemory.toNbt());

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
        // Vanilla persists the elytra-flying flag ("FallFlying"), but the flight controller's state is
        // purely in memory - anything restored from NBT (chunk load, and every materialize, whose NBT
        // was frozen at virtualize time while the record kept moving) would otherwise come back
        // airborne with nothing steering it. Cleared unconditionally: an "if flying" guard here would
        // just be a slower way of writing the same thing.
        this.setElytraFlying(false);
        this.elytraFlight.reset();
        // Unconditional replace, never merge (CLAUDE.md's virtualization trap: frozen NBT vs. a live
        // entity's own state must never mix) - an absent tag (pre-Phase-3 save, or never talked to
        // anyone) decodes to "no memories", which is already the right answer.
        this.dialogueMemory.loadFrom(compound.hasKey("DialogueMemory")
                ? compound.getCompoundTag("DialogueMemory") : new NBTTagCompound());
        if (compound.getBoolean("LeftHanded")) this.setLeftHanded(false);
        if (compound.hasKey("LastCombatTick")) {
            this.dataManager.set(LAST_COMBAT_TICK, compound.getInteger("LastCombatTick"));
        }
        if (!this.world.isRemote) {
            this.spawnAmbientCooldownTicks = Math.max(this.spawnAmbientCooldownTicks, 4 * 20); // at least ~4s
        }
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
        if (compound.hasKey("RecentDests", 11)) {
            travelCommon.recentDestinations =
                    com.jubitus.travellers.entity.entityai.travel.travelcommon.RecentDestinationRing
                            .fromIntPairs(compound.getIntArray("RecentDests"));
        }
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
        itineraryState.readNbt(compound, ITINERARY_NBT_KEY);
        travelCommon.setReadyToDepart(compound.getBoolean("ReadyToDepart"));
        travelCommon.setDepartCooldownTicks(compound.getInteger("DepartCooldown"));
        travelCommon.setVisitedStop(compound.hasKey("VisitedStop") ? BlockPos.fromLong(compound.getLong("VisitedStop")) : null);
        villageHitchStop = compound.hasKey("VillageHitchStop") ? BlockPos.fromLong(compound.getLong("VillageHitchStop")) : null;
        groupLeaderUUID = compound.hasUniqueId("GroupLeaderUUID") ? compound.getUniqueId("GroupLeaderUUID") : null;
        if (compound.hasKey("GroupFormationStyle")) {
            try {
                groupFormationStyle = com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle
                        .valueOf(compound.getString("GroupFormationStyle"));
            } catch (IllegalArgumentException ignored) {
                groupFormationStyle = com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle.RING;
            }
        } else {
            groupFormationStyle = com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle.RING;
        }
        // null (unset) is a real, valid state here (see the field doc) - an old save with no tag
        // lazy-rolls on first read rather than getting a migration pass.
        if (compound.hasKey("TravelMotive")) {
            try {
                travelMotive = TravelMotive.valueOf(compound.getString("TravelMotive"));
            } catch (IllegalArgumentException ignored) {
                travelMotive = null;
            }
        } else {
            travelMotive = null;
        }
        setMountUUID(compound.hasUniqueId("MountUUID") ? compound.getUniqueId("MountUUID") : null);
        mountGeneration = compound.getInteger("MountGen");
        pendingMountPos = compound.hasKey("PendingMountPos") ? BlockPos.fromLong(compound.getLong("PendingMountPos")) : null;
        // Always replaced, never merged: a stay that ended must not survive in a stale snapshot.
        villageVisitState = VillageVisitState.read(compound.hasKey(VillageVisitState.NBT_KEY)
                ? compound.getCompoundTag(VillageVisitState.NBT_KEY) : null);
        pendingMountNbt = compound.hasKey("PendingMountNbt") ? compound.getCompoundTag("PendingMountNbt") : null;
        mountMode = MountMode.byName(compound.getString("MountMode"));
        mountSnapshotNbt = compound.hasKey("MountSnapshot") ? compound.getCompoundTag("MountSnapshot") : null;
        if (compound.hasKey("HomeVillageX")) {
            homeVillage = new BlockPos(
                    compound.getInteger("HomeVillageX"),
                    compound.getInteger("HomeVillageY"),
                    compound.getInteger("HomeVillageZ"));
        } else {
            homeVillage = null;
        }
    }

    @Override
    protected void dropLoot(boolean wasRecentlyHit, int lootingModifier, DamageSource source) {
        // Player-gifted gear always comes back, and must leave the slots before vanilla's per-slot roll.
        equipment.dropPlayerGiftEquipment();
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
            if (travelTorchCooldown > 0) travelTorchCooldown--;  // NEW

            tickHazardRepulsion();
            if (getMountUUID() != null) recordLeadTrailPoint();
            else clearLeadTrail();
            TravellerMountSupport.tickMount(this);
            tickVirtualGroupLeaderFollow();
            tickCatchupExit();
            gaze.tickAwareness();
            greeter.tick();
            com.jubitus.travellers.debug.trace.TravellerTracer.tick(this);
            com.jubitus.travellers.debug.profile.MovementProfiler.tick(this);
            headWriters = "";
        }

        float headBeforeAi = rotationYawHead;
        super.onLivingUpdate();
        if (!world.isRemote && headWriters.isEmpty() && rotationYawHead != headBeforeAi) noteHeadWriter("vanilla");

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

        if (world.isRemote) tickElytraBank();
        if (world.isRemote && hurtAnimCooldown > 0) hurtAnimCooldown--;
        if (speakCooldown > 0) speakCooldown--;
        if (world.isRemote) {
            if (forcedSwingTicks > 0) forcedSwingTicks--;
        }
        if (!world.isRemote) {
            equipment.tickPickupCooldown();
        }

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

                // A unit that visits villages leaves a stop only once its visit is over; the others
                // move on as soon as they reach it and are marked ready.
                boolean leave = usesVillageVisit()
                        ? curStop.equals(travelCommon.getVisitedStop())
                        : travelCommon.isAtTargetStop() && !travelCommon.isVillageIdling() && travelCommon.isReadyToDepart();

                if (leave) {
                    travelCommon.setVisitedStop(null);
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
            String villageCid = com.jubitus.travellers.world.spawn.SpawnCulture.pickNear(this.world, this.getPosition(), this.rand);
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
        if (!world.isRemote) {
            travelMotive = com.jubitus.travellers.server.dialogue.TravelMotiveDialogueContextProvider.rollMotive(this);
        }

        // Reset move attr base and make sure no swim boost lingers
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.setBaseValue(com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeed);
            AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
            if (m != null) attr.removeModifier(m);
        }

        if (!world.isRemote) {
            // Raiders/workers re-roll their texture index against their own appearance pool right
            // after this method returns (their pool differs from the plain traveller pool used
            // above), then call initSpawnEquipment themselves — calling it here too would read
            // equipment tied to the wrong (pre-reroll) appearance and be immediately redone anyway.
            if (!(this instanceof EntityTravellerRaider) && !(this instanceof EntityTravellerWorker)) {
                equipment.initSpawnEquipment(c, this.rand);
            }

            // 3) Seed ambient window if needed
            if (nextTravelAmbientTick <= 0 || nextTravelAmbientTick == Integer.MAX_VALUE) {
                scheduleNextTravelAmbient();
            }
            // 4) Assign culture-based name from Millénaire namelists.
            // Raiders re-pick their own (possibly different) culture right after this method
            // returns and re-name themselves from it — naming here first would leave a stale
            // name if the raider's final culture has no usable name pool of its own.
            if (!(this instanceof EntityTravellerRaider)) {
                String fullName = CultureRegistry.makeTravellerNameForCulture(cid, this.rand);
                if (fullName != null && !fullName.isEmpty()) {
                    this.setCustomNameTag(fullName);
                    this.setAlwaysRenderNameTag(false);
                }
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
        // Dialogue master switch (server-side, off by default - see TravellersConfig.DIALOGUE and
        // the dialogue system's D-9): on, this replaces every canned message below with the real
        // dialogue GUI, regardless of whether this specific player has configured Ollama yet - the
        // server can't know that, so the client itself decides what to show once the GUI is open.
        if (com.jubitus.travellers.config.TravellersConfig.DIALOGUE.enabled) {
            com.jubitus.travellers.server.dialogue.DialogueContext context =
                    com.jubitus.travellers.server.dialogue.DialogueContextBuilder.build(this, player);
            // Built from state as of BEFORE this visit (metPlayerBefore reflects prior encounters,
            // never this one) - marked met only after the context is built.
            dialogueMemory.markMet(player.getUniqueID(), world.getTotalWorldTime());
            com.jubitus.travellers.server.net.handler.TravellerFollowNet.sendToPlayer(player,
                    new com.jubitus.travellers.server.net.message.dialogue.MsgOpenTravellerDialogue(
                            this.getEntityId(), context));
            return true;
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

    // Vanilla scales the cull distance by hitbox size, so small rolled scales vanished early and a rider before its horse.
    @SideOnly(Side.CLIENT)
    @Override
    public boolean isInRangeToRenderDist(double distanceSq) {
        double d = TravellersConfig.ENTITY_RENDER_LOD.entityRenderDistance * getRenderDistanceWeight();
        return distanceSq < d * d;
    }

    public float getRenderScale() {
        return this.dataManager.get(RENDER_SCALE);
    }

    public void setRenderScale(float scale) {
        this.dataManager.set(RENDER_SCALE, scale);
    }

    /**
     * Resizes the hitbox so it matches the rendered model, which is drawn at
     * {@link #getRenderScale()} in {@link com.jubitus.travellers.client.render.RenderTraveller}.
     * Driven from {@link #notifyDataManagerChange} so both sides (server set + client sync) resize.
     * Sleeping keeps its own tiny box until wake-up.
     */
    public void updateSizeForRenderScale() {
        if (isSleepingInBed()) return;
        float s = getRenderScale();
        setSize(TravellersConfig.ATTRIBUTES.hitboxWidth * s, TravellersConfig.ATTRIBUTES.hitboxHeight * s);
    }

    @Override
    public void notifyDataManagerChange(DataParameter<?> key) {
        super.notifyDataManagerChange(key);
        if (RENDER_SCALE.equals(key)) updateSizeForRenderScale();
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

    /**
     * Also drives vanilla's real "using an item" state (setActiveHand/resetActiveHand) whenever a
     * bow is held, not just our own render-pose flag. This is what makes third-party ambient-sound
     * mods (e.g. Dynamic Surroundings) and the bow item's own string-pull texture react correctly -
     * both key off EntityLivingBase.isHandActive()/LivingEntityUseItemEvent, which a private flag
     * alone is invisible to. Safe for a non-player entity: ItemBow's release logic (arrow creation)
     * is guarded by "instanceof EntityPlayer" and its use duration is a fixed 72000 ticks, so nothing
     * auto-fires or auto-completes from just holding the state.
     */
    public void setAimingBow(boolean v) {
        if (world.isRemote) return;
        this.dataManager.set(AIMING_BOW, v);
        if (v) {
            ItemStack hand = getHeldItemMainhand();
            if (!hand.isEmpty() && hand.getItem() instanceof ItemBow && !isHandActive()) {
                setActiveHand(EnumHand.MAIN_HAND);
            }
        } else if (isHandActive() && getActiveItemStack().getItem() instanceof ItemBow) {
            resetActiveHand();
        }
    }

    /**
     * Vanilla bug, client-side only, confirmed via {@code /travellersdebug bow status} +
     * {@code MixinLayerHeldItem}'s render-time log: {@code EntityLivingBase.notifyDataManagerChange}
     * captures {@code activeItemStack} from {@code getHeldItem(getActiveHand())} at the instant the
     * HAND_STATES sync packet is processed - if that happens before the separate equipment sync packet
     * has applied the held item (both fire the same server tick from {@link #setAimingBow}, no ordering
     * guarantee between the two packet types), it captures EMPTY. Vanilla's own {@code updateActiveHand()}
     * then has no way back: {@code ForgeHooks.canContinueUsing(EMPTY, realItem)} is false forever, so the
     * real item is never adopted, and {@code resetActiveHand()} skips touching HAND_STATES on the client
     * (guarded to the server), so {@code isHandActive()} stays true forever too. Net effect: the hand
     * reads as "active" holding nothing, permanently - the item's own use-progress (bow pull/nock, etc.)
     * never advances, though everything server-side (and the arm pose, which reads our own AIMING_BOW
     * flag) is unaffected. Treat "was empty, now isn't" as a valid continuation instead of a break; the
     * rest of the method is unmodified vanilla.
     */
    @Override
    protected void updateActiveHand() {
        if (this.isHandActive()) {
            ItemStack itemstack = this.getHeldItem(this.getActiveHand());

            boolean healingFromEmpty = this.activeItemStack.isEmpty() && !itemstack.isEmpty();
            if (net.minecraftforge.common.ForgeHooks.canContinueUsing(this.activeItemStack, itemstack)
                    || healingFromEmpty) {
                this.activeItemStack = itemstack;
                if (healingFromEmpty) {
                    // activeItemStackUseCount is stale (0, from the broken state) - without this it
                    // underflows to -1 a few lines below in this same call, which fires
                    // onItemUseFinish() -> resetActiveHand() and immediately undoes the heal.
                    this.activeItemStackUseCount = itemstack.getMaxItemUseDuration();
                }
            }

            if (itemstack == this.activeItemStack) {
                if (!this.activeItemStack.isEmpty()) {
                    this.activeItemStackUseCount = net.minecraftforge.event.ForgeEventFactory.onItemUseTick(
                            this, this.activeItemStack, this.activeItemStackUseCount);
                    if (this.activeItemStackUseCount > 0) {
                        this.activeItemStack.getItem().onUsingTick(this.activeItemStack, this, this.activeItemStackUseCount);
                    }
                }

                if (this.getItemInUseCount() <= 25 && this.getItemInUseCount() % 4 == 0) {
                    this.updateItemUse(this.activeItemStack, 5);
                }

                if (--this.activeItemStackUseCount <= 0 && !this.world.isRemote) {
                    this.onItemUseFinish();
                }
            } else {
                this.resetActiveHand();
            }
        }
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
        BlockPos bedPos = this.dataManager.get(SLEEPING_BED_POS);
        this.dataManager.set(SLEEPING, false);
        updateSizeForRenderScale();
        // trySleepInBed parks the entity on the bed's own collision box; restoring the full-size
        // hitbox there leaves it standing on the bed block or clipped into an adjacent wall/ceiling
        // (suffocating, unable to path out). Step it onto a real standable floor cell beside the bed,
        // exactly like a bed death-respawn does.
        if (bedPos != null && !BlockPos.ORIGIN.equals(bedPos)) relocateOffBed(bedPos);
    }

    /**
     * Steps the just-woken entity off the bed onto a real floor cell in front of it. Always moves
     * (the sleep anchor sits on the bed's own partial collision box, which is never a valid standing
     * spot - restoring the full hitbox there clips it into the bed block, an adjacent wall or a low
     * ceiling and it can't path out). Foot-side of the bed first (where you'd step out), then head,
     * then diagonals, then one Y lower; only as an absolute last resort does it stand on the bed
     * itself - never higher, so an indoor bed never sends it to the roof.
     */
    private void relocateOffBed(BlockPos bedHead) {
        BlockPos foot = bedHead;
        IBlockState st = world.getBlockState(bedHead);
        if (st.getBlock() instanceof BlockBed && st.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
            foot = bedHead.offset(st.getValue(BlockHorizontal.FACING).getOpposite());
        }
        BlockPos[] centers = { foot, bedHead };
        for (int dy = 0; dy >= -1; dy--) {
            for (BlockPos c : centers) {
                for (EnumFacing d : EnumFacing.HORIZONTALS) {
                    BlockPos p = c.offset(d);
                    if (p.equals(bedHead) || p.equals(foot)) continue;
                    if (standAt(p.up(dy))) return;
                }
            }
            for (BlockPos c : centers) {
                for (EnumFacing d : EnumFacing.HORIZONTALS) {
                    if (standAt(c.offset(d).offset(d.rotateY()).up(dy))) return;
                }
            }
        }
        standAt(bedHead.up());
    }

    private boolean standAt(BlockPos p) {
        if (!world.getBlockState(p.down()).isSideSolid(world, p.down(), EnumFacing.UP)) return false;
        double cx = p.getX() + 0.5D, cy = p.getY(), cz = p.getZ() + 0.5D;
        if (!isPositionClear(cx, cy, cz)) return false;
        this.setPositionAndUpdate(cx, cy, cz);
        this.motionX = this.motionY = this.motionZ = 0.0D;
        return true;
    }

    /** True if this entity's full (render-scaled) hitbox fits, collision- and liquid-free, with its
     * feet at (x,y,z). Used by duty AI snap-hops to pick a landing cell that won't wedge the body. */
    public boolean fitsStandingAt(double x, double y, double z) {
        return isPositionClear(x, y, z);
    }

    private boolean isPositionClear(double x, double y, double z) {
        AxisAlignedBB box = new AxisAlignedBB(
                x - this.width / 2.0D, y, z - this.width / 2.0D,
                x + this.width / 2.0D, y + this.height, z + this.width / 2.0D).contract(0.02D, 0.0D, 0.02D);
        return world.getCollisionBoxes(this, box).isEmpty() && !world.containsAnyLiquid(box);
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

    /** Every entity re-checks its own hazard scan on a different tick, spreading the cost. */
    private static final int HAZARD_TICK_BUCKETS = 4;

    /**
     * Continuous, cheap safety nudge away from lava/fire/configured hazard blocks and away from
     * deep drops/void, applied to every traveller/raider/worker every tick regardless of which AI
     * task (if any) currently has movement control - idle, travelling, following, camping,
     * fighting. A crowded group jostles its own members via ordinary entity collision every tick,
     * which alone is enough to shove one sideways over an edge before any reactive AI even gets a
     * chance to run; this is a background bias against exactly that, not a hard wall - it's a
     * small motionX/Z nudge, easily overridden by real movement (a deliberate gap-jump, a
     * deliberate lava/water crossing, a player pushing hard and sustained).
     */
    private void tickHazardRepulsion() {
        if (world.isRemote || isDead) return;
        if (!TravellersConfig.HAZARD_SAFETY.enabled) return;
        if (isRiding() || isSleepingInBed() || campingPassive) return;
        // A glide is a deliberate flight over exactly the drops this nudge exists to avoid.
        if (elytraFlight.isActive()) return;

        PathNavigate nav = this.getNavigator();
        if (nav instanceof TravellerNavigateGround && ((TravellerNavigateGround) nav).isGapJumping()) return;

        // Staggered like the follow AI's own heavy-update cadence - the scan itself is cheap per
        // entity, but this now runs for every traveller/raider/worker in the world, not just
        // hired formation followers, so spreading the cost across ticks matters at scale.
        if (Math.floorMod(this.ticksExisted, HAZARD_TICK_BUCKETS) != Math.floorMod(this.getEntityId(), HAZARD_TICK_BUCKETS)) {
            return;
        }

        TravellersConfig.HazardSafety cfg = TravellersConfig.HAZARD_SAFETY;
        double radius = cfg.scanRadius;
        BlockPos feet = new BlockPos(this.posX, this.getEntityBoundingBox().minY, this.posZ);
        int r = MathHelper.ceil(radius);

        // If a valid jump is available in the direction the entity is currently moving, don't let
        // the cliff-type push fight that approach (hazard-material push stays - it's already weak
        // enough not to meaningfully fight a deliberate lava-gap jump, same as before this change).
        double moveLenSq = motionX * motionX + motionZ * motionZ;
        boolean hasJumpAhead = false;
        double jumpDirX = 0.0D, jumpDirZ = 0.0D;
        if (moveLenSq > 1.0E-4D) {
            double moveLen = Math.sqrt(moveLenSq);
            jumpDirX = motionX / moveLen;
            jumpDirZ = motionZ / moveLen;
            GapJumpResult gap = GapJumpEvaluator.evaluate(world, world.getTotalWorldTime(),
                    feet.getX(), feet.getY(), feet.getZ(), jumpDirX, jumpDirZ);
            hasJumpAhead = gap.type == GapJumpResult.Type.JUMPABLE;
        }

        // Whether the entity's OWN column is already the danger (shoved past the edge, not just
        // near it). The edge-repulsion loop below only ever pushes AWAY from a neighbor column, so
        // once the entity is centered on the hazard itself there is no "away from self" direction
        // left to push along - it silently stopped resisting right when it mattered most and just
        // fell in. Handled separately below: search for the nearest safe neighbor and push TOWARD
        // it instead, at full strength.
        boolean selfHazardMaterial = isHazardMaterialColumn(feet);
        boolean selfCliffDanger = !selfHazardMaterial
                && computeCliffDropDepth(feet, cfg.cliffMaxScanDepth) >= cfg.cliffMinDropDepth;
        boolean selfDangerous = selfHazardMaterial || selfCliffDanger;

        double pushX = 0.0D;
        double pushZ = 0.0D;

        double bestSafeDistSq = Double.MAX_VALUE;
        double bestSafeDirX = 0.0D;
        double bestSafeDirZ = 0.0D;
        boolean hasSafeDir = false;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean isSelf = dx == 0 && dz == 0;
                BlockPos col = feet.add(dx, 0, dz);

                double colX = col.getX() + 0.5D;
                double colZ = col.getZ() + 0.5D;
                double awayX = this.posX - colX;
                double awayZ = this.posZ - colZ;
                double distSq = awayX * awayX + awayZ * awayZ;
                if (distSq > radius * radius) continue;

                boolean hazardMat = isSelf ? selfHazardMaterial : isHazardMaterialColumn(col);
                boolean waterCol = !hazardMat && isWaterColumn(col);
                boolean cliffDanger = !hazardMat && !waterCol
                        && computeCliffDropDepth(col, cfg.cliffMaxScanDepth) >= cfg.cliffMinDropDepth;

                if (selfDangerous && !isSelf && !hazardMat && !cliffDanger && distSq < bestSafeDistSq) {
                    bestSafeDistSq = distSq;
                    double dist0 = Math.sqrt(Math.max(distSq, 1.0E-4D));
                    bestSafeDirX = -awayX / dist0;
                    bestSafeDirZ = -awayZ / dist0;
                    hasSafeDir = true;
                }

                if (isSelf) continue; // no "away from self" direction for the edge-repulsion pass below

                double dist = Math.sqrt(Math.max(distSq, 1.0E-4D));
                double falloff = 1.0D - dist / radius;

                double t;
                boolean cliffType = false;

                if (hazardMat) {
                    t = cfg.hazardStrength * falloff;
                } else if (waterCol) {
                    t = cfg.waterStrength * falloff;
                } else if (cliffDanger) {
                    int depth = computeCliffDropDepth(col, cfg.cliffMaxScanDepth);
                    int span = Math.max(1, cfg.cliffMaxScanDepth - cfg.cliffMinDropDepth);
                    double factor = Math.min(1.0D, (depth - cfg.cliffMinDropDepth) / (double) span);
                    t = cfg.cliffStrength * factor * falloff;
                    cliffType = true;
                } else {
                    continue;
                }

                if (t <= 0.0D) continue;

                if (cliffType && hasJumpAhead) {
                    double toColX = colX - this.posX;
                    double toColZ = colZ - this.posZ;
                    double toColLen = Math.sqrt(toColX * toColX + toColZ * toColZ);
                    if (toColLen > 1.0E-4D
                            && (toColX / toColLen) * jumpDirX + (toColZ / toColLen) * jumpDirZ > 0.3D) {
                        continue; // roughly ahead in the direction of a validated jump - don't resist it
                    }
                }

                pushX += (awayX / dist) * t;
                pushZ += (awayZ / dist) * t;
            }
        }

        if (this.getAttackTarget() != null || this.getRevengeTarget() != null) {
            pushX *= cfg.combatRepulsionFactor;
            pushZ *= cfg.combatRepulsionFactor;
        }

        // Emergency push is deliberately NOT reduced by the combat factor above - standing on the
        // hazard itself is worse than a moment's hesitation approaching an enemy near a ledge.
        if (selfDangerous && hasSafeDir) {
            double strength = selfHazardMaterial ? cfg.hazardStrength : cfg.cliffStrength;
            pushX += bestSafeDirX * strength;
            pushZ += bestSafeDirZ * strength;
        }

        if (pushX == 0.0D && pushZ == 0.0D) return;

        this.motionX += pushX;
        this.motionZ += pushZ;

        if (cfg.debugHazardRepulsion && (Math.abs(pushX) + Math.abs(pushZ)) > 0.002D) {
            net.minecraftforge.fml.common.FMLLog.log(
                    org.apache.logging.log4j.Level.INFO,
                    "[Travellers/HazardRepulsion] %s#%d at %.1f,%.1f,%.1f push=(%.4f,%.4f) selfDangerous=%b",
                    this.getClass().getSimpleName(), this.getEntityId(), this.posX, this.posY, this.posZ,
                    pushX, pushZ, selfDangerous);
        }
    }

    /** Column hazard check: any non-water liquid (lava, modded), fire, cactus, or configured hazard blacklist entry, at the column's own level or one below. */
    private boolean isHazardMaterialColumn(BlockPos col) {
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos p = col.up(dy);
            if (!world.isBlockLoaded(p)) continue;
            IBlockState state = world.getBlockState(p);
            Block b = state.getBlock();
            Material mat = state.getMaterial();

            if (BlockRules.isHazard(state)) return true;
            if (b == Blocks.FIRE || b == Blocks.CACTUS) return true;
        }
        return false;
    }

    /** Water is a legitimate crossing medium (fording, swimming after a leader) - kept a separate, much weaker case than real hazards. */
    private boolean isWaterColumn(BlockPos col) {
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos p = col.up(dy);
            if (!world.isBlockLoaded(p)) continue;
            if (world.getBlockState(p).getMaterial() == Material.WATER) return true;
        }
        return false;
    }

    /**
     * Scans straight down from the entity's own feet level at the neighbor column, returning how
     * many blocks below that level the first solid floor is found (0 = flush with the entity's own
     * footing, no drop). No floor within {@code maxDepth} is treated as bottomless/void and clamped
     * to {@code maxDepth}. A liquid encountered along the way returns -1 (handled separately by the
     * hazard/water column checks, never treated as a "cliff").
     */
    private int computeCliffDropDepth(BlockPos colAtFeetLevel, int maxDepth) {
        for (int d = 0; d <= maxDepth; d++) {
            BlockPos p = colAtFeetLevel.down(d);
            if (!world.isBlockLoaded(p)) return maxDepth; // unloaded ahead: be conservative
            IBlockState state = world.getBlockState(p);
            if (state.getMaterial().isLiquid()) return -1;
            if (state.getBlockFaceShape(world, p, EnumFacing.UP) == BlockFaceShape.SOLID) return d;
        }
        return maxDepth;
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

        // Resolve via the settlement registry
        MillSettlements.Settlement vi = MillSettlements.resolve(this.world, target);

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

        // A lethal hit already ran onDeath() synchronously inside super.attackEntityFrom above,
        // which may have dropped this entity's armor as world EntityItems — vanilla's dropEquipment
        // hands the drop the exact same ItemStack reference still sitting in the equipment slot,
        // it never copies it. Damaging armor after that point would silently over-wear the item
        // that's already on the ground instead of anything still worn by this entity.
        // INVARIANT: no wear after a possibly lethal hit - the drop shares this ItemStack.
        if (getHealth() > 0.0F) {
            TravellerItemDamage.damageArmorOnHit(this, amount);
        }

        if (choirSinging) {
            // Keep the OGG stream alive; just mute the sound on clients for 8 seconds so
            // when unmuted the singer is back at the correct position in the song.
            TravellerFollowNet.CHANNEL.sendToAllAround(
                    new MsgChoirSingerMute(getEntityId(), 8000),
                    new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64.0));
            // Don't touch voiceLockUntilTick so the coordinator keeps the session alive.
        }
        // Non-choir case: no explicit stop here anymore. playHurtSound() (called by
        // super.attackEntityFrom above) already crossfades out whatever voice line was
        // playing when it plays the hurt bark. If the hurt bark itself was throttled by
        // TravellerCrowdSoundLimiter (dense battle) there's nothing to interrupt with,
        // so the prior sound is deliberately left alone rather than cut to silence.

        Entity src = source.getTrueSource();

        if (src instanceof EntityPlayer && ((EntityPlayer) src).isCreative()) {
            // Vanilla's super.attackEntityFrom above already set the revenge target to this
            // creative player unconditionally - clear it so nothing downstream (TravellerHurtByTarget,
            // isInCombatRaw, threat memory...) treats a harmless creative hit as an ongoing threat.
            setInCombat(false);
            setRevengeTarget(null);
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
            if (!com.jubitus.travellers.world.mill.MillVillagerTravellerGuard.shouldFightMillVillager(this, mv)) {
                // Absorb hit from a non-enemy millager without retaliating
                return true;
            }
        }

        if (src instanceof EntityLivingBase) {
            this.setRevengeTarget((EntityLivingBase) src);
        }

        if (!TravellersDerivedConfig.isPeacefulBlocked(world) && src instanceof EntityLivingBase) {
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
    // A flight that got cut off mid-air with no more glide control (elytra breaking, the hard flight
    // time cap) has no controlled way down - it really does fall the rest of the way like a stone. That
    // fall must never be a lethal - or even damaging - one just because something else took its wings
    // away without warning; see ElytraFlightController.land()/isPendingSafeDescent.
    if (elytraFlight.isPendingSafeDescent()) {
        elytraFlight.clearPendingSafeDescent();
        return;
    }
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
            TravellerMountSupport.releaseMountOnRiderDeath(this);
            // Camp event system: free this traveller's slot so the camp updates / can be reused.
            com.jubitus.travellers.world.camp.CampsiteManager.get((net.minecraft.world.WorldServer) world)
                    .releaseOccupant(this.getUniqueID());
            // Virtual battles: mark this pawn dead so the offscreen sim stays consistent.
            com.jubitus.travellers.world.battle.VirtualBattleManager.get((net.minecraft.world.WorldServer) world)
                    .onParticipantDeath((net.minecraft.world.WorldServer) world, this, cause);
        }
        tryPlayDeathSound();
        if (!this.world.isRemote && this.isRiding() && this.getRidingEntity() instanceof net.minecraft.entity.item.EntityBoat) {
            net.minecraft.entity.item.EntityBoat boat = (net.minecraft.entity.item.EntityBoat) this.getRidingEntity();
            this.dismountRidingEntity();
            boat.setDead();
        }
        // Strip every ghost-tagged visual copy (sword/bow/torch/block/rod) before vanilla's drop
        // logic runs, or a clone with no attribute modifiers can be dropped alongside the real item.
        equipment.purgeGhostItems();
        super.onDeath(cause);
    }


    @Override
    @javax.annotation.Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        if (this.ticksExisted < hurtSoundCooldownUntilTick) return null;
        hurtSoundCooldownUntilTick = this.ticksExisted + TravellersConfig.SOUND.hurtSoundEntityCooldownTicks;
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

    /**
     * Smoothed banking angle for the flight pose, in degrees. Client-side and purely cosmetic.
     *
     * <p>Vanilla derives a gliding player's roll from the raw angle between motion and look, which is
     * fine at player glide speeds but not here: at low speed the motion vector is mostly noise, so
     * that angle swings across its whole +/-180 range from tick to tick and the model snaps about.
     * This keeps the same idea but clamps it to a believable lean, fades it out as they slow down, and
     * eases toward it instead of jumping - held as prev/current so the renderer interpolates rather
     * than smoothing per frame at whatever framerate happens to be running.
     */
    private float elytraBank;
    private float prevElytraBank;

    /** Below this horizontal speed (blocks/tick) the motion direction is too noisy to bank from. */
    private static final double ELYTRA_BANK_MIN_SPEED = 0.15D;
    /** Speed at which the bank reaches full strength. */
    private static final double ELYTRA_BANK_FULL_SPEED = 0.55D;
    /** Steepest lean, in degrees. */
    private static final float ELYTRA_BANK_MAX = 45.0F;
    /** Per-tick easing toward the target lean. */
    private static final float ELYTRA_BANK_SMOOTHING = 0.2F;

    /** Interpolated banking angle for rendering. */
    public float getElytraBank(float partialTicks) {
        return prevElytraBank + (elytraBank - prevElytraBank) * partialTicks;
    }

    private void tickElytraBank() {
        prevElytraBank = elytraBank;

        float target = 0.0F;
        if (isElytraFlying()) {
            double speedSq = motionX * motionX + motionZ * motionZ;
            Vec3d look = getLookVec();
            double lookSq = look.x * look.x + look.z * look.z;

            if (speedSq > ELYTRA_BANK_MIN_SPEED * ELYTRA_BANK_MIN_SPEED && lookSq > 1.0E-6D) {
                double speed = Math.sqrt(speedSq);
                // Clamped before acos: floating point can push this a hair past 1 and return NaN.
                double cos = MathHelper.clamp(
                        (motionX * look.x + motionZ * look.z) / (speed * Math.sqrt(lookSq)), -1.0D, 1.0D);
                double cross = motionX * look.z - motionZ * look.x;

                target = (float) (Math.signum(cross) * Math.acos(cos) * (180.0D / Math.PI));
                target = MathHelper.clamp(target, -ELYTRA_BANK_MAX, ELYTRA_BANK_MAX);
                target *= (float) MathHelper.clamp(
                        (speed - ELYTRA_BANK_MIN_SPEED) / (ELYTRA_BANK_FULL_SPEED - ELYTRA_BANK_MIN_SPEED),
                        0.0D, 1.0D);
            }
        }

        elytraBank += (target - elytraBank) * ELYTRA_BANK_SMOOTHING;
    }

    /**
     * Vanilla entity flag 7 - the elytra-flying state. Exposed because {@code setFlag} is protected
     * and this is the only thing standing between a mob and vanilla's own elytra physics, which has
     * no player-only gate.
     */
    public void setElytraFlying(boolean flying) {
        this.setFlag(7, flying);
    }

    @Override
    public void travel(float strafe, float vertical, float forward) {
        // Steer here, not in onLivingUpdate: vanilla runs the AI (and the look helper, which would
        // overwrite our rotations) just before calling travel(), so this is the only hook where our
        // heading survives into the same tick's physics.
        if (!this.world.isRemote) {
            this.elytraFlight.tick();
        }

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
            if (TravellersDerivedConfig.TRAVELLER_ATTACK_LIST.isListed(e)) return true;
            return checkAncientWarfare2Hostility(e);
        }

        // Put any hard-coded extra rules here if you need them later
        // (e.g. always attack some specific boss entity)
    }

    // Default: friendlies are NOT aggressive to other travellers.
    public boolean isAggressiveToTravellers() {
        return false;
    }

    /**
     * Ancient Warfare 2: reputation-aware, not a blanket class list. A hired follower mirrors
     * its owner's actual standing with that faction (revenge + persisted reputation - exactly
     * what the owner would see themselves); see AncientWarfare2Compat. All AW2 types are
     * handled purely via reflection inside that class - never referenced here directly - so
     * this class stays loadable with AW2 absent.
     * <p>
     * Shared with {@code EntityTravellerWorker.shouldAttackExtraEntity}'s own override, which
     * must call this itself rather than relying on falling through to this base method - a
     * worker-specific attack list used to silently skip this check entirely otherwise.
     */
    public boolean checkAncientWarfare2Hostility(Entity e) {
        EntityPlayer effectiveOwner = null;
        if (this instanceof EntityTravellerWorker) {
            EntityTravellerWorker worker = (EntityTravellerWorker) this;
            if (worker.isHiredFollower()) effectiveOwner = worker.getFollowOwner();
        }
        return AncientWarfare2Compat.isHostile(e, effectiveOwner);
    }


    @Override
    public void setDead() {
        if (!this.world.isRemote) {
            TravellerDoorways.releaseAll(this.world, this.getEntityId());
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

    // Seat height so the player doesn't clip the head. The hitbox already follows the render scale.
    @Override
    public double getMountedYOffset() {
        return this.height;
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
        // NEUTRAL (not VOICE): travellers are creatures, not player speech — this is
        // the slider players actually expect to control their volume with ("Friendly
        // Creatures" in the vanilla sound options), same category vanilla villagers use.
        return SoundCategory.NEUTRAL;
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
        applyConfiguredAttributes();
        if (getHealth() > getMaxHealth()) setHealth(getMaxHealth());

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

    private long mountAttackedTick = Long.MIN_VALUE;

    /** Someone hurt this rider's bonded horse: retaliate exactly as if the rider itself had been hit. */
    public void noteMountAttacked(EntityLivingBase attacker) {
        this.mountAttackedTick = world.getTotalWorldTime();
        this.setRevengeTarget(attacker);
    }

    /** True for a short window after {@link #noteMountAttacked}, standing in for a recent own damage source. */
    public boolean wasMountRecentlyAttacked() {
        return world.getTotalWorldTime() - mountAttackedTick <= 40L;
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

    // --- Follow-context hooks for EntityAIFollowLeader. Neutral defaults for a plain traveller/
    // raider group follower, whose only concept of a leader is groupLeaderUUID; EntityTravellerWorker
    // overrides most of these with its own richer semantics (player owner, escort leader, missions,
    // duty) - isCatchupTravel/startCatchupTravelTo/getDynamicCatchupGoal are real here too (see
    // below), not neutral no-ops: a group follower's catchup target is always its leader, so it
    // reuses groupLeaderUUID directly instead of tracking a second UUID like a worker does. ---

    /**
     * The entity this follower currently steers toward. Returns null when the leader isn't a loaded
     * entity - a still-virtual group leader is tracked separately, the same way
     * {@code EntityTravellerWorker.tickVirtualLeaderFollow()} tracks a virtual escort leader.
     */
    @Nullable
    public EntityLivingBase getEffectiveFollowTarget() {
        UUID leaderId = groupLeaderUUID;
        if (leaderId == null || !(world instanceof WorldServer)) return null;
        Entity e = ((WorldServer) world).getEntityFromUuid(leaderId);
        return (e instanceof EntityTraveller && !e.isDead) ? (EntityLivingBase) e : null;
    }

    public boolean isFollowOrderActive() {
        return groupLeaderUUID != null;
    }

    public boolean isHiredFollower() {
        return false;
    }

    @Nullable
    public UUID getFollowOwnerId() {
        return null;
    }

    public boolean isJobActive() {
        return false;
    }

    public boolean isReturnUsingFollow() {
        return false;
    }

    /** No Home/Duty concept for a plain group follower. */
    public boolean isGoingHome() {
        return false;
    }

    private boolean catchupTravel = false;
    @Nullable private BlockPos cachedCatchupGoal;
    private long nextCatchupRefreshTick = 0L;

    public boolean isCatchupTravel() {
        return catchupTravel;
    }

    /**
     * Hands this follower off to {@code EntityAITravel}'s long-distance travel (route planning, its
     * own stuck-escalation ladder) instead of {@code EntityAIFollowLeader}'s short-range formation
     * chase - triggered once {@code FollowModeSelector} decides this follower has fallen too far
     * behind its leader, or is genuinely stuck near its slot for too long. See
     * {@code EntityAITravel.shouldExecute()}'s {@code !isCatchupTravel()} exemption for
     * {@link #groupLeaderUUID} and {@code EntityAIFollowLeader.canUseFollow()}'s
     * {@code isCatchupTravel()} check - the two AI tasks hand the movement mutex to each other purely
     * off this flag. {@code EntityTravellerWorker} overrides this with its own owner/escort-aware
     * version (a separate catchup target UUID, since a worker's owner isn't always what
     * {@link #getEffectiveFollowTarget()} tracks).
     */
    public void startCatchupTravelTo(@Nullable EntityLivingBase target) {
        if (target == null || target.dimension != this.dimension) return;
        beginCatchup();
    }

    private void beginCatchup() {
        this.cachedCatchupGoal = null;
        this.nextCatchupRefreshTick = 0L;
        this.catchupTravel = true;

        TravelCommon tc = this.getTravelCommon();
        tc.route.clear();
        tc.routeIndex = -1;
        tc.setTargetVillage(null);
        this.setTargetVillage(null);
        tc.setCurrentWaypoint(null);
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);
    }

    /** Ends catchup - called once close enough to the leader/slot (see {@link #tickCatchupExit()}),
     *  or once the leader is confirmed gone. {@code EntityAIFollowLeader} takes back over next tick. */
    public void stopCatchupTravel() {
        this.catchupTravel = false;
        this.cachedCatchupGoal = null;
        this.nextCatchupRefreshTick = 0L;
    }

    /**
     * The moving destination {@code EntityAITravel} steers toward while {@link #isCatchupTravel()} -
     * the leader's own position, surface-approximated and refreshed periodically rather than every
     * tick. Mirrors {@code EntityTravellerWorker.getDynamicCatchupGoal()} but resolves the target via
     * {@link #getEffectiveFollowTarget()} (always the group leader) instead of a separately tracked
     * UUID.
     */
    @Nullable
    public BlockPos getDynamicCatchupGoal() {
        if (!catchupTravel || !(world instanceof WorldServer)) return cachedCatchupGoal;

        EntityLivingBase target = getEffectiveFollowTarget();
        if (target == null) return virtualLeaderSlot != null ? virtualLeaderSlot : cachedCatchupGoal;

        long now = world.getTotalWorldTime();
        if (cachedCatchupGoal == null || now >= nextCatchupRefreshTick) {
            cachedCatchupGoal = approximateSurfacePosNear(target);
            nextCatchupRefreshTick = now + FollowRuntimeConfig.get().catchup.repathIntervalTicks;
        }
        return cachedCatchupGoal;
    }

    private BlockPos approximateSurfacePosNear(EntityLivingBase target) {
        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        BlockPos actual = new BlockPos(target.posX, target.getEntityBoundingBox().minY, target.posZ);

        if (FollowRecoveryHelper.isLeaderMeaningfullyUnderground(this.world, target, cfg)
                || FollowRecoveryHelper.isEnclosedFollowSpace(this.world, target, cfg)) {
            return actual;
        }
        try {
            BlockPos top = this.world.getTopSolidOrLiquidBlock(new BlockPos(actual.getX(), 0, actual.getZ()));
            return top != null ? top : actual;
        } catch (Exception ignored) {
            return actual;
        }
    }

    /**
     * Per-tick exit watch, called unconditionally from {@link #onLivingUpdate()} exactly like
     * {@link #tickVirtualGroupLeaderFollow()} - mirrors the distance/slot check
     * {@code EntityTravellerWorker.onLivingUpdate()} runs inline for its own catchup. Hands the
     * movement mutex back to {@code EntityAIFollowLeader} once close enough, or the instant the
     * leader is confirmed gone.
     */
    private void tickCatchupExit() {
        if (world.isRemote || !catchupTravel) return;

        EntityLivingBase t = getEffectiveFollowTarget();
        if (t == null) {
            // An offscreen leader is chased through its slot until close to it; a gone one ends catchup.
            if (virtualLeaderSlot == null) {
                stopCatchupTravel();
            } else if (getDistanceSqToCenter(virtualLeaderSlot) <= sq(FollowRuntimeConfig.get().catchup.stopDistanceVisible)
                    && !getStuckRecord().isEscalating()) {
                stopCatchupTravel();
            }
            return;
        }

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        boolean caveLike = FollowRecoveryHelper.isLeaderMeaningfullyUnderground(world, t, cfg)
                || FollowRecoveryHelper.isEnclosedFollowSpace(world, t, cfg);
        double stopDist = caveLike ? cfg.catchup.stopDistanceCave : cfg.catchup.stopDistanceVisible;
        double distToLeader = this.getDistance(t);

        boolean stop;
        if (hasFollowerSlotGoal()) {
            double dx = this.posX - followerSlotGoalX;
            double dy = this.posY - followerSlotGoalY;
            double dz = this.posZ - followerSlotGoalZ;
            double distToSlot = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean closeToSlot = caveLike
                    ? (distToSlot <= stopDist && Math.abs(dy) <= cfg.cave.verticalTolerance + 1.5D)
                    : (distToSlot <= stopDist);
            stop = closeToSlot || distToLeader <= stopDist;
        } else {
            boolean visible = this.canEntityBeSeen(t) || t.canEntityBeSeen(this);
            double verticalGap = Math.abs(this.posY - t.posY);
            stop = caveLike
                    ? (distToLeader <= stopDist && verticalGap <= cfg.cave.verticalTolerance + 1.5D)
                    : (distToLeader <= stopDist && (visible || distToLeader <= stopDist * 0.75D));
        }

        // Near but trapped (a pit under the leader): the travel ladder must finish before follow resumes.
        if (stop && !getStuckRecord().isEscalating()) stopCatchupTravel();
    }

    private int cachedGroupSize = 1;

    public int getCachedGroupSize() {
        return cachedGroupSize;
    }

    public void setCachedGroupSize(int size) {
        cachedGroupSize = Math.max(1, size);
    }

    /** No player-cohesion concept for a group with no owning player. */
    public double getCachedPlayerCohesion() {
        return 1.0D;
    }

    protected boolean followerHasSlotGoal = false;
    protected double followerSlotGoalX, followerSlotGoalY, followerSlotGoalZ;

    /** Called by {@code EntityAIFollowLeader} to report this follower's current formation/idle slot -
     *  also the signal {@link #tickCatchupExit()} and, for workers, idle-look gating key off.
     *  {@code EntityTravellerWorker} overrides this to also stamp a staleness timestamp on top. */
    public void notifyFollowerSlotGoal(double x, double y, double z) {
        this.followerHasSlotGoal = true;
        this.followerSlotGoalX = x;
        this.followerSlotGoalY = y;
        this.followerSlotGoalZ = z;
    }

    public boolean hasFollowerSlotGoal() {
        return followerHasSlotGoal;
    }

    public double getFollowerSlotGoalX() { return followerSlotGoalX; }
    public double getFollowerSlotGoalY() { return followerSlotGoalY; }
    public double getFollowerSlotGoalZ() { return followerSlotGoalZ; }

    private int virtualLeaderRepathCooldown = -1;
    // The slot of an offscreen group leader, surface-resolved where loaded; catchup travel drives toward it.
    @Nullable private BlockPos virtualLeaderSlot;

    /**
     * A loaded group follower whose leader is offscreen: resolves the slot next to the leader's virtual
     * position and hands the follower to catchup travel ({@code EntityAITravel}, with its stuck ladder),
     * the same way a follower too far behind a loaded leader is. Always the RING offset: there is no
     * loaded group to rank against until the leader materializes.
     */
    private void tickVirtualGroupLeaderFollow() {
        UUID leaderId = groupLeaderUUID;
        if (leaderId == null || getEffectiveFollowTarget() != null || !(world instanceof WorldServer)) {
            virtualLeaderSlot = null;
            return;
        }
        WorldServer serverWorld = (WorldServer) world;

        com.jubitus.travellers.world.virtualization.VirtualTravellerManager vwm =
                com.jubitus.travellers.world.virtualization.VirtualTravellerManager.get(serverWorld);
        if (!vwm.isVirtual(leaderId)) {
            // Leader neither loaded nor virtual: gone for good. Clear the link or this follower idles
            // forever (EntityAITravel refuses to run while groupLeaderUUID is set).
            groupLeaderUUID = null;
            virtualLeaderSlot = null;
            return;
        }

        if (virtualLeaderRepathCooldown < 0) {
            virtualLeaderRepathCooldown = Math.floorMod(getEntityId(), 40);
        }
        if (--virtualLeaderRepathCooldown > 0) return;
        virtualLeaderRepathCooldown = 40;

        BlockPos leaderPos = vwm.getVirtualPosition(leaderId);
        if (leaderPos == null) return;

        com.jubitus.travellers.entity.entityai.follow.formations.FormationGeometry.SlotOffset off =
                com.jubitus.travellers.entity.entityai.follow.formations.FormationGeometry.computeRingOffset(getUniqueID());
        virtualLeaderSlot = resolveVirtualSlot(leaderPos.getX() + 0.5D + off.dx, leaderPos.getZ() + 0.5D + off.dz);

        if (!isCatchupTravel() && getDistanceSqToCenter(virtualLeaderSlot)
                > sq(FollowRuntimeConfig.get().catchup.stopDistanceVisible * 2.0D)) {
            beginCatchup();
        }
    }

    /** A slot next to an offscreen leader: its ground where loaded (the virtual Y is not tracked), else our own height. */
    protected BlockPos resolveVirtualSlot(double x, double z) {
        BlockPos column = new BlockPos(x, posY, z);
        if (!(world instanceof WorldServer) || com.jubitus.travellers.world.chunkmanager.LoadedChunks.peek(
                (WorldServer) world, column.getX() >> 4, column.getZ() >> 4) == null) return column;
        int y = com.jubitus.travellers.world.ground.ColumnGround.nearestLayer(world, column.getX(), column.getZ(),
                column.getY(), true);
        return y == com.jubitus.travellers.world.ground.ColumnGround.NONE ? column : new BlockPos(column.getX(), y, column.getZ());
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * True when this traveller and {@code other} march under the same group leader (militia/convoy):
     * either sharing a leader UUID, or one leading the other. Solo travellers (no leader, not a
     * leader) are never groupmates - unrelated same-culture travellers do not count.
     */
    public boolean isGroupmate(@Nullable EntityTraveller other) {
        if (other == null) return false;
        if (other == this) return true;
        UUID myLeader = this.getGroupLeaderUUID();
        UUID otherLeader = other.getGroupLeaderUUID();
        if (myLeader != null && myLeader.equals(otherLeader)) return true;
        if (myLeader != null && myLeader.equals(other.getUniqueID())) return true;
        return otherLeader != null && otherLeader.equals(this.getUniqueID());
    }

    /**
     * A bonded horse whose rider is on our side (ourselves, a groupmate, a same-owner worker, or a
     * rider we cannot classify right now) must never be picked as an attack target - owner-assist and
     * defend AIs would otherwise gang up on a friendly mount they cannot even damage. Only a horse
     * ridden by a genuine enemy stays a valid target.
     */
    public boolean isFriendlyBondedMount(@Nullable EntityLivingBase target) {
        if (!(target instanceof EntityHorse)) return false;
        EntityHorse horse = (EntityHorse) target;
        if (!TravellerMountSupport.isBondedMount(horse)) return false;
        UUID riderId = TravellerMountSupport.getBondedOwner(horse);
        if (riderId == null || riderId.equals(this.getUniqueID())) return true;
        Entity rider = (world instanceof WorldServer) ? ((WorldServer) world).getEntityFromUuid(riderId) : null;
        if (!(rider instanceof EntityTraveller)) return true; // rider not loaded / not classifiable - stay off it
        return !isHostileMountRider((EntityTraveller) rider);
    }

    private boolean isHostileMountRider(EntityTraveller rider) {
        if (this instanceof EntityTravellerWorker && rider instanceof EntityTravellerWorker) {
            return ((EntityTravellerWorker) this).isEnemyHiredWorker((EntityTravellerWorker) rider);
        }
        return combat.isCrossFaction(rider);
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
        travelCommon.setVisitedStop(null);
    }

    /**
     * Returns true if this entity can safely be virtualized (removed from world and simulated offscreen).
     * Does NOT check player distance — that is the caller's responsibility.
     */
    public boolean canVirtualize() {
        return !isInCombat() && canVirtualizeIgnoringCombat();
    }

    public void setDebugPathTest(boolean value) {
        this.debugPathTest = value;
    }

    public boolean isDebugPathTest() {
        return debugPathTest;
    }

    /** Combat-agnostic eligibility — virtual battle participants may virtualize mid-fight. */
    public boolean canVirtualizeIgnoringCombat() {
        if (debugPathTest) return false;
        if (isDead) return false;
        if (isHandActive()) return false;
        // A bonded mount comes along into virtualization (see VirtualTravellerManager); any other
        // ride (boat, someone else's horse) still blocks, same as before.
        if (isRiding() && !isMountedOnBondedMount()) return false;
        // Mid-flight there is no sane position to hand to the offscreen simulation, which only ever
        // walks records along the ground. forceVirtualizeResolvingBlockers() overrides this, as it
        // does for the other soft blockers here.
        if (elytraFlight.isActive()) return false;
        return true;
    }

    /**
     * "The ground I am trying to walk onto is not loaded, so hand off to the offscreen simulation
     * instead of milling about at the boundary." Returns true if this call virtualized the entity.
     *
     * <p><b>Only for {@link com.jubitus.travellers.entity.entityai.follow.EntityAIFollowLeader}
     * followers</b> (see {@link #getEdgeProbeGoal()}) - a worker escorting a leader, or a traveller/raider
     * following its group leader. That AI never hands off to {@link com.jubitus.travellers.entity.entityai.travel.EntityAITravel}
     * even when catching up over a long distance (it re-implements its own stepped waypoints instead),
     * so it has no equivalent of that task's progress-based stuck backstop and would otherwise only ever
     * leave via an actual chunk unload. Ordinary travel, go orders, missions and owner-catchup all end up
     * driven by {@code EntityAITravel} (directly, or via {@code EntityTravellerWorker.startCatchupTravelTo}),
     * which already measures "am I actually getting closer" over several seconds - a strictly more
     * reliable signal than a single directional sample, so this probe deliberately does not run for them
     * ({@link #getEdgeProbeGoal()} returns null in that case).
     *
     * <p>Single directional sample, so it is inherently a guess - hardened against ever firing where a
     * player could notice: {@link TravellersConfig.TRAVEL#edgeStuckMinPlayerDistance} is checked first,
     * before any state here is even touched.
     */
    public boolean tickEdgeVirtualizeProbe(@Nullable BlockPos goal) {
        if (world == null || world.isRemote || !(world instanceof WorldServer)) return false;
        long now = world.getTotalWorldTime();
        if (edgeProbeLastTick == now) return false;
        edgeProbeLastTick = now;

        if (goal == null) {
            edgeBlockedTicks = 0;
            return false;
        }
        double minPlayerDist = TravellersConfig.TRAVEL.edgeStuckMinPlayerDistance;
        if (minPlayerDist > 0
                && ((WorldServer) world).getClosestPlayer(posX, posY, posZ, minPlayerDist, false) != null) {
            edgeBlockedTicks = 0;
            return false;
        }
        // Just materialized: let it settle. A unit that appears right at the boundary would otherwise
        // be probed straight back out on its next tick, appearing and vanishing on the spot.
        if (com.jubitus.travellers.world.virtualization.VirtualTravellerManager
                .get((WorldServer) world).isInVirtualizeGrace(getUniqueID())) {
            edgeBlockedTicks = 0;
            return false;
        }
        // Fighting in loaded terrain: stay physical and finish the fight. The offscreen battle sim
        // exists for units that are already virtual, not as a reason to pull one out of a fight it is
        // visibly having - and the goal used here is a travel destination the unit isn't pursuing
        // right now anyway.
        EntityLivingBase fighting = getAttackTarget();
        if (fighting != null && fighting.isEntityAlive()) {
            edgeBlockedTicks = 0;
            return false;
        }

        if (!isGroundAheadUnloaded(goal)) {
            edgeBlockedTicks = 0;
            return false;
        }

        if (++edgeBlockedTicks < TravellersConfig.TRAVEL.edgeAheadUnloadedGraceTicks) return false;
        edgeBlockedTicks = 0;
        return tryVirtualizeStuckAtEdge(goal);
    }

    /** True when the ground a short way ahead toward the goal is not loaded, i.e. this unit is at the loaded border. */
    public boolean isGroundAheadUnloaded(@Nullable BlockPos goal) {
        if (goal == null || !(world instanceof WorldServer)) return false;
        BlockPos from = getPosition();
        double dx = goal.getX() - from.getX();
        double dz = goal.getZ() - from.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq < 1.0) return false;
        double dist = Math.sqrt(distSq);
        int probeDist = (int) Math.max(EDGE_PROBE_MIN_BLOCKS, Math.min(EDGE_PROBE_MAX_BLOCKS, dist));
        int px = from.getX() + (int) Math.round(dx / dist * probeDist);
        int pz = from.getZ() + (int) Math.round(dz / dist * probeDist);
        return !com.jubitus.travellers.world.virtualization.VirtualTravellerManager.isChunkLoaded(
                (WorldServer) world, px >> 4, pz >> 4);
    }

    /**
     * Where this unit is currently trying to get to, for {@link #tickEdgeVirtualizeProbe}. Only ever a
     * direction hint - a null result simply means "not going anywhere in particular", which correctly
     * exempts an idle unit standing in loaded terrain from ever being probed out of the world.
     *
     * <p>Deliberately does NOT look at {@link TravelCommon}'s waypoint/target village here - that goal
     * belongs to {@link com.jubitus.travellers.entity.entityai.travel.EntityAITravel}, which has its own,
     * more reliable stuck detection (see {@link #tickEdgeVirtualizeProbe}'s class doc). Only a group
     * leader to follow - the one case with no such backstop - is a reason for this probe to run.
     */
    @Nullable
    protected BlockPos getEdgeProbeGoal() {
        UUID leader = getGroupLeaderUUID();
        if (leader != null && world instanceof WorldServer) {
            WorldServer ws = (WorldServer) world;
            Entity live = ws.getEntityFromUuid(leader);
            if (live != null && !live.isDead) return live.getPosition();
            return com.jubitus.travellers.world.virtualization.VirtualTravellerManager
                    .get(ws).getVirtualPosition(leader);
        }
        return null;
    }

    /**
     * Virtualize now, resolving the soft blockers {@link #canVirtualizeIgnoringCombat()} normally
     * obeys instead of waiting them out: a boat this mod spawned is dismounted and killed, and a
     * raised hand is dropped. A bonded horse mount is NOT dismounted here - it is an allowed rider
     * (see {@link #canVirtualizeIgnoringCombat()}) and {@code tryVirtualize()} below captures it
     * along with the entity.
     *
     * <p>Used by the two paths that get no second chance. {@link com.jubitus.travellers.world.virtualization.ChunkUnloadVirtualizeHandler}
     * is one: whatever isn't captured as the chunk unloads is written into the chunk file instead and
     * stops existing as far as the offscreen simulation is concerned. The other is a unit vanilla has
     * stopped ticking ({@link com.jubitus.travellers.world.virtualization.VirtualTravellerManager#isFrozenByVanilla}),
     * where the blockers are not merely inconvenient but permanent: an entity that stopped ticking
     * mid-meal keeps {@code isHandActive()} true forever, so declining to virtualize it for that
     * reason leaves it standing at the border for good.
     *
     * <p>Anything still ineligible after that (already dead, no server world) is left alone.
     */
    public boolean forceVirtualizeResolvingBlockers() {
        if (isDead) return false;
        if (!(world instanceof WorldServer)) return false;

        if (isRiding() && !isMountedOnBondedMount()) {
            Entity ride = getRidingEntity();
            dismountRidingEntity();
            if (ride instanceof EntityTravellerBoat) {
                ((EntityTravellerBoat) ride).requestKill();
            }
        }
        if (isHandActive()) resetActiveHand();
        // A flight cannot simply be waited out here: this path exists for units vanilla has stopped
        // ticking, where the controller will never get another tick to land them.
        boolean wasFlying = elytraFlight.isActive();
        if (wasFlying) {
            elytraFlight.reset();
            setElytraFlying(false);
        }

        // virtualizeNow(), NOT tryVirtualize(): the eligibility gate is deliberately bypassed here.
        // Every condition it protects assumes the unit is still running its AI and will finish what it
        // is doing, and this method exists precisely for the cases where it never will - a frozen unit,
        // or a chunk about to be serialized. Re-asking the gate is what left units stuck forever with a
        // blocker that can no longer end: a worker awaiting a pickup nobody can perform, one parked
        // inside its job village's arrival radius, a hand raised mid-meal. The physical blockers were
        // just resolved above, so there is nothing left for the gate to legitimately catch.
        boolean virtualized = virtualizeNow();
        // virtualizeNow() can still refuse (a record already owns this identity) after the flight was
        // already cleared
        // above - left as-is, that strands the entity mid-air with the elytra flag off and nobody
        // steering it: an uncontrolled freefall from whatever height it was at, wings still on its back.
        // Re-arm a forced landing instead so it comes down under control.
        if (!virtualized && wasFlying) {
            elytraFlight.emergencyRecover();
        }
        return virtualized;
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
        return virtualizeNow();
    }

    /**
     * The virtualize itself, with no eligibility check. Only two callers, and both have already
     * established that this unit must leave the world: {@link #tryVirtualize()} after passing the gate,
     * and {@link #forceVirtualizeResolvingBlockers()} after resolving the physical blockers by hand.
     */
    private boolean virtualizeNow() {
        if (!(world instanceof net.minecraft.world.WorldServer)) return false;
        net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
        com.jubitus.travellers.world.virtualization.VirtualTravellerManager mgr =
                com.jubitus.travellers.world.virtualization.VirtualTravellerManager.get(ws);
        // Workers carry hire/mission/escort state that virtualizeTraveller() doesn't know how to
        // capture (it only writes the plain traveller/raider fields) - they must go through the
        // worker-specific virtualize path or that state is silently dropped.
        // The manager's own answer, never an assumed true: it refuses when a virtual record already
        // holds this identity, and the caller that matters most (ChunkUnloadVirtualizeHandler) is
        // relying on "returned true" meaning "this entity is dead now" - its whole safety argument is
        // that the body is gone before the chunk is serialized.
        if (this instanceof EntityTravellerWorker) {
            return mgr.virtualizeWorker(ws, (EntityTravellerWorker) this, world.getTotalWorldTime());
        }
        return mgr.virtualizeTraveller(ws, this, world.getTotalWorldTime());
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

    /** Whether a hello is still due to this player: not if this traveller's party greeted theirs lately. */
    boolean shouldHelloFor(EntityPlayer p) {
        UUID me = Greetings.partyOf(this);
        return me == null || !Greetings.hasGreeted(world, me, p.getUniqueID());
    }

    void markHello(EntityPlayer p) {
        UUID me = Greetings.partyOf(this);
        if (me != null) Greetings.record(world, me, p.getUniqueID());
    }

    public void playHelloSound() {
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

    /**
     * Called by {@code EntityAITravel} when its own stuck-rescue escalation has repeatedly
     * failed to find any loaded destination to teleport/detour to — a precise signal that the
     * mob is pinned against the edge of the loaded/view area (rather than merely stuck on
     * terrain), since every rescue candidate ahead lives in a chunk that {@code isAreaLoaded}
     * refuses to path or teleport into. Trap: gating this on the mob's OWN chunk via
     * {@code isChunkNeededNow} (an earlier version of this fix) is wrong and never fires right at
     * a player's own view-distance edge specifically - the mob's own position, by construction,
     * stays inside the player-watched view-distance square (hence "needed") for its entire walk up
     * to the boundary, only ever becoming "not needed" once genuinely past it, which it can never
     * physically reach on foot (that region isn't loaded). It happened to still work near a
     * village/other force-loaded pocket only because those tickets are typically patchy (only the
     * building footprint, not a uniform square), so a mob could stand in a gap between forced
     * chunks well before reaching the visual edge - a coincidence of chunk-loading topology, not a
     * sound rule. The real signal that this mob is genuinely blocked is already the caller's own
     * exhaustive multi-directional search (computeNextWaypointOrHandleBlocked / the stall backstop
     * both try every direction, including sideways/behind, before giving up) - nothing here needs
     * to re-derive that from chunk state. Only a small, fixed (not view-distance-scaled) sanity
     * distance is kept, purely to stop a pathologically tiny view-distance setting from popping a
     * mob out right in front of someone. This is how a physical traveller/raider/worker walking
     * toward an unloaded area hands off to the offscreen simulation instead of getting stuck pacing
     * at the edge forever - workers used to be excluded here and rely on TravellerChunkManager
     * force-loading their own chunk instead, but that self-loading is gone (it fought
     * materialize/virtualize now being driven by real chunk-loaded state, see
     * VirtualTravellerManager), so workers go through this exact same path as travellers/raiders.
     */
    public boolean tryVirtualizeStuckAtEdge(@Nullable BlockPos goal) {
        if (this.world.isRemote || !(this.world instanceof WorldServer)) return false;
        if (this.isAutoEating() || this.isHandActive()) return false;
        // Stuck on a cliff or a wall is not a reason to disappear: only the loaded border is.
        if (!isGroundAheadUnloaded(goal)) return false;

        final WorldServer ws = (WorldServer) this.world;
        double minDist = TravellersConfig.TRAVEL.edgeStuckMinPlayerDistance;
        if (minDist > 0 && ws.getClosestPlayer(this.posX, this.posY, this.posZ, minDist, false) != null) {
            return false;
        }

        // Resolve the soft blockers rather than obeying them. This path is only ever reached after the
        // caller has exhaustively established that the unit is going nowhere, so a blocker here is not
        // "wait, it is busy" but "it will be busy forever". A boat is the case that made this visible:
        // riding one fails the ordinary gate, EntityAITravel's boat branch returns before the stall
        // ladder that would notice, and checkVirtualize's non-frozen path refuses it too - so a traveller
        // whose boat ran out of resolvable waypoints at the edge of loaded terrain sat in the water
        // indefinitely. Plain tryVirtualize() also meant falling through to setDead() below, silently
        // deleting the unit instead of handing it to the offscreen simulation.
        com.jubitus.travellers.world.virtualization.VirtualizationLog.virtualize("stuck at loaded border", this);
        return forceVirtualizeResolvingBlockers();
    }

    public boolean hasPlayerPassenger() {
        if (this.getPassengers().isEmpty()) return false;
        for (Entity e : this.getPassengers()) {
            if (e instanceof EntityPlayer) return true;
        }
        return false;
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
            // Don't show when idling at village or camping
            if (travelCommon.isVillageIdling() || isCampingPassive()) return;
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

    public boolean hasTravelTorchVisible() {
        return isGhostTorch(getItemStackFromSlot(EntityEquipmentSlot.OFFHAND));
    }

    public boolean isVillageIdling() {  // you already store this
        return travelCommon.isVillageIdling();
    }

    public void setVillageIdling(boolean villageIdling) {
        travelCommon.setVillageIdling(villageIdling);
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

    /**
     * "Is this entity's own ground chunk loaded" - deliberately the exact same
     * {@link com.jubitus.travellers.world.virtualization.VirtualTravellerManager#isChunkLoaded}
     * predicate that decides materialize/virtualize eligibility, not an independent distance
     * approximation. Used to be its own "is a player roughly within view distance" circle check, which
     * could disagree with the chunk-loaded predicate (near the corners of the loaded square, or once a
     * chunk is kept loaded by something other than raw player distance) - callers of this method (the
     * travel AI's "don't bother pathfinding, nobody could be watching" bail-out chief among them) would
     * then freeze a unit that was, by the other predicate, still fully physical and meant to be acting
     * normally. The name is now a misnomer kept for its call sites' sake: a player's view distance is
     * only one of the reasons a chunk can be loaded.
     */
    public boolean isInPlayerViewDistanceApprox() {
        if (world == null) return true;
        if (world.isRemote) return true;
        if (!(world instanceof net.minecraft.world.WorldServer)) return true;
        net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
        int cx = net.minecraft.util.math.MathHelper.floor(posX) >> 4;
        int cz = net.minecraft.util.math.MathHelper.floor(posZ) >> 4;
        return com.jubitus.travellers.world.virtualization.VirtualTravellerManager.isChunkLoaded(ws, cx, cz);
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
        // Hired workers only wear it on a mission (flag is synced; the mission fields are server-only)
        if (this instanceof EntityTravellerWorker && ((EntityTravellerWorker) this).isHiredFollower()) {
            return ((EntityTravellerWorker) this).isMissionCarrying() && !isBackpackHiddenByCombat();
        }

        IBackpackStyle style = BackpackRegistry.resolve(this);
        if (style != null && !style.isHideable()) return true;

        return !isBackpackHiddenByCombat();
    }

    public boolean isBackpackHiddenByCombat() {
        int last = this.dataManager.get(LAST_COMBAT_TICK);
        int now = (int) world.getTotalWorldTime();
        return (now - last) < COMBAT_HIDE_TICKS;
    }

    public EntityAITravel getTravelAI() {
        return this.aiTravel;
    }

    private final com.jubitus.travellers.entity.entityai.travel.StuckRecord stuckRecord =
            new com.jubitus.travellers.entity.entityai.travel.StuckRecord();

    public com.jubitus.travellers.entity.entityai.travel.StuckRecord getStuckRecord() {
        return stuckRecord;
    }

    public com.jubitus.travellers.world.pathing.itinerary.ItineraryState getItineraryState() {
        return itineraryState;
    }

    /** Handed over from/to the virtual record; never null. */
    public void setItineraryState(@Nullable com.jubitus.travellers.world.pathing.itinerary.ItineraryState state) {
        this.itineraryState = state != null ? state : new com.jubitus.travellers.world.pathing.itinerary.ItineraryState();
    }

    /** True when this unit's travel follows an itinerary (mirrors VirtualTravellerManager.routeDrivenMovement). */
    public boolean isItineraryDriven() {
        return groupLeaderUUID == null && !isCatchupTravel();
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
        if (!PathScheduler.tryAcquire(world, schedOwner, this.getEntityId(), priority, PathBody.isMounted(this))) {
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
        if (world.isRemote) return;

        Entity trueSrc = (source == null) ? null : source.getTrueSource();
        Entity immediateSrc = (source == null) ? null : source.getImmediateSource();
        boolean playerCaused = trueSrc instanceof EntityPlayer || immediateSrc instanceof EntityPlayer;

        if (!playerCaused && !TravellerCrowdSoundLimiter.allowHurt(this)) {
            return;
        }

        // Routed through the voice bus (instead of a plain vanilla one-shot) so hurt barks
        // get the same distance falloff/fade as talk/attack/kill/death, and so it naturally
        // interrupts (crossfades out) whatever voice line was playing when the hit landed.
        SoundEvent hurt = getHurtSound(source);
        if (hurt == null) return;

        int dur = 20 + rand.nextInt(10);
        playVoiceMoving(hurt, getSoundVolume(), getSoundPitch(), dur, true, VoiceKind.HURT);
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