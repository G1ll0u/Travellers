package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.unit.culture.TravellerAppearance;
import com.jubitus.travellers.unit.culture.VillagerFilterRules;
import com.jubitus.travellers.unit.culture.VoiceSet;
import com.jubitus.travellers.ambient.EntityAIAutoEat;
import com.jubitus.travellers.ambient.EntityAIFishWhileTravel;
import com.jubitus.travellers.village.visit.EntityAIVillageVisit;
import com.jubitus.travellers.combat.ai.EntityAIAttackMeleeCustom;
import com.jubitus.travellers.combat.TravellerCombatLogic;
import com.jubitus.travellers.combat.ai.EntityAIDefendIfClose;
import com.jubitus.travellers.combat.ai.EntityAIRangedBowTravellerSafe;
import com.jubitus.travellers.travel.EntityAITravel;
import com.jubitus.travellers.combat.ai.TravellerHurtByTarget;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.sound.net.MsgStopTravellerVoice;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import javax.annotation.Nullable;

public class EntityTravellerRaider extends EntityTraveller {

    public EntityTravellerRaider(World worldIn) {
        super(worldIn);
        // Raiders shouldn't talk/sing, but can reuse swimming/route/group-follow logic etc.
        this.enablePersistence(); // optional: set false if you want normal hostile despawn behavior
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

    // getCanSpawnHere() override removed — raiders no longer register a vanilla biome spawn entry,
    // they spawn exclusively through the virtual population controller (TravellerSoftSpawner), which
    // never calls this. See CLAUDE.md / Travellers.java preInit for the removal.

    // INVARIANT: no super call - every AI task added to EntityTraveller must be mirrored here by hand.
    @Override
    protected void initEntityAI() {
        // Reset AI
        this.tasks.taskEntries.clear();
        this.targetTasks.taskEntries.clear();

        // === TARGETING / COMBAT BEHAVIOR ======================================================

        // 1) Retaliate when hurt
// 1) Retaliate when hurt (safe custom version)
        this.targetTasks.addTask(1, new TravellerHurtByTarget(this, TravellersConfig.COMBAT.callForHelp, TravellersConfig.COMBAT.helpRange));

        // 2) Defend allies / villagers if something is close enough
        this.targetTasks.addTask(
                2,
                new EntityAIDefendIfClose(this, TravellersConfig.COMBAT.attackTriggerRange, true)
                        .setAssistRadius(TravellersConfig.COMBAT.helpRange)
        );

        // 4) Attack nearest enemy — player, friendly traveller, or attack-list entity.
        //    Single task so EntityAINearestAttackableTarget picks the closest one naturally,
        //    with no priority bias between target types.
        this.targetTasks.addTask(
                4,
                new com.jubitus.travellers.combat.ai.RaiderAttackTargetAI<>(
                        this,
                        EntityLivingBase.class,
                        10,
                        true,
                        false,
                        e -> e != null
                                && e.isEntityAlive()
                                && !e.isInvisible()
                                && !this.isIgnoring(e)
                                && com.jubitus.travellers.combat.SharedThreatScan.heightAllows(this, e)
                                && (
                                        (e instanceof EntityPlayer
                                                && !((EntityPlayer) e).isCreative()
                                                && !((EntityPlayer) e).isSpectator())
                                        || (e instanceof EntityTraveller
                                                && e != this
                                                && com.jubitus.travellers.combat.Hostility.isCrossFaction(this, (EntityTraveller) e))
                                        || (!(e instanceof EntityTraveller)
                                                && !this.getAggroBlacklist().matches(e)
                                                && this.shouldAttackExtraEntity(e))
                                )
                )
        );

        // === MOVEMENT / COMBAT TASKS =========================================================

        // Swimming / basic survival
        this.tasks.addTask(0, new EntityAISwimming(this));

        // Ranged combat (bow)
        aiRanged = new EntityAIRangedBowTravellerSafe(
                this,
                TravellersConfig.COMBAT.rangedMinDist,
                TravellersConfig.COMBAT.rangedMaxDist,
                TravellersConfig.COMBAT.rangedCooldown,
                Math.max(2.5D, TravellersConfig.COMBAT.rangedMinDist - 1.0D),
                /* ghostEquipBow */ true
        );
        this.tasks.addTask(2, aiRanged);

        // Melee combat
        aiMelee = new EntityAIAttackMeleeCustom(this, true, TravellersConfig.COMBAT.raiderExtraMeleeReach);
        this.tasks.addTask(5, aiMelee);

        // Follow a group leader (raider groups, same mechanism as travellers - see EntityTraveller).
        // Must run before EntityAICampStay below so its "yield while leader camps" check actually
        // takes effect (see EntityAIFollowLeader's own shouldExecute comment).
        aiFollowGroupLeader = new com.jubitus.travellers.follow.EntityAIFollowLeader(
                this, 1.0F, 1.0F);
        this.tasks.addTask(4, aiFollowGroupLeader);

        // === CAMP / GROUP BEHAVIOR ===========================================================

        if (com.jubitus.travellers.config.TravellersConfig.CAMP_EVENTS.enabled) {
            // New camp event system: same task as travellers (faction separation is enforced by
            // CampsiteRecord.canClaim, not by the AI).
            this.tasks.addTask(6, new com.jubitus.travellers.camp.EntityAICampStay(this));
        }

        // === TRAVEL / ROAMING / FOLLOWING ====================================================

        // General travel route behavior. Keep the reference: getTravelAI()/offscreenHopTowardPos
        // (stuck-rescue step-teleport) are null-checked against it and were silently disabled for
        // raiders while it wasn't stored.
        aiTravel = new EntityAITravel(this, TravellersConfig.ATTRIBUTES.stepBlocks);
        this.tasks.addTask(12, aiTravel);

        // Village visit (streets only for raiders, see EntityAIVillageVisit)
        EntityAIVillageVisit visit = new EntityAIVillageVisit(this);
        setVillageVisitAI(visit);
        this.tasks.addTask(11, visit);

        // Fish while travelling (adds flavor during travel phases)
        if (TravellersConfig.TRAVEL.fishWhileTravelEnabled) {
            this.tasks.addTask(11, new EntityAIFishWhileTravel(this));
        }

        // === UTILITY / IDLE BEHAVIOR =========================================================

        // Open gates and doors, close them behind (shared with EntityTraveller - mirrored here
        // because this initEntityAI() fully replaces the base task list with no super call).
        this.tasks.addTask(9, new com.jubitus.travellers.travel.EntityAIPassDoor(this));
        // Looking around is the gaze's job (TravellerGaze), not an AI task.

        autoEatTask = new EntityAIAutoEat(this, TravellersConfig.COMBAT.eatHealthThreshold);
        this.tasks.addTask(8, autoEatTask);
    }

    @Override
    public void setAttackTarget(@Nullable EntityLivingBase target) {
        if (world != null && TravellersDerivedConfig.isPeacefulBlocked(world)) {
            super.setAttackTarget(null);
            setInCombat(false);
            return;
        }

        if (target instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) target;
            if (p.isCreative() || p.isSpectator()) {
                if (combat.refusalKeepsCurrent(target)) return;
                combat.noteDrop("refused: creative player");
                super.setAttackTarget(null);
                setInCombat(false);
                return;
            }
        }

        super.setAttackTarget(target);
        if (!world.isRemote && target != null) {
            setInCombat(true);
        }
    }

    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        this.spawnAmbientCooldownTicks = TravellersConfig.SOUND.spawnAmbientDelayTicks + this.rand.nextInt(5 * 20);

        livingdata = super.onInitialSpawn(difficulty, livingdata);

        // --- culture pick/propagate ---

        String cid = null;

        boolean whitelistActive = VillagerFilterRules.RAIDER.hasIncludeRules();

        if (whitelistActive) {
            String forced = CultureRegistry.pickRandomCultureIdWithRaiders(this.rand); // you implement
            if (forced != null && !forced.isEmpty()) {
                cid = forced;
                livingdata = new SpawnData(cid);
            }
        }

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

        CultureDef c = CultureRegistry.get(cid);

// pick from RAIDER pool first
        int rCount = (c != null) ? c.getRaiderTextureCount() : 0;
        if (rCount <= 0) rCount = 1;

        setTextureIndex(this.rand.nextInt(rCount));
        rollRenderScale(getActiveAppearance());


        // Reset move attr base and make sure no swim boost lingers
        IAttributeInstance attr = this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.setBaseValue(UnitSpeed.ATTRIBUTE);
            AttributeModifier m = attr.getModifier(SWIM_BOOST_UUID);
            if (m != null) attr.removeModifier(m);
        }

        if (!world.isRemote) {
            equipment.initSpawnEquipment(c, this.rand);

            // 3) Seed ambient window if needed
            if (nextTravelAmbientTick <= 0 || nextTravelAmbientTick == Integer.MAX_VALUE) {
                scheduleNextTravelAmbient();
            }
            assignCultureName();
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
        return false; // ignore right-clicks
    }


//    @Override
//    public IEntityLivingData onInitialSpawn(DifficultyInstance diff, @Nullable IEntityLivingData data) {
//        // Let base travellers handle spawn data & random culture if needed
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
//        // Fallback to travellers count if no specific raiders looks
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
    protected boolean rerollsAppearanceAtSpawn() {
        return true;
    }

    @Override
    protected TravellerAppearance pickAppearance(CultureDef c, int textureIndex) {
        return c.getRaiderAppearance(textureIndex);
    }

    // Don’t run friendly ignore-logic when hurt
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        boolean tookDamage = super.attackEntityFrom(source, amount);
        if (!tookDamage || world.isRemote) return tookDamage;

        // No explicit voice-stop needed here: playHurtSound() (called by super.attackEntityFrom
        // above) already crossfades out whatever voice line was playing when it plays the hurt bark.

        Entity src = source.getTrueSource();

        // Must stay: unlike EntityTraveller.attackEntityFrom, the retaliation branch below
        // unconditionally calls setInCombat(true) right after setAttackTarget(), which would
        // re-flip setInCombat back to true even though setAttackTarget() itself rejects a
        // creative/spectator target - this early return is what actually prevents that.
        if (src instanceof EntityPlayer && ((EntityPlayer) src).isCreative()) {
            setInCombat(false);
            setRevengeTarget(null);
            return true;
        }

        // --- key change: travellers as attackers ---
        if (src instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) src;

            // Only ignore same-faction (raiders-raiders, travellers-travellers)
            if (!com.jubitus.travellers.combat.Hostility.isCrossFaction(this, t)) {
                this.setRevengeTarget(null);
                return true;
            }
            // cross-faction -> allowed to retaliate
        }

        // Peaceful gating for this retaliation happens inside setAttackTarget() itself.
        if (src instanceof EntityLivingBase) {
            EntityLivingBase livingSrc = (EntityLivingBase) src;

            // Travellers-vs-travellers retaliation is handled by the faction rule above.
            if (!(src instanceof EntityTraveller)) {
                combat.rememberThreat(livingSrc, TravellerCombatLogic.THREAT_FOR_HIT);
                this.setAttackTarget(livingSrc);
                setInCombat(true);
            }
        }
        return true;
    }

    @Override
    @Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        VoiceSet v = CultureRegistry.resolveRaiderVoiceSet(getCultureId(), voice.getPersonalityId());
        return (v != null)
                ? v.pickHurt(this.rand, SoundEvents.ENTITY_VINDICATION_ILLAGER_HURT)
                : SoundEvents.ENTITY_VINDICATION_ILLAGER_HURT;
    }

    @Override
    protected String pickVoicePersonalityId(String cultureId) {
        return CultureRegistry.pickRandomRaiderPersonalityId(cultureId, this.rand);
    }

    @Override
    protected com.jubitus.travellers.unit.culture.VoiceSet resolveVoiceSetForAttack() {
        return CultureRegistry.resolveRaiderVoiceSet(getCultureId(), voice.getPersonalityId());
    }

    @Override
    protected com.jubitus.travellers.unit.culture.VoiceSet resolveVoiceSetForKillDeath() {
        return CultureRegistry.resolveRaiderVoiceSet(getCultureId(), voice.getPersonalityId());
    }

    @Override
    public boolean isAggressiveToTravellers() {
        return true;
    }

    @Override
    public boolean usesVillageVisit() {
        return TravellersConfig.VILLAGE_VISIT.enabledRaiders
                && !com.jubitus.travellers.raid.RaidWorldData.isRaider(world, getUniqueID());
    }

    @Override
    public SoundCategory getSoundCategory() {
        return SoundCategory.HOSTILE;
    }

    @Override
    public void maybePlayTravelAmbient() { /* no-op */ }

    public boolean isDifferentCulture(EntityTraveller other) {
        if (other == null) return false;
        String my = this.getCultureId();
        String theirs = other.getCultureId();
        if (my == null) my = "";
        if (theirs == null) theirs = "";
        return !my.equalsIgnoreCase(theirs);
    }
    @Override
    protected double configuredMaxHealth() {
        return TravellersConfig.ATTRIBUTES.raiderMaxHealth;
    }

}

