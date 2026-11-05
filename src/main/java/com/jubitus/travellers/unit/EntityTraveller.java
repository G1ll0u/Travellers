package com.jubitus.travellers.unit;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.stay.StayState;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;


import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.unit.backpack.BackpackRegistry;
import com.jubitus.travellers.unit.backpack.IBackpackStyle;
import com.jubitus.travellers.sound.ModSounds;
import com.jubitus.travellers.sound.TravellerCrowdSoundLimiter;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.unit.culture.TravellerAppearance;
import com.jubitus.travellers.unit.culture.VoiceSet;
import com.jubitus.travellers.ambient.EntityAIAutoEat;
import com.jubitus.travellers.ambient.EntityAIFishWhileTravel;
import com.jubitus.travellers.stay.EntityAIStay;
import com.jubitus.travellers.gaze.EntityAIStopAndLookAtPlayer;
import com.jubitus.travellers.combat.AttackTargetLimiter;
import com.jubitus.travellers.combat.TravellerCombatLogic;
import com.jubitus.travellers.combat.ai.EntityAIAttackMeleeCustom;
import com.jubitus.travellers.combat.TravellerItemDamage;
import com.jubitus.travellers.unit.equipment.TravellerEquipmentManager;
import com.jubitus.travellers.combat.ai.EntityAIDefendIfClose;
import com.jubitus.travellers.combat.ai.EntityAIRangedBowTravellerSafe;
import com.jubitus.travellers.flight.ElytraFlightController;
import com.jubitus.travellers.follow.FollowRecoveryHelper;
import com.jubitus.travellers.follow.FollowRuntimeConfig;
import com.jubitus.travellers.follow.PathScheduler;
import com.jubitus.travellers.travel.EntityAIPassDoor;
import com.jubitus.travellers.travel.TravellerDoorways;
import com.jubitus.travellers.travel.EntityAITravel;
import com.jubitus.travellers.travel.TravelCommon;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import net.minecraft.entity.passive.EntityHorse;
import com.jubitus.travellers.combat.ai.TravellerHurtByTarget;
import com.jubitus.travellers.combat.compat.AncientWarfare2Compat;
import com.jubitus.travellers.combat.lists.EntityRuleList;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.choir.net.MsgChoirSingerMute;
import com.jubitus.travellers.sound.net.MsgPlayTravellerVoice;
import com.jubitus.travellers.sound.net.MsgStopTravellerVoice;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.pathing.GapJumpEvaluator;
import com.jubitus.travellers.pathing.GapJumpResult;
import com.jubitus.travellers.pathing.PathBody;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.*;
import com.jubitus.travellers.gaze.TravellerBodyHelper;
import com.jubitus.travellers.gaze.Greetings;
import com.jubitus.travellers.gaze.TravellerGaze;
import com.jubitus.travellers.gaze.TravellerGreeter;
import com.jubitus.travellers.gaze.TravellerLookHelper;
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
    private static final DataParameter<Boolean> AIMING_BOW =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    // Mirrors isInCombat() so the client refuses right-clicks itself (the player's item use, e.g. a shield, then goes through).
    private static final DataParameter<Boolean> IN_COMBAT =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    // --- Camp sleep pose (synced for render only, never persisted to NBT: virtualization must
    // never rematerialize an entity mid-sleep, see EntityAICampStay) ---
    static final DataParameter<Boolean> SLEEPING =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    static final DataParameter<BlockPos> SLEEPING_BED_POS =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BLOCK_POS);
    // Sitting pose (TravellerSit): synced for render only, never persisted.
    static final DataParameter<Boolean> SITTING =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    static final DataParameter<Float> SIT_YAW =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.FLOAT);
    // Snack while seated (TravellerSnack): shown only, never persisted.
    static final DataParameter<ItemStack> SNACK =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.ITEM_STACK);
    static final DataParameter<Boolean> SNACK_AT_MOUTH =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    // Set only by /travellersdebug path test: a throwaway entity for comparing a mounted route
    // against a hand-marked one. Never persisted - it exists for one test run and is deleted by its
    // own watchdog, never handed off to the offscreen simulation.
    private boolean debugPathTest = false;
    // Gear score taken when the unit dies, for the experience vanilla counts a second later.
    private double gearAtDeath = Double.NaN;
    // Configuration-ish knobs
    private static final DataParameter<String> CULTURE_ID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.STRING);

    // NEW: chosen voice personality (saved + synced)
    static final DataParameter<String> VOICE_PERSONALITY_ID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.STRING);

    // UUID of this entity's bonded horse companion - must be synced (not just server-side), since the
    // client reads isMountedOnBondedMount() for its own local seat-position calculation every tick
    // (see getYOffset()), independent of whatever the last position sync packet said.
    private static final DataParameter<com.google.common.base.Optional<UUID>> MOUNT_UUID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.OPTIONAL_UNIQUE_ID);

    // Group leader this entity follows (absent = leader or solo); synced so the client knows groupmates (arrows).
    private static final DataParameter<com.google.common.base.Optional<UUID>> GROUP_LEADER_UUID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.OPTIONAL_UNIQUE_ID);

    // NBT key (must match exactly in write/read)
    private static final String NBT_VOICE_PERSONALITY_ID = "VoicePersonalityId";


    private static final double EMERGENCY_ENGAGE_RANGE_SQ = EMERGENCY_ENGAGE_RANGE * EMERGENCY_ENGAGE_RANGE;
    public final TravellerCombatLogic combat = new TravellerCombatLogic(this);
    public final TravellerEquipmentManager equipment = new TravellerEquipmentManager(this);
    public final com.jubitus.travellers.combat.ShieldGuard shield = new com.jubitus.travellers.combat.ShieldGuard(this);
    public final ElytraFlightController elytraFlight = new ElytraFlightController(this);
    public final TravellerSleep sleep = new TravellerSleep(this);
    public final TravellerSit sit = new TravellerSit(this);
    public final TravellerSnack snack = new TravellerSnack(this);
    public final TravellerVoice voice = new TravellerVoice(this);
    public final TravellerHandOff handOff = new TravellerHandOff(this);
    public final FollowCatchup catchup = new FollowCatchup(this);
    public final ItemStackHandler inventory = new ItemStackHandler(18); // 18 slots example
    final TravelCommon travelCommon = new TravelCommon(this);
    public static final String ITINERARY_NBT_KEY = "Itin";
    // Current leg's itinerary + cursor, shared with the virtual record at virtualize/materialize.
    private com.jubitus.travellers.pathing.itinerary.ItineraryState itineraryState =
            new com.jubitus.travellers.pathing.itinerary.ItineraryState();
    public final com.jubitus.travellers.dialogue.memory.TravellerMemoryStore dialogueMemory =
            new com.jubitus.travellers.dialogue.memory.TravellerMemoryStore();
    private final int retargetPhase = this.rand.nextInt(10); // 0..9
    private final Map<UUID, Integer> ignoreTargetsUntil = new java.util.HashMap<>();
    public int nextTravelAmbientTick = 0;  // you already had this
    EntityAIAutoEat autoEatTask;
    EntityAIRangedBowTravellerSafe aiRanged;
    EntityAIAttackMeleeCustom aiMelee;
    EntityAIDefendIfClose aiDefendIfClose;
    EntityAITravel aiTravel;
    com.jubitus.travellers.follow.EntityAIFollowLeader aiFollowGroupLeader;

    /**
     * Chosen once, at group creation, for whichever entity is the group's leader - followers read
     * it off their resolved leader (loaded or virtual), never their own. Meaningless on a solo
     * traveller/raider or a follower (kept only so it round-trips cleanly through NBT/virtualize
     * regardless of role - simpler than a nullable field with role-dependent meaning).
     */
    private com.jubitus.travellers.follow.formations.WorkerFormationStyle groupFormationStyle =
            com.jubitus.travellers.follow.formations.WorkerFormationStyle.RING;

    public com.jubitus.travellers.follow.formations.WorkerFormationStyle getGroupFormationStyle() {
        return groupFormationStyle;
    }

    public void setGroupFormationStyle(com.jubitus.travellers.follow.formations.WorkerFormationStyle style) {
        this.groupFormationStyle = style == null
                ? com.jubitus.travellers.follow.formations.WorkerFormationStyle.RING : style;
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
            travelMotive = com.jubitus.travellers.dialogue.TravelMotiveDialogueContextProvider.rollMotive(this);
        }
        return travelMotive;
    }

    // UUID of this entity's bonded horse companion (null = on foot). A permanent 1:1 pairing, not a
    // group resource - see TravellerMountSupport for spawn/bond/anti-theft and MountedNavigator for
    // steering. Backed entirely by the MOUNT_UUID DataParameter (synced) - no separate server-only
    // field, so there is only one source of truth to keep consistent between the two sides.
    @Nullable
    public UUID getMountUUID() {
        return this.dataManager.get(MOUNT_UUID).orNull();
    }

    public void setMountUUID(@Nullable UUID mountUUID) {
        this.dataManager.set(MOUNT_UUID, com.google.common.base.Optional.fromNullable(mountUUID));
    }

    // The current village stay (see StayState); moved to/from the virtual record at hand-off.
    @Nullable
    private StayState villageVisitState;

    @Nullable
    public StayState getStayState() {
        return villageVisitState;
    }

    public void setStayState(@Nullable StayState state) {
        this.villageVisitState = state;
    }

    public final TravellerMountState mountState = new TravellerMountState(this);

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
        com.jubitus.travellers.village.MillFootprints.Footprint f =
                com.jubitus.travellers.village.MillFootprints.at(world, x, y, z, 0);
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
            if (hop > 16.0D) mountState.clearLeadTrail();
        }
        // A horse being led on a leash must come along for the same reason a ridden one must: every
        // one of those rescue hops exists to get this entity out of somewhere it was stuck, and
        // leaving its horse behind is how a rider ends up permanently separated from it. A hitched
        // horse is deliberately left alone - staying put is the entire point of that mode.
        if (!isMountedOnBondedMount() && mountState.getMountMode() == MountMode.LED) {
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
    int spawnAmbientCooldownTicks = 0;                       // counts down server-side
    // server-side only
    private int trackingPlayers = 0;
    private long lastPathCheckTick = Long.MIN_VALUE;
    private int lastPathCheckTargetId = -1;
    private boolean lastPathCheckResult = false;
    private int speakCooldown = 0;
    private int forcedSwingTicks = 0;
    private int hurtAnimCooldown = 0;
    private int travelRecoverTicks = 0;
    // --- Voice channel ---
    private int hurtSoundCooldownUntilTick = 0;
    private boolean choirSinging = false;   // managed by ChoirCoordinator, server-side only
    @Nullable
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
        this.stepHeight = 1.1F;
        // Vanilla's move helper turns up to 90 degrees per tick, which AbstractHorse.travel() copies
        // straight onto a horse's body. See TravellerMoveHelper - identical to vanilla on foot.
        this.moveHelper = new com.jubitus.travellers.pathing.TravellerMoveHelper(this);
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
        return getGroupLeaderUUID() != null || isCatchupTravel();
    }

    // Whether the auto-planner may give this unit a new roaming route right now.
    protected boolean shouldAutoPlanRoute() {
        return true;
    }

    // Raiders and workers re-roll their appearance after onInitialSpawn, then equip themselves.
    protected boolean rerollsAppearanceAtSpawn() {
        return false;
    }

    // Only a hired worker has an owner.
    public boolean isOwner(@Nullable EntityLivingBase target) {
        return false;
    }

    public void ignoreTarget(EntityLivingBase e, int ticks) {
        if (e == null) return;
        ignoreTargetsUntil.put(e.getUniqueID(), this.ticksExisted + Math.max(1, ticks));
    }

    public void unignoreTarget(EntityLivingBase e) {
        ignoreTargetsUntil.remove(e.getUniqueID());
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
        return Math.max(dynamicMax, groupAttackerCapFloor(Math.max(1, TravellersConfig.COMBAT.groupSizeAttackerCapDivisor)));
    }

    // Hired workers raise the attacker cap to their group size / divisor; 0 = no floor (the cap is always >= 1).
    protected int groupAttackerCapFloor(int divisor) {
        return 0;
    }

    // Only hired workers have an owner to assist.
    public boolean isOwnerStillFightingAssistTarget(EntityLivingBase target) {
        return false;
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
        return pickAppearance(c, getTextureIndex());
    }

    // The appearance pool of this unit type (raiders and workers have their own).
    protected TravellerAppearance pickAppearance(CultureDef c, int textureIndex) {
        return c.getAppearance(textureIndex);
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


    /** Whether this unit stops to visit each village of its trip ({@link EntityAIStay}). */
    public boolean usesStays() {
        return TravellersConfig.VILLAGE_VISIT.enabledTravellers;
    }

    @Nullable
    private EntityAIStay stayAI;

    @Nullable
    public EntityAIStay getStayAI() {
        return stayAI;
    }

    protected void setStayAI(@Nullable EntityAIStay ai) {
        this.stayAI = ai;
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
    public boolean isOnStay() {
        return stayAI != null && stayAI.isRunning();
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
        if (!TravellersConfig.VILLAGE_VISIT.followersDisperse || !usesStays()) return false;
        BlockPos stop = getLeaderStayStop();
        if (stop == null) return false;
        com.jubitus.travellers.stay.StayPlace place = com.jubitus.travellers.stay.StayPlace.at(world, stop);
        return place != null && place.contains(posX, posZ);
    }

    /** The village the group leader is staying in right now, loaded or offscreen; null otherwise. */
    @Nullable
    public BlockPos getLeaderStayStop() {
        UUID id = getGroupLeaderUUID();
        if (id == null) return null;
        EntityTraveller leader = getLoadedGroupLeader();
        if (leader != null) {
            EntityAIStay visit = leader.getStayAI();
            return visit != null && visit.isStaying() ? visit.getStayStop() : null;
        }
        if (!(world instanceof WorldServer)) return null;
        com.jubitus.travellers.virtual.VirtualTravellerRecord r =
                com.jubitus.travellers.virtual.VirtualTravellerManager.get((WorldServer) world).getRecord(id);
        StayState st = r == null ? null : r.visit;
        return st != null && !st.isLeaving() && !st.asFollower ? st.stop : null;
    }

    /** Group followers leave their follow AI alone while dispersed in a village (or finishing that visit). */
    public boolean isDispersedInStay() {
        if (getGroupLeaderUUID() == null) return false;
        return isOnStay() || canDisperseWithLeader();
    }

    /** The settlement this unit is about to visit (its own next stop, or its leader's), or null. */
    @Nullable
    public BlockPos getUpcomingStayStop() {
        if (!usesStays()) return null;
        EntityTraveller ref = this;
        if (getGroupLeaderUUID() != null) {
            ref = getLoadedGroupLeader();
            if (ref == null || !ref.usesStays()) return null;
        }
        BlockPos stop = ref.getTravelCommon().getTargetVillage();
        if (stop == null || stop.equals(ref.getTravelCommon().getVisitedStop())) return null;
        return stop;
    }

    // The settlement whose edge this rider hitched its horse at, to visit it on foot. A HITCHED horse
    // carrying this marker is never recalled; see MountModes.reconcileOnFoot.
    @Nullable
    private BlockPos hitchStop;

    @Nullable
    public BlockPos getHitchStop() {
        return hitchStop;
    }

    public void setHitchStop(@Nullable BlockPos stop) {
        this.hitchStop = stop;
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





    public int getSpawnAmbientCooldownTicks() {
        return spawnAmbientCooldownTicks;
    }

    public void setNextTravelAmbientTick(int tick) {
        this.nextTravelAmbientTick = tick;
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


        aiMelee = new EntityAIAttackMeleeCustom(this, true, 0.00);
        this.tasks.addTask(4, aiMelee);

        aiFollowGroupLeader = new com.jubitus.travellers.follow.EntityAIFollowLeader(
                this, 1.0F, 1.0F);
        this.tasks.addTask(5, aiFollowGroupLeader);

        aiDefendIfClose = new EntityAIDefendIfClose(this, TravellersConfig.COMBAT.unitAttackTriggerRange, true);

        this.targetTasks.addTask(1, aiDefendIfClose);

        this.targetTasks.addTask(2, new TravellerHurtByTarget(this, true, TravellersConfig.COMBAT.helpRange));
        autoEatTask = new EntityAIAutoEat(this, TravellersConfig.COMBAT.eatHealthThreshold);
        this.tasks.addTask(8, autoEatTask);

        if (com.jubitus.travellers.config.TravellersConfig.CAMP_EVENTS.enabled) {
            // New camp event system: keep occupants at their slot (build is handled by the manager).
            this.tasks.addTask(6, new com.jubitus.travellers.camp.EntityAICampStay(this));
        }

        this.tasks.addTask(9, new EntityAIStopAndLookAtPlayer(this));
        if (TravellersConfig.TRAVEL.fishWhileTravelEnabled) {
            this.tasks.addTask(22, new EntityAIFishWhileTravel(this));
        }

        // Utility / travel
        this.tasks.addTask(23, new EntityAIPassDoor(this));

        stayAI = new EntityAIStay(this);
        this.tasks.addTask(25, stayAI);

        aiTravel = new EntityAITravel(this, TravellersConfig.ATTRIBUTES.stepBlocks);
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
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(UnitSpeed.ATTRIBUTE);
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

    // Every rule deciding whether a target is taken lives in TravellerCombatLogic.setAttackTarget.
    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        combat.setAttackTarget(target);
    }

    // The vanilla setter, for TravellerCombatLogic only (it applies the rules first).
    public void vanillaSetAttackTarget(@Nullable EntityLivingBase target) {
        super.setAttackTarget(target);
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
        this.dataManager.register(IN_COMBAT, Boolean.FALSE);
        this.dataManager.register(SLEEPING, Boolean.FALSE);
        this.dataManager.register(SLEEPING_BED_POS, BlockPos.ORIGIN);
        this.dataManager.register(SITTING, Boolean.FALSE);
        this.dataManager.register(SIT_YAW, 0.0F);
        this.dataManager.register(SNACK, ItemStack.EMPTY);
        this.dataManager.register(SNACK_AT_MOUTH, Boolean.FALSE);
        this.dataManager.register(MOUNT_UUID, com.google.common.base.Optional.absent());
        this.dataManager.register(GROUP_LEADER_UUID, com.google.common.base.Optional.absent());
    }
    public static void dbg(String s) {
        System.out.println("[TravellersDBG] " + s);
    }

    @Override
    protected int getExperiencePoints(EntityPlayer player) {
        return UnitWorth.experience(this, Double.isNaN(gearAtDeath) ? UnitWorth.gearScore(this) : gearAtDeath);
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
        if (id == 29) return; // shield block: the sound is played server side in damageShield

        super.handleStatusUpdate(id);
    }

    @Override
    public void onUpdate() {
        dropBoatIfOffscreen();
        super.onUpdate();
        // Edge probe for EntityAIFollowLeader followers only - see tickEdgeVirtualizeProbe's class
        // doc. getEdgeProbeGoal() returns null for everyone else, making this a no-op for them.
        if (!world.isRemote && handOff.tickEdgeProbe(getEdgeProbeGoal())) return;
        if (!world.isRemote) voice.tick();
        if (world.isRemote) return;

        combat.tickEngagement();

        if (travelCommon.getDepartCooldownTicks() > 0)
            travelCommon.setDepartCooldownTicks(travelCommon.getDepartCooldownTicks() - 1);
        int rec = travelCommon.getTravelRecoverTicks();
        if (rec > 0) {
            travelCommon.setTravelRecoverTicks(rec - 1);
        }

        combat.tickTargetLoss();

        if (TravellersConfig.SOUND.travellerAmbient && this.ticksExisted >= nextTravelAmbientTick) {
            travelCommon.maybePlayTravelAmbient();
        }
        equipment.handleWeaponVisibility();
        combat.decayAndPruneThreats();
        pruneIgnoredTargets();

        combat.tickChaseMemory();
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
            travelCommon.writeNbt(compound);

            compound.setInteger("LastCombatTick", this.dataManager.get(LAST_COMBAT_TICK));
            itineraryState.writeNbt(compound, ITINERARY_NBT_KEY);
            compound.setInteger("TextureIdx", getTextureIndex());
            compound.setFloat("RenderScale", getRenderScale());
            if (hitchStop != null) compound.setLong("VillageHitchStop", hitchStop.toLong());
            compound.setString("CultureId", getCultureId());
            compound.setString(NBT_VOICE_PERSONALITY_ID, voice.getPersonalityId());
            UUID groupLeader = getGroupLeaderUUID();
            if (groupLeader != null) compound.setUniqueId("GroupLeaderUUID", groupLeader);
            compound.setString("GroupFormationStyle", groupFormationStyle.name());
            if (travelMotive != null) compound.setString("TravelMotive", travelMotive.name());
            if (getMountUUID() != null) compound.setUniqueId("MountUUID", getMountUUID());
            if (villageVisitState != null) compound.setTag(StayState.NBT_KEY, villageVisitState.write());
            mountState.writeNbt(compound);
            if (homeVillage != null) {
                compound.setInteger("HomeVillageX", homeVillage.getX());
                compound.setInteger("HomeVillageY", homeVillage.getY());
                compound.setInteger("HomeVillageZ", homeVillage.getZ());
            }

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
        // The saved attribute may predate the fixed speed scale.
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(UnitSpeed.ATTRIBUTE);
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
        travelCommon.readNbt(compound);
        if (compound.hasKey("TravellerInv", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            inventory.deserializeNBT(compound.getCompoundTag("TravellerInv"));
        }
        if (compound.hasKey("TextureIdx")) setTextureIndex(compound.getInteger("TextureIdx"));
        if (compound.hasKey("RenderScale")) setRenderScale(compound.getFloat("RenderScale"));
        if (compound.hasKey("CultureId")) setCultureId(compound.getString("CultureId"));

        if (!this.world.isRemote) {
            if (compound.hasKey(NBT_VOICE_PERSONALITY_ID)) {
                voice.setPersonalityId(compound.getString(NBT_VOICE_PERSONALITY_ID));
            } else {
                voice.setPersonalityId(""); // older worlds: keep empty -> old fallback behavior
            }
        }


        itineraryState.readNbt(compound, ITINERARY_NBT_KEY);
        hitchStop = compound.hasKey("VillageHitchStop") ? BlockPos.fromLong(compound.getLong("VillageHitchStop")) : null;
        setGroupLeaderUUID(compound.hasUniqueId("GroupLeaderUUID") ? compound.getUniqueId("GroupLeaderUUID") : null);
        if (compound.hasKey("GroupFormationStyle")) {
            try {
                groupFormationStyle = com.jubitus.travellers.follow.formations.WorkerFormationStyle
                        .valueOf(compound.getString("GroupFormationStyle"));
            } catch (IllegalArgumentException ignored) {
                groupFormationStyle = com.jubitus.travellers.follow.formations.WorkerFormationStyle.RING;
            }
        } else {
            groupFormationStyle = com.jubitus.travellers.follow.formations.WorkerFormationStyle.RING;
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
        // Always replaced, never merged: a stay that ended must not survive in a stale snapshot.
        villageVisitState = StayState.read(compound.hasKey(StayState.NBT_KEY)
                ? compound.getCompoundTag(StayState.NBT_KEY) : null);
        mountState.readNbt(compound);
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
        super.setAIMoveSpeed(Math.min(speed, UnitSpeed.maxAiSpeed(this)));
    }

    @Override
    public void onLivingUpdate() {

        if (!world.isRemote) {
            voice.choosePersonalityIfNeeded();
            if (!this.world.isRemote) {
                ensureDismountIfInCombat();
            }

            if (spawnAmbientCooldownTicks > 0) spawnAmbientCooldownTicks--;

            HazardRepulsion.tick(this);
            if (getMountUUID() != null) mountState.recordLeadTrailPoint();
            else mountState.clearLeadTrail();
            TravellerMountSupport.tickMount(this);
            tickVirtualGroupLeaderFollow();
            catchup.tickExit();
            gaze.tickAwareness();
            greeter.tick();
            com.jubitus.travellers.debug.trace.TravellerTracer.tick(this);
            com.jubitus.travellers.debug.profile.MovementProfiler.tick(this);
            com.jubitus.travellers.debug.freeze.FreezeWatchdog.tick(this);
            com.jubitus.travellers.debug.trace.MountWedgeWatch.tick(this);
            headWriters = "";
        }

        float headBeforeAi = rotationYawHead;
        super.onLivingUpdate();
        if (!world.isRemote && headWriters.isEmpty() && rotationYawHead != headBeforeAi) noteHeadWriter("vanilla");

        // Undo any drift super.onLivingUpdate() may have caused (nearby-entity collision, stray AI
        // movement) after it's already run, so a sleeping traveller never visibly slides.
        if (!world.isRemote && sleep.isSleeping()) {
            sleep.holdPosition();
        }
        if (!world.isRemote) {
            sit.tick();
            snack.tick();
        } else {
            snack.clientTick();
        }

        // Clear revenge target immediately if the attacker is blacklisted
        if (!world.isRemote) {
            EntityLivingBase revenge = this.getRevengeTarget();
            if (revenge != null) {
                EntityRuleList aggroBl = getAggroBlacklist();
                if (aggroBl != null && aggroBl.matches(revenge)) {
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
        if (!world.isRemote) this.dataManager.set(IN_COMBAT, isInCombat());

        if (world.isRemote) tickElytraBank();
        if (world.isRemote && hurtAnimCooldown > 0) hurtAnimCooldown--;
        if (speakCooldown > 0) speakCooldown--;
        if (world.isRemote) {
            if (forcedSwingTicks > 0) forcedSwingTicks--;
        }
        if (!world.isRemote) {
            equipment.tickPickupCooldown();
            shield.tick();
        }

        // Pick an initial random target only once
        if (!world.isRemote) {
            travelCommon.updateNearestVillage();

            // 1) Ensure we have a plan (unless subclass has locked routing)
            if (!isRouteLocked()
                    && (travelCommon.routeIndex < 0 || travelCommon.routeIndex >= travelCommon.route.size())) {

                if (shouldAutoPlanRoute()) {
                    travelCommon.ensureRoutePlanned();
                }
            }


            // 2) Drive the current target from the route
            if (travelCommon.routeIndex >= 0 && travelCommon.routeIndex < travelCommon.route.size()) {
                BlockPos curStop = travelCommon.route.get(travelCommon.routeIndex);
                travelCommon.setTargetVillage(curStop);

                // A unit that visits villages leaves a stop only once its visit is over; the others
                // move on as soon as they reach it and are marked ready.
                boolean leave = usesStays()
                        ? curStop.equals(travelCommon.getVisitedStop())
                        : travelCommon.isAtTargetStop() && !travelCommon.isIdlingAtStop() && travelCommon.isReadyToDepart();

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
            // A shield lives in the offhand (unless the unit uses a bow); everything else goes to the backpack
            if (TravellerEquipmentManager.isShield(stack) && !equipment.hasBowAnywhere()) {
                super.setItemStackToSlot(slot, stack);
                return;
            }
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
            String villageCid = com.jubitus.travellers.spawn.SpawnCulture.pickNear(this.world, this.getPosition(), this.rand);
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
        voice.choosePersonalityIfNeeded();


        CultureDef c = CultureRegistry.get(cid);
        int tCount = (c != null) ? c.travellerAppearanceCount() : 0;
        setTextureIndex(this.rand.nextInt(Math.max(1, tCount <= 0 ? 1 : tCount)));
        rollRenderScale(getActiveAppearance());
        if (!world.isRemote) {
            travelMotive = com.jubitus.travellers.dialogue.TravelMotiveDialogueContextProvider.rollMotive(this);
        }

        // Reset move attr base and make sure no swim boost lingers
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.setBaseValue(UnitSpeed.ATTRIBUTE);
            AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
            if (m != null) attr.removeModifier(m);
        }

        if (!world.isRemote) {
            // Raiders/workers re-roll their texture index against their own appearance pool right
            // after this method returns (their pool differs from the plain traveller pool used
            // above), then call initSpawnEquipment themselves — calling it here too would read
            // equipment tied to the wrong (pre-reroll) appearance and be immediately redone anyway.
            if (!rerollsAppearanceAtSpawn()) {
                equipment.initSpawnEquipment(c, this.rand);
                assignCultureName();
            }

            // 3) Seed ambient window if needed
            if (nextTravelAmbientTick <= 0 || nextTravelAmbientTick == Integer.MAX_VALUE) {
                scheduleNextTravelAmbient();
            }
        }

        // Personality seeds
        this.setHealth(this.getMaxHealth());
        java.util.Random r = new java.util.Random(this.getUniqueID().getMostSignificantBits()
                ^ this.getUniqueID().getLeastSignificantBits());
        this.travelCommon.wanderlust = 0.25f + r.nextFloat() * 0.60f;
        this.travelCommon.exploreChance = 0.08f + r.nextFloat() * 0.06f;
        this.travelCommon.hopPreference = 0.45f + r.nextFloat() * 0.25f;
        this.travelCommon.pathLaneOffset = 0;
        this.travelCommon.pathStepJitter = 0;
        this.travelCommon.recalcPhase = r.nextInt(12);

        return livingdata;
    }

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return !isInCombatSynced();
        if (this.isInCombat()) return false;
        if (!(player instanceof net.minecraft.entity.player.EntityPlayerMP)
                || !com.jubitus.travellers.config.TravellersConfig.DIALOGUE.enabled) {
            return talkTo(player, hand);
        }
        // Talk or follow: the choice screen's "Talk" comes back through MsgTalkToTraveller -> talkTo.
        com.jubitus.travellers.net.TravellersNetwork.sendToPlayer(player,
                new com.jubitus.travellers.follow.net.MsgOpenFollowChoice(this.getEntityId()));
        return true;
    }

    /** The plain "talk to" interaction: greeting voice, then dialogue screen or canned heading message. */
    public boolean talkTo(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;
        if (this.isInCombat()) return false;
        if (speakCooldown > 0) return true;

        speakCooldown = 20; // ~1s between barks
        doInteractSwing(hand);
        // Choose hello vs talk
        if (voice.shouldHelloFor(player)) {
            voice.playHello();         // one hello variation
            voice.markHello(player);        // start the 5-minute window for THIS player
        } else {
            voice.playTalk();          // normal chatter
        }
        // Dialogue master switch (server-side, off by default - see TravellersConfig.DIALOGUE and
        // the dialogue system's D-9): on, this replaces every canned message below with the real
        // dialogue GUI, regardless of whether this specific player has configured Ollama yet - the
        // server can't know that, so the client itself decides what to show once the GUI is open.
        if (com.jubitus.travellers.config.TravellersConfig.DIALOGUE.enabled) {
            com.jubitus.travellers.dialogue.DialogueContext context =
                    com.jubitus.travellers.dialogue.DialogueContextBuilder.build(this, player);
            // Built from state as of BEFORE this visit (metPlayerBefore reflects prior encounters,
            // never this one) - marked met only after the context is built.
            dialogueMemory.markMet(player.getUniqueID(), world.getTotalWorldTime());
            com.jubitus.travellers.net.TravellersNetwork.sendToPlayer(player,
                    new com.jubitus.travellers.dialogue.net.MsgOpenTravellerDialogue(
                            this.getEntityId(), context));
            return true;
        }

        // Keep your existing “village idle / announce heading” messages:
        if (travelCommon.isIdlingAtStop()) {
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
     * {@link #getRenderScale()} in {@link com.jubitus.travellers.unit.render.RenderTraveller}.
     * Driven from {@link #notifyDataManagerChange} so both sides (server set + client sync) resize.
     * Sleeping keeps its own tiny box until wake-up.
     */
    public void updateSizeForRenderScale() {
        if (sleep.isSleeping()) return;
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

    /** Names the unit from the lists of the villager type behind its current appearance. */
    protected final void assignCultureName() {
        CultureDef c = CultureRegistry.get(getCultureId());
        if (c == null) return;
        String fullName = c.randomFullName(getActiveAppearance(), this.rand);
        if (!fullName.isEmpty()) {
            this.setCustomNameTag(fullName);
            this.setAlwaysRenderNameTag(false);
        }
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

    /** Shrinks the hitbox to the sleeping pose (restored by {@link #updateSizeForRenderScale}). */
    void setSleepingSize() {
        this.setSize(0.2F, 0.2F);
    }

    @Override
    public boolean canBePushed() {
        if (sleep.isSleeping() || sit.isSitting()) return false;
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
        if (sleep.isSleeping() || campingPassive) return false;
        return super.isEntityInsideOpaqueBlock();
    }

    /**
     * Client-only: while asleep, sample render light at the midpoint of the VISIBLE body (shifted
     * toward the bed's foot by {@link TravellerSleep#getRenderOffset}) instead of the anchor cell near the
     * headboard — a shelter block directly above the bed head otherwise darkens the whole body even
     * though most of it lies in a lit cell.
     */
    @Override
    @SideOnly(Side.CLIENT)
    public int getBrightnessForRender() {
        if (sleep.isSleeping()) {
            Vec3d off = sleep.getRenderOffset();
            BlockPos p = new BlockPos(posX + off.x * 0.5D, posY + 0.5D, posZ + off.z * 0.5D);
            if (world.isBlockLoaded(p)) {
                return world.getCombinedLight(p, 0);
            }
        }
        return super.getBrightnessForRender();
    }

    /** Hook: the voice set attack barks use (raiders have their own). */
    protected VoiceSet resolveVoiceSetForAttack() {
        return voice.activeVoiceSet();
    }

    /** Hook: the voice set kill and death barks use (raiders have their own). */
    protected VoiceSet resolveVoiceSetForKillDeath() {
        return voice.activeVoiceSet();
    }

    public void doInteractSwing(EnumHand hand) {
        // Tell all nearby clients to render a swing (4 = main hand, 5 = offhand)
        world.setEntityState(this, (byte) (hand == EnumHand.OFF_HAND ? 5 : 4));
        // Also update server-side state so other logic that checks swing progresses is consistent
        this.swingArm(hand);
        // If you use your forcedSwingTicks visual, give it a nudge
        this.forcedSwingTicks = 6; // matches your handleStatusUpdate()
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
        BlockPos target = travelCommon.getTargetSettlement();
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

    // was: updateTargetVillage()
    private void updateNearestVillage() {

        travelCommon.updateNearestVillage();
    }

    // The reaction to a hit (armor wear, revenge, retaliation) lives in TravellerCombatLogic.onAttacked.
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (!world.isRemote) {
            sit.stand();
            snack.stop();
        }
        return combat.onAttacked(source, amount);
    }

    // Vanilla leaves damageShield empty for mobs: wear the offhand shield like a player's.
    @Override
    protected void damageShield(float damage) {
        shield.onBlocked();
        this.playSound(net.minecraft.init.SoundEvents.ITEM_SHIELD_BLOCK, 1.0F, 0.8F + this.world.rand.nextFloat() * 0.4F);
        if (damage >= 3.0F && this.activeItemStack.getItem().isShield(this.activeItemStack, this)) {
            this.activeItemStack.damageItem(1 + net.minecraft.util.math.MathHelper.floor(damage), this);
            if (this.activeItemStack.isEmpty()) {
                this.setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
                this.activeItemStack = ItemStack.EMPTY;
                this.playSound(net.minecraft.init.SoundEvents.ITEM_SHIELD_BREAK, 0.8F, 0.8F + this.world.rand.nextFloat() * 0.4F);
            }
        }
    }

    // A hit absorbed by the shield pushes the unit back half as much.
    @Override
    public void knockBack(Entity entityIn, float strength, double xRatio, double zRatio) {
        super.knockBack(entityIn, shield.blockedThisTick() ? strength * 0.5F : strength, xRatio, zRatio);
    }

    // The vanilla damage path, for TravellerCombatLogic only.
    public boolean vanillaAttackEntityFrom(DamageSource source, float amount) {
        // Entity hits (arrows, melee) use the configured protection (vanilla = 10, its window is the top half of the 20-tick timer); environmental damage keeps vanilla's.
        if (source.getTrueSource() != null && this.maxHurtResistantTime - this.hurtResistantTime >= TravellersConfig.COMBAT.hitProtectionTicks) {
            this.hurtResistantTime = 0;
        }
        return super.attackEntityFrom(source, amount);
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
            TravellersNetwork.CHANNEL.sendToAllAround(
                    new MsgStopTravellerVoice(this),
                    new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64.0));
            TravellerMountSupport.releaseMountOnRiderDeath(this);
            // Camp event system: free this traveller's slot so the camp updates / can be reused.
            com.jubitus.travellers.camp.CampsiteManager.get((net.minecraft.world.WorldServer) world)
                    .releaseOccupant(this.getUniqueID());
            // Virtual battles: mark this pawn dead so the offscreen sim stays consistent.
            com.jubitus.travellers.battle.VirtualBattleManager.get((net.minecraft.world.WorldServer) world)
                    .onParticipantDeath((net.minecraft.world.WorldServer) world, this, cause);
            com.jubitus.travellers.follow.GroupSuccession.onLeaderDied((net.minecraft.world.WorldServer) world, this);
            callAlliesAgainstKiller(cause);
        }
        voice.tryPlayDeath();
        if (!this.world.isRemote && this.isRiding() && this.getRidingEntity() instanceof net.minecraft.entity.item.EntityBoat) {
            net.minecraft.entity.item.EntityBoat boat = (net.minecraft.entity.item.EntityBoat) this.getRidingEntity();
            this.dismountRidingEntity();
            boat.setDead();
        }
        // Strip every ghost-tagged visual copy (sword/bow/torch/block/rod) before vanilla's drop
        // logic runs, or a clone with no attribute modifiers can be dropped alongside the real item.
        equipment.purgeGhostItems();
        if (!world.isRemote) {
            gearAtDeath = UnitWorth.gearScore(this);
            UnitWorth.dropCoins(this, gearAtDeath, recentlyHit > 0);
        }
        super.onDeath(cause);
    }


    @Override
    @javax.annotation.Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        if (this.ticksExisted < hurtSoundCooldownUntilTick) return null;
        hurtSoundCooldownUntilTick = this.ticksExisted + TravellersConfig.SOUND.hurtSoundEntityCooldownTicks;
        VoiceSet v = voice.activeVoiceSet();
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
            // A passenger is placed by its mount right after; its own physics pass is a wasted collision query.
            if (this.isRiding()) return;
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
        if (!shield.mayStrike()) return false; // like a player: no strike with the shield up or just lowered
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

    /** The one blacklist ("never fight these") for this unit's kind: raiders have their own. */
    public EntityRuleList getAggroBlacklist() {
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
            return TravellersDerivedConfig.RAIDER_ATTACK_LIST.matches(e);
        } else {
            // Normal/friendly travellers
            if (TravellersDerivedConfig.TRAVELLER_ATTACK_LIST.matches(e)) return true;
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
        return AncientWarfare2Compat.isHostile(e, getHiredOwner());
    }

    // The player this unit fights for (a hired worker's owner), or null.
    @Nullable
    public EntityPlayer getHiredOwner() {
        return null;
    }


    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        if (!this.world.isRemote) {
            com.jubitus.travellers.battle.VirtualBattleManager.get((net.minecraft.world.WorldServer) world)
                    .onParticipantLeftWorld((net.minecraft.world.WorldServer) world, this);
        }
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
        return this.dataManager.get(GROUP_LEADER_UUID).orNull();
    }

    public void setGroupLeaderUUID(@Nullable UUID uuid) {
        this.dataManager.set(GROUP_LEADER_UUID, com.google.common.base.Optional.fromNullable(uuid));
    }

    // --- Follow-context hooks for EntityAIFollowLeader. Neutral defaults for a plain traveller/
    // raider group follower, whose only concept of a leader is groupLeaderUUID; EntityTravellerWorker
    // overrides most of these with its own richer semantics (player owner, escort leader, missions,
    // duty). Catchup itself is shared: see FollowCatchup. ---

    /**
     * The entity this follower currently steers toward. Returns null when the leader isn't a loaded
     * entity - a still-virtual group leader is tracked separately, the same way
     * {@code EntityTravellerWorker.tickVirtualLeaderFollow()} tracks a virtual escort leader.
     */
    @Nullable
    public EntityLivingBase getEffectiveFollowTarget() {
        UUID leaderId = getGroupLeaderUUID();
        if (leaderId == null || !(world instanceof WorldServer)) return null;
        Entity e = ((WorldServer) world).getEntityFromUuid(leaderId);
        return (e instanceof EntityTraveller && !e.isDead) ? (EntityLivingBase) e : null;
    }

    public boolean isFollowOrderActive() {
        return getGroupLeaderUUID() != null;
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

    public boolean isCatchupTravel() {
        return catchup.isActive();
    }

    /** Hands this follower to catchup travel (see {@link FollowCatchup}). */
    public void startCatchupTravelTo(@Nullable EntityLivingBase target) {
        catchup.start(target);
    }

    /** Hook run when catchup starts, before the route is cleared. */
    protected void onCatchupBegin() {
    }

    /** Hook run when catchup ends, after the route is cleared. */
    protected void onCatchupEnd() {
    }

    /** The slot next to an offscreen leader that catchup drives toward, or null when the leader isn't virtual. */
    @Nullable
    protected BlockPos getOffscreenLeaderSlot() {
        return virtualLeaderSlot;
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
     *  also the signal {@link FollowCatchup#tickExit()} and, for workers, idle-look gating key off.
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

    // The slot of an offscreen group leader, surface-resolved where loaded; catchup travel drives toward it.
    @Nullable private BlockPos virtualLeaderSlot;

    /**
     * A loaded group follower whose leader is offscreen: resolves the slot next to the leader's virtual
     * position and hands the follower to catchup travel ({@code EntityAITravel}, with its stuck ladder),
     * the same way a follower too far behind a loaded leader is. Always the RING offset: there is no
     * loaded group to rank against until the leader materializes.
     */
    private void tickVirtualGroupLeaderFollow() {
        UUID leaderId = getGroupLeaderUUID();
        if (leaderId == null || getEffectiveFollowTarget() != null || !(world instanceof WorldServer)) {
            virtualLeaderSlot = null;
            return;
        }
        WorldServer serverWorld = (WorldServer) world;

        com.jubitus.travellers.virtual.VirtualTravellerManager vwm =
                com.jubitus.travellers.virtual.VirtualTravellerManager.get(serverWorld);
        if (!vwm.isVirtual(leaderId)) {
            // Leader neither loaded nor virtual: gone without a death hook. Elect a successor (possibly
            // this unit) or this follower idles forever (EntityAITravel refuses to run while
            // groupLeaderUUID is set).
            setGroupLeaderUUID(com.jubitus.travellers.follow.GroupSuccession.onLeaderMissing(serverWorld, leaderId,
                    getUniqueID()));
            virtualLeaderSlot = null;
            return;
        }

        if (!catchup.virtualRepathDue()) return;

        BlockPos leaderPos = vwm.getVirtualPosition(leaderId);
        if (leaderPos == null) return;

        com.jubitus.travellers.follow.formations.FormationGeometry.SlotOffset off =
                com.jubitus.travellers.follow.formations.FormationGeometry.computeRingOffset(getUniqueID());
        virtualLeaderSlot = resolveVirtualSlot(leaderPos.getX() + 0.5D + off.dx, leaderPos.getZ() + 0.5D + off.dz);

        catchup.beginIfFarFromVirtualSlot(leaderId, virtualLeaderSlot);
    }

    /** A slot next to an offscreen leader: its ground where loaded (the virtual Y is not tracked), else our own height. */
    protected BlockPos resolveVirtualSlot(double x, double z) {
        BlockPos column = new BlockPos(x, posY, z);
        if (!(world instanceof WorldServer) || com.jubitus.travellers.ground.chunk.LoadedChunks.peek(
                (WorldServer) world, column.getX() >> 4, column.getZ() >> 4) == null) return column;
        int y = com.jubitus.travellers.ground.ColumnGround.nearestLayer(world, column.getX(), column.getZ(),
                column.getY(), true);
        return y == com.jubitus.travellers.ground.ColumnGround.NONE ? column : new BlockPos(column.getX(), y, column.getZ());
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
        if (!HorseMarkers.isBondedMount(horse)) return false;
        UUID riderId = HorseMarkers.getBondedOwner(horse);
        if (riderId == null || riderId.equals(this.getUniqueID())) return true;
        Entity rider = (world instanceof WorldServer) ? ((WorldServer) world).getEntityFromUuid(riderId) : null;
        if (!(rider instanceof EntityTraveller)) return true; // rider not loaded / not classifiable - stay off it
        return !isHostileMountRider((EntityTraveller) rider);
    }

    private boolean isHostileMountRider(EntityTraveller rider) {
        return isHostileUnit(rider);
    }

    // A player who kills a unit within its free hits still gets its allies on their back.
    private void callAlliesAgainstKiller(DamageSource cause) {
        if (isAggressiveToTravellers() || !(cause.getTrueSource() instanceof EntityPlayer)) return;
        EntityPlayer killer = (EntityPlayer) cause.getTrueSource();
        if (killer.isCreative() || killer.isSpectator() || isOwner(killer)) return;
        if (!TravellersConfig.COMBAT.callForHelp || TravellersDerivedConfig.isPeacefulBlocked(world)) return;
        com.jubitus.travellers.combat.ai.TravellerHurtByTarget.callAllies(this, killer, TravellersConfig.COMBAT.helpRange);
    }

    // Opposite factions or a fight under way with hired workers; hired workers also fight the workers of an enemy owner.
    public boolean isHostileUnit(EntityTraveller other) {
        return com.jubitus.travellers.combat.Hostility.isAtWar(this, other);
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
        travelCommon.setIdlingAtStop(false);
        // Clear stale AI state from virtualization time
        travelCommon.setCurrentWaypoint(null);
        travelCommon.setDepartCooldownTicks(0);
        travelCommon.setVisitedStop(null);
    }

    public void setDebugPathTest(boolean value) {
        this.debugPathTest = value;
    }

    public boolean isDebugPathTest() {
        return debugPathTest;
    }

    /**
     * Where this unit is currently trying to get to, for {@link #tickEdgeVirtualizeProbe}. Only ever a
     * direction hint - a null result simply means "not going anywhere in particular", which correctly
     * exempts an idle unit standing in loaded terrain from ever being probed out of the world.
     *
     * <p>Deliberately does NOT look at {@link TravelCommon}'s waypoint/target village here - that goal
     * belongs to {@link com.jubitus.travellers.travel.EntityAITravel}, which has its own,
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
            return com.jubitus.travellers.virtual.VirtualTravellerManager
                    .get(ws).getVirtualPosition(leader);
        }
        return null;
    }


    public boolean isChoirSinging() { return choirSinging; }

    public void setChoirSinging(boolean singing) { this.choirSinging = singing; }

    public void stopChoir() {
        choirSinging = false;
        voice.stopNow();
    }

    // Called by server when client signals a choir sound finished: frees the voice lock.
    public void interruptChoirVoiceLock() {
        if (!choirSinging) return;
        voice.release();
    }

    public void markPlayerRecentAggro(EntityPlayer p) {
        combat.markPlayerRecentAggro(p);
    }

    public int lastPlayerAggroAgeTicks(EntityPlayer p) {
        return combat.lastPlayerAggroAgeTicks(p);
    }

    public boolean hasPlayerPassenger() {
        if (this.getPassengers().isEmpty()) return false;
        for (Entity e : this.getPassengers()) {
            if (e instanceof EntityPlayer) return true;
        }
        return false;
    }

    public boolean isInCombat() {
        return combat.isInCombat();
    }

    /** Client-readable combat state (server value, synced). */
    public boolean isInCombatSynced() {
        return this.dataManager.get(IN_COMBAT);
    }

    public boolean isInPostCombatSilence() {
        return combat.isInPostCombatSilence();
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

    public boolean isAutoEating() {
        return autoEatTask != null && autoEatTask.isEating();
    }

    public boolean isLowHealth() {
        float max = getMaxHealth();
        return max > 0 && getHealth() / max < TravellersConfig.COMBAT.lowHealthCombatFrac;
    }

    public boolean isIdlingAtStop() {  // you already store this
        return travelCommon.isIdlingAtStop();
    }


    public int getTicksExisted() {
        return this.ticksExisted;
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
     * {@link LoadedChunks#isLoaded}
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
        return LoadedChunks.isChunkLoaded(ws, cx, cz);
    }

    protected void clearAggroIfOffscreen() {
        if (!TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen) return;
        if (this.world.isRemote) return;
        if (this.isInPlayerViewDistance()) return;

        combat.noteDrop("offscreen");
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


    protected void markCombatNow() {
        combat.markCombatNow();
    }

    public boolean shouldRenderBackpack() {
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

    private final com.jubitus.travellers.travel.StuckRecord stuckRecord =
            new com.jubitus.travellers.travel.StuckRecord();

    public com.jubitus.travellers.travel.StuckRecord getStuckRecord() {
        return stuckRecord;
    }

    public com.jubitus.travellers.pathing.itinerary.ItineraryState getItineraryState() {
        return itineraryState;
    }

    /** Handed over from/to the virtual record; never null. */
    public void setItineraryState(@Nullable com.jubitus.travellers.pathing.itinerary.ItineraryState state) {
        this.itineraryState = state != null ? state : new com.jubitus.travellers.pathing.itinerary.ItineraryState();
    }

    /** True when this unit's travel follows an itinerary (mirrors VirtualTravellerManager.routeDrivenMovement). */
    public boolean isItineraryDriven() {
        return getGroupLeaderUUID() == null && !isCatchupTravel();
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

    /**
     * Combat path build on the world-wide combat budget: null when denied this tick.
     */
    @Nullable
    public Path getPathToEntityThrottled(EntityLivingBase target, int priority) {
        if (target == null || world == null) return null;

        // Emergency close range: never throttle
        if (this.getDistanceSq(target) <= EMERGENCY_ENGAGE_RANGE_SQ) {
            return this.getNavigator().getPathToEntityLiving(target);
        }

        if (!com.jubitus.travellers.pathing.PathBudget.tryAcquire(world, com.jubitus.travellers.pathing.PathBudget.Client.COMBAT, this.getEntityId(), priority)) {
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
        if (TravellersConfig.EQUIPMENT.shieldBlockMutesHurtSound && shield.blockedThisTick()) return;

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
        voice.play(hurt, getSoundVolume(), getSoundPitch(), dur, true, TravellerVoice.Kind.HURT);
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

        // Locked on a useful target: refuse before a limiter slot gets reserved on the new one.
        if (!combat.mayReplaceTarget(target)) return false;

        // Emergency / urgent priority bypasses limiter
        if (priority >= 2 || shouldBypassAttackTargetLimiter(target)) {
            this.setAttackTarget(target);
            this.setInCombat(true);
            return true;
        }
        int prio = AttackTargetLimiter.priorityFor(this);
        if (!AttackTargetLimiter.tryJoin(this, target, prio, computeEffectiveAttackerCap(target))) {
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

    public static final class SpawnData implements IEntityLivingData {
        public final String cultureId;

        public SpawnData(String id) {
            this.cultureId = id;
        }
    }

    /** Hook: the personality picked once at spawn (raiders pick from their own pool). */
    protected String pickVoicePersonalityId(String cultureId) {
        return CultureRegistry.pickRandomTravellerPersonalityId(cultureId, this.rand);
    }
}
