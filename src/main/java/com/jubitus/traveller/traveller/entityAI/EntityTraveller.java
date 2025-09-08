package com.jubitus.traveller.traveller.entityAI;


import com.google.common.collect.Multimap;
import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.pathing.TravellerNavigateGround;
import com.jubitus.traveller.traveller.utils.MillenaireVillageDirectory;
import com.jubitus.traveller.traveller.utils.VillageDataLoader;
import com.jubitus.traveller.traveller.utils.VillageIndex;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAIOpenDoor;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.*;
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
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.*;
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
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EntityTraveller extends EntityCreature {


    private static final int EAT_COOLDOWN_TICKS = 60;
    // --- AI State Fields (saved to disk)---
    private BlockPos targetVillage;
    private BlockPos currentWaypoint;
    private BlockPos roamTarget;
    private int pauseTicks;
    private int villageIdleTicks;
    private boolean villageIdling;
    public static final int VILLAGE_NEAR = TravellersModConfig.villageNear;       // distance considered "arrived"
    public static final int VILLAGE_PICK_RADIUS = TravellersModConfig.villagePickRadius; // max radius for next-destination picks
    public static final int VILLAGE_RADIUS = TravellersModConfig.villageRadius;     // area to roam in (blocks from center)
    private BlockPos nearestVillage;   // updated every tick (do NOT use for travel path)
    private final ItemStackHandler inventory = new ItemStackHandler(18); // 18 slots example


    // --- Combat State Fields ---
    private boolean inCombat = false;
    private int swordHideTicks = 0;
    private static final int SWORD_HIDE_DELAY = TravellersModConfig.swordHideDelay; // ticks until sword is hidden

    // --- Texture related fields ---
    private static final ResourceLocation TRAVELLER_LOOT =
            new ResourceLocation("travellers", "entities/traveller_pack");
    private static final DataParameter<Integer> TEXTURE_INDEX = EntityDataManager.createKey(EntityTraveller.class, DataSerializers.VARINT);

    // --- Auto-eat state ---
    private static final float EAT_HEALTH_THRESHOLD = TravellersModConfig.eatHealthThreshold; // start eating at or below 90%
    private static final int EAT_DURATION_TICKS = TravellersModConfig.eatDurationTicks;    // 4s
    private int eatTicks = 0;
    private int eatCooldown = 0;
    private ItemStack savedMainHand = ItemStack.EMPTY; // to restore whatever they held before

    // --- Ranged combat tuning ---
    private static final double RANGED_MIN_DIST = TravellersModConfig.rangedMinDist;   // start using bow beyond this
    private static final double RANGED_MAX_DIST = TravellersModConfig.rangedMaxDist;  // don't try to snipe farther than this
    private static final int RANGED_COOLDOWN = TravellersModConfig.rangedCooldown;    // ticks between shots (~1.5s)
    private int bowCooldown = 0;

    // ---- GAP / CLIFF handling ----
    private static final int MAX_HOP_FORWARD = 8;  // how far forward we search (blocks)
    private static final int MAX_DROP_SCAN = 16; // how far down we scan to find surface (blocks)

    // ---- gap snap throttling ----
    private int edgeStuckTicks = 0;
    private int snapCooldownTicks = 0;
    private Vec3d lastPosForStuck = Vec3d.ZERO;
    private static final int EDGE_STUCK_TIME = 20;  // ticks to wait at edge before snap (~3s)
    private static final int SNAP_COOLDOWN = 40;  // ticks after a snap before another (~4s)
    private static final double MIN_PROGRESS_SQ = 0.03; // ~0.1 blocks moved squared
    // --- Caravan / follow-other-traveller state ---
    @Nullable
    private UUID followLeaderId = null;
    private int followTimeoutTicks = 0;
    // Camp state (not persisted—optional)
    private int campCooldown = 0; // ticks until next chance to start/join a camp

    // --- Equipment ---
    private int pickupCooldown = 0;
    private static final String GHOST_SWORD_TAG = "MillmixGhostSword";

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

    // --- Route planning state ---
    private final java.util.List<BlockPos> route = new java.util.ArrayList<>();
    private int routeIndex = -1; // -1 = no active route

    private int travelRecoverTicks = 0;
    public int getTravelRecoverTicks() { return travelRecoverTicks; }
    public void setTravelRecoverTicks(int t) { travelRecoverTicks = t; }

    // --- Village dwell/route handoff ---
    private boolean readyToDepart = false;
    public boolean isReadyToDepart() { return readyToDepart; }
    public void setReadyToDepart(boolean v) { this.readyToDepart = v; }

    private int departCooldownTicks = 0;
    public int getDepartCooldownTicks() { return departCooldownTicks; }
    public void setDepartCooldownTicks(int t) { departCooldownTicks = t; }

    // Personality: probability to insert an intermediate stop before the far destination
    private float hopPreference; // 0..1, e.g., 0.0 = never stop at B first, 1.0 = always

    private static final double CORRIDOR_WIDTH = 48.0; // tune 32–64

    public EntityTraveller(World worldIn) {
        super(worldIn);

        this.setSize(0.5F, 1.6F);
        this.enablePersistence(); // make them persist in worl
        this.stepHeight = 1.8F; // Allow them to not jump but just stepUp 1 block height (avoids bugs with non-full blocks)
        this.setCanPickUpLoot(true);
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

// (Optional) still avoid lava, etc.
                this.setPathPriority(PathNodeType.LAVA, 8.0F);

            }
            try {
                this.setPathPriority(net.minecraft.pathfinding.PathNodeType.valueOf("ICE"), 0.0F);
            } catch (IllegalArgumentException ignored) {

            }
        }
        File millenaireFolder = new File(this.world.getSaveHandler().getWorldDirectory(), "millenaire");
        List<BlockPos> villages = VillageDataLoader.loadVillageCenters(millenaireFolder);

        // Combat AI
        this.tasks.addTask(0, new EntityAIAttackMeleeCustom(this, 0.6D, true));
        this.tasks.addTask(1, new EntityAICombatDrawSword(this));
        // defend if a hostile gets near (e.g., 6 blocks, require line-of-sight) ---
        this.targetTasks.addTask(1, new EntityAIDefendIfClose(this, 2.0D, true));

        // stop and look at nearby player like a trader ---
        // args: range blocks, yaw speed, pitch speed
        this.tasks.addTask(2, new EntityAIStopAndLookAtPlayer(this, 2.4D, 30.0F, 30.0F));


        // NEW: Occasionally follow another traveller you meet
        this.tasks.addTask(3, new EntityAIFollowTraveller(this, 0.55D));


//        this.tasks.addTask(2, new EntityAICampPause(this));              // <-- new

        // Utility / Roaming AI
        this.tasks.addTask(5, new EntityAIOpenFenceGate(this)); // true = allow opening
        this.tasks.addTask(5, new EntityAIOpenDoor(this, true));
        this.tasks.addTask(5, new EntityAINewTravel(this, 0.54D, 48)); // speed, step size in blocks
       this.tasks.addTask(0, new EntityAISwimming(this));
        this.tasks.addTask(7, new EntityAILookIdle(this));
        this.tasks.addTask(4, new EntityAIRoamInsideVillage(this, 0.34D, 50 * 20, 120 * 20, 20 * 5, 20 * 30));
        this.targetTasks.addTask(0,
                new TravellerHurtByTarget(this, true, 8));
    }
    @Override
    public void onUpdate() {
        super.onUpdate();

        if (!world.isRemote) {
            if (departCooldownTicks > 0) departCooldownTicks--;
        }
        if (!world.isRemote) {
            tickFollowTimer();
        }
        if (!world.isRemote) {
            if (travelRecoverTicks > 0) travelRecoverTicks--;
        }
        if (!world.isRemote) {
            if (getAttackTarget() == null || !getAttackTarget().isEntityAlive()) {
                if (isInCombat()) {
                    setInCombat(false);
                    setTravelRecoverTicks(60); // ~3s grace; tune 40–80
                }
            }
            handleRangedVisibilityAndShooting();
            handleSwordVisibility();
        }

    }
    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if (world.isRemote && hurtAnimCooldown > 0) hurtAnimCooldown--;
        if (speakCooldown > 0) speakCooldown--;
        if (world.isRemote) {
            if (forcedSwingTicks > 0) forcedSwingTicks--;
        }
        if (!world.isRemote) {
            if (pickupCooldown > 0) pickupCooldown--;
        }
        if (!world.isRemote) {
            updateNearestVillage();

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

                    double near2 = (double) VILLAGE_NEAR * (double) VILLAGE_NEAR;
                    boolean nearCurrentStop = this.getPosition().distanceSq(curStop) <= near2;

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
            }


            if (!world.isRemote) {

                // Cooldown
                if (eatCooldown > 0) eatCooldown--;

                // If in combat, never eat
                if (isInCombat()) {
                    cancelEating();
                } else {
                    // Consider eating
                    float hp = this.getHealth();
                    float max = (float) this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).getAttributeValue();

                    if (eatTicks > 0) {
                        // actively eating: keep still and play occasional bite sounds
                        this.getNavigator().clearPath();

                        if (eatTicks % 7 == 0) {
                            this.playSound(net.minecraft.init.SoundEvents.ENTITY_GENERIC_EAT, 0.8F,
                                    0.9F + this.rand.nextFloat() * 0.2F);
                        }

                        eatTicks--;
                        if (eatTicks <= 0) {
                            // finish snack: heal and clean up
                            this.addPotionEffect(new PotionEffect(MobEffects.REGENERATION, 80, 2)); // 3s Regen I                            eatCooldown = EAT_COOLDOWN_TICKS;

                            // stop animation & restore held item
                            this.resetActiveHand();
                            setHeldItem(EnumHand.MAIN_HAND, savedMainHand);
                            savedMainHand = ItemStack.EMPTY;
                        }
                    } else {
                        // not currently eating: should we start?
                        boolean midHealth = hp > 0 && hp <= max * EAT_HEALTH_THRESHOLD;
                        boolean safe = !hasNearbyHostiles();
                        boolean notMoving = this.getNavigator().noPath();

                        if (eatCooldown == 0 && midHealth && safe) {
                            beginEating();
                        }
                    }
                }
            }
            if (this.canPickUpLoot() && !isInCombat() && pickupCooldown == 0) {
                scanAndPickupGroundItems();
            }

        }

// ---- GAP ESCAPE with stuck timer ----
        if (!isAutoEating()) { // <-- skip edge snap while intentionally paused to eat
            if (snapCooldownTicks > 0) snapCooldownTicks--;

            boolean holeAhead = isHoleAhead(0.6, 2);
            boolean onGroundNow = this.onGround;

            // track movement every tick
            Vec3d now = this.getPositionVector();
            double movedSq = lastPosForStuck == Vec3d.ZERO ? 0.0 : now.squareDistanceTo(lastPosForStuck);
            lastPosForStuck = now;

            // consider "not progressing" if barely moving horizontally and navigator has no path
            boolean noProgress = movedSq < MIN_PROGRESS_SQ && this.getNavigator().noPath();

            // increment/dec edge timer
            if (holeAhead && onGroundNow && noProgress) {
                edgeStuckTicks++;
            } else {
                edgeStuckTicks = 0;
            }

            // only snap if we've been stuck at the edge long enough and cooldown is over
            if (edgeStuckTicks >= EDGE_STUCK_TIME && snapCooldownTicks == 0) {
                BlockPos landing = findGapLandingForward();
                if (landing != null) {
                    this.setPosition(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                    this.motionX = this.motionY = this.motionZ = 0.0D;
                    this.getNavigator().clearPath();
                    this.velocityChanged = true;

                    // reset timers
                    snapCooldownTicks = SNAP_COOLDOWN;
                    edgeStuckTicks = 0;
                    lastPosForStuck = this.getPositionVector();
                }
            }
        } else {
            // while eating, don't accumulate false "stuck" state
            edgeStuckTicks = 0;
            lastPosForStuck = this.getPositionVector();
        }
    }


    // was: updateTargetVillage()
    private void updateNearestVillage() {
        List<BlockPos> villages = VillageIndex.getVillages(this.world);
        if (villages.isEmpty()) {
            this.nearestVillage = null;
            return;
        }
        double closestDist = Double.MAX_VALUE;
        BlockPos closest = null;
        BlockPos me = this.getPosition();
        for (BlockPos v : villages) {
            double d = me.distanceSq(v);
            if (d < closestDist) {
                closestDist = d;
                closest = v;
            }
        }
        this.nearestVillage = closest;
        if (!world.isRemote) {
            // Realign torso while walking to the next waypoint
            syncBodyYawWhileTravelling();
        }
    }

    @Override
    public boolean getCanSpawnHere() {
        if (world.isRemote) return false;
        BlockPos pos = new BlockPos(this.posX, this.posY, this.posZ);
        for (BlockPos v : VillageIndex.getVillages(this.world)) {
            if (v.distanceSq(pos) <= 2000L * 2000L) {
                return super.getCanSpawnHere();
            }
        }
        return false;
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

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {

        boolean result = super.attackEntityFrom(source, amount);
        if (result && !world.isRemote && world.getDifficulty() != EnumDifficulty.PEACEFUL) {
            cancelEating();
            setInCombat(true);                 // triggers immediate equip
            if (source.getTrueSource() instanceof EntityLivingBase) {
                this.setAttackTarget((EntityLivingBase) source.getTrueSource());
            }
            if (source.getTrueSource() instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) source.getTrueSource();
                if (player.isCreative()) {
                    return false; // cancels damage + prevents retaliation
                }
            }

            return super.attackEntityFrom(source, amount);
        }

        return result;

    }

    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        if (world != null && world.getDifficulty() == EnumDifficulty.PEACEFUL) {
            super.setAttackTarget(null);
            setInCombat(false);
            return;
        }
        super.setAttackTarget(target);
        if (!world.isRemote && target != null) {
            setInCombat(true);               // triggers cancelEating()
        }
    }

    public boolean isInCombat() {
        return inCombat;
    }

    public void setInCombat(boolean combat) {
        this.inCombat = combat;
        if (!world.isRemote && combat) {
            cancelEating();                  // ← stop the snack NOW
            ensureBestSwordVisualNow();      // your existing helper (optional)
        }
    }

    private void handleSwordVisibility() {
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
                    // if some real sword ended up in hand (from other code), try stashing it
                    ItemStack remainder = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inventory, cur.copy(), false);
                    if (remainder.isEmpty()) setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                }
            }
        }
    }


    // Equipment things
    private double armorScore(ItemStack s, EntityEquipmentSlot slot) {
        // Base from attributes
        Multimap<String, AttributeModifier> a = getAttribsFor(s, slot);
        double armor = sumAttr(a, SharedMonsterAttributes.ARMOR.getName());                 // "generic.armor"
        double tough = sumAttr(a, SharedMonsterAttributes.ARMOR_TOUGHNESS.getName());       // "generic.armorToughness"

        // Enchantments: Protection adds ~= 4%/lvl to many types; weight it modestly
        int prot = EnchantmentHelper.getEnchantmentLevel(Enchantments.PROTECTION, s);
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

    private void takeOneAndStash(EntityItem ei) {
        ItemStack src = ei.getItem();
        ItemStack one = src.copy();
        one.setCount(1);
        ItemHandlerHelper.insertItemStacked(inventory, one, false);
        shrinkOrRemove(ei);
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

    private int findFirstInInv(java.util.function.Predicate<ItemStack> p) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty() && p.test(s)) return i;
        }
        return -1;
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

    @SideOnly(Side.CLIENT)
    public float getForcedSwingProgress() {
        return (forcedSwingTicks <= 0) ? 0f : (1f - (forcedSwingTicks / 6.0f));
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
    public void onDeath(DamageSource cause) {
        // remove ghost item if present
        if (isGhostSword(getHeldItemMainhand())) {
            setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        super.onDeath(cause);
    }


    // --- Home Override? Dunno if useful since I don't extend villager ---
    @Override
    public boolean hasHome() {
        return false;
    }

    @Override
    public void setHomePosAndDistance(BlockPos pos, int distance) {
    }

    @Override
    public boolean isWithinHomeDistanceCurrentPosition() {
        return true;
    }

    // Disabling fallDamage
    @Override
    public void fall(float distance, float damageMultiplier) { /* Immune to fall damage */ }

    // --- Getters & Setters for AI Fields ---
    public BlockPos getTargetVillage() {
        return this.targetVillage;
    }

    public void setTargetVillage(@Nullable BlockPos pos) {
        this.targetVillage = pos;
    }

    public BlockPos getCurrentWaypoint() {
        return currentWaypoint;
    }


    public void setCurrentWaypoint(BlockPos currentWaypoint) {
        this.currentWaypoint = currentWaypoint;
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

    public void setVillageIdling(boolean villageIdling) {
        this.villageIdling = villageIdling;
    }

    // --- NBT Saving / Loading ---
    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        compound.setTag("TravellerInv", inventory.serializeNBT());
        if (targetVillage != null) {
            compound.setIntArray("TargetVillage", new int[]{targetVillage.getX(), targetVillage.getY(), targetVillage.getZ()});
        }
        if (currentWaypoint != null) {
            compound.setIntArray("CurrentWaypoint", new int[]{currentWaypoint.getX(), currentWaypoint.getY(), currentWaypoint.getZ()});
        }
        if (roamTarget != null) {
            compound.setIntArray("RoamTarget", new int[]{roamTarget.getX(), roamTarget.getY(), roamTarget.getZ()});
        }
        compound.setInteger("PauseTicks", pauseTicks);
        compound.setInteger("VillageIdleTicks", villageIdleTicks);
        compound.setBoolean("VillageIdling", villageIdling);
        compound.setInteger("RouteIndex", routeIndex);
        NBTTagList lst = new NBTTagList();
        for (BlockPos p : route) {
            NBTTagIntArray arr = new NBTTagIntArray(new int[]{p.getX(), p.getY(), p.getZ()});
            lst.appendTag(arr);
        }
        compound.setTag("RouteVillages", lst);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);

        if (compound.hasKey("TargetVillage")) {
            int[] arr = compound.getIntArray("TargetVillage");
            targetVillage = new BlockPos(arr[0], arr[1], arr[2]);
        }
        if (compound.hasKey("CurrentWaypoint")) {
            int[] arr = compound.getIntArray("CurrentWaypoint");
            currentWaypoint = new BlockPos(arr[0], arr[1], arr[2]);
        }
        if (compound.hasKey("RoamTarget")) {
            int[] arr = compound.getIntArray("RoamTarget");
            roamTarget = new BlockPos(arr[0], arr[1], arr[2]);
        }
        if (compound.hasKey("TravellerInv", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            inventory.deserializeNBT(compound.getCompoundTag("TravellerInv"));
        }

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


    // Traveller textures
    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(TEXTURE_INDEX, 0);
        // default texture index
    }

    // markDirty: called when capability changes
    private void markDirty() {
        // if you later sync to client, trigger it here
    }

    // ---- capability plumbing ----
    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return true;
        return super.hasCapability(capability, facing);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
            return (T) inventory;
        return super.getCapability(capability, facing);
    }

    public int getTextureIndex() {
        return this.dataManager.get(TEXTURE_INDEX);
    }

    public void setTextureIndex(int index) {
        this.dataManager.set(TEXTURE_INDEX, index);
    }

    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
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
        this.wanderlust   = 0.25f + r.nextFloat() * 0.60f;   // 0.25–0.85 (most are mid-long)
        this.exploreChance = 0.08f + r.nextFloat() * 0.06f;  // 8–14% “pick anything” chance
        this.hopPreference = 0.45f + r.nextFloat() * 0.25f; // 45–70% of travellers will add a mid stop
        this.pathAngleBiasDeg = (r.nextFloat() - 0.5f) * 12f;          // -6°..+6°
        this.pathLaneOffset = (r.nextBoolean() ? 1 : -1) * (0.6 + r.nextFloat() * 0.9); // 0.6..1.5 blocks
        this.pathStepJitter = r.nextInt(9) - 4;                       // -4..+4 blocks
        this.recalcPhase = r.nextInt(12);                          // 0..11 ticks phase
        return livingdata;
    }

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;

        if (speakCooldown > 0) return true; // avoid spam if someone spams clicks
        speakCooldown = 20; // ~1s

        // 1) If following another traveller: do NOT reveal destination
        if (isFollowingSomeone()) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "I'm travelling with someone right now. Ask me again later."
            ));
            return true;
        }

        // 2) If we’re roaming/loitering inside a village: do NOT reveal destination
        if (isVillageIdling()) {
            // Optional: say where we’re staying (nearest village name), *not* the next target
            String stayName = lookupNearestVillageNameOrCoords();
            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "I'm staying in " +
                            TextFormatting.GOLD + stayName +
                            TextFormatting.WHITE + " for a bit."
            ));
            return true;
        }

        // 3) Otherwise: normal “I’m heading to … in the …” line
        world.setEntityState(this, (byte) 4); // tell clients to swing NOW
        announceHeadingTo(player);
        return true;
    }

    private String lookupNearestVillageNameOrCoords() {
        BlockPos near = getNearestVillage();
        if (near == null) return "this area";

        File millenaireFolder = new File(this.world.getSaveHandler().getWorldDirectory(), "millenaire");
        MillenaireVillageDirectory.load(millenaireFolder);

       MillenaireVillageDirectory.Entry e =
               MillenaireVillageDirectory.findExact(near);
        if (e == null) e = MillenaireVillageDirectory.findNearest(near, 64);

        return (e != null)
                ? e.name
                : (near.getX() + "/" + near.getY() + "/" + near.getZ());
    }





    private boolean isAreaLoaded(BlockPos pos, int radius) {
        if (!(world instanceof WorldServer)) return world.isAreaLoaded(pos, radius);
        WorldServer ws = (WorldServer) world;
        int minCX = (pos.getX() - radius) >> 4, maxCX = (pos.getX() + radius) >> 4;
        int minCZ = (pos.getZ() - radius) >> 4, maxCZ = (pos.getZ() + radius) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++)
            for (int cz = minCZ; cz <= maxCZ; cz++)
                if (!ws.getChunkProvider().chunkExists(cx, cz)) return false;
        return true;
    }

    /**
     * Feet: solid below, feet & head free.
     */
    private boolean isStandable(BlockPos feet, boolean allowWater) {
        World w = this.world;
        IBlockState below = w.getBlockState(feet.down());
        IBlockState at    = w.getBlockState(feet);
        IBlockState head  = w.getBlockState(feet.up());

        if (!allowWater) {
            return supportsStandingSurface(w, feet.down(), below)
                    && at.getMaterial().isReplaceable()
                    && head.getMaterial().isReplaceable();
        }

        // Amphibious: allow feet/head in water or air; below can be SOLID, water top, or full collision
        boolean belowOk = supportsStandingSurface(w, feet.down(), below)
                || below.getMaterial() == Material.WATER;

        return belowOk
                && isPassableOrWater(w, feet)
                && isPassableOrWater(w, feet.up());
    }
    private static boolean supportsStandingSurface(World w, BlockPos pos, IBlockState st) {
        if (st.getMaterial().isLiquid()) return false;

        BlockFaceShape shape = st.getBlockFaceShape(w, pos, EnumFacing.UP);
        if (shape == BlockFaceShape.SOLID) return true;

        AxisAlignedBB bb = st.getBoundingBox(w, pos);
        if (bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001) return true;

        Block b = st.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.FROSTED_ICE
                || b == Blocks.GLASS || b == Blocks.STAINED_GLASS;
    }


    private static boolean isPassableOrWater(World w, BlockPos p) {
        IBlockState s = w.getBlockState(p);
        return s.getMaterial().isReplaceable() || s.getMaterial() == Material.WATER;
    }
    /**
     * True if the block column in front is air for a few blocks down = a hole/cliff.
     */
    private boolean isHoleAhead(double dist, int depthAirNeeded) {
        Vec3d look = this.getLookVec();
        if (look.lengthSquared() < 1e-6) return false;

        int ax = MathHelper.floor(this.posX + look.x * dist);
        int az = MathHelper.floor(this.posZ + look.z * dist);
        int y = MathHelper.floor(this.posY);

        // Check air at feet and for a little depth
        for (int d = 0; d < depthAirNeeded; d++) {
            BlockPos p = new BlockPos(ax, y - d, az);
            if (!world.isAirBlock(p)) return false;
        }
        return true; // feet ahead is open downward => gap
    }

    /**
     * Look forward up to MAX_HOP_FORWARD to find the next standable block (no chunk loads).
     * Returns the feet position centered on top, or null if none in range.
     */
    @Nullable
    private BlockPos findGapLandingForward() {
        Vec3d look = this.getLookVec();
        if (look.lengthSquared() < 1e-6) return null;

        Vec3d dir = new Vec3d(look.x, 0, look.z).normalize();
        if (dir.lengthSquared() < 1e-6) return null;

        int startY = MathHelper.floor(this.posY);

        // Step forward 1 block at a time
        for (int step = 1; step <= MAX_HOP_FORWARD; step++) {
            int x = MathHelper.floor(this.posX + dir.x * step);
            int z = MathHelper.floor(this.posZ + dir.z * step);
            BlockPos col = new BlockPos(x, startY, z);

            if (!isAreaLoaded(col, 1)) continue; // never load chunks

            // Scan downward to find a surface (limited)
            BlockPos cursor = col;
            for (int dy = 0; dy <= MAX_DROP_SCAN; dy++) {
                BlockPos feet = cursor.down(dy);
                if (isStandable(feet, true)) {
                    return feet;
                }
                // Early stop: if we hit bedrock levels or non-air column becomes weird
                if (feet.getY() <= 1) break;
            }
        }
        return null;
    }

    private boolean hasNearbyHostiles() {
        AxisAlignedBB box = this.getEntityBoundingBox().grow(12.0, 6.0, 12.0);
        List<EntityLivingBase> list = this.world.getEntitiesWithinAABB(EntityLivingBase.class, box,
                e -> e instanceof net.minecraft.entity.monster.IMob && e.isEntityAlive());
        return !list.isEmpty();
    }

    private void beginEating() {
        if (eatCooldown > 0 || eatTicks > 0) return;

        // stash whatever was in hand (sword, tool, etc.)
        savedMainHand = getHeldItemMainhand().copy();

        // put a fake food for animation (server-side is enough; sync handles client)
        setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.BREAD));

        // play “use item” animation
        this.setActiveHand(EnumHand.MAIN_HAND);

        // stop moving
        this.getNavigator().clearPath();

        eatTicks = EAT_DURATION_TICKS;
    }

    private void cancelEating() {
        // already not eating / not using anything?
        if (eatTicks <= 0 && !this.isHandActive()) return;

        // stop the use animation & reset state
        eatTicks = 0;
        this.resetActiveHand();

        // restore what was in hand before the snack (ghost sword/bow is fine)
        if (!savedMainHand.isEmpty()) {
            setHeldItem(EnumHand.MAIN_HAND, savedMainHand);
        }
        savedMainHand = ItemStack.EMPTY;

        // don't immediately re-trigger eating
        eatCooldown = EAT_COOLDOWN_TICKS;

        // let movement AI re-push a path right away
        this.getNavigator().clearPath();
    }

    // EQUIPMENT RELATED THINGS
    @Override
    protected void updateEquipmentIfNeeded(EntityItem itemEntity) {
        // Route ALL pickups through your logic (no vanilla hand/armor swaps)
        ItemStack stack = itemEntity.getItem();
        Item item = stack.getItem();

        // Armor upgrades (use your isArmorItemForSlot/isArmorUpgrade helpers)
        for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{
                EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET}) {
            if (isArmorItemForSlot(stack, slot) && isArmorUpgrade(stack, slot)) {
                ItemStack one = stack.copy();
                one.setCount(1);
                safeReplaceInSlot(slot, one);    // stashes old piece or drops if full
                shrinkOrRemove(itemEntity);
                this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
                return;
            }
            // Bow: stash only if we don't have one yet
            if (item instanceof ItemBow && !hasBowInBackpack()) {
                takeOneAndStash(itemEntity); // takes exactly one bow
                this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
                return;
            }
        }


        // Swords: stash only if upgrade; we equip on combat start
        if (item instanceof ItemSword && isSwordUpgrade(stack)) {
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

        // Everything else: ignore (prevents vanilla from equipping bows/tools/etc.)
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

    private int findBestSwordSlot() {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (s.isEmpty() || !(s.getItem() instanceof ItemSword)) continue;
            double sc = swordScore(s);
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        return best;
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

        int sharp = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                net.minecraft.init.Enchantments.SHARPNESS, s);
        double ench = sharp > 0 ? (0.51 * sharp + 0.5) : 0.0;

        double dur = 0.0;
        if (s.isItemStackDamageable()) {
            int max = s.getMaxDamage();
            int left = Math.max(0, max - s.getItemDamage());
            dur = Math.min(0.9, (left / (double) max) * 0.5);
        }

        return baseDmg + ench + dur + (materialRank * 0.1);
    }


    private boolean isArmorItemForSlot(ItemStack s, EntityEquipmentSlot slot) {
        if (s.isEmpty()) return false;
        Item it = s.getItem();

        if (it instanceof ItemArmor) {
            return ((ItemArmor) it).armorType == slot;
        }
        // Forge-friendly fallback: many modded items implement this
        try {
            return it.isValidArmor(s, slot, this);
        } catch (Throwable t) {
            return false;
        }
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

    private boolean isGhostSword(ItemStack stack) {
        return !stack.isEmpty()
                && isSwordLike(stack)
                && stack.hasTagCompound()
                && stack.getTagCompound().getBoolean(GHOST_SWORD_TAG);
    }

    private ItemStack makeGhostCopy(ItemStack src) {
        ItemStack copy = src.copy();
        copy.setCount(1);
        net.minecraft.nbt.NBTTagCompound tag = copy.getTagCompound();
        if (tag == null) tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setBoolean(GHOST_SWORD_TAG, true);
        copy.setTagCompound(tag);
        return copy;
    }

    private void ensureBestSwordVisualNow() {
        ItemStack best = findBestSwordInBackpack();
        if (!best.isEmpty()) setHeldItem(EnumHand.MAIN_HAND, makeGhostCopy(best));
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


    @SuppressWarnings("deprecation")
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

    private boolean isBowLike(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof net.minecraft.item.ItemBow;
    }


    private ItemStack findBowInBackpack() {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (isBowLike(s)) return s;
        }
        return ItemStack.EMPTY;
    }

    private void handleRangedVisibilityAndShooting() {
        if (world.isRemote) return;

        if (bowCooldown > 0) bowCooldown--;

        EntityLivingBase tgt = getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) {
            return;
        }

        // Distance & LOS
        double distSq = this.getDistanceSq(tgt);
        boolean farEnough = distSq > (RANGED_MIN_DIST * RANGED_MIN_DIST);
        boolean notTooFar = distSq <= (RANGED_MAX_DIST * RANGED_MAX_DIST);
        boolean canSee = this.getEntitySenses().canSee(tgt);

        ItemStack bow = findBowInBackpack();
        boolean haveBow = !bow.isEmpty();

        // Use bow only when we have one, target is far, within max range, and visible
        if (haveBow && farEnough && notTooFar && canSee) {
            // face the target and stop pathing a moment
            this.getNavigator().clearPath();
            this.getLookHelper().setLookPositionWithEntity(tgt, 30.0F, 30.0F);

            // shoot on cooldown
            if (bowCooldown == 0) {
                shootArrowAt(tgt, bow);
                bowCooldown = RANGED_COOLDOWN;
            }

            // make sure melee handler doesn't immediately swap in a sword this tick
            swordHideTicks = SWORD_HIDE_DELAY; // “I'm busy ranged-attacking”

        }
    }

    private void shootArrowAt(EntityLivingBase target, ItemStack bowStack) {
        if (world.isRemote) return;

        // 1) LOS checks (your originals)
        Vec3d eyes = new Vec3d(this.posX, this.posY + this.getEyeHeight(), this.posZ);
        Vec3d aim  = new Vec3d(target.posX, target.posY + target.getEyeHeight() * 0.6, target.posZ);
        RayTraceResult blockHit = world.rayTraceBlocks(eyes, aim, false, true, false);
        if (blockHit != null && blockHit.typeOfHit == RayTraceResult.Type.BLOCK) return;
        if (isFriendlyBlockingShot(eyes, aim, target)) return;

        // 2) Build the arrow exactly like before
        ItemStack pretendArrow = new ItemStack(Items.ARROW);
        net.minecraft.entity.projectile.EntityArrow arrow =
                ((net.minecraft.item.ItemArrow) pretendArrow.getItem()).createArrow(world, pretendArrow, this);

        // 3) Direction & gravity compensation
        // Horizontal vector components
        double dx = target.posX - this.posX;
        double dz = target.posZ - this.posZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        // Base “center-of-mass” aim height on target
        double baseY = target.posY + target.getEyeHeight() * 0.33F - (this.posY + this.getEyeHeight());

        /*
         * Arrow gravity in 1.12 is ~0.05 per tick and arrows experience drag,
         * but a simple over-aim works well. Scale extra lift with distance.
         * Short range: tiny lift; long range: more lift.
         */
        double extraLift = horiz * 0.10;     // try 0.08–0.14
        if (horiz > 24.0) extraLift += (horiz - 24.0) * 0.05;  // extra help beyond mid-range

        double dy = baseY + extraLift;

        // 4) Launch power & spread:
        // - Higher velocity sends arrows much farther
        // - Lower inaccuracy tightens long shots (but keep some NPC spread)
        float velocity = 2.6F;   // was 1.6F; try 2.3–3.0F
        float inaccuracy = 2.5F; // was 4.0F; lower = straighter

        // 5) Fire!
        arrow.shoot(dx, dy, dz, velocity, inaccuracy);

        // 6) Respect bow enchants (kept from your version)
        int power = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(net.minecraft.init.Enchantments.POWER, bowStack);
        int punch = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(net.minecraft.init.Enchantments.PUNCH, bowStack);
        int flame = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(net.minecraft.init.Enchantments.FLAME, bowStack);

        if (power > 0) arrow.setDamage(arrow.getDamage() + 0.5D * power + 0.5D);
        if (punch > 0) arrow.setKnockbackStrength(punch);
        if (flame > 0) arrow.setFire(100);

        arrow.shootingEntity = this;

        world.playSound(null, this.posX, this.posY, this.posZ,
                net.minecraft.init.SoundEvents.ENTITY_SKELETON_SHOOT,
                net.minecraft.util.SoundCategory.HOSTILE, 1.0F,
                1.0F / (this.getRNG().nextFloat() * 0.4F + 0.8F));

        world.spawnEntity(arrow);
    }



    private boolean isFriendlyBlockingShot(Vec3d start, Vec3d end, EntityLivingBase intendedTarget) {
        // Expand aabb covering the segment
        double dx = end.x - start.x, dy = end.y - start.y, dz = end.z - start.z;
        AxisAlignedBB segBB = new AxisAlignedBB(
                Math.min(start.x, end.x), Math.min(start.y, end.y), Math.min(start.z, end.z),
                Math.max(start.x, end.x), Math.max(start.y, end.y), Math.max(start.z, end.z)
        ).grow(1.0); // a little padding

        List<EntityTraveller> list = world.getEntitiesWithinAABB(EntityTraveller.class, segBB,
                e -> e != null && e.isEntityAlive() && e != this && e != intendedTarget);

        double closestSq = Double.POSITIVE_INFINITY;
        Entity blocking = null;

        for (EntityTraveller t : list) {
            AxisAlignedBB bb = t.getEntityBoundingBox().grow(0.2);
            RayTraceResult hit = bb.calculateIntercept(start, end);
            if (hit != null) {
                double distSq = start.squareDistanceTo(hit.hitVec);
                if (distSq < closestSq) {
                    closestSq = distSq;
                    blocking = t;
                }
            }
        }

        // If the closest intersecting entityAI on the path is a traveller (and not the target), don't shoot.
        return blocking != null;
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

    public boolean isAutoEating() {
        return eatTicks > 0 || this.isHandActive(); // covers our snack + any use animation
    }

    @Override
    protected PathNavigate createNavigator(World worldIn) {
        return new TravellerNavigateGround(this, worldIn);
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

    // rotate a vector around Y axis
    public static Vec3d rotateY(Vec3d v, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3d(
                v.x * cos - v.z * sin,
                v.y,
                v.x * sin + v.z * cos
        );
    }

    @Nullable
    public BlockPos getNearestVillage() {
        return this.nearestVillage; // may be null if none found yet
    }

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

    // Helper: clamp angular delta and step toward a target
    private static float rotlerp(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    /**
     * Align torso (renderYawOffset) to travel direction while pathing.
     */
    private void syncBodyYawWhileTravelling() {
        // Only when navigating somewhere
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
        float bodyTurn = 75.0F; // how fast body can rotate toward desired
        float headTurn = 90.0F; // how fast head catches up to body
        float headLimit = 75.0F; // max head-body divergence

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
    private boolean hasBowInBackpack() {
        return !findBowInBackpack().isEmpty();
    }
    public boolean isVillageIdling() {  // you already store this
        return this.villageIdling;
    }
    public boolean isFollowingSomeone() {
        return this.hasFollowLeader() && this.getFollowLeader() != null;
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
        java.util.List<BlockPos> all = VillageIndex.getVillages(this.world);
        if (all.isEmpty()) return null;

        long r2 = (long)maxRadius * (long)maxRadius;
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
        float longP  = 0.50f * (this.wanderlust) + 0.20f;        // 0.20–0.70
        float midP   = 1.0f - (shortP + longP);
        if (midP < 0.10f) midP = 0.10f; // keep some mids
        float rest = 1.0f - (shortP + longP + midP);
        if (rest != 0) { longP += rest * 0.5f; shortP += rest * 0.5f; } // normalize

        // Exploration override: sometimes pick across all uniformly
        if (this.rand.nextFloat() < this.exploreChance) {
            return candidates.get(this.rand.nextInt(n));
        }

        // Define bands by quantiles
        int nearEnd   = Math.max(1, n / 3);             // first ~third
        int midStart  = nearEnd;
        int midEnd    = Math.max(midStart + 1, (2 * n) / 3); // second ~third
        int farStart  = midEnd;

        // Decide which band to pick from
        float roll = this.rand.nextFloat();
        int from, to;
        if (roll < shortP) {
            from = 0; to = nearEnd;
        } else if (roll < shortP + midP) {
            from = midStart; to = midEnd;
        } else {
            from = farStart; to = n;
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
        java.util.List<BlockPos> all = VillageIndex.getVillages(this.world);
        java.util.List<BlockPos> mids = new java.util.ArrayList<>();
        if (all.isEmpty()) return mids;

        Vec3d A = new Vec3d(start.getX()+0.5, 0, start.getZ()+0.5);
        Vec3d B = new Vec3d(dest.getX()+0.5,  0, dest.getZ()+0.5);
        Vec3d AB = B.subtract(A);
        double ab2 = AB.lengthSquared();
        if (ab2 < 1e-6) return mids;

        final class Mid implements Comparable<Mid> {
            final double t; final BlockPos p;
            Mid(double t, BlockPos p) { this.t = t; this.p = p; }
            public int compareTo(Mid o){ return Double.compare(this.t, o.t); }
        }
        java.util.List<Mid> tmp = new java.util.ArrayList<>();

        for (BlockPos v : all) {
            if (v.equals(start) || v.equals(dest)) continue;
            Vec3d P = new Vec3d(v.getX()+0.5, 0, v.getZ()+0.5);
            double t = AB.dotProduct(P.subtract(A)) / ab2;     // along-track fraction
            if (t <= 0.02 || t >= 0.98) continue;              // strictly between A and C
            if (pointToSegment2D(P, A, B) <= corridorWidth) {  // laterally near the line
                tmp.add(new Mid(t, v));
            }
        }
        java.util.Collections.sort(tmp);
        for (Mid m : tmp) mids.add(m.p);
        return mids;
    }


    private static double pointToSegment2D(Vec3d P, Vec3d A, Vec3d B) {
        Vec3d AP = P.subtract(A);
        Vec3d AB = B.subtract(A);
        double ab2 = AB.lengthSquared();
        if (ab2 < 1e-9) return AP.length();
        double t = AP.dotProduct(AB) / ab2;
        if (t < 0) return AP.length();
        if (t > 1) return P.subtract(B).length();
        Vec3d H = A.add(AB.scale(t));
        return P.subtract(H).length();
    }
    // --- tiny holder instead of javafx.util.Pair ---
    private static final class Waypoint implements Comparable<Waypoint> {
        final double t;        // progress along A->B (0..1)
        final BlockPos pos;
        Waypoint(double t, BlockPos pos) { this.t = t; this.pos = pos; }
        @Override public int compareTo(Waypoint o) { return Double.compare(this.t, o.t); }
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
}







