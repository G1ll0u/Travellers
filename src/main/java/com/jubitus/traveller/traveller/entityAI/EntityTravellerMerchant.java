package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.Travellers;
import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.pathing.TravellerChunkManager;
import com.jubitus.traveller.traveller.utils.TravellerSoftSpawner;
import com.jubitus.traveller.traveller.utils.villages.MillChestHelper;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.millenaire.common.village.Building;

import javax.annotation.Nullable;

public class EntityTravellerMerchant extends EntityTraveller {
    @Nullable
    private MillVillageIndex.BuildingInfo currentTH;

    @Nullable
    private BlockPos currentTHVillageCenter;
    private boolean hasDepositedHere = false;
    private int thRepathCooldown = 0;
    @Nullable
    private BlockPos homeVillageCenter;

    public EntityTravellerMerchant(World worldIn) {
        super(worldIn);
        this.enablePersistence(); // vanilla: mark as persistent so it never despawns
        removeTaskOfType(EntityAIRoamInsideVillage.class);
        this.tasks.addTask(9, new EntityAIMerchantDeliverGoods(this, TravellersModConfig.movementSpeedWhileTravel));
    }
    // --- home village API ---

    public void setHomeVillage(@Nullable BlockPos pos) {
        this.homeVillageCenter = pos;
    }

    @Nullable
    public BlockPos getHomeVillage() {
        return this.homeVillageCenter;
    }
    @Override
    protected boolean canDespawn() {
        return false;
    }

    @Override
    public boolean isNoDespawnRequired() {
        return true;
    }

    @Override
    protected void despawnEntity() {
        // NO-OP: merchants are permanent
    }
//    @Override
//    public void onLivingUpdate() {
//        super.onLivingUpdate();
//    }
    @Override
    public void onUpdate() {
        super.onUpdate();

        if (!this.world.isRemote) {
            TravellerChunkManager.INSTANCE.onMerchantTick(this);
        }
    }
    @Override
    protected void initEntityAI() {
        super.initEntityAI();

        // Remove the default traveller AITravel (stepBlocks)
        removeTaskOfType(EntityAITravel.class);

        // Add our merchant version using merchantStepBlocks
        this.tasks.addTask(16,
                new EntityAITravel(
                        this,
                        TravellersModConfig.movementSpeedWhileTravel,
                        TravellersModConfig.merchantStepBlocks
                )
        );
    }

    @Override
    public void setDead() {
        // Don't untrack here – this is called during world unload too.
        super.setDead();
    }
    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            TravellerChunkManager.INSTANCE.untrackMerchant(this);
            if (this.homeVillageCenter != null && this.world instanceof WorldServer) {
                TravellerSoftSpawner.spawnMerchantAtVillage(
                        (WorldServer) this.world,
                        this.homeVillageCenter
                );
            }
        }

        super.onDeath(cause);
    }
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();

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
    // --- NBT ---

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



    @Nullable
    private Building getTownHallBuildingFromInfo(MillVillageIndex.BuildingInfo bi) {
        if (bi == null || bi.location == null) return null;
        return bi.location.getBuilding(this.world);
    }
    private void removeTaskOfType(Class<? extends EntityAIBase> clazz) {
        // Remove from normal tasks
        for (Object obj : this.tasks.taskEntries.toArray()) {
            if (obj instanceof EntityAITasks.EntityAITaskEntry) {
                EntityAITasks.EntityAITaskEntry entry = (EntityAITasks.EntityAITaskEntry) obj;
                if (clazz.isInstance(entry.action)) {
                    this.tasks.removeTask(entry.action);
                }
            }
        }

        // Remove from target tasks (not needed for roam, but good util)
        for (Object obj : this.targetTasks.taskEntries.toArray()) {
            if (obj instanceof EntityAITasks.EntityAITaskEntry) {
                EntityAITasks.EntityAITaskEntry entry = (EntityAITasks.EntityAITaskEntry) obj;
                if (clazz.isInstance(entry.action)) {
                    this.targetTasks.removeTask(entry.action);
                }
            }
        }
    }



}
