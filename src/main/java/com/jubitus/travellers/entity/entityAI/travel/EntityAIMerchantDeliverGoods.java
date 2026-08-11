package com.jubitus.travellers.entity.entityAI.travel;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.mill.TravellerMillHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;
import org.millenaire.common.forge.BuildingChunkLoader;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.Map;

public class EntityAIMerchantDeliverGoods extends EntityAIBase {

    private static final int BUGGED_LOG_THROTTLE_TICKS = 20 * 60; // 60s between logs per visit
    private final double speed;
    private final EntityTraveller owner;
    // To avoid re-delivering repeatedly in same visit
    private final BlockPos lastServicedVillage = null;
    private int buggedVillageLogCooldown = 0;
    private BlockPos townHallCenter;      // Center of the town hall
    private boolean chestSearchDone = false; // Only search for chest once per visit
    private BuildingChunkLoader chunkLoader;      // Millénaire chunk loader instance
    private Building chunkLoaderTownHall;         // which TH this loader belongs to
    private boolean villageChunksLoaded = false;  // our own flag
    private BlockPos visitVillageCenter = null;
    private boolean donatedThisVisit = false;
    private Building currentTownHall;
    private BlockPos chestTarget;
    private BlockPos currentVillageCenter;
    private State state = State.IDLE;

    private int waitTicks = 0;


    public EntityAIMerchantDeliverGoods(EntityTraveller owner, double speed) {
        this.owner = owner;
        this.speed = speed;
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        if (this.owner instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this.owner;
            // Only allow merchant behaviour if not hired follower
            // OR if on an active job (delivery).
            if (w.isHiredFollower() && !w.isJobActive()) {
                return false;
            }
        }
        // Don’t run while fighting or eating
        if (owner.isInCombat() || owner.isAutoEating() || owner.getAttackTarget() != null) {
            return false;
        }
        // NEW: if this is a worker, only run when idle (no job active)
        if (owner instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) owner;
            if (!w.isIdleForRoaming()) {
                return false;
            }
        }
        TravelCommon tc = owner.getTravelCommon();

// Prefer the *entity* target village (used by AITravel)
        BlockPos targetVillage = owner.getTargetVillage();
        if (targetVillage == null) {
            // fall back to TravelCommon's target, then nearest
            targetVillage = tc.getTargetVillage();
        }
        if (targetVillage == null) {
            targetVillage = tc.getNearestVillage();
        }
        if (targetVillage == null) return false;

        int r = tc.arrivalRadiusBlocks(targetVillage);
        if (owner.getDistanceSqToCenter(targetVillage) > (double) (r * r)) {
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
        World world = owner.world;
        MillWorldData wd = MillVillageIndex.getWD(world);
        if (wd == null) {
            // No Millénaire data – treat as invalid stop and move on
            skipBuggedVillage(targetVillage, "MillWorldData is null");
            return false;
        }

        Building th = wd.getBuilding(new Point(
                targetVillage.getX(),
                targetVillage.getY(),
                targetVillage.getZ()
        ));

        if (th == null || !th.isTownhall) {
            // For village stops, require a town hall. For lone building stops, any building is fine.
            Building fallback = MillVillageIndex.findBuildingForDonation(wd, targetVillage, 64);
            if (fallback == null) {
                skipBuggedVillage(targetVillage, "no building near registered center");
                return false;
            }
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
        if (owner.isInCombat() || owner.getAttackTarget() != null) return false;
        if (this.owner instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this.owner;
            // Only allow merchant behaviour if not hired follower
            // OR if on an active job (delivery).
            if (w.isHiredFollower() && !w.isJobActive()) {
                return false;
            }
        }
        // If we somehow left the village bubble, abort
        if (visitVillageCenter != null) {
            int r = owner.getTravelCommon().arrivalRadiusBlocks(visitVillageCenter);
            if (owner.getDistanceSqToCenter(visitVillageCenter) > (double) (r * r)) {
                // LEFT the village: end of visit
                visitVillageCenter = null;
                donatedThisVisit = false;
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

        owner.getTravelCommon().setReadyToDepart(false);

        // Load Millénaire chunks once per visit
        if (!owner.world.isRemote && chunkLoader != null && !villageChunksLoaded) {
            chunkLoader.loadChunks();
            villageChunksLoaded = true;
        }

        if (chestTarget != null) {
            owner.getNavigator().tryMoveToXYZ(
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
        owner.getNavigator().clearPath();

        // Treat reset as “end of visit” as well
        visitVillageCenter = null;
        donatedThisVisit = false;

        if (!owner.world.isRemote) {
            TravelCommon tc = owner.getTravelCommon();
            tc.setVillageIdling(false);    // <-- add
            tc.setReadyToDepart(true);
            tc.setDepartCooldownTicks(0);
        }

        // If this behaviour is cancelled, clean up chunks
        if (!owner.world.isRemote && chunkLoader != null && villageChunksLoaded) {
            chunkLoader.unloadChunks();
            villageChunksLoaded = false;
        }
    }

    @Override
    public void updateTask() {
        if (buggedVillageLogCooldown > 0) {
            buggedVillageLogCooldown--;
        }
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
            double distToCenterSq = owner.getDistanceSqToCenter(townHallCenter);
            if (distToCenterSq <= 32.0D * 32.0D) { // inside radius 8
                chestSearchDone = true;

                BlockPos chest = findFirstTownHallChest(townHallCenter, 32);

                if (chest != null) {
                    // Try to path to this chest
                    boolean pathOk = owner.getNavigator().tryMoveToXYZ(
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
        double d2 = owner.getDistanceSqToCenter(chestTarget);
        if (d2 <= 5.0D) { // close enough to chest OR standable center
            state = State.DELIVERING;
        } else if (owner.getNavigator().noPath()) {
            // ❗ NEW: if we've already done chest search and have no path,
            //         give up on the chest and donate anyway.
            if (chestSearchDone) {
                state = State.DELIVERING;
                return;
            }

            // Haven't searched yet / still heading to interior target: try re-path
            owner.getNavigator().tryMoveToXYZ(
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
            TravelCommon tc = owner.getTravelCommon();
            tc.setReadyToDepart(true);
            tc.setVillageIdling(false);    // <-- add this
            tc.setDepartCooldownTicks(0);

            // Visit done → unload village chunks
            if (!owner.world.isRemote && chunkLoader != null && villageChunksLoaded) {
                chunkLoader.unloadChunks();
                villageChunksLoaded = false;
            }

            state = State.IDLE;
        }
    }

    /**
     * Find the first chest within radius around a center.
     * Used once when the merchant enters radius 8 of the town hall.
     */
    @Nullable
    private BlockPos findFirstTownHallChest(BlockPos center, int radius) {
        if (center == null) return null;

        World w = owner.world;

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


    /**
     * Merchant "magically" donates goods directly to the Town Hall,
     * but ONLY for building construction (resCost of plans).
     * <p>
     * No merchant inventory is used – we just top up the village a bit.
     */
    private void donateToVillageConstruction(Building townHall) {
        String travellerName = this.state.name();

        Map<InvItem, Integer> reqMap = TravellerMillHelper.computeActiveConstructionRequirements(townHall);
        Map<InvItem, Integer> wallReq = TravellerMillHelper.computeWallRequirements(townHall);

        // Merge walls into the same map
        for (Map.Entry<InvItem, Integer> e : wallReq.entrySet()) {
            reqMap.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        if (reqMap.isEmpty()) {
            if (TravellersConfig.MERCHANT.merchantDebugLog) {
                FMLLog.log(Level.INFO, "[Traveller] merchant " + travellerName +
                        " found no construction or wall requirements at " + townHall.getPos());
            }
            return;
        }

        if (TravellersConfig.MERCHANT.merchantDebugLog) {
            FMLLog.log(Level.INFO, "[Traveller] merchant " + travellerName +
                    " checking construction + wall needs at " + townHall.getPos());
        }

        int donationsDone = 0;

        // === CONFIGURABLE LIMIT ON DISTINCT ITEMS ===
        int maxDistinctItems = TravellersConfig.MERCHANT.merchantMaxDistinctItems;
        if (maxDistinctItems <= 0) {
            maxDistinctItems = 1; // safety
        }

        // === CONFIGURABLE FRACTION & MIN AMOUNTS ===
        double giveFraction = TravellersConfig.MERCHANT.merchantGiveFraction;
        if (giveFraction <= 0.0D) {
            giveFraction = 1.0D / 12.0D; // fallback safety
        }

        int minBulkAmount = TravellersConfig.MERCHANT.merchantMinBulkAmount;
        int minSmallAmount = TravellersConfig.MERCHANT.merchantMinSmallAmount;
        if (minBulkAmount <= 0) minBulkAmount = 1;
        if (minSmallAmount <= 0) minSmallAmount = 1;

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

            // slice = configurable fraction of total requirement
            int slice = (int) Math.floor(totalReq * giveFraction);
            if (slice < 1) slice = 1;

            // configurable minimums for bulk vs small
            int minAmount = smallStack ? minSmallAmount : minBulkAmount;

            int desired = Math.max(slice, minAmount);
            int toGive = Math.min(missing, desired);
            if (toGive <= 0) continue;

            int stored = townHall.storeGoods(item, meta, toGive); // magic stock
            if (stored > 0) {
                donationsDone++;

                if (TravellersConfig.MERCHANT.merchantDebugLog) {
                    String line = TravellerMillHelper.formatDonationLine(
                            ii, totalReq, inVillage, missing, stored);
                    FMLLog.log(Level.INFO,
                            "[Traveller] merchant " + travellerName + " donated: " + line);
                }
            }
        }

        if (donationsDone == 0 && TravellersConfig.MERCHANT.merchantDebugLog) {
            FMLLog.log(Level.INFO, "[Traveller] merchant " + travellerName +
                    " found requirements but nothing missing enough to donate.");
        }
    }


    // ------------------------
    // Helpers
    // ------------------------

    private void skipBuggedVillage(BlockPos center, String reason) {
        if (center == null || owner.world.isRemote) {
            return;
        }

        TravelCommon tc = owner.getTravelCommon();

        // Mark this "visit" as already handled so we don't spam shouldExecute()
        visitVillageCenter = center;
        donatedThisVisit = true;

        // Let travel AI move on from this stop
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);
        tc.setVillageIdling(false);

        // Optional simple log throttling per AI instance
        if (buggedVillageLogCooldown <= 0) {
            if (TravellersConfig.MERCHANT.merchantDebugLog) {
                FMLLog.log(Level.WARN, "[Travellers] Skipping bugged village at " +
                        center + " : " + reason);
            }
            buggedVillageLogCooldown = BUGGED_LOG_THROTTLE_TICKS;
        }
        tc.markVillageBad(center);
    }

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
        TravelCommon tc = owner.getTravelCommon();

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

    private enum State {IDLE, WALKING, DELIVERING, WAITING}

}

