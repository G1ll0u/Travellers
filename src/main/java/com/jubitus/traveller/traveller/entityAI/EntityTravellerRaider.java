package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.SpawnThrottles;
import com.jubitus.traveller.traveller.culture.CultureDef;
import com.jubitus.traveller.traveller.culture.CultureRegistry;
import com.jubitus.traveller.traveller.culture.VoiceSet;
import com.jubitus.traveller.traveller.utils.debug.TravellerFollowNet;
import com.jubitus.traveller.traveller.utils.sound.MsgStopTravellerVoice;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import javax.annotation.Nullable;

public class EntityTravellerRaider extends EntityTraveller {

    public EntityTravellerRaider(World worldIn) {
        super(worldIn);
        // Raiders shouldn't talk/sing or follow, but can reuse swimming/route logic etc.
        this.enablePersistence(); // optional: set false if you want normal hostile despawn behavior
        // Slightly higher base speed/HP if you want them scarier
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(
                Math.max(20.0, TravellersModConfig.maxHealth));
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(
                Math.max(0.28, TravellersModConfig.movementSpeed));
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_VINDICATION_ILLAGER_HURT;
    }    @Override
    protected boolean shouldAttackExtraEntity(EntityLivingBase other) {
        // Never include same-faction travellers (already handled by XOR rule elsewhere)
        if (other instanceof EntityTraveller) return false;
        return TravellersModConfig.RAIDER_ATTACK_LIST.isListed(other);
    }

    private boolean isValidMonsterLight() {
        // mimic EntityMob#getCanSpawnHere light gating
        BlockPos p = new BlockPos(this.posX, this.getEntityBoundingBox().minY, this.posZ);
        if (world.isDaytime()) {
            float f = world.getCelestialAngleRadians(1.0F);
            if ((MathHelper.cos(f) > 0.0F ? 1 : 0) == 0) {
                // (vanilla does sky light tweak during thunderstorms; optional to copy)
            }
        }
        int sky = world.getLightFromNeighbors(p);
        if (sky > rand.nextInt(32)) return false;
        int block = world.getLight(p);
        return block <= rand.nextInt(8);
    }    // No friendly chat

    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance diff, @Nullable IEntityLivingData data) {
        // Let base traveller handle culture selection & SpawnData propagation
        data = super.onInitialSpawn(diff, data);

        String cid = getCultureId();
        if (cid == null || cid.isEmpty()) {
            // Just in case, but normally super already set this via SpawnData
            cid = CultureRegistry.pickRandomId(this.rand);
            setCultureId(cid);
            data = new EntityTraveller.SpawnData(cid);
        }

        CultureDef def = CultureRegistry.get(cid);
        int rCount = (def != null ? def.getRaiderTextureCount() : 0);

        // Fallback to traveller count if no specific raider looks
        if (rCount <= 0 && def != null) {
            rCount = def.getTravellerTextureCount();
        }
        if (rCount <= 0) {
            rCount = 1; // steve fallback in CultureDef
        }

        this.setTextureIndex(this.rand.nextInt(Math.max(1, rCount)));

        return data;
    }

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        return false; // ignore right-clicks
    }

    @Override
    protected boolean isAggressiveToTravellers() {
        return true;
    }

    // Don’t mute combat for creative players; still avoid aggro if you prefer:
    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        // Peaceful: no combat at all
        if (world != null && world.getDifficulty() == EnumDifficulty.PEACEFUL) {
            super.setAttackTarget(null);
            setInCombat(false);
            return;
        }

        if (target instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) target;

            // Never target same-culture travellers/raiders
            if (!isDifferentCulture(other)) {
                super.setAttackTarget(null);
                return;
            }
        }

        // (Optionally you might still want to ignore some blacklisted entities)
        super.setAttackTarget(target);
        if (!world.isRemote && target != null) {
            setInCombat(true);
        }
    }

    @Override
    protected void initEntityAI() {
        // Reset AI
        this.tasks.taskEntries.clear();
        this.targetTasks.taskEntries.clear();

        // === TARGETING / COMBAT BEHAVIOR ======================================================

        // 1) Retaliate when hurt
        this.targetTasks.addTask(1, new EntityAIHurtByTarget(this, true));

        // 2) Defend allies / villagers if something is close enough
        this.targetTasks.addTask(
                2,
                new EntityAIDefendIfClose(this, TravellersModConfig.attackTriggerRange, true)
                        .setAssistRadius(TravellersModConfig.helpRange)
                        .setBowRangeBonus(0.0) // keep attackTriggerRange strict
        );

        // 3) Help allies that are hurt within helpRange
        this.targetTasks.addTask(3, new TravellerHurtByTarget(this, true, TravellersModConfig.helpRange));

        // 4) Attack anything in RAIDER_ATTACK_LIST (villagers, golems, modded guards, etc.)
        //    No IMob requirement here.
        this.targetTasks.addTask(
                4,
                new EntityAINearestAttackableTarget<>(
                        this,
                        EntityLivingBase.class,
                        10,    // tick rate
                        true,  // mustCheckSight
                        false, // nearbyOnly
                        e -> e != null
                                && e.isEntityAlive()
                                && !(e instanceof EntityTraveller) // travellers handled by the task below
                                && !TravellersModConfig.RAIDER_AGGRO_BLACKLIST.isBlacklisted(e)
                                && this.shouldAttackExtraEntity(e) // uses RAIDER_ATTACK_LIST internally
                )
        );

        // 5) Explicitly target players
        this.targetTasks.addTask(5, new EntityAINearestAttackableTarget<>(this, EntityPlayer.class, true));

        // 6) Target travellers/raiders of OTHER cultures
        this.targetTasks.addTask(
                6,
                new EntityAINearestAttackableTarget<>(
                        this,
                        EntityTraveller.class,
                        10,
                        true,
                        false,
                        t -> t != null && isDifferentCulture(t)
                )
        );

        // === MOVEMENT / COMBAT TASKS =========================================================

        // Swimming / basic survival
        this.tasks.addTask(0, new EntityAISwimming(this));

        // Ranged combat (bow)
        aiRanged = new EntityAIRangedBowTravellerSafe(
                this,
                TravellersModConfig.rangedMinDist,
                TravellersModConfig.rangedMaxDist,
                TravellersModConfig.rangedCooldown,
                Math.max(2.5D, TravellersModConfig.rangedMinDist + 1.0D),
                /* ghostEquipBow */ true
        );
        this.tasks.addTask(2, aiRanged);

        // Melee combat
        double baseWalk = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        double meleeSpeed = Math.min(TravellersModConfig.attackMovementSpeed, baseWalk * 0.95D); // <= 95% of walk speed
        aiMelee = new EntityAIAttackMeleeCustom(this, meleeSpeed, true, 0.65D);
        this.tasks.addTask(5, aiMelee);

        // === CAMP / GROUP BEHAVIOR ===========================================================

        if (TravellersModConfig.campsEnabled) {
            // 6: leader pauses at camp
            this.tasks.addTask(6, new EntityAICampPause(this));

            // 7: others join the campfire
            this.tasks.addTask(7, new EntityAICampJoinFire(this, 0.34D));
        }

        // === TRAVEL / ROAMING / FOLLOWING ====================================================

        // General travel route behavior
        this.tasks.addTask(12, new EntityAITravel(this, TravellersModConfig.movementSpeedWhileTravel, 48));

        // Roam inside villages
        this.tasks.addTask(
                11,
                new EntityAIRoamInsideVillage(
                        this,
                        0.34D,
                        3 * 20,
                        10 * 20,
                        TravellersModConfig.villageRoamMinDuration * 20,
                        TravellersModConfig.villageRoamMaxDuration * 20
                )
        ); // Optional: customize 2-minute duration or wordlist at runtime

        // Fish while travelling (adds flavor during travel phases)
        this.tasks.addTask(11, new EntityAIFishWhileTravel(this));

        // Follow leader / follow behavior for raiders
        aiFollow = new EntityAIFollowTraveller(
                this,
                0.819D,
                TravellersModConfig.movementSpeedWhileFollowing
        );
        this.tasks.addTask(15, aiFollow);

        // === UTILITY / IDLE BEHAVIOR =========================================================

        // Open gates and doors
        this.tasks.addTask(9, new EntityAIOpenFenceGate(this));
        this.tasks.addTask(10, new EntityAIOpenDoor(this, true));

        // Look at nearby players and idle looking around
        this.tasks.addTask(13, new EntityAIWatchClosest(this, EntityPlayer.class, 12.0F));
        this.tasks.addTask(14, new EntityAILookIdle(this));

        // === OPTIONAL / DISABLED FEATURES ====================================================

        // Raiders don't auto-eat by default; add this back if you want survival-focused foes.
        aiTravel = null;
        autoEatTask = null;
    }

//    @Override
//    public IEntityLivingData onInitialSpawn(DifficultyInstance diff, @Nullable IEntityLivingData data) {
//        // Let base traveller handle spawn data & random culture if needed
//        data = super.onInitialSpawn(diff, data);
//
//        String cid = getCultureId();
//        if (cid == null || cid.isEmpty()) {
//            // Just in case super didn’t set it for some reason
//            cid = CultureRegistry.pickRandomId(this.rand);
//            setCultureId(cid);
//            data = new EntityTraveller.SpawnData(cid);
//        }
//
//        CultureDef def = CultureRegistry.get(cid);
//        int rCount = (def != null ? def.getRaiderTextureCount() : 0);
//
//        // Fallback to traveller count if no specific raider looks
//        if (rCount <= 0 && def != null) {
//            rCount = def.getTravellerTextureCount();
//        }
//        if (rCount <= 0) {
//            rCount = 1; // last-chance safety, steve fallback in CultureDef
//        }
//
//        this.setTextureIndex(this.rand.nextInt(Math.max(1, rCount)));
//
//        return data;
//    }


    // Disable friendly voice/ambient entirely
    @Override
    public void scheduleNextTravelAmbient() { /* no-op */ }

    @Override
    public void maybePlayTravelAmbient() { /* no-op */ }

    @Override
    @Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        VoiceSet v = CultureRegistry.RAIDER_VOICES.get(getCultureId());
        return (v != null && v.hurt != null) ? v.hurt : SoundEvents.ENTITY_VINDICATION_ILLAGER_HURT;
    }


    @Override
    public SoundCategory getSoundCategory() {
        return SoundCategory.HOSTILE;
    }

    // Hostile spawn rules (if keeping EntityCreature). If you extend EntityMob you get these for free.
    @Override
    public boolean getCanSpawnHere() {
        if (world.isRemote) return false;
        if (world.getDifficulty() == EnumDifficulty.PEACEFUL) return false;

        // HARD cap for loaded raiders
        int cap = TravellersModConfig.loadedRaiderCap;
        int loaded = this.world.getEntities(EntityTravellerRaider.class, Entity::isEntityAlive).size();
        if (loaded >= cap) return false;

        BlockPos pos = new BlockPos(this.posX, this.posY, this.posZ);

        // Must be near a Millénaire village
        if (!isNearAnyVillage(this.world, pos, TravellersModConfig.villageSpawnRadius)) return false;

        // Local density
        if (!SpawnThrottles.underLocalCap(this.world, pos, EntityTravellerRaider.class,
                TravellersModConfig.raiderLocalCap, TravellersModConfig.raiderLocalCapRadius)) return false;

        // Monster-style darkness: use BLOCK light, not combined
        int blockLight = world.getLightFor(net.minecraft.world.EnumSkyBlock.BLOCK, pos);
        if (blockLight > TravellersModConfig.raiderMaxBlockLight) return false;

        // Optional: disallow daytime sky-exposed locations
        // int skyLight = world.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, pos);
        // if (world.isDaytime() && skyLight > 0) return false;

        // Ground sanity similar to vanilla
        if (!world.getBlockState(pos.down()).isOpaqueCube()) return false;

        return super.getCanSpawnHere();
    }


    // Don’t run friendly ignore-logic when hurt
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        boolean tookDamage = super.attackEntityFrom(source, amount);
        if (!tookDamage || world.isRemote) return tookDamage;

        TravellerFollowNet.CHANNEL.sendToAllAround(
                new MsgStopTravellerVoice(this),
                new NetworkRegistry.TargetPoint(this.dimension, this.posX, this.posY, this.posZ, 64.0));
        beginVoiceLock(20, VoiceKind.HURT);

        Entity src = source.getTrueSource();

        // Allow creative hits: take damage but don't aggro
        if (src instanceof EntityPlayer && ((EntityPlayer) src).isCreative()) {
            setInCombat(false);
            return true;
        }

        // --- key change: travellers as attackers ---
        if (src instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) src;

            // Same culture: never aggro (could be "friendly fire" or civil squabble)
            if (!isDifferentCulture(t)) {
                this.setRevengeTarget(null);
                return true;
            }
            // Different culture: treat as an enemy below
        }


        if (world.getDifficulty() != EnumDifficulty.PEACEFUL) {
            if (autoEatTask != null) autoEatTask.cancel();
            setInCombat(true);
            if (src instanceof EntityLivingBase) {
                rememberThreat((EntityLivingBase) src, THREAT_FOR_HIT);
                this.setAttackTarget((EntityLivingBase) src);
            }
        }
        if (src instanceof EntityLivingBase) {
            EntityLivingBase livingSrc = (EntityLivingBase) src;

            // If not a traveller-vs-traveller case, allow raiders to retaliate
            // to any listed extra-hostile entity too:
            if (this.shouldAttackExtraEntity(livingSrc) || !(src instanceof EntityTraveller)) {
                rememberThreat(livingSrc, THREAT_FOR_HIT);
                this.setAttackTarget(livingSrc);
                setInCombat(true);
            }
        }
        return true;
    }
    public boolean isDifferentCulture(EntityTraveller other) {
        if (other == null) return false;
        String my = this.getCultureId();
        String theirs = other.getCultureId();
        if (my == null) my = "";
        if (theirs == null) theirs = "";
        return !my.equalsIgnoreCase(theirs);
    }

}

