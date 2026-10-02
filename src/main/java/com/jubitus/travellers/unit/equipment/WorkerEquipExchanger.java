package com.jubitus.travellers.unit.equipment;

import com.jubitus.travellers.world.mill.MillSettlements;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneRoles;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import org.millenaire.common.entity.TileEntityLockedChest;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.buildingmanagers.ResManager;
import org.millenaire.common.world.MillWorldData;

import java.util.ArrayList;
import java.util.Collections;
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

    /** Returns {workers that got something, workers considered} (leader plus followers). */
    public static int[] exchangeWithFollowers(EntityTravellerWorker leader, World world,
                                              BlockPos homePos, boolean useWholeVillage) {
        if (world.isRemote || !(world instanceof WorldServer)) return new int[]{0, 1};
        WorldServer ws = (WorldServer) world;

        List<IInventory> chests = buildChestList(homePos, world, useWholeVillage);
        if (chests.isEmpty()) return new int[]{0, 1};

        int equipped = 0;
        int total = 1;
        // Leader gets first pick
        if (doSwaps(leader, chests)) equipped++;

        UUID leaderUUID = leader.getUniqueID();

        // Real followers — scan all loaded worlds
        net.minecraft.server.MinecraftServer srv = ws.getMinecraftServer();
        if (srv != null) {
            for (WorldServer otherWs : srv.worlds) {
                for (net.minecraft.entity.Entity e : new ArrayList<>(otherWs.loadedEntityList)) {
                    if (e instanceof EntityTravellerWorker) {
                        EntityTravellerWorker follower = (EntityTravellerWorker) e;
                        if (leaderUUID.equals(follower.getEscortLeaderUUID())) {
                            total++;
                            if (doSwaps(follower, chests)) equipped++;
                        }
                    }
                }
            }
        }

        // Virtual followers
        VirtualTravellerManager vwm = VirtualTravellerManager.get(ws);
        boolean dirty = false;
        for (VirtualTravellerRecord r : vwm.getAllRecords()) {
            if (!leaderUUID.equals(r.escortLeaderUUID)) continue;
            EntityTravellerWorker temp = new EntityTravellerWorker(ws);
            temp.readFromNBT(r.entityNbt);
            temp.setUniqueId(r.uuid);
            total++;
            if (doSwaps(temp, chests)) equipped++;
            NBTTagCompound updatedNbt = new NBTTagCompound();
            temp.writeToNBT(updatedNbt);
            r.entityNbt = updatedNbt;
            dirty = true;
        }
        if (dirty) vwm.markDirty();

        for (IInventory chest : chests) chest.markDirty();
        return new int[]{equipped, total};
    }

    /**
     * Loads the chest chunks a virtual equipment exchange needs (Millénaire building first, then lone
     * barrack). Returns the chunks it had to load; release them with LoadedChunks.release afterwards.
     */
    public static List<net.minecraft.world.chunk.Chunk> loadChestChunks(WorldServer world, BlockPos homePos, boolean useWholeVillage) {
        List<net.minecraft.world.chunk.Chunk> loaded = new java.util.ArrayList<>();
        List<Building> buildings = millBuildings(world, homePos, useWholeVillage);
        if (buildings != null) {
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (Building b : buildings) {
                ResManager rm = b == null ? null : b.getResManager();
                if (rm == null) continue;
                for (Point p : rm.chests) {
                    int cx = p.getiX() >> 4;
                    int cz = p.getiZ() >> 4;
                    if (seen.add(((long) cx << 32) | ((long) cz & 0xFFFFFFFFL))) {
                        LoadedChunks.loadTemporarily(world, cx, cz, loaded);
                    }
                }
            }
            return loaded;
        }

        // Lone barrack: force-load every chunk covered by the detection box
        PlayerZoneRecord rec = findLoneBarrackAt(world, homePos);
        if (rec == null) return loaded;
        int cx1 = (rec.center.getX() - rec.halfWidth)  >> 4;
        int cx2 = (rec.center.getX() + rec.halfWidth)  >> 4;
        int cz1 = (rec.center.getZ() - rec.halfDepth)  >> 4;
        int cz2 = (rec.center.getZ() + rec.halfDepth)  >> 4;
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                LoadedChunks.loadTemporarily(world, cx, cz, loaded);
            }
        }
        return loaded;
    }

    private static List<IInventory> buildChestList(BlockPos homePos, World world, boolean useWholeVillage) {
        List<Building> buildings = millBuildings(world, homePos, useWholeVillage);
        if (buildings != null) return collectChests(buildings, world);
        // Lone barrack fallback
        return buildChestListForLoneBarrack(world, homePos);
    }

    /** The village buildings whose chests serve a Millénaire target (its barracks, or the whole village),
     * or null when no Millénaire building stands at {@code homePos}. */
    @javax.annotation.Nullable
    private static List<Building> millBuildings(World world, BlockPos homePos, boolean useWholeVillage) {
        MillWorldData wd = MillSettlements.wd(world);
        if (wd == null) return null;
        Building target = wd.getBuilding(new Point(homePos.getX(), homePos.getY(), homePos.getZ()));
        if (target == null) return null;
        Building townHall = target.isTownhall ? target : target.getTownHall();
        if (townHall == null) townHall = target;
        List<Building> buildings = useWholeVillage ? townHall.getBuildings() : townHall.getBuildingsWithTag("barracks");
        return buildings.isEmpty() ? townHall.getBuildings() : buildings;
    }

    /** Scans the lone barrack box for vanilla chests, refreshing stored coffer positions first. */
    private static List<IInventory> buildChestListForLoneBarrack(World world, BlockPos homePos) {
        PlayerZoneRecord rec = findLoneBarrackAt(world, homePos);
        if (rec == null) return Collections.emptyList();

        // Refresh so newly placed chests are included
        rec.setRolePositions(ZoneRoles.CHEST,
            PlayerZoneManager.findCoffers(world, rec.center, rec.halfWidth, rec.halfDepth, rec.halfHeight));
        PlayerZoneManager.get(world).markDirty();

        List<IInventory> result = new ArrayList<>();
        for (BlockPos p : rec.getRolePositions(ZoneRoles.CHEST)) {
            TileEntity te = world.getTileEntity(p);
            if (te instanceof IInventory) result.add((IInventory) te);
        }
        return result;
    }

    /** Returns the lone barrack record whose center equals homePos, or null. */
    private static PlayerZoneRecord findLoneBarrackAt(World world, BlockPos homePos) {
        PlayerZoneManager mgr = PlayerZoneManager.get(world);
        for (PlayerZoneRecord r : mgr.getAll()) {
            if (homePos.equals(r.center)) return r;
        }
        return null;
    }

    private static boolean doSwaps(EntityTravellerWorker worker, List<IInventory> chests) {
        boolean changed = false;
        for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
            changed |= swapArmorSlot(worker, chests, slot);
        }
        changed |= swapWeapon(worker, chests);
        return changed;
    }

    private static List<IInventory> collectChests(List<Building> buildings, World world) {
        List<IInventory> result = new ArrayList<>();
        for (Building b : buildings) {
            if (b == null) continue;
            ResManager rm = b.getResManager();
            if (rm == null) continue;
            for (Point p : rm.chests) {
                TileEntityLockedChest chest = p.getMillChest(world);
                if (chest != null) result.add(chest);
            }
        }
        return result;
    }

    private static boolean swapArmorSlot(EntityTravellerWorker worker, List<IInventory> chests, EntityEquipmentSlot slot) {
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

        if (bestChest == null) return false;

        ItemStack better = bestChest.getStackInSlot(bestSlotIdx).copy();
        bestChest.setInventorySlotContents(bestSlotIdx, current.isEmpty() ? ItemStack.EMPTY : current.copy());
        worker.setItemStackToSlot(slot, better);
        return true;
    }

    private static boolean swapWeapon(EntityTravellerWorker worker, List<IInventory> chests) {
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

        if (bestChest == null) return false;

        ItemStack better = bestChest.getStackInSlot(bestSlotIdx).copy();
        bestChest.setInventorySlotContents(bestSlotIdx, current.isEmpty() ? ItemStack.EMPTY : current.copy());
        worker.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, better);
        return true;
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
