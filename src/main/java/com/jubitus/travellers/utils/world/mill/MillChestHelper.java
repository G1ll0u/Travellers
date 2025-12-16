package com.jubitus.travellers.utils.world.mill;

import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;


public final class MillChestHelper {

    private MillChestHelper() {
    }

    @Nullable
    public static IInventory getBuildingChestInventory(World world, MillVillageIndex.BuildingInfo bi) {
        TileEntity te = getBuildingChestTE(world, bi);
        if (te instanceof IInventory) {
            return (IInventory) te;
        }
        return null;
    }

    @Nullable
    public static TileEntity getBuildingChestTE(World world, MillVillageIndex.BuildingInfo bi) {
        if (bi == null || bi.chestPos == null) return null;
        if (!world.isBlockLoaded(bi.chestPos)) return null;
        return world.getTileEntity(bi.chestPos);
    }

    @Nullable
    public static IItemHandler getBuildingChestItemHandler(World world, MillVillageIndex.BuildingInfo bi) {
        TileEntity te = getBuildingChestTE(world, bi);
        if (te == null) return null;
        return te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
    }
}
