package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.travel.TravelCommon;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.config.MillConfigValues;
import org.millenaire.common.forge.BuildingChunkLoader;
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

    private BlockPos townHallCenter;      // Center of the town hall
    private boolean chestSearchDone = false; // Only search for chest once per visit

    private BuildingChunkLoader chunkLoader;      // Millénaire chunk loader instance
    private Building chunkLoaderTownHall;         // which TH this loader belongs to
    private boolean villageChunksLoaded = false;  // our own flag
    private BlockPos visitVillageCenter = null;
    private boolean donatedThisVisit = false;

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

        // 1) Use target village if present, otherwise fall back to nearestVillage
        BlockPos targetVillage = tc.getTargetVillage();
        if (targetVillage == null) {
            targetVillage = tc.getNearestVillage();
        }
        if (targetVillage == null) {
            return false; // nowhere to donate
        }

        // 2) Only run if we're actually inside that village's arrival radius
        int r = tc.arrivalRadiusBlocks(targetVillage);
        if (merchant.getDistanceSqToCenter(targetVillage) > (double) (r * r)) {
            return false;
        }

        // Per-visit tracking: new village ⇒ reset, same village & already donated ⇒ do nothing
        if (visitVillageCenter == null || !visitVillageCenter.equals(targetVillage)) {
            visitVillageCenter = targetVillage;
            donatedThisVisit = false;
        } else if (donatedThisVisit) {
            // Already gifted this village for this visit, let travel AI handle departure
            return false;
        }

        // ---------- Town hall lookup as before ----------
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

        // Use the *building center* as the logical target, not chestPos
        Point p = th.getPos();
        this.townHallCenter = new BlockPos(p.getiX(), p.getiY(), p.getiZ());

        // Just find a standable spot near the center (radius 8)
        BlockPos interior = findTownHallInteriorTarget(th);
        if (interior == null) {
            interior = this.townHallCenter;
        }
        this.chestTarget = interior;

        // --- chunk loader block stays as you have it ---

        return true;
    }
    @Override
    public boolean shouldContinueExecuting() {
        if (state == State.IDLE) return false;
        if (merchant.isInCombat() || merchant.getAttackTarget() != null) return false;

        // If we somehow left the village bubble, abort
        if (visitVillageCenter != null) {
            int r = merchant.getTravelCommon().arrivalRadiusBlocks(visitVillageCenter);
            if (merchant.getDistanceSqToCenter(visitVillageCenter) > (double) (r * r)) {
                return false;
            }
        }

        return true;
    }


    @Override
    public void startExecuting() {
        state = State.WALKING;
        waitTicks = 0;
        chestSearchDone = false; // new

        merchant.getTravelCommon().setReadyToDepart(false);

        // Load Millénaire chunks once per visit
        if (!merchant.world.isRemote && chunkLoader != null && !villageChunksLoaded) {
            chunkLoader.loadChunks();
            villageChunksLoaded = true;
        }

        if (chestTarget != null) {
            merchant.getNavigator().tryMoveToXYZ(
                    chestTarget.getX() + 0.5,
                    chestTarget.getY(),
                    chestTarget.getZ() + 0.5,
                    speed
            );
        }
    }

    @Override
    public void resetTask() {
        state = State.IDLE;
        currentTownHall = null;
        chestTarget = null;
        currentVillageCenter = null;
        townHallCenter = null;
        chestSearchDone = false;
        waitTicks = 0;
        merchant.getNavigator().clearPath();

        // Allow travel AI to take over again even on failure
        if (!merchant.world.isRemote) {
            TravelCommon tc = merchant.getTravelCommon();
            tc.setReadyToDepart(true);
            tc.setDepartCooldownTicks(0);
        }

        // If this behaviour is cancelled, clean up chunks
        if (!merchant.world.isRemote && chunkLoader != null && villageChunksLoaded) {
            chunkLoader.unloadChunks();
            villageChunksLoaded = false;
        }
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
        if (chestTarget == null || currentTownHall == null) {
            state = State.IDLE;
            return;
        }

        // --- 1) When we enter radius 8 around the town hall center,
        //        search for a chest once.
        if (!chestSearchDone && townHallCenter != null) {
            double distToCenterSq = merchant.getDistanceSqToCenter(townHallCenter);
            if (distToCenterSq <= 16.0D * 16.0D) { // inside radius 8
                chestSearchDone = true;

                BlockPos chest = findFirstTownHallChest(townHallCenter, 8);

                if (chest != null) {
                    // Try to path to this chest
                    boolean pathOk = merchant.getNavigator().tryMoveToXYZ(
                            chest.getX() + 0.5,
                            chest.getY(),
                            chest.getZ() + 0.5,
                            speed
                    );

                    if (pathOk) {
                        // Now our final movement target is the chest
                        chestTarget = chest;
                        // keep WALKING, donation will happen when close to chest
                    } else {
                        // ❗ Chest is unpathable → just donate right here
                        state = State.DELIVERING;
                        return;
                    }
                } else {
                    // ❗ No chest found in radius → just donate here
                    state = State.DELIVERING;
                    return;
                }
            }
        }

        // --- 2) Normal walking logic to whatever current chestTarget is
        double d2 = merchant.getDistanceSqToCenter(chestTarget);
        if (d2 <= 5.0D) { // close enough to chest OR standable center
            state = State.DELIVERING;
        } else if (merchant.getNavigator().noPath()) {
            // ❗ NEW: if we've already done chest search and have no path,
            //         give up on the chest and donate anyway.
            if (chestSearchDone) {
                state = State.DELIVERING;
                return;
            }

            // Haven't searched yet / still heading to interior target: try re-path
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

        donatedThisVisit = true;   // ← per-visit gate

        // After one delivery cycle, wait a bit, then let travel AI take over
        waitTicks = 8 * 20; // 8 seconds; tweak
        state = State.WAITING;
    }


    private void tickWaiting() {
        if (--waitTicks <= 0) {
            TravelCommon tc = merchant.getTravelCommon();
            tc.setReadyToDepart(true);
            tc.setDepartCooldownTicks(0);

            // Visit done → unload village chunks
            if (!merchant.world.isRemote && chunkLoader != null && villageChunksLoaded) {
                chunkLoader.unloadChunks();
                villageChunksLoaded = false;
            }

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

    @Nullable
    private BlockPos findTownHallInteriorTarget(Building th) {
        TravelCommon tc = merchant.getTravelCommon();

        if (th == null) return null;

        // Always use the building center, not chestPos
        Point centerP = th.getPos();
        BlockPos center = new BlockPos(centerP.getiX(), centerP.getiY(), centerP.getiZ());

        final int radius = 16; // search radius around the town hall center
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos guess = center.add(dx, 0, dz);
                BlockPos stand = tc.topStandable(guess);
                if (stand != null) {
                    return stand;
                }
            }
        }

        // Fallback: try directly above the center
        return tc.topStandable(center);
    }
    /**
     * Find the first chest within radius around a center.
     * Used once when the merchant enters radius 8 of the town hall.
     */
    @Nullable
    private BlockPos findFirstTownHallChest(BlockPos center, int radius) {
        if (center == null) return null;

        World w = merchant.world;

        // Search a low vertical band around the town hall
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    IBlockState state = w.getBlockState(pos);
                    Block block = state.getBlock();

                    // 1.12: BlockChest is the chest block type
                    if (block instanceof BlockChest) {
                        return pos;
                    }
                }
            }
        }

        return null;
    }

}

