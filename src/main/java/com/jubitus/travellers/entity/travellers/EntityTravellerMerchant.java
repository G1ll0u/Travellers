package com.jubitus.travellers.entity.travellers;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.entityAI.ambient.EntityAIRoamInsideVillage;
import com.jubitus.travellers.entity.entityAI.travel.EntityAIMerchantDeliverGoods;
import com.jubitus.travellers.entity.entityAI.travel.EntityAITravel;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager;
import com.jubitus.travellers.utils.world.spawn.TravellerVillageRegistry;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.utilities.MillLog;
import org.millenaire.common.village.Building;

import javax.annotation.Nullable;

public class EntityTravellerMerchant extends EntityTraveller {

    private final boolean hasDepositedHere = false;
    private final int thRepathCooldown = 0;
    private double baseFollowRange;
    private double boostedFollowRange;
    private boolean usingBoostedNav = false;
    private int stuckAtVillageTicks = 0;
    @Nullable
    private MillVillageIndex.BuildingInfo currentTH;
    @Nullable
    private BlockPos currentTHVillageCenter;
    @Nullable
    private BlockPos homeVillageCenter;

    public EntityTravellerMerchant(World worldIn) {
        super(worldIn);
        this.enablePersistence(); // vanilla: mark as persistent so it never despawns
        removeTaskOfType(EntityAIRoamInsideVillage.class);
        this.tasks.addTask(9, new EntityAIMerchantDeliverGoods(this, com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileTravel));
    }
    // --- home village API ---

    @Nullable
    public BlockPos getHomeVillage() {
        return this.homeVillageCenter;
    }

    public void setHomeVillage(@Nullable BlockPos pos) {
        this.homeVillageCenter = pos;
    }

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

    @Override
    protected void initEntityAI() {
        super.initEntityAI();

        // Remove the default travellers AITravel (stepBlocks)
        removeTaskOfType(EntityAITravel.class);

        // Add our merchant version using merchantStepBlocks
        this.tasks.addTask(16,
                new EntityAITravel(
                        this,
                        com.jubitus.travellers.config.TravellersConfig.SPEED.movementSpeedWhileTravel,
                        TravellersModConfig.merchantStepBlocks
                )
        );
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();

        baseFollowRange = TravellersModConfig.merchantFollowRange;
        boostedFollowRange = baseFollowRange * 4.0D;

        // Override with merchant-specific stats
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH)
                .setBaseValue(TravellersModConfig.merchantMaxHealth);

        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE)
                .setBaseValue(TravellersModConfig.merchantFollowRange);

        // Clamp current health to new max
        if (this.getHealth() > this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (!this.world.isRemote) {
            TravellerChunkManager.INSTANCE.onChunkloaderTick(
                    this,
                    TravellersModConfig.merchantChunkRadius
            );
        }
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);

        if (homeVillageCenter != null) {
            nbt.setInteger("HomeVillageX", homeVillageCenter.getX());
            nbt.setInteger("HomeVillageY", homeVillageCenter.getY());
            nbt.setInteger("HomeVillageZ", homeVillageCenter.getZ());
        }
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);

        if (nbt.hasKey("HomeVillageX")) {
            int x = nbt.getInteger("HomeVillageX");
            int y = nbt.getInteger("HomeVillageY");
            int z = nbt.getInteger("HomeVillageZ");
            this.homeVillageCenter = new BlockPos(x, y, z);
        } else {
            this.homeVillageCenter = null;
        }
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if (!world.isRemote) {
            updateDynamicNavigation();
            clearAggroIfOffscreen();
        }
        TravelCommon tc = this.getTravelCommon();

        if (stuckAtVillageTicks > 20 * 120) { // 2 minutes, tweak as you like
            BlockPos v = tc.getTargetVillage();
            MillLog.major(this, "[TravellersWorker] Watchdog: stuck at village "
                    + v + " – forcing new route.");

            // Let travel AI move on
            tc.setReadyToDepart(true);
            tc.setDepartCooldownTicks(0);
            tc.routeIndex = -1;
            tc.setTargetVillage(null);
            this.getNavigator().clearPath();

            stuckAtVillageTicks = 0;

            // Optionally mark village bad for this worker:
            tc.markVillageBad(v);
        }
    }

    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        livingdata = super.onInitialSpawn(difficulty, livingdata);

        String cid = this.getCultureId();
        CultureDef c = CultureRegistry.get(cid);
        if (c != null) {
            int count = c.getMerchantTextureCount();
            int idx = this.rand.nextInt(Math.max(1, count <= 0 ? 1 : count));
            this.setTextureIndex(idx);
        }

        return livingdata;
    }
    // --- NBT ---

    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            TravellerChunkManager.INSTANCE.untrackChunkloader(this);

            if (homeVillageCenter != null && world instanceof WorldServer) {
                TravellerVillageRegistry reg = TravellerVillageRegistry.get((WorldServer) world);
                reg.removeMerchantFor(homeVillageCenter, this.getUniqueID());
            }
        }

        super.onDeath(cause);
    }

    @Override
    public void setDead() {
        // Don't untrack here – this is called during world unload too.
        super.setDead();
    }

    private void updateDynamicNavigation() {
        if (world.isRemote) return;

        boolean shouldBoost = isInPlayerViewDistance();
        if (shouldBoost == usingBoostedNav) return; // no change

        usingBoostedNav = shouldBoost;

        // FOLLOW_RANGE
        IAttributeInstance followAttr =
                this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE);
        if (followAttr != null) {
            followAttr.setBaseValue(shouldBoost ? boostedFollowRange : baseFollowRange);
        }

        // stepBlocks (see next section: needs setter on EntityAITravel)
//        if (aiTravel != null) {
//            int baseStep = TravellersModConfig.workerStepBlocks;
//            int boostedStep = baseStep * 4;
//            // I *recommend* instead:
//            //   int boostedStep = baseStep / 2;
//            // if you want more precise pathing near the player.
//            aiTravel.setStepBlocks(shouldBoost ? boostedStep : baseStep);
//        }
    }

    @Nullable
    private Building getTownHallBuildingFromInfo(MillVillageIndex.BuildingInfo bi) {
        if (bi == null || bi.location == null) return null;
        return bi.location.getBuilding(this.world);
    }

}
