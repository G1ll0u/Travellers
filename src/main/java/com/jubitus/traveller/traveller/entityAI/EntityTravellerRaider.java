package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.SpawnThrottles;
import com.jubitus.traveller.traveller.culture.CultureRegistry;
import com.jubitus.traveller.traveller.culture.VoiceSet;
import com.jubitus.traveller.traveller.utils.debug.TravellerFollowNet;
import com.jubitus.traveller.traveller.utils.sound.ModSounds;
import com.jubitus.traveller.traveller.utils.sound.MsgStopTravellerVoice;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.monster.IMob;
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

    @Override
    protected boolean shouldAttackExtraEntity(EntityLivingBase other) {
        // Never include same-faction travellers (already handled by XOR rule elsewhere)
        if (other instanceof EntityTraveller) return false;
        return TravellersModConfig.RAIDER_ATTACK_LIST.isListed(other);
    }
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
    public IEntityLivingData onInitialSpawn(DifficultyInstance diff, @Nullable IEntityLivingData data) {
        data = super.onInitialSpawn(diff, data);

        String cid;
        if (data instanceof EntityTraveller.SpawnData) {
            cid = ((EntityTraveller.SpawnData) data).cultureId;
        } else {
            cid = CultureRegistry.pickRandomId(this.rand);
            data = new EntityTraveller.SpawnData(cid);
        }
        setCultureId(cid);

        int rCount = CultureRegistry.get(cid).raiderTextureCount;
        this.setTextureIndex(this.rand.nextInt(Math.max(1, rCount)));

        return data;
    }

    // No friendly chat
    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        return false; // ignore right-clicks
    }
    @Override
    protected boolean isAggressiveToTravellers() { return true; }
    // Don’t mute combat for creative players; still avoid aggro if you prefer:
    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        // Peaceful: no combat
        if (target instanceof EntityTravellerRaider) {
            super.setAttackTarget(null);
            return;
        }
        if (world != null && world.getDifficulty() == EnumDifficulty.PEACEFUL) {
            super.setAttackTarget(null);
            setInCombat(false);
            return;
        }

        // Travellers: allow only if EITHER side is aggressive-to-travellers (i.e., it's a raider fight)
        if (target instanceof EntityTraveller) {
            EntityTraveller other = (EntityTraveller) target;
            boolean thisAggro = this.isAggressiveToTravellers();
            boolean otherAggro = other.isAggressiveToTravellers();
            if (!thisAggro && !otherAggro) {
                super.setAttackTarget(null); // friendly vs friendly: never
                return;
            }
        }

        super.setAttackTarget(target);
        if (!world.isRemote && target != null) setInCombat(true);
    }

    @Override
    protected void initEntityAI() {
        // Keep swim + movement helpers
        this.tasks.taskEntries.clear();
        this.targetTasks.taskEntries.clear();
        this.targetTasks.addTask(3, new TravellerHurtByTarget(this, true, TravellersModConfig.helpRange));
        autoEatTask = new EntityAIAutoEat(this, 3);

        // Movement / survival
        this.tasks.addTask(0, new EntityAISwimming(this));

        // Use your existing ranged + melee (they already work well)
        aiRanged = new EntityAIRangedBowTravellerSafe(
                this,
                TravellersModConfig.rangedMinDist,
                TravellersModConfig.rangedMaxDist,
                TravellersModConfig.rangedCooldown,
                Math.max(2.5D, TravellersModConfig.rangedMinDist + 1.0D),
                /* ghostEquipBow */ true
        );
        this.targetTasks.addTask(
                2,
                new EntityAIDefendIfClose(this, TravellersModConfig.attackTriggerRange, true)
                        .setAssistRadius(TravellersModConfig.helpRange)
                        .setBowRangeBonus(0.0) // keep attackTriggerRange strict
        );

        this.tasks.addTask(2, aiRanged);
        this.targetTasks.addTask(1, new EntityAIHurtByTarget(this, true));
        double baseWalk = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        double meleeSpeed = Math.min(TravellersModConfig.attackMovementSpeed, baseWalk * 0.95); // <= 95% of walk
        aiMelee = new EntityAIAttackMeleeCustom(this, meleeSpeed, true, 0.65);        this.tasks.addTask(5, aiMelee);

        // Simple “hostile” look/idle & doors etc. (optional utility tasks)
        this.tasks.addTask(13, new EntityAIWatchClosest(this, EntityPlayer.class, 12.0F));
        this.tasks.addTask(14, new EntityAILookIdle(this));
        this.tasks.addTask(9, new EntityAIOpenFenceGate(this));
        this.tasks.addTask(10, new EntityAIOpenDoor(this, true));
        this.tasks.addTask(12, new EntityAITravel(this, TravellersModConfig.movementSpeedWhileTravel, 48));
        this.tasks.addTask(11, new EntityAIRoamInsideVillage(this, 0.34D, 3 * 20, 10 * 20, TravellersModConfig.villageRoamMinDuration * 20, TravellersModConfig.villageRoamMaxDuration * 20));// Optional: customize 2-minute duration or wordlist at runtime

// Attack anything explicitly listed in RAIDER_ATTACK_LIST (villagers, golems, modded guards, etc.)
// No IMob requirement here.
        this.targetTasks.addTask(
                4,
                new EntityAINearestAttackableTarget<>(
                        this,
                        EntityLivingBase.class,
                        10,   // tick rate
                        true, // mustCheckSight
                        false,// nearbyOnly
                        e -> e != null
                                && e.isEntityAlive()
                                && !(e instanceof EntityTraveller) // travellers handled by the other task below
                                && !TravellersModConfig.RAIDER_AGGRO_BLACKLIST.isBlacklisted(e)
                                && this.shouldAttackExtraEntity(e) // <- uses RAIDER_ATTACK_LIST internally
                )
        );
// 3 = target opposite-faction travellers ONLY
        this.targetTasks.addTask(
                6,
                new EntityAINearestAttackableTarget<>(
                        this,
                        EntityTraveller.class,
                        10, true, false,
                        t -> t != null && (this.isAggressiveToTravellers() ^ t.isAggressiveToTravellers())
                )
        );
        this.targetTasks.addTask(5, new EntityAINearestAttackableTarget<>(this, EntityPlayer.class, true));

        aiFollow = null;
        aiTravel = null;
        autoEatTask = null; // raiders don’t auto-eat by default; add back if you want survival-y foes
    }

    // Disable friendly voice/ambient entirely
    @Override public void scheduleNextTravelAmbient() { /* no-op */ }
    @Override public void maybePlayTravelAmbient() { /* no-op */ }
    @Override
    @Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        VoiceSet v = CultureRegistry.RAIDER_VOICES.get(getCultureId());
        return (v != null && v.hurt != null) ? v.hurt : SoundEvents.ENTITY_VINDICATION_ILLAGER_HURT;
    }

    @Override protected SoundEvent getDeathSound() { return SoundEvents.ENTITY_VINDICATION_ILLAGER_HURT; }
    @Override public SoundCategory getSoundCategory() { return SoundCategory.HOSTILE; }

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
            boolean attackerIsRaider = t.isAggressiveToTravellers();
            if (!attackerIsRaider) {
                // friendly traveller: ignore aggro
                this.setRevengeTarget(null);
                return true;
            }
            // attacker is a raider: DO retaliate
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
}

