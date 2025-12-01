package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.traveller.travel.TravelCommon;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.config.MillConfigValues;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.item.TradeGood;
import org.millenaire.common.utilities.MillLog;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.ConstructionIP;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EntityAIMerchantDeliverGoods extends EntityAIBase {

    private final EntityTravellerMerchant merchant;
    private final double speed;

    private Building currentTownHall;
    private BlockPos chestTarget;
    private BlockPos currentVillageCenter;

    private enum State { IDLE, WALKING, DELIVERING, WAITING }
    private State state = State.IDLE;

    private int waitTicks = 0;

    // To avoid re-delivering repeatedly in same visit
    private BlockPos lastServicedVillage = null;

    public EntityAIMerchantDeliverGoods(EntityTravellerMerchant merchant, double speed) {
        this.merchant = merchant;
        this.speed = speed;
        this.setMutexBits(3); // move+look; adjust if needed
    }

    @Override
    public boolean shouldExecute() {
        // Don’t run while fighting or eating
        if (merchant.isInCombat() || merchant.isAutoEating() || merchant.getAttackTarget() != null) {
            return false;
        }

        TravelCommon tc = merchant.getTravelCommon();
        BlockPos targetVillage = tc.getTargetVillage();
        if (targetVillage == null) return false;

        // Only when we've reached the village
        if (!tc.isAtTargetStop()) return false;

        // Avoid doing it twice for exactly same center without leaving
        if (lastServicedVillage != null && lastServicedVillage.equals(targetVillage)
                && !tc.isReadyToDepart()) {
            return false;
        }

        // Find Town Hall
        World world = merchant.world;
        MillWorldData wd = MillVillageIndex.getWD(world);
        if (wd == null) return false;

        Building th = wd.getBuilding(new Point(targetVillage.getX(),
                targetVillage.getY(),
                targetVillage.getZ()));
        if (th == null) return false;
        if (!th.isTownhall) {
            // Fallback: search for a Town Hall building around
            Building fallback = findAnyTownHallNear(wd, targetVillage, 64);
            if (fallback == null) return false;
            th = fallback;
        }

        this.currentTownHall = th;
        this.currentVillageCenter = targetVillage;

        // Decide where to walk: chestPos if present, else TH pos
        Point chest = (th.location != null && th.location.chestPos != null)
                ? th.location.chestPos
                : th.getPos();

        this.chestTarget = new BlockPos(chest.getiX(), chest.getiY(), chest.getiZ());
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (state == State.IDLE) return false;
        if (merchant.isInCombat() || merchant.getAttackTarget() != null) return false;

        // If we somehow left the village, abort
        TravelCommon tc = merchant.getTravelCommon();
        if (!tc.isAtTargetStop()) return false;

        return true;
    }

    @Override
    public void startExecuting() {
        state = State.WALKING;
        waitTicks = 0;
        // Walk toward chest
        merchant.getNavigator().tryMoveToXYZ(
                chestTarget.getX() + 0.5,
                chestTarget.getY(),
                chestTarget.getZ() + 0.5,
                speed
        );
    }

    @Override
    public void resetTask() {
        state = State.IDLE;
        currentTownHall = null;
        chestTarget = null;
        currentVillageCenter = null;
        waitTicks = 0;
        merchant.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        switch (state) {
            case WALKING:
                tickWalking();
                break;
            case DELIVERING:
                tickDelivering();
                break;
            case WAITING:
                tickWaiting();
                break;
            default:
                break;
        }
    }

    private void tickWalking() {
        if (chestTarget == null) {
            state = State.IDLE;
            return;
        }

        double d2 = merchant.getDistanceSqToCenter(chestTarget);
        if (d2 <= 3.0D) { // close enough
            state = State.DELIVERING;
        } else if (merchant.getNavigator().noPath()) {
            // Re-path occasionally if stuck
            merchant.getNavigator().tryMoveToXYZ(
                    chestTarget.getX() + 0.5,
                    chestTarget.getY(),
                    chestTarget.getZ() + 0.5,
                    speed
            );
        }
    }

    private void tickDelivering() {
        if (currentTownHall == null) {
            state = State.IDLE;
            return;
        }

        donateToVillageConstruction(this.currentTownHall);
        // After one delivery cycle, wait a bit, then let travel AI take over
        waitTicks = 8 * 20; // 8 seconds; tweak
        state = State.WAITING;

        // Mark that we handled this village this visit
        if (currentVillageCenter != null) {
            lastServicedVillage = currentVillageCenter;
        }

        // Tell TravelCommon that we’re ready to leave after the idle time
        merchant.getTravelCommon().setReadyToDepart(true);
    }

    private void tickWaiting() {
        if (--waitTicks <= 0) {
            state = State.IDLE;
        }
    }

    /**
     * Merchant "magically" donates goods directly to the Town Hall,
     * but ONLY for building construction (resCost of plans).
     *
     * No merchant inventory is used – we just top up the village a bit.
     */
    private void donateToVillageConstruction(Building townHall) {
        String travellerName = this.currentTownHall.getGameBuildingName();

        Map<InvItem, Integer> reqMap = TravellerMillHelper.computeActiveConstructionRequirements(townHall);
        Map<InvItem, Integer> wallReq = TravellerMillHelper.computeWallRequirements(townHall);

        // Merge walls into the same map
        for (Map.Entry<InvItem, Integer> e : wallReq.entrySet()) {
            reqMap.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        if (reqMap.isEmpty()) {
            MillLog.major(townHall, "[Traveller] merchant " + travellerName +
                    " found no construction or wall requirements at " + townHall.getPos());
            return;
        }

        MillLog.major(townHall, "[Traveller] merchant " + travellerName +
                " checking construction + wall needs at " + townHall.getPos());

        int donationsDone = 0;
        final int maxDistinctItems = 6;

        for (Map.Entry<InvItem, Integer> e : reqMap.entrySet()) {
            if (donationsDone >= maxDistinctItems) break;

            InvItem ii = e.getKey();
            int totalReq = e.getValue();
            if (ii == null || ii.getItem() == null || totalReq <= 0) continue;

            Item item = ii.getItem();
            int meta = ii.meta;

            int inVillage = townHall.countGoods(item, meta);
            int missing = totalReq - inVillage;
            if (missing <= 0) continue;

            ItemStack probe = new ItemStack(item, 1, meta);
            int maxStack = probe.getMaxStackSize();
            boolean smallStack = maxStack <= 16; // banners, tools, etc.

            int slice = Math.max(1, totalReq / 12); // ~1/12 rule
            int minBulk = smallStack ? 1 : 64;      // 1 for banners etc., 64 for planks etc.

            int desired = Math.max(slice, minBulk);
            int toGive = Math.min(missing, desired);
            if (toGive <= 0) continue;

            int stored = townHall.storeGoods(item, meta, toGive); // magic stock
            if (stored > 0) {
                donationsDone++;

                String line = TravellerMillHelper.formatDonationLine(
                        ii, totalReq, inVillage, missing, stored);
                MillLog.major(townHall,
                        "[Traveller] merchant " + travellerName + " donated: " + line);
            }
        }

        if (donationsDone == 0) {
            MillLog.major(townHall, "[Traveller] merchant " + travellerName +
                    " found requirements but nothing missing enough to donate.");
        }
    }



    // ------------------------
    // Helpers
    // ------------------------


    @Nullable
    private Building findAnyTownHallNear(MillWorldData wd, BlockPos center, int radiusXZ) {
        int cx = center.getX();
        int cz = center.getZ();
        int r2 = radiusXZ * radiusXZ;

        for (Point p : wd.villagesList.pos) {
            int dx = p.getiX() - cx;
            int dz = p.getiZ() - cz;
            if (dx * dx + dz * dz > r2) continue;

            Building b = wd.getBuilding(p);
            if (b != null && b.isTownhall) {
                return b;
            }
        }
        return null;
    }
    /**
     * Compute construction-only material needs for the given Town Hall.
     * Looks only at building plans (current goal + constructions in progress),
     * and ignores trade stock, export buffers, villager food, etc.
     *
     * Returns a map of InvItem -> number of items still missing.
     */
    private Map<InvItem, Integer> computeConstructionNeeds(Building townHall) {
        Map<InvItem, Integer> needs = new HashMap<>();

        if (townHall == null || townHall.culture == null) {
            return needs;
        }

        // --- 1) Collect all InvItems used by any *active* construction (buildings, walls, etc.) ---

        Set<InvItem> constructionKeys = new HashSet<>();

        // Current building goal
        BuildingPlan goal = townHall.getCurrentGoalBuildingPlan();
        if (goal != null && goal.resCost != null) {
            constructionKeys.addAll(goal.resCost.keySet());
        }

        // Constructions in progress (includes walls, gateways, etc.)
        for (ConstructionIP cip : townHall.getConstructionsInProgress()) {
            if (cip == null || cip.getBuildingLocation() == null) continue;
            BuildingPlan plan = cip.getBuildingLocation().getPlan();
            if (plan == null || plan.resCost == null) continue;

            constructionKeys.addAll(plan.resCost.keySet());
        }

        if (constructionKeys.isEmpty()) {
            // No active constructions → nothing to donate
            return needs;
        }

        // --- 2) Ask Millénaire how many of each trade good is needed,
        //         but keep only those goods that are actually used in constructions. ---

        for (TradeGood good : townHall.culture.goodsList) {
            InvItem inv = good.item;
            if (inv == null || inv.getItem() == null) continue;

            // Check if this trade good is used by ANY active construction plan
            boolean usedInConstruction = false;
            for (InvItem key : constructionKeys) {
                if (key != null && key.matches(inv)) {
                    usedInConstruction = true;
                    break;
                }
            }
            if (!usedInConstruction) {
                continue;
            }

            // Let Millénaire compute "how many more of this item the village wants"
            int missing = townHall.nbGoodNeeded(inv.getItem(), inv.meta);
            if (missing > 0) {
                needs.put(inv, missing);
            }
        }

        return needs;
    }

    /**
     * For a given plan, compute what is *still missing* in storage
     * based purely on resCost and what we’ve already allocated
     * for previous plans.
     */
    private void accumulatePlanConstructionNeeds(
            Building townHall,
            BuildingPlan plan,
            Map<InvItem, Integer> needs,
            Map<InvItem, Integer> allocated
    ) {
        for (Map.Entry<InvItem, Integer> e : plan.resCost.entrySet()) {
            InvItem key = e.getKey();
            int required = e.getValue();
            if (required <= 0 || key == null || key.getItem() == null) {
                continue;
            }

            int totalHave = townHall.countGoods(key.getItem(), key.meta);
            int alreadyAllocated = allocated.getOrDefault(key, 0);

            int usableForThisPlan = Math.max(totalHave - alreadyAllocated, 0);
            int missingForThisPlan = Math.max(required - usableForThisPlan, 0);

            if (missingForThisPlan > 0) {
                needs.merge(key, missingForThisPlan, Integer::sum);
            }

            int usedNow = Math.min(required, usableForThisPlan);
            if (usedNow > 0) {
                allocated.put(key, alreadyAllocated + usedNow);
            }
        }
    }

    /**
     * Decide how many of a given item to donate, based on how many are still missing.
     *
     * Rules:
     *  - Base amount: about 1/12 of the missing amount (ceil).
     *  - If item is maxStack == 1 (banners/tools/etc): at least about 1/8 of missing.
     *  - If item is maxStack >= 64 (bulk blocks): at least 64 if missing >= 64.
     *  - Never exceed the missing amount.
     */
    private int computeDonationAmount(Item item, int meta, int missing) {
        if (item == null || missing <= 0) {
            return 0;
        }

        // Probe stack to detect stack size
        ItemStack probe = new ItemStack(item, 1, meta);
        int maxStack = probe.getMaxStackSize(); // in 1.12: probe.func_77976_d()

        // Base: ~1/12th of missing (ceil)
        int base = (missing + 11) / 12;
        if (base < 1) base = 1;
        int toGive = base;

        if (maxStack == 1) {
            // "Banner-like" (non-stackable) items:
            // ~1/8 of missing, at least 1.
            int bannersBase = (missing + 7) / 8;
            if (bannersBase < 1) bannersBase = 1;
            toGive = Math.max(toGive, bannersBase);
        } else if (maxStack >= 64) {
            // Bulk resources (planks, stone, etc.)
            if (missing >= 64) {
                toGive = Math.max(toGive, 64);
            } else {
                // If missing < 64, just aim to fill the gap completely
                toGive = Math.max(toGive, missing);
            }
        }

        // Never give more than what's missing
        if (toGive > missing) {
            toGive = missing;
        }

        return Math.max(1, toGive);
    }

}

