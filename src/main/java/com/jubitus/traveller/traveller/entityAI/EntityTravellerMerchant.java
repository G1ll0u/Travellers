package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.Travellers;
import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.pathing.TravellerChunkManager;
import com.jubitus.traveller.traveller.utils.villages.MillChestHelper;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
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
    private BlockPos currentTHChestPos;
    @Nullable
    private BlockPos currentTHVillageCenter;
    private boolean hasDepositedHere = false;
    private int thRepathCooldown = 0;


    public EntityTravellerMerchant(World worldIn) {
        super(worldIn);
        this.enablePersistence(); // vanilla: mark as persistent so it never despawns
        removeTaskOfType(EntityAIRoamInsideVillage.class);
        this.tasks.addTask(9, new EntityAIMerchantDeliverGoods(this, TravellersModConfig.movementSpeedWhileTravel));
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
    public void setDead() {
        // Don't untrack here – this is called during world unload too.
        super.setDead();
    }
    @Override
    public void onDeath(DamageSource cause) {
        if (!world.isRemote) {
            TravellerChunkManager.INSTANCE.untrackMerchant(this);
        }
        super.onDeath(cause);
    }
    private void tickMerchantTownHallTrade() {
        if (world.isRemote) return;
        if (this.isInCombat() || this.isAutoEating() || this.isHandActive()) return;

        // We only care when the merchant is "at" his current stop village
        BlockPos tv = this.getTargetVillage();
        if (tv == null) return;

        // Use your existing arrival radius logic
        if (!this.isAtTargetStopHys(false)) {
            // moving between villages -> reset per-village trade state
            currentTH = null;
            currentTHChestPos = null;
            currentTHVillageCenter = null;
            hasDepositedHere = false;
            thRepathCooldown = 0;
            return;
        }

        // We are inside the arrival radius of tv
        if (currentTHVillageCenter == null || !currentTHVillageCenter.equals(tv)) {
            // New village: reset and look up town hall
            currentTHVillageCenter = tv;
            currentTH = findTownHallFor(tv);
            currentTHChestPos = (currentTH != null) ? currentTH.chestPos : null;
            hasDepositedHere = false;
            thRepathCooldown = 0;
        }

        if (hasDepositedHere) return;           // already did our test trade in this village
        if (currentTH == null || currentTHChestPos == null) return; // no TH found

        double d2 = this.getDistanceSqToCenter(currentTHChestPos);

        // Not yet at the chest: walk there
        if (d2 > 4.0D) { // > 2 blocks away
            if (thRepathCooldown > 0) {
                thRepathCooldown--;
                return;
            }

            this.getNavigator().tryMoveToXYZ(
                    currentTHChestPos.getX() + 0.5,
                    currentTHChestPos.getY(),
                    currentTHChestPos.getZ() + 0.5,
                    TravellersModConfig.movementSpeedWhileTravel // or a custom merchant speed
            );
            thRepathCooldown = 20; // 1s before we reissue path
            return;
        }

        // Close enough: attempt to deposit one red wool
        IItemHandler handler = MillChestHelper.getBuildingChestItemHandler(world, currentTH);
        if (handler == null) {
            hasDepositedHere = true; // avoid retry spam
            return;
        }

        ItemStack wool = new ItemStack(Blocks.WOOL, 1, EnumDyeColor.RED.getMetadata());
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(handler, wool, false);

        if (remainder.isEmpty()) {
            hasDepositedHere = true;

            // Optional: feedback for testing
            this.world.getMinecraftServer().getPlayerList().sendMessage(
                    new TextComponentString(
                            "[TravellerMerchant] Delivered 1 red wool to TH at " +
                                    currentTHVillageCenter.getX() + "/" +
                                    currentTHVillageCenter.getY() + "/" +
                                    currentTHVillageCenter.getZ()
                    )
            );
        } else {
            // Chest full or can't accept – mark as done so we don't spam
            hasDepositedHere = true;
        }
    }
    @Nullable
    private MillVillageIndex.BuildingInfo findTownHallFor(BlockPos villageCenter) {
        int radiusXZ = 48;
        int radiusY = 16;

        java.util.List<MillVillageIndex.BuildingInfo> infos =
                MillVillageIndex.listBuildingsAroundVillage(world, villageCenter, radiusXZ, radiusY);

        for (MillVillageIndex.BuildingInfo bi : infos) {
            if (bi != null && bi.isTownhall) {
                return bi;
            }
        }
        return null;
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
