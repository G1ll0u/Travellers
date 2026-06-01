package com.jubitus.travellers.utils.world.mill;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.mixins.MixinBuildingResManagerAccessor;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualWorkerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualWorkerRecord;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.entity.TileEntityLockedChest;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.buildingmanagers.ResManager;
import org.millenaire.common.world.MillWorldData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WorkerEquipExchanger {

    private static final EntityEquipmentSlot[] ARMOR_SLOTS = {
        EntityEquipmentSlot.HEAD,
        EntityEquipmentSlot.CHEST,
        EntityEquipmentSlot.LEGS,
        EntityEquipmentSlot.FEET
    };

    private WorkerEquipExchanger() {}

    public static void exchange(EntityTravellerWorker worker, World world, boolean useWholeVillage) {
        if (world.isRemote || !(world instanceof WorldServer)) return;
        BlockPos homePos = worker.getAssignedHomePos();
        if (homePos == null) return;
        List<IInventory> chests = buildChestList(homePos, world, useWholeVillage);
        if (chests.isEmpty()) return;
        doSwaps(worker, chests);
        for (IInventory chest : chests) chest.markDirty();
    }

    public static void exchangeWithFollowers(EntityTravellerWorker leader, World world,
                                              BlockPos homePos, boolean useWholeVillage) {
        if (world.isRemote || !(world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) world;

        List<IInventory> chests = buildChestList(homePos, world, useWholeVillage);
        if (chests.isEmpty()) return;

        // Leader gets first pick
        doSwaps(leader, chests);

        UUID leaderUUID = leader.getUniqueID();

        // Real followers — scan all loaded worlds
        net.minecraft.server.MinecraftServer srv = ws.getMinecraftServer();
        if (srv != null) {
            for (WorldServer otherWs : srv.worlds) {
                for (net.minecraft.entity.Entity e : new ArrayList<>(otherWs.loadedEntityList)) {
                    if (e instanceof EntityTravellerWorker) {
                        EntityTravellerWorker follower = (EntityTravellerWorker) e;
                        if (leaderUUID.equals(follower.getEscortLeaderUUID())) {
                            doSwaps(follower, chests);
                        }
                    }
                }
            }
        }

        // Virtual followers
        VirtualWorkerManager vwm = VirtualWorkerManager.get(ws);
        boolean dirty = false;
        for (VirtualWorkerRecord r : vwm.getAllRecords()) {
            if (!leaderUUID.equals(r.escortLeaderUUID)) continue;
            EntityTravellerWorker temp = new EntityTravellerWorker(ws);
            temp.readFromNBT(r.entityNbt);
            temp.setUniqueId(r.uuid);
            doSwaps(temp, chests);
            NBTTagCompound updatedNbt = new NBTTagCompound();
            temp.writeToNBT(updatedNbt);
            r.entityNbt = updatedNbt;
            dirty = true;
        }
        if (dirty) vwm.markDirty();

        for (IInventory chest : chests) chest.markDirty();
    }

    /**
     * Force-loads the chunks containing the chests of the target building so that
     * TileEntityLockedChest instances are accessible for a virtual exchange.
     * Chunks will be garbage-collected naturally after no longer referenced.
     */
    public static void forceLoadChestChunks(WorldServer world, BlockPos homePos, boolean useWholeVillage) {
        MillWorldData wd = MillVillageIndex.getWD(world);
        if (wd == null) return;
        Point homePoint = new Point(homePos.getX(), homePos.getY(), homePos.getZ());
        Building barracks = wd.getBuilding(homePoint);
        if (barracks == null) return;
        Building townHall = barracks.isTownhall ? barracks : barracks.getTownHall();
        if (townHall == null) townHall = barracks;
        List<Building> buildings = useWholeVillage
                ? townHall.getBuildings()
                : townHall.getBuildingsWithTag("barracks");
        if (buildings.isEmpty()) buildings = townHall.getBuildings();

        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Building b : buildings) {
            if (b == null) continue;
            ResManager rm = ((MixinBuildingResManagerAccessor) b).travellers$getResManager();
            if (rm == null) continue;
            for (Point p : rm.chests) {
                int cx = p.getiX() >> 4;
                int cz = p.getiZ() >> 4;
                long key = ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
                if (seen.add(key)) {
                    world.getChunkProvider().provideChunk(cx, cz);
                }
            }
        }
    }

    private static List<IInventory> buildChestList(BlockPos homePos, World world, boolean useWholeVillage) {
        MillWorldData wd = MillVillageIndex.getWD(world);
        if (wd == null) return java.util.Collections.emptyList();
        Point homePoint = new Point(homePos.getX(), homePos.getY(), homePos.getZ());
        Building barracks = wd.getBuilding(homePoint);
        if (barracks == null) return java.util.Collections.emptyList();
        Building townHall = barracks.isTownhall ? barracks : barracks.getTownHall();
        if (townHall == null) townHall = barracks;
        List<Building> buildings = useWholeVillage
                ? townHall.getBuildings()
                : townHall.getBuildingsWithTag("barracks");
        if (buildings.isEmpty()) buildings = townHall.getBuildings();
        return collectChests(buildings, world);
    }

    private static void doSwaps(EntityTravellerWorker worker, List<IInventory> chests) {
        for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
            swapArmorSlot(worker, chests, slot);
        }
        swapWeapon(worker, chests);
    }

    private static List<IInventory> collectChests(List<Building> buildings, World world) {
        List<IInventory> result = new ArrayList<>();
        for (Building b : buildings) {
            if (b == null) continue;
            ResManager rm = ((MixinBuildingResManagerAccessor) b).travellers$getResManager();
            if (rm == null) continue;
            for (Point p : rm.chests) {
                TileEntityLockedChest chest = p.getMillChest(world);
                if (chest != null) result.add(chest);
            }
        }
        return result;
    }

    private static void swapArmorSlot(EntityTravellerWorker worker, List<IInventory> chests, EntityEquipmentSlot slot) {
        ItemStack current = worker.getItemStackFromSlot(slot);
        double currentVal = armorValue(current, slot);

        int bestSlotIdx = -1;
        IInventory bestChest = null;
        double bestVal = currentVal;

        for (IInventory chest : chests) {
            for (int i = 0; i < chest.getSizeInventory(); i++) {
                ItemStack candidate = chest.getStackInSlot(i);
                if (candidate.isEmpty()) continue;
                if (!fitsSlot(candidate, slot)) continue;
                double val = armorValue(candidate, slot);
                if (val > bestVal) {
                    bestVal = val;
                    bestSlotIdx = i;
                    bestChest = chest;
                }
            }
        }

        if (bestChest == null) return;

        ItemStack better = bestChest.getStackInSlot(bestSlotIdx).copy();
        bestChest.setInventorySlotContents(bestSlotIdx, current.isEmpty() ? ItemStack.EMPTY : current.copy());
        worker.setItemStackToSlot(slot, better);
    }

    private static void swapWeapon(EntityTravellerWorker worker, List<IInventory> chests) {
        ItemStack current = worker.getHeldItemMainhand();
        double currentVal = attackDamage(current);

        int bestSlotIdx = -1;
        IInventory bestChest = null;
        double bestVal = currentVal;

        for (IInventory chest : chests) {
            for (int i = 0; i < chest.getSizeInventory(); i++) {
                ItemStack candidate = chest.getStackInSlot(i);
                if (candidate.isEmpty()) continue;
                double val = attackDamage(candidate);
                if (val <= bestVal) continue;
                if (!isWeapon(candidate)) continue;
                bestVal = val;
                bestSlotIdx = i;
                bestChest = chest;
            }
        }

        if (bestChest == null) return;

        ItemStack better = bestChest.getStackInSlot(bestSlotIdx).copy();
        bestChest.setInventorySlotContents(bestSlotIdx, current.isEmpty() ? ItemStack.EMPTY : current.copy());
        worker.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, better);
    }

    private static boolean fitsSlot(ItemStack stack, EntityEquipmentSlot slot) {
        Item item = stack.getItem();
        if (item instanceof ItemArmor) {
            return ((ItemArmor) item).armorType == slot;
        }
        return false;
    }

    private static double armorValue(ItemStack stack, EntityEquipmentSlot slot) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (item instanceof ItemArmor) {
            ItemArmor armor = (ItemArmor) item;
            if (armor.armorType == slot) return armor.damageReduceAmount;
        }
        return 0;
    }

    private static double attackDamage(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        com.google.common.collect.Multimap<String, net.minecraft.entity.ai.attributes.AttributeModifier> attrs =
                stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND);
        java.util.Collection<net.minecraft.entity.ai.attributes.AttributeModifier> mods =
                attrs.get("generic.attackDamage");
        if (mods.isEmpty()) return 0;
        return mods.iterator().next().getAmount();
    }

    private static boolean isWeapon(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof net.minecraft.item.ItemSword
                || item instanceof net.minecraft.item.ItemAxe
                || attackDamage(stack) > 1.0;
    }
}
