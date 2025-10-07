package com.jubitus.traveller.traveller.entityAI;


import com.google.common.collect.Multimap;
import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.pathing.TravellerNavigateGround;
import com.jubitus.traveller.traveller.utils.commands.CommandFollowTraveller;
import com.jubitus.traveller.traveller.utils.debug.FollowTravellerClient;
import com.jubitus.traveller.traveller.utils.debug.TravellerFollowNet;
import com.jubitus.traveller.traveller.utils.sound.ModSounds;
import com.jubitus.traveller.traveller.utils.sound.MsgPlayTravellerVoice;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import com.jubitus.traveller.traveller.utils.villages.MillenaireVillageDirectory;
import com.jubitus.traveller.traveller.utils.villages.VillageIndex;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAIOpenDoor;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
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
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EntityTraveller extends EntityCreature {

    public static final int VILLAGE_NEAR = TravellersModConfig.villageNear;       // distance considered "arrived"
    public static final int VILLAGE_PICK_RADIUS = TravellersModConfig.villagePickRadius; // max radius for next-destination picks
    public static final int VILLAGE_RADIUS = TravellersModConfig.villageRadius;     // area to roam in (blocks from center)
    public static final int THREAT_FOR_SEE_ATTACK = 5;// when we "observe" hostiles hurting allies/players
    private static final double NEAR_HYS_DELTA_SQ = 4 * 4; // must be 4 blocks closer to switch
    private static final int THREAT_DECAY_INTERVAL_TICKS = 20; // 1s
    private static final int SWORD_HIDE_DELAY = TravellersModConfig.swordHideDelay; // ticks until sword is hidden
    // --- Texture related fields ---
    private static final ResourceLocation TRAVELLER_LOOT =
            new ResourceLocation("travellers", "entities/traveller_pack");
    private static final DataParameter<Integer> TEXTURE_INDEX = EntityDataManager.createKey(EntityTraveller.class, DataSerializers.VARINT);
    // ---- GAP / CLIFF handling ----
    private static final String GHOST_SWORD_TAG = "MillmixGhostSword";
    // --- Threat memory (not persisted by default) ---
    private static final int THREAT_FOR_HIT = 8;       // score added when this gets hit
    private static final int THREAT_DECAY_PER_SEC = 2; // decay rate
    private static final int THREAT_TTL_TICKS = 20 * 30; // forget after 30s of not seeing it
    private static final java.util.UUID SWIM_BOOST_UUID =
            java.util.UUID.fromString("b3b1a5ee-9c12-4e6f-a7e4-1c4ef2f35d61");
    private static final String SWIM_BOOST_NAME = "TravellerSwimBoost";
    // --- Auto-eat state ---
    private static final double SWIM_MULT = 1.8; // 80% faster in water
    private static final int ARRIVE_IN = Math.max(8, VILLAGE_NEAR - 4); // e.g., 28 if near=32
    private static final int ARRIVE_OUT = VILLAGE_NEAR + 4;              // e.g., 36
    private static final double CORRIDOR_WIDTH = 48.0; // tune 32–64
    private static final DataParameter<Boolean> AIMING_BOW =
            EntityDataManager.createKey(EntityTraveller.class, DataSerializers.BOOLEAN);
    private static final String GHOST_BOW_TAG = "TravellerGhostBow";
    // --- Grace hits vs players ---
    private static final int PLAYER_FREE_HITS = 3;
    private static final int PLAYER_FORGIVE_WINDOW_TICKS = 6 * 20; // reset window per attacker
    private static final double AMBIENT_MUTE_RADIUS = 20.0; // blocks (sphere)
    private static final double CHORUS_RADIUS = 20.0;
    private static final int CHORUS_INTENT_WINDOW_TICKS = 10; // treat near-simultaneous intents as “together”
    private static final SoundEvent[] DUO_SONGS = new SoundEvent[]{
            ModSounds.TRAVELLER_SONG1, ModSounds.TRAVELLER_SONG2, ModSounds.TRAVELLER_SONG3
    };
    private final ItemStackHandler inventory = new ItemStackHandler(18); // 18 slots example
    // --- Route planning state ---
    private final java.util.List<BlockPos> route = new java.util.ArrayList<>();
    private final java.util.Map<java.util.UUID, Threat> threatMap = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, HitGrace> playerGrace = new java.util.HashMap<>();
    int nextTravelAmbientTick = 0;  // you already had this
    private List<BlockPos> cachedVillages = java.util.Collections.emptyList();
    private int villageCacheTicker = 0;
    // ---- GAP / CLIFF handling control ----
    private boolean wasInWater = false;
    // --- AI State Fields (saved to disk)---
    private BlockPos targetVillage;
    private BlockPos currentWaypoint;
    private BlockPos roamTarget;
    private int pauseTicks;
    private int villageIdleTicks;
    private boolean villageIdling;
    private BlockPos nearestVillage;   // updated every tick
    // --- Combat State Fields ---
    private boolean inCombat = false;
    private int swordHideTicks = 0;
    private int lastThreatDecayTick = 0;
    // field on EntityTraveller
    private EntityAIAutoEat autoEatTask;
    // --- Caravan / follow-other-traveller state ---
    @Nullable
    private UUID followLeaderId = null;
    private int followTimeoutTicks = 0;
    // --- Equipment ---
    private int pickupCooldown = 0;
    // --- Path personality (persistent), so they don't always follow exact same path ---
    private float pathAngleBiasDeg;      // small left/right heading bias
    private double pathLaneOffset;       // lateral offset from the beeline
    private int pathStepJitter;          // jitter step size a little
    private int recalcPhase;             // desync repath ticks
    // Personality knobs
    private float wanderlust;          // 0..1 (0 = likes short trips, 1 = likes long trips)
    private float exploreChance;       // small epsilon to allow any village sometimes
    private int speakCooldown = 0;
    private int forcedSwingTicks = 0;
    private int hurtAnimCooldown = 0;
    private int routeIndex = -1; // -1 = no active route
    private int travelRecoverTicks = 0;
    // --- Village dwell/route handoff ---
    private boolean readyToDepart = false;
    private int departCooldownTicks = 0;
    // Personality: probability to insert an intermediate stop before the far destination
    private float hopPreference; // 0..1, e.g., 0.0 = never stop at B first, 1.0 = always
    private int pickupScanTicker = 0;
    private EntityAIRangedBowTravellerSafe aiRanged;
    private EntityAIAttackMeleeCustom aiMelee;
    private EntityAIDefendIfClose aiDefendIfClose;
    private EntityAIFollowTraveller aiFollow;
    private EntityAITravel aiTravel;
    // --- Voice channel ---
    private int voiceLockUntilTick = 0;     // no new sounds until this tick (entity-local, server authoritative)
    private VoiceKind voiceKindNow = VoiceKind.NONE;

    public EntityTraveller(World worldIn) {
        super(worldIn);
        this.setPathPriority(PathNodeType.WATER, 8.0F); // tries to avoid water

        this.setSize(0.5F, 1.6F);
        this.enablePersistence(); // make them persist in worl
        this.stepHeight = 1.4F; // Allow them to not jump but just stepUp 1 block height (avoids bugs with non-full blocks)
        this.setCanPickUpLoot(true);
        this.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    // Helper: clamp angular delta and step toward a target
    private static float rotlerp(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    private static double pointToSegment2DSq(Vec3d P, Vec3d A, Vec3d B) {
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
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) FollowTravellerClient.onClientTick();
    }

    public boolean isAtTargetStopHys(boolean lastWasInside) {
        BlockPos tv = this.getTargetVillage();
        if (tv == null) return false;
        double d2 = this.getDistanceSqToCenter(tv);
        int r = arrivalRadiusBlocks(tv);
        int inR = Math.min(r, ARRIVE_IN);
        int outR = Math.max(r, ARRIVE_OUT);
        return lastWasInside ? d2 <= (double) outR * outR
                : d2 <= (double) inR * inR;
    }


    // --- Getters & Setters for AI Fields ---
    public BlockPos getTargetVillage() {
        return this.targetVillage;
    }

    public void setTargetVillage(@Nullable BlockPos pos) {
        this.targetVillage = pos;
    }

    // --- Arrival policy (ONE source of truth) ---
    public int arrivalRadiusBlocks(BlockPos target) {
        // Prefer Millénaire’s custom near radius if present, else your config
        int r = MillenaireVillageDirectory.nearRadiusFor(target);
        if (r <= 0) r = EntityTraveller.VILLAGE_NEAR;
        return r;
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
        this.swordHideTicks = SWORD_HIDE_DELAY;
    }

    public int getTravelRecoverTicks() {
        return travelRecoverTicks;
    }

    public void setTravelRecoverTicks(int t) {
        travelRecoverTicks = t;
    }

    public boolean isReadyToDepart() {
        return readyToDepart;
    }

    public void setReadyToDepart(boolean v) {
        this.readyToDepart = v;
    }

    public int getDepartCooldownTicks() {
        return departCooldownTicks;
    }

    public void setDepartCooldownTicks(int t) {
        departCooldownTicks = t;
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

                this.setPathPriority(PathNodeType.LAVA, 8.0F);
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

        this.targetTasks.addTask(3, new TravellerHurtByTarget(this, true, TravellersModConfig.helpRange));
        autoEatTask = new EntityAIAutoEat(this, 3);
        this.tasks.addTask(6, autoEatTask);


//        this.tasks.addTask(5, new EntityAICombatDrawSword(this));

//        this.tasks.addTask(3, new EntityAICampPause(this));     // leader: can place fire + wait
//        this.tasks.addTask(4, new EntityAICampJoinFire(this));  // joiner: gather around existing fires

        this.tasks.addTask(7, new EntityAIStopAndLookAtPlayer(this, 3.2D, 30.0F, 30.0F));

        // Utility / travel
        this.tasks.addTask(9, new EntityAIOpenFenceGate(this));

        this.tasks.addTask(10, new EntityAIOpenDoor(this, true));

        this.tasks.addTask(11, new EntityAIRoamInsideVillage(this, 0.34D, 3 * 20, 10 * 20, TravellersModConfig.villageRoamMinDuration * 20, TravellersModConfig.villageRoamMaxDuration * 20));// Optional: customize 2-minute duration or wordlist at runtime

        aiFollow = new EntityAIFollowTraveller(this, 0.819, TravellersModConfig.movementSpeedWhileFollowing);
        this.tasks.addTask(12, aiFollow);

        aiTravel = new EntityAITravel(this, TravellersModConfig.movementSpeedWhileTravel, 48);
        this.tasks.addTask(13, aiTravel);

        this.tasks.addTask(15, new EntityAILookIdle(this));
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
        // Never target other travellers
        if (target instanceof EntityTraveller) {
            super.setAttackTarget(null);
            return;
        }
        // Peaceful guard
        if (world != null && world.getDifficulty() == EnumDifficulty.PEACEFUL) {
            super.setAttackTarget(null);
            setInCombat(false);
            return;
        }
        super.setAttackTarget(target);
        if (!world.isRemote && target != null) {
            setInCombat(true);
        }
    }

    // Traveller textures
    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(TEXTURE_INDEX, 0);
        this.dataManager.register(AIMING_BOW, Boolean.FALSE); // NEW
        // default texture index
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

        if (!world.isRemote) {
            if (departCooldownTicks > 0) departCooldownTicks--;

            if (travelRecoverTicks > 0) travelRecoverTicks--;
            tickFollowTimer();

            if (getAttackTarget() == null || !getAttackTarget().isEntityAlive()) {
                if (isInCombat()) {
                    setInCombat(false);
                    setTravelRecoverTicks(60); // ~3s grace; tune 40–80
                }
            }
            handleSwordVisibility();
            decayAndPruneThreats();
            considerRetargetFromMemory();
        }
        if (!isInCombat() || getAttackTarget() == null) {
            if (isAimingBow()) {
                setAimingBow(false);
                resetActiveHand();
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
            compound.setTag("TravellerInv", inventory.serializeNBT());
            if (targetVillage != null)
                compound.setTag("TargetVillage", net.minecraft.nbt.NBTUtil.createPosTag(targetVillage));
            if (currentWaypoint != null)
                compound.setIntArray("CurrentWaypoint", new int[]{currentWaypoint.getX(), currentWaypoint.getY(), currentWaypoint.getZ()});
            if (roamTarget != null)
                compound.setIntArray("RoamTarget", new int[]{roamTarget.getX(), roamTarget.getY(), roamTarget.getZ()});
            compound.setInteger("PauseTicks", pauseTicks);
            compound.setInteger("VillageIdleTicks", villageIdleTicks);
            compound.setBoolean("VillageIdling", villageIdling);
            compound.setInteger("RouteIndex", routeIndex);
            compound.setInteger("TextureIdx", getTextureIndex());
            compound.setFloat("Wanderlust", wanderlust);
            compound.setFloat("ExploreChance", exploreChance);
            compound.setFloat("HopPreference", hopPreference);
            compound.setFloat("PathAngleBiasDeg", pathAngleBiasDeg);
            compound.setDouble("PathLaneOffset", pathLaneOffset);
            compound.setInteger("PathStepJitter", pathStepJitter);
            compound.setInteger("RecalcPhase", recalcPhase);
            compound.setBoolean("ReadyToDepart", readyToDepart);
            compound.setInteger("DepartCooldown", departCooldownTicks);

            NBTTagList lst = new NBTTagList();
            for (BlockPos p : route) {
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

        if (compound.hasKey("TargetVillage", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            this.targetVillage = net.minecraft.nbt.NBTUtil.getPosFromTag(
                    compound.getCompoundTag("TargetVillage"));
        } else {
            this.targetVillage = null;
        }

        // CurrentWaypoint (only once)
        if (compound.hasKey("CurrentWaypoint")) {
            int[] a = compound.getIntArray("CurrentWaypoint");
            if (a.length == 3) currentWaypoint = new BlockPos(a[0], a[1], a[2]);
        } else {
            currentWaypoint = null;
        }

        if (compound.hasKey("RoamTarget")) {
            int[] arr = compound.getIntArray("RoamTarget");
            roamTarget = new BlockPos(arr[0], arr[1], arr[2]);
        } else {
            roamTarget = null;
        }
        if (compound.hasKey("TravellerInv", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            inventory.deserializeNBT(compound.getCompoundTag("TravellerInv"));
        }
        if (compound.hasKey("TextureIdx")) setTextureIndex(compound.getInteger("TextureIdx"));
        if (compound.hasKey("Wanderlust")) wanderlust = compound.getFloat("Wanderlust");
        if (compound.hasKey("ExploreChance")) exploreChance = compound.getFloat("ExploreChance");
        if (compound.hasKey("HopPreference")) hopPreference = compound.getFloat("HopPreference");
        if (compound.hasKey("PathAngleBiasDeg")) pathAngleBiasDeg = compound.getFloat("PathAngleBiasDeg");
        if (compound.hasKey("PathLaneOffset")) pathLaneOffset = compound.getDouble("PathLaneOffset");
        if (compound.hasKey("PathStepJitter")) pathStepJitter = compound.getInteger("PathStepJitter");
        if (compound.hasKey("RecalcPhase")) recalcPhase = compound.getInteger("RecalcPhase");
        pauseTicks = compound.getInteger("PauseTicks");
        villageIdleTicks = compound.getInteger("VillageIdleTicks");
        villageIdling = compound.getBoolean("VillageIdling");
        route.clear();
        if (compound.hasKey("RouteVillages", net.minecraftforge.common.util.Constants.NBT.TAG_LIST)) {
            NBTTagList lst = compound.getTagList("RouteVillages", net.minecraftforge.common.util.Constants.NBT.TAG_INT_ARRAY);
            for (int i = 0; i < lst.tagCount(); i++) {
                int[] a = lst.getIntArrayAt(i);
                if (a.length == 3) route.add(new BlockPos(a[0], a[1], a[2]));
            }
        }
        routeIndex = compound.getInteger("RouteIndex");
    }

    @Override
    protected void dropLoot(boolean wasRecentlyHit, int lootingModifier, DamageSource source) {
        super.dropLoot(wasRecentlyHit, lootingModifier, source);

        if (world.isRemote) return;

        // Drop traveller's backpack contents
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty()) {
                EntityItem item = new EntityItem(world, posX, posY + 0.5, posZ, s.copy());
                // Give a tiny toss so they don’t all stack in one spot
                item.motionX = (rand.nextDouble() - 0.5) * 0.1;
                item.motionY = 0.1;
                item.motionZ = (rand.nextDouble() - 0.5) * 0.1;
                world.spawnEntity(item);
                // Clear slot so we don't dupe if something else reads it
                inventory.extractItem(i, s.getCount(), false);
            }
        }
    }

    @Override
    public void onLivingUpdate() {
        // Handle swim boost FIRST so speed is correct for this tick's AI/motion
        this.tickSwimBoost();

        float NORMAL_STEP = 1.4F;
        float WATER_STEP = 0.0F;
        if (this.isInWater()) {
            if (this.stepHeight != WATER_STEP) this.stepHeight = WATER_STEP;
        } else {
            if (this.stepHeight != NORMAL_STEP) this.stepHeight = NORMAL_STEP;
        }

        super.onLivingUpdate();

        if (world.isRemote && hurtAnimCooldown > 0) hurtAnimCooldown--;
        if (speakCooldown > 0) speakCooldown--;
        if (world.isRemote) {
            if (forcedSwingTicks > 0) forcedSwingTicks--;
        }
        if (!world.isRemote) {
            if (pickupCooldown > 0) pickupCooldown--;
        }


        // Pick an initial random target only once
        if (!world.isRemote) {
            updateNearestVillage();

            // 1) Ensure we have a plan
            if (routeIndex < 0 || routeIndex >= route.size()) {
                ensureRoutePlanned();
            }

            // 2) Drive the current target from the route
            if (routeIndex >= 0 && routeIndex < route.size()) {
                BlockPos curStop = route.get(routeIndex);
                this.setTargetVillage(curStop);

                boolean nearCurrentStop = this.isAtTargetStop();

                // If we're inside the stop village AND the roam AI finished its stay, move to next hop
                if (nearCurrentStop && !this.isVillageIdling() && this.isReadyToDepart()) {
                    routeIndex++;
                    this.setReadyToDepart(false);
                    this.setDepartCooldownTicks(80); // ~4s @20tps (tune: 60–120 feels good)
                    if (routeIndex < route.size()) {
                        this.setTargetVillage(route.get(routeIndex));
                        this.getNavigator().clearPath();
                    } else {
                        // Trip finished
                        routeIndex = -1;
                        route.clear();
                        this.setTargetVillage(null);
                    }
                }

            }
            if (this.canPickUpLoot() && !isInCombat() && pickupCooldown == 0) {
                tryScanAndPickupGroundItemsThrottled();
            }

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
        if (slot == EntityEquipmentSlot.OFFHAND && !stack.isEmpty()) {
            // Reject or redirect
            ItemHandlerHelper.insertItemStacked(inventory, stack.copy(), false);
            return; // don’t set it
        }
        super.setItemStackToSlot(slot, stack);
    }

    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr != null) {
            // set your intended base walking speed here (typical vanilla base ~0.23D)
            attr.setBaseValue(TravellersModConfig.movementSpeed);
            // remove any leftover copy of our swim boost just in case
            net.minecraft.entity.ai.attributes.AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
            if (m != null) attr.removeModifier(m);
        }
        livingdata = super.onInitialSpawn(difficulty, livingdata);
        this.setTextureIndex(this.rand.nextInt(7));

        if (!world.isRemote) {
            // 1) Roll the loot table to fill the backpack
            LootTable table = world.getLootTableManager().getLootTableFromLocation(TRAVELLER_LOOT);
            LootContext ctx = new LootContext.Builder((WorldServer) world)
                    .withLootedEntity(this)
                    .build();

            for (ItemStack stack : table.generateLootForPools(this.rand, ctx)) {
                ItemHandlerHelper.insertItemStacked(inventory, stack, false);
            }

            // 2) Move some items to equipment slots so they render
            equipFromBackpackOnce();
            equipArmorSetFromBackpack();

        }
        this.setHealth(this.getMaxHealth());
        java.util.Random r = new java.util.Random(this.getUniqueID().getMostSignificantBits()
                ^ this.getUniqueID().getLeastSignificantBits());
        this.wanderlust = 0.25f + r.nextFloat() * 0.60f;   // 0.25–0.85 (most are mid-long)
        this.exploreChance = 0.08f + r.nextFloat() * 0.06f;  // 8–14% “pick anything” chance
        this.hopPreference = 0.45f + r.nextFloat() * 0.25f; // 45–70% of travellers will add a mid stop
        this.pathAngleBiasDeg = (r.nextFloat() - 0.5f) * 12f;          // -6°..+6°
        this.pathLaneOffset = (r.nextBoolean() ? 1 : -1) * (0.6 + r.nextFloat() * 0.9); // 0.6..1.5 blocks
        this.pathStepJitter = r.nextInt(9) - 4;                       // -4..+4 blocks
        this.recalcPhase = r.nextInt(12);                          // 0..11 ticks phase
        return livingdata;
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

    private void tryEquipArmorPiece(EntityEquipmentSlot slot) {
        if (!getItemStackFromSlot(slot).isEmpty()) return;

        int idx = findFirstInInv(s -> !s.isEmpty() && s.getItem().isValidArmor(s, slot, this));
        if (idx >= 0) {
            ItemStack taken = inventory.extractItem(idx, 1, false);
            if (!taken.isEmpty()) setItemStackToSlot(slot, taken);
        }
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

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;

        if (speakCooldown > 0) return true;
        speakCooldown = 20; // ~1s

        // NEW: optional voice line
        if (TravellersModConfig.travellerSpeaks) {
            playTalkSound();
        }
        doInteractSwing(hand);
        // … your existing logic …
        if (isFollowingSomeone()) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "I'm travelling with someone right now. Ask me again later."
            ));
            return true;
        }
        if (isVillageIdling()) {
            String stayName = lookupNearestVillageNameOrCoords();
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

    private void playTalkSound() {
        float vol = 1.0F;
        float pitch = 0.95F + (this.rand.nextFloat() - 0.5F) * 0.08F;
        int dur = 40 + this.rand.nextInt(20);
        playVoiceMoving(ModSounds.TRAVELLER_TALK, vol, pitch, dur, /*preempt=*/true, VoiceKind.TALK);
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

    public boolean isVillageIdling() {  // you already store this
        return this.villageIdling;
    }

    // was: lookupNearestVillageNameOrCoords()
    private String lookupNearestVillageNameOrCoords() {
        BlockPos near = getNearestVillage();
        if (near == null) return "this area";
        return MillVillageIndex.nameForApprox(this.world, near);
    }

    // was: announceHeadingTo(player)
    private void announceHeadingTo(EntityPlayer player) {
        if (targetVillage == null) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "I don’t currently have a destination."
            ));
            return;
        }

        File millenaireFolder = new File(this.world.getSaveHandler().getWorldDirectory(), "millenaire");
        MillenaireVillageDirectory.reloadIfStale(millenaireFolder); // <-- keep fresh

        MillenaireVillageDirectory.Entry e = MillenaireVillageDirectory.findExact(targetVillage);
        if (e == null) {
            // Try up to ~128 blocks radius in 3D first
            e = MillenaireVillageDirectory.findNearest(targetVillage, 128);
            if (e == null) {
                // If still nothing (Y might be far off), try 2D nearest
                e = MillenaireVillageDirectory.findNearest2D(targetVillage, 160);
            }
        }

        String name = (e != null)
                ? e.name
                : (targetVillage.getX() + "/" + targetVillage.getY() + "/" + targetVillage.getZ());
        String dir = cardinalDirectionTo(targetVillage);

        ITextComponent msg = new TextComponentString(
                TextFormatting.WHITE + "I'm actually heading to " +
                        TextFormatting.GOLD + name +
                        TextFormatting.WHITE + " in the " +
                        TextFormatting.GOLD + dir +
                        TextFormatting.RESET + "."
        );
        player.sendMessage(msg);
    }

    private boolean playVoiceMoving(SoundEvent evt, float vol, float pitch, int durationTicks, boolean preempt, VoiceKind kind) {
        if (!preempt && this.ticksExisted < voiceLockUntilTick) return false;

        // Don’t also call playSound here; we only want the moving instance on clients.
        sendFollowSound(evt, vol, pitch, 48.0);

        voiceLockUntilTick = Math.max(voiceLockUntilTick, this.ticksExisted + Math.max(1, durationTicks));
        voiceKindNow = kind;
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

    // Follower things
    public boolean hasFollowLeader() {
        return followLeaderId != null;
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
        return this.nearestVillage; // may be null if none found yet
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
        MsgPlayTravellerVoice msg = new MsgPlayTravellerVoice(this, evt, vol, pitch /* duration now unused, keep or remove field */);
        NetworkRegistry.TargetPoint tp = new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, radius);
        TravellerFollowNet.CHANNEL.sendToAllAround(msg, tp);
    }

    public void setVillageIdling(boolean villageIdling) {
        this.villageIdling = villageIdling;
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

    public int getTextureIndex() {
        return this.dataManager.get(TEXTURE_INDEX);
    }

    public void setTextureIndex(int index) {
        this.dataManager.set(TEXTURE_INDEX, index);
    }

    @Mod.EventHandler
    public void serverStarting(net.minecraftforge.fml.common.event.FMLServerStartingEvent e) {
        e.registerServerCommand(new CommandFollowTraveller());
    }

    // was: updateTargetVillage()
    private void updateNearestVillage() {
        List<BlockPos> villages = getVillagesCached();
        if (villages.isEmpty()) {
            this.nearestVillage = null;
            return;
        }

        BlockPos me = this.getPosition();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (BlockPos v : villages) {
            double d2 = me.distanceSq(v);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = v;
            }
        }

        if (this.nearestVillage == null) {
            this.nearestVillage = best;
        } else {
            double curD2 = me.distanceSq(this.nearestVillage);
            if (bestD2 + NEAR_HYS_DELTA_SQ < curD2) {
                this.nearestVillage = best;
            }
        }

        if (!world.isRemote) syncBodyYawWhileTravelling();
    }

    @Override
    public boolean getCanSpawnHere() {
        if (world.isRemote) return false;

        // Global-per-dimension loaded cap
        int cap = TravellersModConfig.loadedTravellerCap;
        int loaded = this.world.getEntities(EntityTraveller.class, net.minecraft.entity.Entity::isEntityAlive).size();
        if (loaded >= cap) return false;

        // Your existing “near a village” check
        BlockPos pos = new BlockPos(this.posX, this.posY, this.posZ);
        for (BlockPos v : MillVillageIndex.getAllVillageCenters(this.world)) {
            if (v.distanceSq(pos) <= 2000L * 2000L) {
                return super.getCanSpawnHere();
            }
        }
        return false;
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

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        boolean tookDamage = super.attackEntityFrom(source, amount);
        if (!tookDamage || world.isRemote) return tookDamage;
        // Preempt ambience right now; duration ~1s (match your hurt clip length)
        beginVoiceLock(20, VoiceKind.HURT); // ~1s, tune to your hurt clip
        Entity src = source.getTrueSource();

        // Allow Creative hits: take damage, but no aggro / no help ping
        if (src instanceof EntityPlayer && ((EntityPlayer) src).isCreative()) {
            setInCombat(false);                 // don't enter combat state
            // do NOT setAttackTarget, do NOT cancelEating
            return true;
        }

        // Friendly fire from another traveller: take damage, but ignore for combat logic
        if (src instanceof EntityTraveller) {
            // Clear revenge so HurtByTarget AI won't fire or call allies
            this.setRevengeTarget(null);
            // Don't enter combat, don't cancel eating, don't set target
            return true;
        }

        // Normal retaliation (non-creative, non-traveller)
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
        // remove ghost item if present
        if (isGhostSword(getHeldItemMainhand())) {
            setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        super.onDeath(cause);
    }

    @Override
    @Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.TRAVELLER_HURT;  // plays at the entity’s position for all nearby players
    }

    // Disabling fallDamage
    @Override
    public void fall(float distance, float damageMultiplier) { /* Immune to fall damage */ }

    @Override
    protected float getSoundVolume() {
        return 0.95F; // a touch louder than default
    }

    @Override
    protected float getSoundPitch() {
        // small per-hit pitch variance so repeats don’t sound robotic
        return 0.95F + (this.rand.nextFloat() - 0.5F) * 0.08F;
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

    private void handleSwordVisibility() {
        if (isAimingBow()) return;
        if (world.isRemote) return;

        if (inCombat) {
            swordHideTicks = SWORD_HIDE_DELAY;

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
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr != null) {
            net.minecraft.entity.ai.attributes.AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
            if (m != null) attr.removeModifier(m);
        }
        super.setDead();
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
        return roamTarget;
    }

    public void setRoamTarget(BlockPos roamTarget) {
        this.roamTarget = roamTarget;
    }

    public int getPauseTicks() {
        return pauseTicks;
    }

    public void setPauseTicks(int pauseTicks) {
        this.pauseTicks = pauseTicks;
    }

    public int getVillageIdleTicks() {
        return villageIdleTicks;
    }

    public void setVillageIdleTicks(int villageIdleTicks) {
        this.villageIdleTicks = villageIdleTicks;
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
        BlockPos base = this.getPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = base.add(dx, dy, dz);
                    IBlockState st = world.getBlockState(p);
                    if (st.getBlock() instanceof net.minecraft.block.BlockLeaves) {
                        world.destroyBlock(p, true); // drop leaves
                    }
                }
            }
        }
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
        return pathAngleBiasDeg;
    }

    public double getPathLaneOffset() {
        return pathLaneOffset;
    }

    public int getPathStepJitter() {
        return pathStepJitter;
    }

    public int getRecalcPhase() {
        return recalcPhase;
    }

    /**
     * Align torso (renderYawOffset) to travel direction while pathing.
     */
    private void syncBodyYawWhileTravelling() {
        // Only when navigating somewhere
        if (this.isInCombat() || this.getAttackTarget() != null) return;
        if (this.getNavigator().noPath() && this.getCurrentWaypoint() == null) return;
        if (this.getNavigator().noPath() && this.getCurrentWaypoint() == null) return;

        // Decide desired yaw: prefer actual motion; fallback to waypoint direction
        double dx = this.motionX;
        double dz = this.motionZ;

        if (dx * dx + dz * dz < 0.0004D) { // ~0.02^2 — basically stationary
            BlockPos wp = this.getCurrentWaypoint();
            if (wp != null) {
                double tx = (wp.getX() + 0.5D) - this.posX;
                double tz = (wp.getZ() + 0.5D) - this.posZ;
                if (tx * tx + tz * tz > 1e-6D) {
                    dx = tx;
                    dz = tz;
                }
            } else {
                return; // nowhere to face
            }
        }

        float desired = (float) (Math.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;

        // Turn limits per tick (tweak to taste)
        float bodyTurn = 45.0F;
        float headTurn = 60.0F;
        float headLimit = 75.0F; // max head-body divergence
        if (this.isInWater() && this.motionY < -0.2D) return;

        // Smoothly steer body toward movement/waypoint
        this.rotationYaw = rotlerp(this.rotationYaw, desired, bodyTurn);
        this.renderYawOffset = rotlerp(this.renderYawOffset, this.rotationYaw, bodyTurn);

        // Keep head near the body while travelling (don’t let combat stare linger)
        this.rotationYawHead = rotlerp(this.rotationYawHead, this.renderYawOffset, headTurn);

        // Clamp head divergence
        float diff = MathHelper.wrapDegrees(this.rotationYawHead - this.renderYawOffset);
        if (diff < -headLimit) this.rotationYawHead = this.renderYawOffset - headLimit;
        if (diff > headLimit) this.rotationYawHead = this.renderYawOffset + headLimit;
    }

    private void ensureRoutePlanned() {
        if (routeIndex >= 0 && routeIndex < route.size()) return;

        BlockPos here = this.getPosition();

        // diverse destination: may be near, mid, or far
        BlockPos dest = pickDiverseDestination(here, VILLAGE_PICK_RADIUS * 4);
        if (dest == null) return;

        // collect villages along the way (your JavaFX-free corridor code)
        java.util.List<BlockPos> mids = collectMidsOnCorridor(here, dest, CORRIDOR_WIDTH);

        this.route.clear();

        // Decide whether to stop at an intermediate first (same “hopPreference” idea as before)
        boolean takeMidFirst = !mids.isEmpty() && (this.rand.nextFloat() < this.hopPreference);
        if (takeMidFirst) {
            // Usually choose the first mid (closest to start), occasionally a later one for variety
            int idx = 0;
            if (mids.size() >= 3 && this.rand.nextFloat() < 0.25f) {
                idx = 1 + this.rand.nextInt(Math.min(2, mids.size() - 1));
            }
            this.route.add(mids.get(idx));
        } else if (!mids.isEmpty() && this.rand.nextFloat() < 0.20f) {
            // Sometimes include all mids
            this.route.addAll(mids);
        }

        this.route.add(dest);   // final destination
        this.routeIndex = 0;
        this.setTargetVillage(this.route.get(0));
        this.setCurrentWaypoint(null);
    }

    @Nullable
    private BlockPos pickDiverseDestination(BlockPos origin, int maxRadius) {
        List<BlockPos> all = getVillagesCached();
        if (all.isEmpty()) return null;

        long r2 = (long) maxRadius * (long) maxRadius;
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>(all.size());
        for (BlockPos v : all) {
            if (v.equals(origin)) continue;
            if (maxRadius > 0 && origin.distanceSq(v) > r2) continue;
            candidates.add(v);
        }
        if (candidates.isEmpty()) return null;

        // Sort by distance ascending
        candidates.sort(java.util.Comparator.comparingDouble(origin::distanceSq));

        int n = candidates.size();
        if (n == 1) return candidates.get(0);

        // Chance split driven by wanderlust:
        //   low wanderlust -> more short trips; high -> more long trips
        float shortP = 0.40f * (1.0f - this.wanderlust) + 0.10f; // 0.10–0.50
        float longP = 0.50f * (this.wanderlust) + 0.20f;        // 0.20–0.70
        float midP = 1.0f - (shortP + longP);
        if (midP < 0.10f) midP = 0.10f; // keep some mids
        float rest = 1.0f - (shortP + longP + midP);
        if (rest != 0) {
            shortP += rest * 0.5f;
        } // normalize

        // Exploration override: sometimes pick across all uniformly
        if (this.rand.nextFloat() < this.exploreChance) {
            return candidates.get(this.rand.nextInt(n));
        }

        // Define bands by quantiles
        int nearEnd = Math.max(1, n / 3);             // first ~third
        int midStart = nearEnd;
        int midEnd = Math.max(midStart + 1, (2 * n) / 3); // second ~third
        int farStart = midEnd;

        // Decide which band to pick from
        float roll = this.rand.nextFloat();
        int from, to;
        if (roll < shortP) {
            from = 0;
            to = nearEnd;
        } else if (roll < shortP + midP) {
            from = midStart;
            to = midEnd;
        } else {
            from = farStart;
            to = n;
        }

        // Pick inside the band with a mild weight:
        // - near band: prefer slightly farther within near (avoid 1-block hops)
        // - mid band: flat
        // - far band: prefer the very far end
        BlockPos pick = weightedPickInRange(candidates, origin, from, to,
                (from == 0) ? +0.5 : (from == farStart ? -0.5 : 0.0));
        return pick != null ? pick : candidates.get(this.rand.nextInt(n));
    }

    /**
     * Returns [v1, v2, ..., dest], where each v is a village near the straight line
     */
    private java.util.List<BlockPos> collectMidsOnCorridor(BlockPos start, BlockPos dest, double corridorWidth) {
        List<BlockPos> all = getVillagesCached();
        java.util.List<BlockPos> mids = new java.util.ArrayList<>();
        if (all.isEmpty()) return mids;

        Vec3d A = new Vec3d(start.getX() + 0.5, 0, start.getZ() + 0.5);
        Vec3d B = new Vec3d(dest.getX() + 0.5, 0, dest.getZ() + 0.5);
        Vec3d AB = B.subtract(A);
        double ab2 = AB.lengthSquared();
        if (ab2 < 1e-6) return mids;

        final class Mid implements Comparable<Mid> {
            final double t;
            final BlockPos p;

            Mid(double t, BlockPos p) {
                this.t = t;
                this.p = p;
            }

            public int compareTo(Mid o) {
                return Double.compare(this.t, o.t);
            }
        }
        java.util.List<Mid> tmp = new java.util.ArrayList<>();

        for (BlockPos v : all) {
            if (v.equals(start) || v.equals(dest)) continue;
            Vec3d P = new Vec3d(v.getX() + 0.5, 0, v.getZ() + 0.5);
            double t = AB.dotProduct(P.subtract(A)) / ab2;     // along-track fraction
            if (t <= 0.02 || t >= 0.98) continue;              // strictly between A and C
            double cw2 = corridorWidth * corridorWidth;
            if (pointToSegment2DSq(P, A, B) <= cw2) {
                tmp.add(new Mid(t, v));
            }
        }
        java.util.Collections.sort(tmp);
        for (Mid m : tmp) mids.add(m.p);
        return mids;
    }

    private BlockPos weightedPickInRange(java.util.List<BlockPos> list, BlockPos origin,
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
        if (e instanceof EntityTraveller) return; // never treat fellows as threats
        if (e instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) e;
            if (p.isCreative() || p.isSpectator()) return;
        }
        java.util.UUID id = e.getUniqueID();
        Threat th = threatMap.get(id);
        if (th == null) {
            threatMap.put(id, new Threat(Math.max(1, addScore), this.ticksExisted));
        } else {
            th.score = Math.min(1000, th.score + addScore);
            th.lastSeenTick = this.ticksExisted;
        }
    }

    private void decayAndPruneThreats() {
        if (threatMap.isEmpty()) return;
        int now = this.ticksExisted;

        // Only touch the map once per THREAT_DECAY_INTERVAL_TICKS
        if (now - lastThreatDecayTick < THREAT_DECAY_INTERVAL_TICKS) return;
        lastThreatDecayTick = now;

        Iterator<Map.Entry<UUID, Threat>> it = threatMap.entrySet().iterator();
        while (it.hasNext()) {
            Threat t = it.next().getValue();
            int age = now - t.lastSeenTick;
            if (age > THREAT_TTL_TICKS) {
                it.remove();
                continue;
            }

            t.score = Math.max(0, t.score - THREAT_DECAY_PER_SEC);
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

    public void tickSwimBoost() {
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr == null) return;

        boolean inWater = this.isInWater();
        net.minecraft.entity.ai.attributes.AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);

        // Keep modifier exactly in sync with being in water
        if (inWater) {
            if (m == null) {
                // MULTIPLY_TOTAL (op=2) like vanilla Speed potion: (1 + amount)
                attr.applyModifier(new AttributeModifier(SWIM_BOOST_UUID, SWIM_BOOST_NAME, SWIM_MULT - 1.0, 2));
            }
        } else {
            if (m != null) {
                attr.removeModifier(m);
            }
            // Damp the “carry-over” momentum once on exit
            if (wasInWater) {
                // reduce horizontal lurch; tune factor 0.35–0.7 if you like
                this.motionX *= 0.52D;
                this.motionZ *= 0.52D;
            }
        }

        wasInWater = inWater;
    }


    public boolean isAtTargetStop() {
        BlockPos tv = this.getTargetVillage();
        if (tv == null) return false;
        int r = arrivalRadiusBlocks(tv);
        // center-based distance is the least confusing
        return this.getDistanceSqToCenter(tv) <= (double) (r * r);
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
        if ((villageCacheTicker++ & 15) == 0) { // refresh every 16 ticks
            cachedVillages = VillageIndex.getVillages(this.world);
        }
        return cachedVillages;
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

    public boolean isAimingBow() {
        return this.dataManager.get(AIMING_BOW);
    }

    public void setAimingBow(boolean v) {
        if (!world.isRemote) this.dataManager.set(AIMING_BOW, v);
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
        g.expiresAt = now + PLAYER_FORGIVE_WINDOW_TICKS;

        playerGrace.put(id, g);
        return g.count <= PLAYER_FREE_HITS;
    }

    /**
     * Optional: call occasionally to prune stale entries.
     */
    public void pruneGraceHits() {
        final int now = this.ticksExisted;
        playerGrace.entrySet().removeIf(e -> now > e.getValue().expiresAt);
    }

    public void maybePlayTravelAmbient() {
        if (!TravellersModConfig.travellerAmbient) return;
        if (this.isInCombat() || this.isAutoEating() || this.isHandActive()) return;
        if (this.getTargetVillage() == null) return;
        if (this.getNavigator().noPath() && this.getCurrentWaypoint() == null) return;
        if (this.motionX * this.motionX + this.motionZ * this.motionZ < 0.005) return;

        if (this.ticksExisted < voiceLockUntilTick) return;


        // 3) Fallback: solo ambient
        float vol = TravellersModConfig.travellerAmbientVolume;
        float pitch = 0.95F + (this.rand.nextFloat() - 0.5F) * 0.06F;
        int dur = 50 + this.rand.nextInt(30);

        if (playVoiceMoving(ModSounds.AMBIENT, vol, pitch, dur, /*preempt=*/false, VoiceKind.AMBIENT)) {
            scheduleNextTravelAmbient();
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

    public BlockPos getCurrentWaypoint() {
        return currentWaypoint;
    }

    public void setCurrentWaypoint(BlockPos currentWaypoint) {
        this.currentWaypoint = currentWaypoint;
    }

    private void beginVoiceLock(int durationTicks, VoiceKind kind) {
        voiceLockUntilTick = Math.max(voiceLockUntilTick, this.ticksExisted + Math.max(1, durationTicks));
        voiceKindNow = kind;
    }

    public boolean isAmbientPlayingNow() {
        return isVoiceLockedNow() && voiceKindNow == VoiceKind.AMBIENT;
    }

    public boolean isVoiceLockedNow() {
        return this.ticksExisted < voiceLockUntilTick;
    }

    private enum VoiceKind {NONE, AMBIENT, TALK, HURT}

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