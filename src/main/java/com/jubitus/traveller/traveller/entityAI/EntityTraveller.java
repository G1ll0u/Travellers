package com.jubitus.traveller.traveller.entityAI;


import com.google.common.collect.Multimap;
import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.SpawnThrottles;
import com.jubitus.traveller.traveller.culture.CultureDef;
import com.jubitus.traveller.traveller.culture.CultureRegistry;
import com.jubitus.traveller.traveller.culture.VoiceSet;
import com.jubitus.traveller.traveller.pathing.TravellerNavigateGround;
import com.jubitus.traveller.traveller.travel.TravelCommon;
import com.jubitus.traveller.traveller.utils.debug.TravellerFollowNet;
import com.jubitus.traveller.traveller.utils.sound.ModSounds;
import com.jubitus.traveller.traveller.utils.sound.MsgPlayTravellerVoice;
import com.jubitus.traveller.traveller.utils.sound.MsgStopTravellerVoice;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import com.jubitus.traveller.traveller.utils.villages.MillenaireVillageDirectory;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAIOpenDoor;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
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
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
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
import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EntityTraveller extends EntityCreature {


    public static final String GHOST_TORCH_TAG = "TravellerGhostTorch";
    // FISHING ROD
    public static final String GHOST_ROD_TAG = "TravellerGhostRod";
    public static final double NEAR_HYS_DELTA_SQ = 4 * 4; // must be 4 blocks closer to switch
    // --- Threat memory (not persisted by default) ---
    static final int THREAT_FOR_HIT = 8;       // score added when this gets hit
    // --- Texture related fields ---
    private static final ResourceLocation TRAVELLER_LOOT =
            new ResourceLocation("travellers", "entities/traveller_pack");
    private static final DataParameter<Integer> TEXTURE_INDEX = EntityDataManager.createKey(EntityTraveller.class, DataSerializers.VARINT);
    // ---- GAP / CLIFF handling ----
    private static final String GHOST_SWORD_TAG = "MillmixGhostSword";
    static final java.util.UUID SWIM_BOOST_UUID =
            java.util.UUID.fromString("b3b1a5ee-9c12-4e6f-a7e4-1c4ef2f35d61");
    private static final String SWIM_BOOST_NAME = "TravellerSwimBoost";
    private static final int ARRIVE_IN = Math.max(8, TravellersModConfig.VILLAGE_NEAR - 4); // e.g., 28 if near=32
    private static final int ARRIVE_OUT = TravellersModConfig.VILLAGE_NEAR + 4;              // e.g., 36
    private static final DataParameter<Boolean> AIMING_BOW =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    private static final String GHOST_BOW_TAG = "TravellerGhostBow";
    // Configuration-ish knobs
    private static final DataParameter<String> CULTURE_ID =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.STRING);
    private static final int HELLO_COOLDOWN_TICKS = 5 * 60 * 20; // 5 min
    public final TravellerCamp travellerCamp = new TravellerCamp(this);
    final ItemStackHandler inventory = new ItemStackHandler(18); // 18 slots example
    // --- Camp join cooldown (prevents placing a fire right after resting) ---
    private final TravellerCamp camp = new TravellerCamp(this);
    private final java.util.Map<java.util.UUID, Threat> threatMap = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, HitGrace> playerGrace = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> playerLastAggroTick = new java.util.HashMap<>();
    // per-player last hello tick (server-authoritative)
    private final java.util.Map<java.util.UUID, Integer> lastHelloTickByPlayer = new java.util.HashMap<>();
    // ---- GAP / CLIFF handling control ----
    private final boolean wasInWater = false;
    private final int noPathTicks = 0;
    private final float baseWaterPenalty = 6.0F;
    final TravelCommon travelCommon = new TravelCommon(this);
    int nextTravelAmbientTick = 0;  // you already had this
    // field on EntityTraveller
    EntityAIAutoEat autoEatTask;
    EntityAIRangedBowTravellerSafe aiRanged;
    EntityAIAttackMeleeCustom aiMelee;
    EntityAIDefendIfClose aiDefendIfClose;
    EntityAIFollowTraveller aiFollow;
    EntityAITravel aiTravel;
    int lastLOSorCloseTick = 0; // last time we either saw the player OR they were close
    private int dogScanCooldown = 0;
    int spawnAmbientCooldownTicks = 0;                       // counts down server-side
    // --- Combat State Fields ---
    private boolean inCombat = false;
    private int swordHideTicks = 0;
    private int lastThreatDecayTick = 0;

    // NEW: cultural aggression toggle (per entity, persisted)
    private final boolean aggressiveToCultures = false;

    // --- Caravan / follow-other-traveller state ---
    @Nullable
    private UUID followLeaderId = null;
    private int followTimeoutTicks = 0;
    // --- Equipment ---
    private int pickupCooldown = 0;
    private int speakCooldown = 0;
    private int forcedSwingTicks = 0;
    private int hurtAnimCooldown = 0;
    private int travelRecoverTicks = 0;
    private int pickupScanTicker = 0;

    // --- Voice channel ---
    private int voiceLockUntilTick = 0;     // no new sounds until this tick (entity-local, server authoritative)
    private VoiceKind voiceKindNow = VoiceKind.NONE;
    private int nearUnloadTicks = 0; // counts consecutive "at the edge" seconds
    private int travelTorchCooldown = 0;
    // --- spawn helper flags ---
    private boolean checkedCompanionSpawn = false;   // only run logic once
    private boolean suppressCompanionSpawn = false;  // set for eggs/commands/etc.
    private int farFromPlayersTicks = 0;
    @Nullable
    private java.util.UUID chasingPlayerId = null;
    @Nullable
    private UUID dogId = null;
    private int currentFollowers = 0;

    public EntityTraveller(World worldIn) {
        super(worldIn);
        this.setPathPriority(PathNodeType.BLOCKED, -1.0F);
        this.setPathPriority(PathNodeType.WATER, 1.0F);     // mildly worse than WALKABLE

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
        this.stepHeight = 1.0F; // Allow them to not jump but just stepUp 1 block height (avoids bugs with non-full blocks)
        this.setCanPickUpLoot(true);
        this.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        this.setLeftHanded(false);

    }

    public boolean isGhostTorch(ItemStack s) {
        return !s.isEmpty()
                && s.getItem() == Item.getItemFromBlock(Blocks.TORCH)
                && s.hasTagCompound()
                && s.getTagCompound().getBoolean(GHOST_TORCH_TAG);
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
            // This traveller came from spawn egg, command, structure, etc.
            t.setSuppressCompanionSpawn(true);
        }
    }

    public void setSuppressCompanionSpawn(boolean suppress) {
        this.suppressCompanionSpawn = suppress;
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
        int cap = TravellersModConfig.loadedTravellerCap;
        int loaded = this.world.getEntities(EntityTraveller.class, Entity::isEntityAlive).size();
        if (loaded >= cap) return false;

        // MUCH smaller “near a village”
        BlockPos pos = new BlockPos(this.posX, this.posY, this.posZ);
        if (!isNearAnyVillage(this.world, pos, TravellersModConfig.villageSpawnRadius)) return false;

        // Light & ground sanity (creature-like)
        if (!world.getBlockState(pos.down()).isOpaqueCube()) return false;
        if (world.getLight(pos) < 8) return false; // spawn mostly in lit village areas

        // Local density throttle (see next section)
        if (!SpawnThrottles.underLocalCap(this.world, pos, EntityTraveller.class,
                TravellersModConfig.travellerLocalCap, TravellersModConfig.travellerLocalCapRadius))
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

    static boolean isNearAnyVillage(World world, BlockPos pos, int radius) {
        long r2 = (long) radius * (long) radius;
        for (BlockPos v : MillVillageIndex.getAllVillageCenters(world)) {
            if (v.distanceSq(pos) <= r2) return true;
        }
        return false;
    }

    public boolean isCampLeader() {
        return travellerCamp.isCampLeader();
    }

    public void setCampLeader(boolean campLeader) {
        travellerCamp.setCampLeader(campLeader);
    }

    /**
     * Call every tick from onLivingUpdate to decay the avoid map.
     */
    public void tickCampAvoid() {
        travellerCamp.tickCampAvoid();
    }

    /**
     * Mark a camp to avoid for N ticks.
     */
    public void avoidCamp(BlockPos pos, int ticks) {
        travellerCamp.avoidCamp(pos, ticks);
    }

    /**
     * True if this camp is currently blacklisted for this traveller.
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


// === Shared camp helpers ===

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
        return !s.isEmpty() && s.getItem() instanceof net.minecraft.item.ItemFishingRod;
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

    public CultureDef getCulture() {
        return CultureRegistry.get(getCultureId());
    }

    public String getCultureId() {
        return this.dataManager.get(CULTURE_ID);
    }

    // Default: friendlies are NOT aggressive to other travellers.
    protected boolean isAggressiveToTravellers() {
        return false;
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
            if (top != null && top.distanceSq(fire) <= (TravellersModConfig.CAMP_RADIUS + 2) * (TravellersModConfig.CAMP_RADIUS + 2))
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
        return hasBowInBackpack();
    }

    private boolean hasBowInBackpack() {
        return !findBowInBackpack().isEmpty();
    }

    private ItemStack findBowInBackpack() {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (isBowLike(s)) return s;
        }
        return ItemStack.EMPTY;
    }

    private boolean isBowLike(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof net.minecraft.item.ItemBow;
    }

    public ItemStack findBowInBackpackPublic() {
        return findBowInBackpack();
    }

    // Keep the existing visual behavior for swords while shooting:
    public void delaySwordHideVisual() {
        this.swordHideTicks = TravellersModConfig.SWORD_HIDE_DELAY;
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

    protected boolean shouldAttackExtraEntity(net.minecraft.entity.EntityLivingBase other) {
        return false;
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
                this.setPathPriority(net.minecraft.pathfinding.PathNodeType.valueOf("ICE"), 0.0F);
            } catch (IllegalArgumentException ignored) {

            }
        }

        // Highest priority
        this.tasks.addTask(0, new EntityAISwimming(this));

        // Combat
        aiRanged = new EntityAIRangedBowTravellerSafe(
                this,
                TravellersModConfig.rangedMinDist,
                TravellersModConfig.rangedMaxDist,
                TravellersModConfig.rangedCooldown,
                /* meleeHandOff */ Math.max(2.5D, TravellersModConfig.rangedMinDist + 1.0D),
                /* ghostEquipBow */ true
        );
        this.tasks.addTask(2, aiRanged);


        aiMelee = new EntityAIAttackMeleeCustom(this, TravellersModConfig.attackMovementSpeed, true, 0.65);
        this.tasks.addTask(3, aiMelee);

        aiDefendIfClose = new EntityAIDefendIfClose(this, TravellersModConfig.attackTriggerRange, true)
                .setAssistRadius(TravellersModConfig.helpRange)
                .setBowRangeBonus(0.30); // +30%

        this.targetTasks.addTask(1, aiDefendIfClose);

        this.targetTasks.addTask(1, new TravellerHurtByTarget(this, true, TravellersModConfig.helpRange));
        autoEatTask = new EntityAIAutoEat(this, TravellersModConfig.eatHealthThreshold);
        this.tasks.addTask(8, autoEatTask);


//        this.tasks.addTask(5, new EntityAICombatDrawSword(this));

//        this.tasks.addTask(3, new EntityAICampPause(this));     // leader: can place fire + wait
//        this.tasks.addTask(4, new EntityAICampJoinFire(this));  // joiner: gather around existing fires

        if (TravellersModConfig.campsEnabled) {
            this.tasks.addTask(6, new EntityAICampPause(this));                       // leader
            this.tasks.addTask(7, new EntityAICampJoinFire(this, 0.34D)); // joiner
        }

        this.tasks.addTask(10, new EntityAIStopAndLookAtPlayer(this, 3.2D, 30.0F, 30.0F));
        if (TravellersModConfig.fishWhileTravelEnabled) {
            this.tasks.addTask(11, new EntityAIFishWhileTravel(this));
        }

        // Utility / travel
        this.tasks.addTask(12, new EntityAIOpenFenceGate(this));

        this.tasks.addTask(13, new EntityAIOpenDoor(this, true));

        this.tasks.addTask(14, new EntityAIRoamInsideVillage(this, 0.34D, 3 * 20, 10 * 20, TravellersModConfig.villageRoamMinDuration * 20, TravellersModConfig.villageRoamMaxDuration * 20));// Optional: customize 2-minute duration or wordlist at runtime

        aiFollow = new EntityAIFollowTraveller(this, 0.819, TravellersModConfig.movementSpeedWhileFollowing);
        this.tasks.addTask(15, aiFollow);

        aiTravel = new EntityAITravel(this, TravellersModConfig.movementSpeedWhileTravel, TravellersModConfig.stepBlocks);
        this.tasks.addTask(16, aiTravel);

        this.tasks.addTask(17, new EntityAILookIdle(this));
    }

    // Entity Attributes
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(TravellersModConfig.maxHealth);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(TravellersModConfig.movementSpeed);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(TravellersModConfig.followRange);
        // ATTACK_DAMAGE is not present by default for many entities
        if (this.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.ATTACK_DAMAGE) == null) {
            this.getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        }
        this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(TravellersModConfig.attackDamage);
    }

    @Override
    protected PathNavigate createNavigator(World worldIn) {
        return new TravellerNavigateGround(this, worldIn);
    }

    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        if (world != null && world.getDifficulty() == EnumDifficulty.PEACEFUL) {
            super.setAttackTarget(null);
            setInCombat(false);
            return;
        }

        if (target instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) target;

            // Block SAME-faction fights: (friendly↔friendly) and (raider↔raider)
            if (!isCrossFaction(other)) {
                super.setAttackTarget(null);
                return;
            }

            // NEW: friendly travellers only fight raiders of a different culture
            // this.isAggressiveToTravellers() == false  => normal traveller
            // other.isAggressiveToTravellers() == true  => raider
            if (!this.isAggressiveToTravellers() && other.isAggressiveToTravellers()) {
                // Same culture? Then don't attack.
                if (isSameCulture(other)) {
                    super.setAttackTarget(null);
                    return;
                }
            }
        }


        super.setAttackTarget(target);

        if (!world.isRemote) {
            if (target != null) setInCombat(true);
            if (target instanceof EntityPlayer) {
                chasingPlayerId = target.getUniqueID();
                lastLOSorCloseTick = this.ticksExisted; // we just “saw” them
            } else if (target == null) {
                resetChaseMemory();
            }
        }
    }

    // Traveller textures
    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(CULTURE_ID, "en");  // default
        this.dataManager.register(TEXTURE_INDEX, 0);
        this.dataManager.register(AIMING_BOW, Boolean.FALSE); // NEW
        // default texture index
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
        super.onUpdate();
        if (!world.isRemote && voiceKindNow != VoiceKind.NONE && this.ticksExisted >= voiceLockUntilTick) {
            voiceKindNow = VoiceKind.NONE;
        }

//        if (!world.isRemote && getTargetVillage() != null) {
//            if (this.getNavigator().noPath()) {
//                noPathTicks++;
//                if (noPathTicks == 60) { // ~3s stuck
//                    this.setPathPriority(PathNodeType.WATER, Math.max(1.0F, baseWaterPenalty - 3.0F));
//                } else if (noPathTicks == 140) { // ~7s stuck
//                    this.setPathPriority(PathNodeType.WATER, 0.0F); // treat as neutral
//                }
//            } else {
//                if (noPathTicks > 0) {
//                    noPathTicks = 0;
//                    this.setPathPriority(PathNodeType.WATER, baseWaterPenalty); // restore
//                }
//            }
//        }

        if (!world.isRemote) {
            EntityLivingBase tgt = this.getAttackTarget();
            if (tgt != null && tgt.isEntityAlive()) {
                double maxEngage = TravellersModConfig.attackTriggerRange; // strict requirement
                double d2 = this.getDistanceSq(tgt);
                if (d2 > maxEngage * maxEngage) {
                    // Too far: disengage so AI can resume travelling
                    this.setAttackTarget(null);
                    this.setInCombat(false);
                    this.getNavigator().clearPath();
                    // also stop aiming if we were in ranged mode
                    if (isAimingBow()) {
                        setAimingBow(false);
                        resetActiveHand();
                    }
                }
            }
        }


        if (!world.isRemote) {
            if (travelCommon.getDepartCooldownTicks() > 0)
                travelCommon.setDepartCooldownTicks(travelCommon.getDepartCooldownTicks() - 1);

            if (travelRecoverTicks > 0) travelRecoverTicks--;
            tickFollowTimer();

            if (getAttackTarget() == null || !getAttackTarget().isEntityAlive()) {
                if (isInCombat()) {
                    setInCombat(false);
                    travelCommon.setTravelRecoverTicks(60); // ~3s grace; tune 40–80
                }
            }
            if (TravellersModConfig.travellerAmbient && this.ticksExisted >= nextTravelAmbientTick) {
                travelCommon.maybePlayTravelAmbient();
            }
            handleSwordVisibility();
            decayAndPruneThreats();
            considerRetargetFromMemory();
        }
        if (!isInCombat()) {
            if (isAimingBow()) {
                // Only cancel if the thing we're using is a bow, so eating stays intact
                ItemStack hand = this.getHeldItemMainhand();
                if (!hand.isEmpty() && hand.getItem() instanceof net.minecraft.item.ItemBow) {
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
            double keepDist = TravellersModConfig.playerFleeKeepDistance;
            boolean close = this.getDistanceSq(p) <= (keepDist * keepDist);
            if (see || close) {
                lastLOSorCloseTick = this.ticksExisted; // still engaged; don’t forgive
            }

            // 2) Calm player? (your earlier memory updated by the hurt event)
            boolean playerCalm =
                    lastPlayerAggroAgeTicks(p) > TravellersModConfig.playerCalmForgiveTicks;

            // Optional: don’t instantly forgive right after being damaged by them
            Threat th = threatMap.get(p.getUniqueID());
            boolean wasRecentlyThreat = (th != null) && (this.ticksExisted - th.lastSeenTick <= 40);

            // 3) Fleeing window: must be away/out of LOS for long enough
            boolean fleeingLongEnough =
                    (this.ticksExisted - lastLOSorCloseTick) > TravellersModConfig.playerFleeForgiveTicks;

            if (playerCalm && fleeingLongEnough && !wasRecentlyThreat) {
                this.setAttackTarget(null);
                this.setInCombat(false);
                this.getNavigator().clearPath();
                if (isAimingBow()) {
                    setAimingBow(false);
                    resetActiveHand();
                }
                resetChaseMemory();
                this.setRevengeTarget(null);
            }
        } else if (tgt == null) {
            resetChaseMemory();
        }
        if (!world.isRemote) {
            if (isAimingBow()) {
                this.setSprinting(false); // belt-and-suspenders for ranged pose
                if ((this.ticksExisted & 0x3F) == 0) { // every ~64 ticks (~3.2s)
                    pruneGraceHits();
                }
            }
        }
    }

    // --- NBT Saving / Loading ---
    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        // --- Temporarily remove transient modifiers to avoid "already applied" during save ---
        IAttributeInstance move = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
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
                compound.setTag("TargetVillage", net.minecraft.nbt.NBTUtil.createPosTag(travelCommon.getTargetVillage()));
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


            compound.setInteger("PauseTicks", travelCommon.getPauseTicks());
            compound.setInteger("VillageIdleTicks", travelCommon.getVillageIdleTicks());
            compound.setBoolean("VillageIdling", travelCommon.isVillageIdling());
            compound.setInteger("RouteIndex", travelCommon.routeIndex);
            compound.setInteger("TextureIdx", getTextureIndex());
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
        if (compound.hasKey("Wanderlust")) travelCommon.wanderlust = compound.getFloat("Wanderlust");
        if (compound.hasKey("ExploreChance")) travelCommon.exploreChance = compound.getFloat("ExploreChance");
        if (compound.hasKey("HopPreference")) travelCommon.hopPreference = compound.getFloat("HopPreference");
        if (compound.hasKey("PathAngleBiasDeg")) travelCommon.pathAngleBiasDeg = compound.getFloat("PathAngleBiasDeg");
        if (compound.hasKey("PathLaneOffset")) travelCommon.pathLaneOffset = compound.getDouble("PathLaneOffset");
        if (compound.hasKey("PathStepJitter")) travelCommon.pathStepJitter = compound.getInteger("PathStepJitter");
        if (compound.hasKey("RecalcPhase")) travelCommon.recalcPhase = compound.getInteger("RecalcPhase");
        if (compound.hasKey("CultureId")) setCultureId(compound.getString("CultureId"));
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
    }

    @Override
    protected void dropLoot(boolean wasRecentlyHit, int lootingModifier, DamageSource source) {
        super.dropLoot(wasRecentlyHit, lootingModifier, source);

        if (world.isRemote || inventory == null) return;

        final float DROP_CHANCE = 0.30f;     // 30% per item
        final int MAX_DROPS = 2;         // cap at 2 items

        // Build and shuffle slot order to avoid early-slot bias
        int n = inventory.getSlots();
        java.util.List<Integer> slots = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) slots.add(i);
        java.util.Collections.shuffle(slots, rand);

        int dropped = 0;
        for (int slot : slots) {
            if (dropped >= MAX_DROPS) break;

            ItemStack s = inventory.getStackInSlot(slot);
            if (s.isEmpty()) continue;

            if (rand.nextFloat() < DROP_CHANCE) {
                // Spawn a copy, then extract to avoid dupes
                EntityItem item = new EntityItem(world, posX, posY + 0.5, posZ, s.copy());
                item.motionX = (rand.nextDouble() - 0.5) * 0.1;
                item.motionY = 0.1;
                item.motionZ = (rand.nextDouble() - 0.5) * 0.1;
                world.spawnEntity(item);

                inventory.extractItem(slot, s.getCount(), false);
                dropped++;
            }
        }
    }

    @Override
    public void setAIMoveSpeed(float speed) {
        double base = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();

        float cap;
        if (this.isAimingBow()) {
            cap = (float) Math.min(base * 0.98D, 0.30D); // was 0.26
        } else if (this.getAttackTarget() != null) {
            cap = (float) Math.min(base * 1.22D, 0.38D); // was 1.18 & 0.35
        } else {
            cap = (float) Math.min(base * 1.02D, 0.32D); // was 0.30
        }
        super.setAIMoveSpeed(Math.min(speed, cap));
    }

    @Override
    public void onLivingUpdate() {
        if (!world.isRemote) {
            if (!this.world.isRemote) {
                ensureDismountIfInCombat();
            }
            // NEW: only once per traveller, on first server tick
            if (!checkedCompanionSpawn) {
                checkedCompanionSpawn = true;
                maybeSpawnCompanionDog();
            }

            if (spawnAmbientCooldownTicks > 0) spawnAmbientCooldownTicks--;
            if (travellerCamp.campGlobalCooldown > 0) travellerCamp.campGlobalCooldown--;
            maybeRecruitDog();
            if (travelTorchCooldown > 0) travelTorchCooldown--;  // NEW
        }

        float NORMAL_STEP = 1.4F;
// Use a *reduced* but nonzero step in water so we can climb a +1 rim out of water
        float WATER_STEP = 0.6F; // was 0.0F
        if (this.isInWater()) {
            if (this.stepHeight != WATER_STEP) this.stepHeight = WATER_STEP;
        } else {
            if (this.stepHeight != NORMAL_STEP) this.stepHeight = NORMAL_STEP;
        }


        super.onLivingUpdate();
        if (travellerCamp.campJoinCooldown > 0) travellerCamp.campJoinCooldown--;
        if (world.isRemote && hurtAnimCooldown > 0) hurtAnimCooldown--;
        if (speakCooldown > 0) speakCooldown--;
        if (world.isRemote) {
            if (forcedSwingTicks > 0) forcedSwingTicks--;
        }
        if (!world.isRemote) {
            if (pickupCooldown > 0) pickupCooldown--;
        }
        travellerCamp.tickCampAvoid();

        // Pick an initial random target only once
        if (!world.isRemote) {
            travelCommon.updateNearestVillage();

            // 1) Ensure we have a plan
            if (travelCommon.routeIndex < 0 || travelCommon.routeIndex >= travelCommon.route.size()) {
                travelCommon.ensureRoutePlanned();
            }

            // 2) Drive the current target from the route
            if (travelCommon.routeIndex >= 0 && travelCommon.routeIndex < travelCommon.route.size()) {
                BlockPos curStop = travelCommon.route.get(travelCommon.routeIndex);
                travelCommon.setTargetVillage(curStop);

                boolean nearCurrentStop = travelCommon.isAtTargetStop();

                // If we're inside the stop village AND the roam AI finished its stay, move to next hop
                if (nearCurrentStop && !travelCommon.isVillageIdling() && travelCommon.isReadyToDepart()) {
                    travelCommon.routeIndex++;
                    travelCommon.setReadyToDepart(false);
                    travelCommon.setDepartCooldownTicks(80); // ~4s @20tps (tune: 60–120 feels good)
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
            if (this.canPickUpLoot() && !isInCombat() && pickupCooldown == 0) {
                tryScanAndPickupGroundItemsThrottled();
            }
            if (travellerCamp.getCampfirePos() != null && this.isEntityAlive()) {
                travellerCamp.tickCampSocial();                // <— central place, runs for everyone
                if (!travellerCamp.isSocialLooking()) {
                    this.lookAtFire();                // yield to social look if active
                }
            }
            checkAndMaybeDespawnFarFromPlayers();
            checkAndMaybeDespawnAtChunkEdge(); // ← NEW: fade out near not-loaded chunks
        }


    }

    // Equipment
    @Override
    protected void updateEquipmentIfNeeded(EntityItem itemEntity) {
        ItemStack stack = itemEntity.getItem();
        Item item = stack.getItem();


        // Armor upgrades first
        for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{
                EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET}) {
            if (isArmorItemForSlot(stack, slot) && isArmorUpgrade(stack, slot)) {
                ItemStack one = stack.copy();
                one.setCount(1);
                safeReplaceInSlot(slot, one);
                shrinkOrRemove(itemEntity);
                this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
                return;
            }
        }

        // Bow: stash one if we don’t have any
        if (isBowLike(stack) && !hasBowInBackpack()) {
            takeOneAndStash(itemEntity);
            this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
            return;
        }

        // Sword: only if upgrade
        if (isSwordLike(stack) && isSwordUpgrade(stack)) {
            takeOneAndStash(itemEntity);
            this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
            return;
        }

        // Food: always stash
        if (item instanceof ItemFood) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, stack.copy(), false);
            if (remainder.isEmpty()) itemEntity.setDead();
            else itemEntity.setItem(remainder);
            this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
        }
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
        this.spawnAmbientCooldownTicks = TravellersModConfig.SPAWN_AMBIENT_DELAY_TICKS + this.rand.nextInt(5 * 20);

        livingdata = super.onInitialSpawn(difficulty, livingdata);

        // --- culture pick/propagate ---

        String cid = null;

        // 1) If the spawner (soft-spawner or vanilla group leader) already gave us a culture, honour it.
        if (livingdata instanceof SpawnData) {
            cid = ((SpawnData) livingdata).cultureId;
        }

        // 2) If we still don't have a culture, try village-based pick around our spawn position.
        if (cid == null || cid.isEmpty()) {
            // Uses the same logic as your soft spawner (weighted by nearby Millénaire villages).
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

        CultureDef c = CultureRegistry.get(cid);
        int tCount = (c != null) ? c.travellerAppearanceCount() : 0;
        setTextureIndex(this.rand.nextInt(Math.max(1, tCount <= 0 ? 1 : tCount)));

        // Reset move attr base and make sure no swim boost lingers
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.setBaseValue(TravellersModConfig.movementSpeed);
            AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
            if (m != null) attr.removeModifier(m);
        }

        if (!world.isRemote) {
            if (c != null) {
                java.util.Random r = this.rand;

                // --- MAIN HAND: random melee or tool from culture ---
                ItemStack meleeOrTool = c.randomMeleeOrTool(r);
                if (!meleeOrTool.isEmpty()) {
                    this.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, meleeOrTool);
                }

                // --- OPTIONAL BOW ---
                if (r.nextFloat() < 0.20f) {
                    ItemStack bow = c.randomBow(r);
                    if (!bow.isEmpty()) {
                        this.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, bow);
                    }
                }

                // --- ARMOR: low chances per slot ---
                if (r.nextFloat() < 0.15f) {
                    ItemStack helm = c.randomArmorForSlot(EntityEquipmentSlot.HEAD, r);
                    if (!helm.isEmpty()) {
                        this.setItemStackToSlot(EntityEquipmentSlot.HEAD, helm);
                    }
                }
                if (r.nextFloat() < 0.10f) {
                    ItemStack chest = c.randomArmorForSlot(EntityEquipmentSlot.CHEST, r);
                    if (!chest.isEmpty()) {
                        this.setItemStackToSlot(EntityEquipmentSlot.CHEST, chest);
                    }
                }
                if (r.nextFloat() < 0.08f) {
                    ItemStack legs = c.randomArmorForSlot(EntityEquipmentSlot.LEGS, r);
                    if (!legs.isEmpty()) {
                        this.setItemStackToSlot(EntityEquipmentSlot.LEGS, legs);
                    }
                }
                if (r.nextFloat() < 0.12f) {
                    ItemStack boots = c.randomArmorForSlot(EntityEquipmentSlot.FEET, r);
                    if (!boots.isEmpty()) {
                        this.setItemStackToSlot(EntityEquipmentSlot.FEET, boots);
                    }
                }
            }

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

        // Choose hello vs talk
        if (shouldHelloFor(player)) {
            playHelloSound();         // one hello variation
            markHello(player);        // start the 5-minute window for THIS player
        } else {
            playTalkSound();          // normal chatter
        }

        // Keep your existing “I’m following / village idle / announce heading” messages:
        if (isFollowingSomeone()) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "I'm travelling with someone right now. Ask me again later."
            ));
            return true;
        }
        if (travelCommon.isVillageIdling()) {
            String stayName = travelCommon.lookupNearestVillageNameOrCoords();
            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "I'm staying in " +
                            TextFormatting.GOLD + stayName +
                            TextFormatting.WHITE + " for a bit."
            ));
            return true;
        }
        world.setEntityState(this, (byte) 4); // swing animation
        announceHeadingTo(player);
        return true;
    }

    public void scheduleNextTravelAmbient() {
        // spreads entities out and respects config
        if (!TravellersModConfig.travellerAmbient) {
            nextTravelAmbientTick = Integer.MAX_VALUE;
            return;
        }
        int min = Math.max(40, TravellersModConfig.travellerAmbientMinDelay);
        int max = Math.max(min, TravellersModConfig.travellerAmbientMaxDelay);
        int delta = min + this.rand.nextInt(Math.max(1, max - min + 1));
        nextTravelAmbientTick = this.ticksExisted + delta;
    }

    private boolean isArmorItemForSlot(ItemStack s, EntityEquipmentSlot slot) {
        if (s.isEmpty()) return false;
        Item it = s.getItem();
        if (it instanceof ItemArmor) return ((ItemArmor) it).armorType == slot;
        try {
            return it.isValidArmor(s, slot, this);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private boolean isArmorUpgrade(ItemStack candidate, EntityEquipmentSlot slot) {
        if (!isArmorItemForSlot(candidate, slot)) return false;

        double cand = armorScore(candidate, slot);

        // Compare vs equipped
        ItemStack cur = getItemStackFromSlot(slot);
        double have = cur.isEmpty() ? Double.NEGATIVE_INFINITY : armorScore(cur, slot);

        // Compare vs best same-slot piece already in backpack
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty() && isArmorItemForSlot(s, slot)) {
                have = Math.max(have, armorScore(s, slot));
            }
        }

        return cand > have + 0.05; // small epsilon avoids thrash on ties
    }

    private void safeReplaceInSlot(EntityEquipmentSlot slot, ItemStack newStack) {
        ItemStack old = getItemStackFromSlot(slot);
        setItemStackToSlot(slot, newStack);

        if (!old.isEmpty()) {
            ItemStack rem = ItemHandlerHelper.insertItemStacked(inventory, old, false);
            if (!rem.isEmpty()) {
                // backpack full -> drop the remainder so nothing “vanishes”
                EntityItem drop = new EntityItem(world, posX, posY + 0.5, posZ, rem);
                world.spawnEntity(drop);
            }
        }
    }

    private void shrinkOrRemove(EntityItem ei) {
        ItemStack s = ei.getItem();
        if (s.getCount() <= 1) {
            ei.setDead();
        } else {
            s.shrink(1);
            ei.setItem(s);
        }
    }

    private void takeOneAndStash(EntityItem ei) {
        ItemStack src = ei.getItem();
        ItemStack one = src.copy();
        one.setCount(1);
        ItemHandlerHelper.insertItemStacked(inventory, one, false);
        shrinkOrRemove(ei);
    }

    // ---- "sword-like" detection (mod-friendly) ----
    private boolean isSwordLike(ItemStack s) {
        if (s.isEmpty()) return false;
        Item it = s.getItem();

        // Vanilla/mods that extend ItemSword
        if (it instanceof ItemSword) return true;

        // Forge tool class "sword"
        try {
            java.util.Set<String> classes = it.getToolClasses(s);
            if (classes != null && classes.contains("sword")) return true;
        } catch (Throwable ignored) {
        }

        // Attribute fallback: main-hand attack damage above a threshold,
        // and not clearly an axe (most axes advertise "axe" tool class).
        double dmg = getAttackDamage(s, EntityEquipmentSlot.MAINHAND);
        if (dmg >= 3.0) {
            try {
                java.util.Set<String> classes = it.getToolClasses(s);
                if (classes != null && classes.contains("axe")) return false;
            } catch (Throwable ignored) {
            }

            net.minecraft.util.ResourceLocation rl = Item.REGISTRY.getNameForObject(it);
            return rl == null || !rl.getPath().toLowerCase(java.util.Locale.ROOT).contains("axe");
        }
        return false;
    }

    private boolean isSwordUpgrade(ItemStack candidate) {
        if (!isSwordLike(candidate)) return false;

        double cand = swordScore(candidate);

        double have = Double.NEGATIVE_INFINITY;

        // consider a REAL sword in hand (ignore ghost)
        ItemStack hand = getHeldItemMainhand();
        boolean holdingRealSword = !hand.isEmpty() && isSwordLike(hand) && !isGhostSword(hand);
        if (holdingRealSword) have = Math.max(have, swordScore(hand));

        // consider best in backpack
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (isSwordLike(s)) have = Math.max(have, swordScore(s));
        }

        return cand > have + 0.05;
    }

    // Equipment things
    private double armorScore(ItemStack s, EntityEquipmentSlot slot) {
        // Base from attributes
        Multimap<String, AttributeModifier> a = getAttribsFor(s, slot);
        double armor = sumAttr(a, SharedMonsterAttributes.ARMOR.getName());                 // "generic.armor"
        double tough = sumAttr(a, SharedMonsterAttributes.ARMOR_TOUGHNESS.getName());       // "generic.armorToughness"

        // Enchantments: Protection adds ~= 4%/lvl to many types; weight it modestly
        int prot = 0;
        if (Enchantments.PROTECTION != null) {
            prot = EnchantmentHelper.getEnchantmentLevel(Enchantments.PROTECTION, s);
        }
        double ench = prot * 0.4;

        // Durability bias so nearly-broken items don’t win ties
        double dur = 0.0;
        if (s.isItemStackDamageable()) {
            int max = s.getMaxDamage();
            int left = Math.max(0, max - s.getItemDamage());
            dur = Math.min(0.9, (left / (double) max) * 0.5);
        }

        // Slot weight (chest > legs > boots/helm). Helm you don’t equip, but keep generic:
        int slotWeight =
                (slot == EntityEquipmentSlot.CHEST) ? 3 :
                        (slot == EntityEquipmentSlot.LEGS) ? 2 : 1;

        return (armor + 0.5 * tough) * slotWeight + ench + dur;
    }

    private double getAttackDamage(ItemStack s, EntityEquipmentSlot slot) {
        com.google.common.collect.Multimap<String, net.minecraft.entity.ai.attributes.AttributeModifier> map =
                s.getAttributeModifiers(slot);
        double sum = 0.0;
        for (net.minecraft.entity.ai.attributes.AttributeModifier m :
                map.get(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
            if (m.getOperation() == 0) sum += m.getAmount(); // ADDITION only
        }
        return sum;
    }

    // ---- sword scoring (works for modded swords) ----
    private double swordScore(ItemStack s) {
        if (s.isEmpty()) return Double.NEGATIVE_INFINITY;

        double baseDmg;
        int materialRank = 0;

        if (s.getItem() instanceof ItemSword) {
            ItemSword sw = (ItemSword) s.getItem();
            baseDmg = sw.getAttackDamage();
            String mat = sw.getToolMaterialName();
            materialRank =
                    "DIAMOND".equals(mat) ? 5 :
                            "IRON".equals(mat) ? 4 :
                                    "STONE".equals(mat) ? 3 :
                                            "GOLD".equals(mat) ? 3 :
                                                    "WOOD".equals(mat) ? 2 : 1;
        } else {
            baseDmg = getAttackDamage(s, EntityEquipmentSlot.MAINHAND);
            materialRank = 3;
        }

        int sharp = 0;
        if (Enchantments.SHARPNESS != null) {
            sharp = EnchantmentHelper.getEnchantmentLevel(
                    Enchantments.SHARPNESS, s);
        }
        double ench = sharp > 0 ? (0.51 * sharp + 0.5) : 0.0;

        double dur = 0.0;
        if (s.isItemStackDamageable()) {
            int max = s.getMaxDamage();
            int left = Math.max(0, max - s.getItemDamage());
            dur = Math.min(0.9, (left / (double) max) * 0.5);
        }

        return baseDmg + ench + dur + (materialRank * 0.1);
    }

    private boolean isGhostSword(ItemStack stack) {
        return !stack.isEmpty()
                && isSwordLike(stack)
                && stack.hasTagCompound()
                && stack.getTagCompound().getBoolean(GHOST_SWORD_TAG);
    }

    @SuppressWarnings("deprecation")
    private Multimap<String, AttributeModifier> getAttribsFor(ItemStack s, EntityEquipmentSlot slot) {
        // 1.12.2 Forge method has (slot, stack). If your mappings differ, adjust accordingly.
        return s.getAttributeModifiers(slot);
        // If your mappings require calling through the Item:
        // return s.getItem().getAttributeModifiers(slot, s);
    }

    private double sumAttr(Multimap<String, AttributeModifier> map, String attrName) {
        double sum = 0.0;
        for (AttributeModifier mod : map.get(attrName)) {
            switch (mod.getOperation()) {
                case 0:
                    sum += mod.getAmount();
                    break;            // ADDITION
                case 1:
                    sum += 0;
                    break;                           // MULTIPLY_BASE (ignore for armor)
                case 2:
                    sum += 0;
                    break;                           // MULTIPLY_TOTAL (ignore)
            }
        }
        return sum;
    }

    public boolean isAimingBow() {
        return this.dataManager.get(AIMING_BOW);
    }

    public void setAimingBow(boolean v) {
        if (!world.isRemote) this.dataManager.set(AIMING_BOW, v);
    }

    public int getTextureIndex() {
        return this.dataManager.get(TEXTURE_INDEX);
    }

    public void setTextureIndex(int index) {
        this.dataManager.set(TEXTURE_INDEX, index);
    }

    public void setCultureId(String id) {
        this.dataManager.set(CULTURE_ID, id);
    }

    private void equipFromBackpackOnce() {
        tryEquipArmorPiece(EntityEquipmentSlot.HEAD);
        tryEquipArmorPiece(EntityEquipmentSlot.CHEST);
        tryEquipArmorPiece(EntityEquipmentSlot.LEGS);
        tryEquipArmorPiece(EntityEquipmentSlot.FEET);

        // Put a “non-combat” item in hand by default (maps/tools/etc).
        if (getItemStackFromSlot(EntityEquipmentSlot.MAINHAND).isEmpty()) {
            int idx = findFirstInInv(s ->
                    s.getItem() == Items.MAP ||
                            s.getItem() instanceof ItemTool ||
                            s.getItem() instanceof ItemShears ||
                            s.getItem() instanceof ItemFood);
            if (idx >= 0) {
                ItemStack taken = inventory.extractItem(idx, 1, false);
                if (!taken.isEmpty()) setItemStackToSlot(EntityEquipmentSlot.MAINHAND, taken);
            }
        }
        if (!getItemStackFromSlot(EntityEquipmentSlot.OFFHAND).isEmpty()) {
            ItemStack off = getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, off, false);
            if (!remainder.isEmpty()) {
                EntityItem drop = new EntityItem(world, posX, posY + 0.5, posZ, remainder);
                world.spawnEntity(drop);
            }
            setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
    }

    private void equipArmorSetFromBackpack() {
        // Find 3 pieces in backpack
        int bootsIdx = findFirstInInv(s -> isArmorForSlot(s, EntityEquipmentSlot.FEET));
        int legsIdx = findFirstInInv(s -> isArmorForSlot(s, EntityEquipmentSlot.LEGS));
        int chestIdx = findFirstInInv(s -> isArmorForSlot(s, EntityEquipmentSlot.CHEST));

        if (bootsIdx < 0 || legsIdx < 0 || chestIdx < 0) return; // require full set

        // (Optional) require matching material (leather vs iron)
        ItemArmor.ArmorMaterial mat = getArmorMaterial(inventory.getStackInSlot(chestIdx));
        if (mat != null) {
            if (getArmorMaterial(inventory.getStackInSlot(bootsIdx)) != mat) return;
            if (getArmorMaterial(inventory.getStackInSlot(legsIdx)) != mat) return;
        }

        // Equip them (leave helmet logic out)
        if (getItemStackFromSlot(EntityEquipmentSlot.FEET).isEmpty())
            setItemStackToSlot(EntityEquipmentSlot.FEET, inventory.extractItem(bootsIdx, 1, false));
        if (getItemStackFromSlot(EntityEquipmentSlot.LEGS).isEmpty())
            setItemStackToSlot(EntityEquipmentSlot.LEGS, inventory.extractItem(legsIdx, 1, false));
        if (getItemStackFromSlot(EntityEquipmentSlot.CHEST).isEmpty())
            setItemStackToSlot(EntityEquipmentSlot.CHEST, inventory.extractItem(chestIdx, 1, false));
    }

    private int findFirstInInv(java.util.function.Predicate<ItemStack> p) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty() && p.test(s)) return i;
        }
        return -1;
    }

    private boolean isArmorForSlot(ItemStack s, EntityEquipmentSlot slot) {
        return !s.isEmpty() && s.getItem() instanceof ItemArmor &&
                ((ItemArmor) s.getItem()).armorType == slot;
    }

    @Nullable
    private ItemArmor.ArmorMaterial getArmorMaterial(ItemStack s) {
        return (s.getItem() instanceof ItemArmor) ? ((ItemArmor) s.getItem()).getArmorMaterial() : null;
    }

    private void tryEquipArmorPiece(EntityEquipmentSlot slot) {
        if (!getItemStackFromSlot(slot).isEmpty()) return;

        int idx = findFirstInInv(s -> !s.isEmpty() && s.getItem().isValidArmor(s, slot, this));
        if (idx >= 0) {
            ItemStack taken = inventory.extractItem(idx, 1, false);
            if (!taken.isEmpty()) setItemStackToSlot(slot, taken);
        }
    }

    private void playTalkSound() {
        String cid = getCultureId();
        VoiceSet v = CultureRegistry.TRAVELLER_VOICES.get(cid);

        SoundEvent talk = (v != null && v.talk != null) ? v.talk : ModSounds.TRAVELLER_TALK;

        float vol = 1.0F;
        float pitch = 0.95F + (this.rand.nextFloat() - 0.5F) * 0.08F;
        int dur = 40 + this.rand.nextInt(20);
        playVoiceMoving(talk, vol, pitch, dur, /*preempt=*/true, VoiceKind.TALK);
        scheduleNextTravelAmbient();
    }

    private void doInteractSwing(EnumHand hand) {
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

    // was: announceHeadingTo(player)
    private void announceHeadingTo(EntityPlayer player) {
        if (travelCommon.getTargetVillage() == null) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "I don’t currently have a destination."
            ));
            return;
        }

        File millenaireFolder = new File(this.world.getSaveHandler().getWorldDirectory(), "millenaire");
        MillenaireVillageDirectory.reloadIfStale(millenaireFolder); // <-- keep fresh

        MillenaireVillageDirectory.Entry e = MillenaireVillageDirectory.findExact(travelCommon.getTargetVillage());
        if (e == null) {
            // Try up to ~128 blocks radius in 3D first
            e = MillenaireVillageDirectory.findNearest(travelCommon.getTargetVillage(), 128);
            if (e == null) {
                // If still nothing (Y might be far off), try 2D nearest
                e = MillenaireVillageDirectory.findNearest2D(travelCommon.getTargetVillage(), 160);
            }
        }

        String name = (e != null)
                ? e.name
                : (travelCommon.getTargetVillage().getX() + "/" + travelCommon.getTargetVillage().getY() + "/" + travelCommon.getTargetVillage().getZ());
        String dir = cardinalDirectionTo(travelCommon.getTargetVillage());

        ITextComponent msg = new TextComponentString(
                TextFormatting.WHITE + "I'm actually heading to " +
                        TextFormatting.GOLD + name +
                        TextFormatting.WHITE + " in the " +
                        TextFormatting.GOLD + dir +
                        TextFormatting.RESET + "."
        );
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

    // Disabling fallDamage
//    @Override
//    public void fall(float distance, float damageMultiplier) { /* Immune to fall damage */ }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        boolean tookDamage = super.attackEntityFrom(source, amount);
        if (!tookDamage || world.isRemote) return tookDamage;

        TravellerFollowNet.CHANNEL.sendToAllAround(
                new MsgStopTravellerVoice(this),
                new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64.0));
        beginVoiceLock(20, VoiceKind.HURT);

        Entity src = source.getTrueSource();

        if (src instanceof EntityPlayer && ((EntityPlayer) src).isCreative()) {
            setInCombat(false);
            return true;
        }

        // Travellers hitting travellers: retaliate ONLY across factions
        if (src instanceof EntityTraveller) {
            // Make absolutely sure HurtByTarget sees a revenge target
            this.setRevengeTarget((EntityLivingBase) src);
            EntityTraveller other = (EntityTraveller) src;
            if (!isCrossFaction(other)) {
                // same faction -> take damage but no aggro/revenge
                this.setRevengeTarget(null);
                return true;
            }
            // cross-faction -> allowed to retaliate
        }

        if (world.getDifficulty() != EnumDifficulty.PEACEFUL) {
            if (autoEatTask != null) autoEatTask.cancel();
            setInCombat(true);
            if (src instanceof EntityLivingBase) {
                rememberThreat((EntityLivingBase) src, THREAT_FOR_HIT);
                this.setAttackTarget((EntityLivingBase) src);
            }
        }
        return true;
    }

    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            TravellerFollowNet.CHANNEL.sendToAllAround(
                    new MsgStopTravellerVoice(this),
                    new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64.0));
            travellerCamp.cleanupOwnedCampfireIfAny();
        }
        if (!this.world.isRemote && this.isRiding() && this.getRidingEntity() instanceof net.minecraft.entity.item.EntityBoat) {
            net.minecraft.entity.item.EntityBoat boat = (net.minecraft.entity.item.EntityBoat) this.getRidingEntity();
            this.dismountRidingEntity();
            boat.setDead();
        }
        if (isGhostSword(getHeldItemMainhand())) setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        EntityTravellerDog dog = getDog();
        if (dog != null && !dog.world.isRemote) {
            dog.setAttackTarget(null);
            dog.setSitting(true);
            // optionally: dog.setOwnerId(null); dog.setTamed(false);  // if you want it to go wild again
        }

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
    @Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        VoiceSet v = CultureRegistry.TRAVELLER_VOICES.get(getCultureId());
        return (v != null && v.hurt != null) ? v.hurt : ModSounds.TRAVELLER_HURT;
    }

    @Override
    protected float getSoundVolume() {
        return 0.95F; // a touch louder than default
    }

    @Override
    protected float getSoundPitch() {
        // small per-hit pitch variance so repeats don’t sound robotic
        return 0.95F + (this.rand.nextFloat() - 0.5F) * 0.08F;
    }

    @Override
    public void travel(float strafe, float vertical, float forward) {
        if (this.isInWater()) {
            float boost = (float) TravellersModConfig.SWIM_MULT;
            super.travel(strafe * boost, vertical, forward * boost);
        } else {
            super.travel(strafe, vertical, forward);
        }
    }

    // --- Combat Handling ---
    @Override
    public boolean attackEntityAsMob(Entity target) {
        // base mob damage
        float damage = (float) this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();

        // use whatever is "in hand" (ghost or real)
        ItemStack weapon = this.getHeldItemMainhand();

        // Extra damage from Sharpness/Smite/Bane against the target's creature attribute
        if (!weapon.isEmpty() && target instanceof EntityLivingBase) {
            EntityLivingBase victim = (EntityLivingBase) target;
            // 1.12 helper that returns the total bonus vs that creature
            damage += net.minecraft.enchantment.EnchantmentHelper.getModifierForCreature(weapon, victim.getCreatureAttribute());
        }

        // Perform the hit
        boolean hit = target.attackEntityFrom(DamageSource.causeMobDamage(this), damage);
        if (!hit) return false;
        // tell clients to play the swing animation
        if (!world.isRemote) {
            world.setEntityState(this, (byte) 4); // vanilla “swing arm” packet
        }
        // Knockback from enchantments
        int kb = EnchantmentHelper.getKnockbackModifier(this);
        if (kb > 0 && target instanceof EntityLivingBase) {
            EntityLivingBase victim = (EntityLivingBase) target;

            // ← this is the correct vector: (+sin, -cos)
            double x = MathHelper.sin(this.rotationYaw * 0.017453292F);
            double z = -MathHelper.cos(this.rotationYaw * 0.017453292F);

            victim.knockBack(this, kb * 0.5F, x, z);

            // dampen our own forward motion (same as player code)
            this.motionX *= 0.6D;
            this.motionZ *= 0.6D;
        }

        // Fire Aspect
        int fire = net.minecraft.enchantment.EnchantmentHelper.getFireAspectModifier(this);
        if (fire > 0) {
            target.setFire(fire * 4); // same timing vanilla uses
        }

        // Play swing animation so it looks right
        this.swingArm(EnumHand.MAIN_HAND);

        // Apply remaining enchantment hooks (e.g., Thorns-like callbacks)
        this.applyEnchantments(this, target);

        return true;
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

    void cleanupOwnedCampfireIfAny() {
        travellerCamp.cleanupOwnedCampfireIfAny();
    }

    private void handleSwordVisibility() {
        if (isAimingBow()) return;
        if (world.isRemote) return;

        if (inCombat) {
            swordHideTicks = TravellersModConfig.SWORD_HIDE_DELAY;

            ItemStack best = findBestSwordInBackpack();
            if (!best.isEmpty()) {
                ItemStack cur = getHeldItemMainhand();
                if (cur.isEmpty() || !isGhostSword(cur) || swordScore(best) > swordScore(cur)) {
                    setHeldItem(EnumHand.MAIN_HAND, makeGhostCopy(best));
                }
            } else {
                if (isGhostSword(getHeldItemMainhand())) {
                    setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                }
            }
        } else {
            if (swordHideTicks > 0) {
                swordHideTicks--;
            } else {
                ItemStack cur = getHeldItemMainhand();
                if (isGhostSword(cur)) {
                    setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                } else if (!cur.isEmpty() && isSwordLike(cur)) {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, cur.copy(), false);
                    if (remainder.isEmpty()) setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                    // else: leave it in hand; avoids constant trying each tick
                }
            }
        }
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
            net.minecraft.entity.ai.attributes.AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
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
        return this.height * 0.85D; // tweak 0.8–0.9 to taste
    }

    // Allow exactly one passenger.
    @Override
    protected boolean canFitPassenger(Entity passenger) {
        return this.getPassengers().size() < 1;
    }

    @Override
    public boolean getAlwaysRenderNameTag() {
        // Hard disable via config: no name tag, even if custom name exists.
        if (!TravellersModConfig.showTravellerNames) {
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

    private ItemStack findBestSwordInBackpack() {
        int bestIdx = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!isSwordLike(s)) continue;
            double sc = swordScore(s);
            if (sc > bestScore) {
                bestScore = sc;
                bestIdx = i;
            }
        }
        return bestIdx >= 0 ? inventory.getStackInSlot(bestIdx) : ItemStack.EMPTY;
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

    private void scanAndPickupGroundItems() {
        AxisAlignedBB box = this.getEntityBoundingBox().grow(1.6, 0.6, 1.6); // close range
        List<EntityItem> drops = this.world.getEntitiesWithinAABB(EntityItem.class, box, e ->
                e != null && e.isEntityAlive() && !e.cannotPickup() && !e.getItem().isEmpty());

        boolean picked = false;

        for (EntityItem ei : drops) {
            ItemStack stack = ei.getItem();
            Item item = stack.getItem();

            // FOOD -> always useful
            if (item instanceof ItemFood) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, stack.copy(), false);
                if (remainder.isEmpty()) {
                    ei.setDead();
                    picked = true;
                    continue;
                } else if (remainder.getCount() != stack.getCount()) {
                    ei.setItem(remainder);
                    picked = true;
                    continue;
                }
            }
// BOW -> if we don't own any bow yet, take ONE and stash it
            if (isBowLike(stack) && !hasBowInBackpack()) {
                takeOneAndStash(ei);   // inserts 1 bow into backpack and shrinks ground stack
                picked = true;
                continue;
            }
            // SWORD -> if better than what we own, take ONE
            if (isSwordLike(stack) && isSwordUpgrade(stack)) {
                takeOneAndStash(ei);

                // Auto-equip only if not holding a *real* sword
                ItemStack hand = getHeldItemMainhand();
                boolean holdingRealSword = !hand.isEmpty() && isSwordLike(hand) && !isGhostSword(hand);
                if (!holdingRealSword) {
                    // optional: equipBestSwordFromBackpack();
                    // in practice, the ghost-hand logic will show it during combat anyway
                }

                picked = true;
                continue;
            }


            // ARMOR -> if better for its slot, equip and stash old piece
            for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{
                    EntityEquipmentSlot.FEET, EntityEquipmentSlot.LEGS, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.HEAD}) {
                if (isArmorItemForSlot(stack, slot) && isArmorUpgrade(stack, slot)) {
                    // equip candidate into 'slot', stash old piece
                    ItemStack one = stack.copy();
                    one.setCount(1);
                    ItemStack old = getItemStackFromSlot(slot);
                    setItemStackToSlot(slot, one);
                    if (!old.isEmpty()) ItemHandlerHelper.insertItemStacked(inventory, old, false);
                    shrinkOrRemove(ei);
                    picked = true;
                    break;
                }
            }
        }

        if (picked) {
            pickupCooldown = 20; // ~1s before next scan to avoid thrashing
            this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 0.9F + this.rand.nextFloat() * 0.2F);
        }

    }

    private ItemStack makeGhostCopy(ItemStack src) {
        ItemStack copy = src.copy();
        copy.setCount(1);
        NBTTagCompound tag = copy.getTagCompound();
        if (tag == null) tag = new NBTTagCompound();

        // Mark as ghost
        tag.setBoolean(GHOST_SWORD_TAG, true);

        // CRITICAL: strip AttributeModifiers so the ghost can't change stats/speed
        if (tag.hasKey("AttributeModifiers")) {
            tag.removeTag("AttributeModifiers");
        }
        // (Optional) also nuke any mod-specific speed tags you know about

        copy.setTagCompound(tag);
        return copy;
    }

    private void ensureBestSwordVisualNow() {
        ItemStack best = findBestSwordInBackpack();
        if (!best.isEmpty()) setHeldItem(EnumHand.MAIN_HAND, makeGhostCopy(best));
    }

    public void breakNearbyLeaves() {
        travelCommon.breakNearbyLeaves();
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

    /**
     * Align torso (renderYawOffset) to travel direction while pathing.
     */
    private void syncBodyYawWhileTravelling() {

        // In water, trust the waypoint over noisy motion

        // Low-pass desired yaw to avoid oscillation

        travelCommon.syncBodyYawWhileTravelling();
    }

    private void ensureRoutePlanned() {

        // diverse destination: may be near, mid, or far

        // collect villages along the way (your JavaFX-free corridor code)

        // Decide whether to stop at an intermediate first (same “hopPreference” idea as before)

        travelCommon.ensureRoutePlanned();
    }

    @Nullable
    private BlockPos pickDiverseDestination(BlockPos origin, int maxRadius) {

        // Sort by distance ascending

        // Chance split driven by wanderlust:
        //   low wanderlust -> more short trips; high -> more long trips

        // Exploration override: sometimes pick across all uniformly

        // Define bands by quantiles

        // Decide which band to pick from

        // Pick inside the band with a mild weight:
        // - near band: prefer slightly farther within near (avoid 1-block hops)
        // - mid band: flat
        // - far band: prefer the very far end
        return travelCommon.pickDiverseDestination(origin, maxRadius);
    }

    /**
     * Returns [v1, v2, ..., dest], where each v is a village near the straight line
     */
    private java.util.List<BlockPos> collectMidsOnCorridor(BlockPos start, BlockPos dest, double corridorWidth) {

        return travelCommon.collectMidsOnCorridor(start, dest, corridorWidth);
    }

    public BlockPos weightedPickInRange(java.util.List<BlockPos> list, BlockPos origin,
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

    public void rememberThreat(EntityLivingBase e, int addScore) {
        if (e == null || !e.isEntityAlive()) return;

        // Travellers: only across factions (XOR)
        if (e instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) e;

            // Same-faction (friendly↔friendly or raider↔raider) are never threats
            if (this.isAggressiveToTravellers() == t.isAggressiveToTravellers()) return;

            // NEW: friendly travellers do NOT treat same-culture raiders as threats
            if (!this.isAggressiveToTravellers() && t.isAggressiveToTravellers()) {
                if (isSameCulture(t)) return;
            }
        } else {
            // Non-travellers: allow if raider says they’re extra-hostile
            if (!shouldAttackExtraEntity(e)) {
                if (!(e instanceof net.minecraft.entity.monster.IMob)) return;
            }
        }


        if (e instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) e;
            if (p.isCreative() || p.isSpectator()) return;
        }

        // ...insert/throttle into threatMap (your existing code)...
        UUID id = e.getUniqueID();
        Threat th = threatMap.get(id);
        if (th == null) threatMap.put(id, new Threat(Math.max(1, addScore), this.ticksExisted));
        else {
            th.score = Math.min(1000, th.score + addScore);
            th.lastSeenTick = this.ticksExisted;
        }
    }

    private void decayAndPruneThreats() {
        if (threatMap.isEmpty()) return;
        int now = this.ticksExisted;

        // Only touch the map once per THREAT_DECAY_INTERVAL_TICKS
        if (now - lastThreatDecayTick < TravellersModConfig.THREAT_DECAY_INTERVAL_TICKS) return;
        lastThreatDecayTick = now;

        Iterator<Map.Entry<UUID, Threat>> it = threatMap.entrySet().iterator();
        while (it.hasNext()) {
            Threat t = it.next().getValue();
            int age = now - t.lastSeenTick;
            if (age > TravellersModConfig.THREAT_TTL_TICKS) {
                it.remove();
                continue;
            }

            t.score = Math.max(0, t.score - TravellersModConfig.THREAT_DECAY_PER_SEC);
            if (t.score == 0) it.remove();
        }
    }

    /**
     * Scan nearby hostiles; prefer the one with the highest threat score.
     * If several tie, prefer LOS, then closest.
     */
    @Nullable
    private EntityLivingBase pickBestTargetFromMemory(double range) {
        if (threatMap.isEmpty()) return null;

        AxisAlignedBB box = this.getEntityBoundingBox().grow(range, 8.0, range);
        List<EntityLivingBase> seen = this.world.getEntitiesWithinAABB(
                EntityLivingBase.class, box,
                e -> threatMap.containsKey(e.getUniqueID()) && e.isEntityAlive()
        );
        if (seen.isEmpty()) return null;

        EntityLivingBase best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (EntityLivingBase e : seen) {
            Threat t = threatMap.get(e.getUniqueID());
            if (t == null) continue;
            double s = t.score;

            if (this.getEntitySenses().canSee(e)) s += 2.0; // prefer LOS
            double d = Math.max(1.0, this.getDistance(e));
            s += 10.0 / d; // closer is slightly more urgent

            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }
        return best;
    }

    /**
     * Consider switching to a higher-priority remembered hostile.
     */
    private void considerRetargetFromMemory() {
        if (this.world.isRemote) return;
        // keep current target if fine
        EntityLivingBase cur = this.getAttackTarget();
        boolean curValid = cur != null && cur.isEntityAlive();
        if (curValid && this.getEntitySenses().canSee(cur)) {
            // Only switch if something is *much* more urgent than current
            EntityLivingBase candidate = pickBestTargetFromMemory(24.0);
            if (candidate != null && candidate != cur) {
                Threat curT = threatMap.get(cur.getUniqueID());
                int curScore = (curT != null ? curT.score : 0);
                Threat candT = threatMap.get(candidate.getUniqueID());
                int candScore = (candT != null ? candT.score : 0);

                if (candScore >= curScore + 4) {
                    this.setAttackTarget(candidate);
                    this.setInCombat(true);
                }
            }
        } else {
            // If we have no good current target, take the best remembered one
            EntityLivingBase pick = pickBestTargetFromMemory(24.0);
            if (pick != null) {
                this.setAttackTarget(pick);
                this.setInCombat(true);
            }
        }
    }

    public boolean isAtTargetStop() {
        // center-based distance is the least confusing
        return travelCommon.isAtTargetStop();
    }

    //    // --- tiny holder instead of javafx.util.Pair ---
//    private static final class Waypoint implements Comparable<Waypoint> {
//        final double t;        // progress along A->B (0..1)
//        final BlockPos pos;
//
//        Waypoint(double t, BlockPos pos) {
//            this.t = t;
//            this.pos = pos;
//        }
//
//        @Override
//        public int compareTo(Waypoint o) {
//            return Double.compare(this.t, o.t);
//        }
//    }
    private void tryScanAndPickupGroundItemsThrottled() {
        if (pickupCooldown > 0) return;
        if ((pickupScanTicker++ & 15) != 0) return;

        scanAndPickupGroundItems();
    }

    private List<BlockPos> getVillagesCached() {
        return travelCommon.getVillagesCached();
    }

    /**
     * Called by config system after a /travellerreload or GUI change.
     */
    public void onConfigReload() {
        // 1) Attributes
        getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(TravellersModConfig.maxHealth);
        if (getHealth() > getMaxHealth()) setHealth(getMaxHealth());
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(TravellersModConfig.movementSpeed);
        getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(TravellersModConfig.followRange);
        if (getAttributeMap().getAttributeInstance(SharedMonsterAttributes.ATTACK_DAMAGE) == null) {
            getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        }
        getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(TravellersModConfig.attackDamage);

        // 2) AI tunables
        if (aiDefendIfClose != null) aiDefendIfClose.setAssistRadius(TravellersModConfig.helpRange);
        if (aiTravel != null) aiTravel.setSpeed(TravellersModConfig.movementSpeedWhileTravel);

    }

    ItemStack makeGhostBow(ItemStack src) {
        ItemStack copy = src.copy();
        copy.setCount(1);
        NBTTagCompound tag = copy.getTagCompound();
        if (tag == null) tag = new NBTTagCompound();

        tag.setBoolean(GHOST_BOW_TAG, true);
        if (tag.hasKey("AttributeModifiers")) tag.removeTag("AttributeModifiers");

        // Make sure the item still *is* a bow – we want vanilla bow model & pull props
        copy.setTagCompound(tag);
        return copy;
    }

    public boolean hasBowAnywherePublic() {
        ItemStack hand = this.getHeldItemMainhand();
        boolean handBow = !hand.isEmpty() && hand.getItem() instanceof net.minecraft.item.ItemBow;
        return handBow || hasBowInBackpack();
    }

    /**
     * Increments the hit counter for this player and returns true while still under grace.
     */
    public boolean notePlayerHitAndIsStillGrace(net.minecraft.entity.player.EntityPlayer p) {
        final int now = this.ticksExisted;
        final java.util.UUID id = p.getUniqueID();

        HitGrace g = playerGrace.get(id);
        if (g == null || now > g.expiresAt) {
            g = new HitGrace();
            g.count = 0;
        }
        g.count++;
        g.expiresAt = now + TravellersModConfig.PLAYER_FORGIVE_WINDOW_TICKS;

        playerGrace.put(id, g);
        return g.count <= TravellersModConfig.PLAYER_FREE_HITS;
    }

    /**
     * Optional: call occasionally to prune stale entries.
     */
    public void pruneGraceHits() {
        final int now = this.ticksExisted;
        playerGrace.entrySet().removeIf(e -> now > e.getValue().expiresAt);
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

    public boolean isAmbientPlayingNow() {
        return isVoiceLockedNow() && voiceKindNow == VoiceKind.AMBIENT;
    }

    public boolean isVoiceLockedNow() {
        return this.ticksExisted < voiceLockUntilTick;
    }

    void beginVoiceLock(int durationTicks, VoiceKind kind) {
        voiceLockUntilTick = Math.max(voiceLockUntilTick, this.ticksExisted + Math.max(1, durationTicks));
        voiceKindNow = kind;
    }

    private boolean isCrossFaction(EntityTraveller other) {
        // fight only if one is aggressive and the other isn't
        return this.isAggressiveToTravellers() ^ other.isAggressiveToTravellers();
    }

    public void markPlayerRecentAggro(EntityPlayer p) {
        playerLastAggroTick.put(p.getUniqueID(), this.ticksExisted);
    }

    int lastPlayerAggroAgeTicks(EntityPlayer p) {
        Integer t = playerLastAggroTick.get(p.getUniqueID());
        return (t == null) ? Integer.MAX_VALUE : (this.ticksExisted - t);
    }

    private void resetChaseMemory() {
        chasingPlayerId = null;
        lastLOSorCloseTick = 0;
    }

    private boolean shouldHelloFor(EntityPlayer p) {
        Integer t = lastHelloTickByPlayer.get(p.getUniqueID());
        return t == null || (this.ticksExisted - t) >= HELLO_COOLDOWN_TICKS;
    }

    private void markHello(EntityPlayer p) {
        lastHelloTickByPlayer.put(p.getUniqueID(), this.ticksExisted);
        // prune very old entries opportunistically
        if ((this.ticksExisted & 0xFF) == 0) {
            int now = this.ticksExisted;
            lastHelloTickByPlayer.entrySet().removeIf(e -> now - e.getValue() > (HELLO_COOLDOWN_TICKS * 4));
        }
    }

    private void playHelloSound() {
        String cid = getCultureId();
        VoiceSet v = CultureRegistry.TRAVELLER_VOICES.get(cid);
        SoundEvent hello = (v != null && v.hello != null) ? v.hello : ModSounds.TRAVELLER_HELLO;

        float vol = 1.0F;
        float pitch = 0.95F + (this.rand.nextFloat() - 0.5F) * 0.08F;
        int dur = 30 + this.rand.nextInt(20);
        playVoiceMoving(hello, vol, pitch, dur, true, VoiceKind.TALK);
    }

    private void maybeRecruitDog() {
        if (dogScanCooldown > 0) {
            dogScanCooldown--;
            return;
        }
        dogScanCooldown = TravellersModConfig.DOG_SCAN_COOLDOWN_TICKS;

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

        // Convert wild wolf -> traveller dog
        EntityTravellerDog dog = new EntityTravellerDog(this.world);
        dog.setLocationAndAngles(wild.posX, wild.posY, wild.posZ, wild.rotationYaw, wild.rotationPitch);
        dog.enablePersistence();
        dog.setTamed(true);
        dog.setSitting(false);
        dog.setHealth(dog.getMaxHealth());
        dog.setOwnerId(this.getUniqueID()); // crucial: owner is the traveller
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
     * If we are travelling and very close to the not-loaded edge, count up and
     * despawn after a small grace window. This never force-loads chunks.
     */
    private void checkAndMaybeDespawnAtChunkEdge() {
        if (!TravellersModConfig.despawnNearUnloadedChunks) return;
        if (this.world.isRemote) return;                         // server only
        if (!(this.world instanceof WorldServer)) return;
        if (this.isInCombat() || this.isAutoEating() || this.isHandActive()) {
            nearUnloadTicks = 0;
            return;
        }
        if (travelCommon.isVillageIdling() || travelCommon.getTargetVillage() == null) {
            nearUnloadTicks = 0;
            return;
        }

        final WorldServer ws = (WorldServer) this.world;

        // Probe a small chunk square around the mob; if ANY is missing, we're at the edge.
        final BlockPos p = this.getPosition();
        final int r = Math.max(0, TravellersModConfig.despawnProbeRadiusChunks);
        final int cx0 = (p.getX() >> 4) - r;
        final int cx1 = (p.getX() >> 4) + r;
        final int cz0 = (p.getZ() >> 4) - r;
        final int cz1 = (p.getZ() >> 4) + r;

        boolean touchingEdge = false;
        for (int cx = cx0; cx <= cx1 && !touchingEdge; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!ws.getChunkProvider().chunkExists(cx, cz)) {
                    touchingEdge = true;
                    break;
                }
            }
        }

        if (touchingEdge) {
            nearUnloadTicks++;
        } else {
            nearUnloadTicks = 0;
        }

        if (nearUnloadTicks >= TravellersModConfig.despawnEdgeGraceTicks) {
            // Cleanly "fade out": no loot, no sound spam, also remove the companion dog.
            EntityTravellerDog dog = getDog();
            if (dog != null && !dog.world.isRemote) {
                // DON'T kill if owned by a player
                if (!(dog.isTamed() && dog.getOwner() instanceof EntityPlayer)) {
                    dog.setDead();
                }
            }
            this.setDead();
        }
    }

    private void checkAndMaybeDespawnFarFromPlayers() {
        if (!TravellersModConfig.despawnWhenFarFromPlayers) return;
        if (this.world.isRemote) return;
        if (!(this.world instanceof WorldServer)) return;
        if (this.isInCombat() || this.isAutoEating() || this.isHandActive()) {
            farFromPlayersTicks = 0;
            return;
        }
        if (travelCommon.isVillageIdling()) {
            farFromPlayersTicks = 0;
            return;
        }

        WorldServer ws = (WorldServer) this.world;

        // 1.12.2: get view distance from PlayerList, not PlayerChunkMap
        int viewChunks;
        try {
            viewChunks = ws.getMinecraftServer().getPlayerList().getViewDistance(); // e.g., 8–12
        } catch (Throwable t) {
            // configurable fallback if something weird happens (singleplayer etc.)
            viewChunks = TravellersModConfig.serverViewDistanceFallbackChunks; // e.g., 10
        }

        int bubbleChunks = Math.max(0, viewChunks + TravellersModConfig.farExtraChunks);
        int bubbleBlocks = Math.max(TravellersModConfig.farMinDistanceBlocks, bubbleChunks * 16);

        boolean anyNear = ws.getClosestPlayer(this.posX, this.posY, this.posZ, bubbleBlocks, false) != null;

        if (!anyNear) {
            farFromPlayersTicks++;
        } else {
            farFromPlayersTicks = 0;
        }

        if (farFromPlayersTicks >= TravellersModConfig.farGraceTicks) {
            EntityTravellerDog d = getDog();
            if (d != null && !d.world.isRemote) {
                if (!(d.isTamed() && d.getOwner() instanceof EntityPlayer)) {
                    d.setDead();
                }
            }
            this.setDead(); // clean fade (no loot)
        }
    }

    public boolean hasPlayerPassenger() {
        if (this.getPassengers().isEmpty()) return false;
        for (Entity e : this.getPassengers()) {
            if (e instanceof net.minecraft.entity.player.EntityPlayer) return true;
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

        // don't yank someone who's already looking at another traveller (unless it's at us)

        // make it mutual most of the time

        // cooldown before starting another new glance
        travellerCamp.tickCampSocial();
    }

    public void setTravelTorchVisible(boolean on) {
        if (world.isRemote) return;

        ItemStack off = getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);

        // === HARD GLOBAL OFF SWITCH ===
        if (!TravellersModConfig.travelTorchEnabled) {
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
            int cd = TravellersModConfig.travelTorchCooldownTicks > 0
                    ? TravellersModConfig.travelTorchCooldownTicks
                    : TravellersModConfig.TRAVEL_TORCH_COOLDOWN_TICKS_DEFAULT;
            if (travelTorchCooldown > 0) return;

            // If another traveller nearby already has a torch, skip
            double rad = TravellersModConfig.travelTorchNearbyRadius > 0
                    ? TravellersModConfig.travelTorchNearbyRadius
                    : TravellersModConfig.TRAVEL_TORCH_NEARBY_RADIUS_DEFAULT;
            if (hasNearbyTravellerTorch(rad)) return;

            // Only equip if offhand is empty or already ghost torch
            if (off.isEmpty() || isGhostTorch(off)) {
                setItemStackToSlot(EntityEquipmentSlot.OFFHAND, makeGhostTorch());
            }
        } else {
            // Turning OFF: clear ghost torch and start cooldown
            if (!off.isEmpty() && isGhostTorch(off)) {
                setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);

                int cd = TravellersModConfig.travelTorchCooldownTicks > 0
                        ? TravellersModConfig.travelTorchCooldownTicks
                        : TravellersModConfig.TRAVEL_TORCH_COOLDOWN_TICKS_DEFAULT;
                travelTorchCooldown = cd;
            }
        }
    }

    public boolean isInCombat() {
        return inCombat;
    }

    public void setInCombat(boolean combat) {
        if (world.isRemote) return; // server authority only
        this.inCombat = combat;
        if (combat) {
            if (autoEatTask != null) autoEatTask.cancel();
            ensureBestSwordVisualNow();
        }
    }

    public boolean isAutoEating() {
        return autoEatTask != null && autoEatTask.isEating();
    }

    public boolean hasNearbyTravellerTorch(double radius) {
        if (world == null) return false;

        AxisAlignedBB box = this.getEntityBoundingBox().grow(radius, 2.0, radius);
        java.util.List<EntityTraveller> peers = world.getEntitiesWithinAABB(
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
        ItemStack copy = new ItemStack(Blocks.TORCH);
        NBTTagCompound tag = copy.getTagCompound();
        if (tag == null) tag = new NBTTagCompound();

        tag.setBoolean(GHOST_TORCH_TAG, true);
        if (tag.hasKey("AttributeModifiers")) {
            tag.removeTag("AttributeModifiers");
        }

        copy.setTagCompound(tag);
        return copy;
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

    @Nullable
    public BlockPos getCampfirePos() {
        return travellerCamp.getCampfirePos();
    }

    private void maybeSpawnCompanionDog() {
        if (world.isRemote) return;
        if (suppressCompanionSpawn) return;                    // egg/command/etc.
        if (!TravellersModConfig.spawnTravellerWithDog) return;
        if (this.dogId != null) return;                        // already has one?

        double chance = TravellersModConfig.travellerDogSpawnChance; // or hard-code 0.35
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

        this.dogId = dog.getUniqueID();
        // If your dog has an API for owner, call it here:
        // dog.setTravellerOwner(this);

        ws.spawnEntity(dog);
    }

    /**
     * Find a nearby, non-solid, ground-supported position where the dog
     * won’t spawn inside a block.
     */
    @Nullable
    private BlockPos findSafeDogSpawnPos(World world, BlockPos center) {
        int radius = 2;

        // Prefer around the traveller at same Y, then one block up
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
// --- companion dog spawn helpers ---

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

            // If it's our traveller boat, it'll auto-despawn when empty
            // thanks to onUpdate() above. No need to force kill here,
            // but you can be explicit if you want:
            // if (boat instanceof EntityTravellerBoat) {
            //     boat.setDead();
            // }
        }
    }

    public enum VoiceKind {NONE, AMBIENT, TALK, HURT}

    public static final class SpawnData implements IEntityLivingData {
        public final String cultureId;

        public SpawnData(String id) {
            this.cultureId = id;
        }
    }

    private static final class HitGrace {
        int count;
        int expiresAt; // ticksExisted when this entry expires
    }

    private static final class Threat {
        int score;           // higher = more urgent
        int lastSeenTick;    // game tick when last updated

        Threat(int s, int t) {
            score = s;
            lastSeenTick = t;
        }
    }

}