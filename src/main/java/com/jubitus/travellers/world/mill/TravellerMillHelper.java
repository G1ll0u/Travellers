package com.jubitus.travellers.world.mill;

import com.jubitus.travellers.config.TravellersConfig;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import org.millenaire.common.utilities.MillLog;
import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.village.BuildingProject;
import org.millenaire.common.village.ConstructionIP;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TravellerMillHelper {

    private TravellerMillHelper() {
    }

    public static String formatDonationLine(InvItem ii, int totalReq, int inVillage, int missing, int donate) {
        return String.format(
                "%s (meta %d): totalReq=%d, chest=%d, missing=%d, donate=%d",
                ii.getName(), ii.meta, totalReq, inVillage, missing, donate
        );
    }

    public static void debugVillageNeeds(EntityPlayerMP player, Building townHall) {
        if (townHall == null) {
            player.sendMessage(new TextComponentString("[travellers] No Town Hall found here."));
            return;
        }

        BlockPos thPos = new BlockPos(
                townHall.getPos().getiX(),
                townHall.getPos().getiY(),
                townHall.getPos().getiZ()
        );

        String name = townHall.getGameBuildingName();
        player.sendMessage(new TextComponentString(
                String.format("=== Village needs for %s at %d/%d/%d ===",
                        name != null ? name : "<no name>",
                        thPos.getX(), thPos.getY(), thPos.getZ())
        ));

        // --- Basic goal + wall info -------------------------------------------------
        player.sendMessage(new TextComponentString("--- Goal / wall info ---"));

        String goalKey = townHall.buildingGoal;
        BuildingLocation goalLoc = townHall.buildingGoalLocation;
        BuildingPlan goalPlan = townHall.getCurrentGoalBuildingPlan(); // Millénaire API

        player.sendMessage(new TextComponentString("  buildingGoal: " + goalKey));
        player.sendMessage(new TextComponentString("  buildingGoalLevel: " + townHall.buildingGoalLevel));
        player.sendMessage(new TextComponentString("  buildingGoalVariation: " + townHall.buildingGoalVariation));
        player.sendMessage(new TextComponentString("  buildingGoalLocation: " + goalLoc));

        if (goalPlan != null) {
            player.sendMessage(new TextComponentString("  goalPlan: " + goalPlan.planName
                    + " (wall=" + goalPlan.isWallSegment + ")"));
            if (goalPlan.resCost != null && !goalPlan.resCost.isEmpty()) {
                player.sendMessage(new TextComponentString("    goalPlan.resCost:"));
                for (Map.Entry<InvItem, Integer> e : goalPlan.resCost.entrySet()) {
                    InvItem ii = e.getKey();
                    int req = e.getValue();
                    player.sendMessage(new TextComponentString("      - " + ii.getName()
                            + " (meta " + ii.meta + "): " + req));
                }
            } else {
                player.sendMessage(new TextComponentString("    goalPlan.resCost: <empty>"));
            }
        } else {
            player.sendMessage(new TextComponentString("  goalPlan: <null>"));
        }

        int wallLevel = townHall.computeCurrentWallLevel();
        player.sendMessage(new TextComponentString("  computeCurrentWallLevel() => " + wallLevel));

        // --- Construction IPs (CIPs) ------------------------------------------------
        player.sendMessage(new TextComponentString("--- Active constructions (CIPs) ---"));

        List<ConstructionIP> cips = townHall.getConstructionsInProgress();
        if (cips == null || cips.isEmpty()) {
            player.sendMessage(new TextComponentString("  <no ConstructionIP entries>"));
        } else {
            for (ConstructionIP cip : cips) {
                if (cip == null) continue;
                String kind = cip.isWallConstruction() ? "WALL CIP" : "BUILD CIP";

                player.sendMessage(new TextComponentString(
                        "  [" + kind + "] id=" + cip.getId()
                ));

                BuildingLocation bl = cip.getBuildingLocation();
                if (bl == null) {
                    player.sendMessage(new TextComponentString("    location: <null>"));
                    continue;
                }

                player.sendMessage(new TextComponentString(
                        "    location: planKey=" + bl.planKey
                                + ", level=" + bl.level
                                + ", variation=" + bl.getVariation()
                                + ", isSub=" + bl.isSubBuildingLocation
                ));

                BuildingPlan plan = bl.getPlan();
                if (plan == null) {
                    player.sendMessage(new TextComponentString("    plan: <null>"));
                    continue;
                }

                player.sendMessage(new TextComponentString(
                        "    plan: " + plan.planName
                                + " (wall=" + plan.isWallSegment + ", isWallSegment=" + plan.isWallSegment + ")"
                ));

                if (plan.resCost == null || plan.resCost.isEmpty()) {
                    player.sendMessage(new TextComponentString("    (resCost empty)"));
                } else {
                    player.sendMessage(new TextComponentString("    resCost:"));
                    for (Map.Entry<InvItem, Integer> e : plan.resCost.entrySet()) {
                        InvItem ii = e.getKey();
                        int req = e.getValue();
                        if (ii == null || ii.getItem() == null || req <= 0) continue;

                        int inVillage = townHall.nbGoodAvailable(ii, true, false, false);
                        int missing = Math.max(0, req - inVillage);

                        player.sendMessage(new TextComponentString(
                                "      - " + ii.getName()
                                        + " (meta " + ii.meta + "): needed=" + req
                                        + ", available=" + inVillage
                                        + ", missing=" + missing
                        ));
                    }
                }
            }
        }

        // --- Aggregated requirements from CIPs -------------------------------------
        player.sendMessage(new TextComponentString("--- Combined construction requirements (CIPs) ---"));
        Map<InvItem, Integer> cipReq = computeActiveConstructionRequirements(townHall);
        if (cipReq.isEmpty()) {
            player.sendMessage(new TextComponentString("  No resCost entries on any CIP."));
        } else {
            for (Map.Entry<InvItem, Integer> e : cipReq.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                int inVillage = townHall.nbGoodAvailable(ii, true, false, false);
                int missing = Math.max(0, req - inVillage);

                player.sendMessage(new TextComponentString(
                        "  * " + ii.getName()
                                + " (meta " + ii.meta + "): totalReq=" + req
                                + ", available=" + inVillage
                                + ", missing=" + missing
                ));
            }
        }

        // --- Wall requirements from projects ---------------------------------------
        player.sendMessage(new TextComponentString("--- Wall needs (projects) ---"));
        List<BuildingProject> projects = townHall.getFlatProjectList();
        if (projects == null || projects.isEmpty()) {
            player.sendMessage(new TextComponentString("  <no BuildingProject entries>"));
        } else {
            for (BuildingProject project : projects) {
                if (project == null) continue;
                BuildingPlan next = project.getNextBuildingPlan(false);
                BuildingPlan initial = project.getPlan(0, 0);

                boolean isWall = false;
                if (next != null && next.isWallSegment) isWall = true;
                else if (initial != null && initial.isWallSegment) isWall = true;

                if (!isWall) continue;

                String key = project.planSet != null ? project.planSet.key : "<nullPlanSet>";
                player.sendMessage(new TextComponentString(
                        "  [WALL PROJECT] key=" + key
                                + ", nextPlan=" + (next != null ? next.planName : "<null>")
                ));

                BuildingPlan plan = (next != null ? next : initial);
                if (plan == null) {
                    player.sendMessage(new TextComponentString("    plan: <null>"));
                    continue;
                }

                if (plan.resCost == null || plan.resCost.isEmpty()) {
                    player.sendMessage(new TextComponentString("    (resCost empty)"));
                } else {
                    player.sendMessage(new TextComponentString("    resCost:"));
                    for (Map.Entry<InvItem, Integer> e : plan.resCost.entrySet()) {
                        InvItem ii = e.getKey();
                        int req = e.getValue();
                        if (ii == null || ii.getItem() == null || req <= 0) continue;

                        int inVillage = townHall.nbGoodAvailable(ii, true, false, false);
                        int missing = Math.max(0, req - inVillage);

                        player.sendMessage(new TextComponentString(
                                "      - " + ii.getName()
                                        + " (meta " + ii.meta + "): needed=" + req
                                        + ", available=" + inVillage
                                        + ", missing=" + missing
                        ));
                    }
                }
            }
        }

        // --- Aggregated wall requirements -----------------------------------------
        player.sendMessage(new TextComponentString("--- Combined wall requirements (projects) ---"));
        Map<InvItem, Integer> wallReq = computeWallRequirements(townHall);
        if (wallReq.isEmpty()) {
            player.sendMessage(new TextComponentString("  No wall plans with resCost found."));
        } else {
            for (Map.Entry<InvItem, Integer> e : wallReq.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                int inVillage = townHall.nbGoodAvailable(ii, true, false, false);
                int missing = Math.max(0, req - inVillage);

                player.sendMessage(new TextComponentString(
                        "  * " + ii.getName()
                                + " (meta " + ii.meta + "): totalReq=" + req
                                + ", available=" + inVillage
                                + ", missing=" + missing
                ));
            }
        }
    }

    /**
     * Returns TOTAL required items for active *building* constructions
     * (current goal + ConstructionIP plans). This matches the logic in
     * your /villageneeds "Combined construction requirements (CIPs)" view,
     * but also includes the current goal plan.
     * <p>
     * The map values are "total required", not "missing" – the caller is
     * expected to subtract chest contents.
     */
    public static Map<InvItem, Integer> computeActiveConstructionRequirements(Building townHall) {
        Map<InvItem, Integer> needs = new LinkedHashMap<>();
        if (townHall == null) return needs;

        Map<InvItem, Integer> allocated = new HashMap<>();

        // 1) Current building goal plan (even if there is no CIP yet)
        BuildingPlan goal = townHall.getCurrentGoalBuildingPlan();
        if (goal != null && goal.resCost != null && !goal.resCost.isEmpty()) {
            accumulatePlanConstructionNeeds(townHall, goal, needs, allocated);
        }

        // 2) Constructions in progress (CIPs) – both build and wall CIPs might show up here,
        // but we treat them as normal construction for "building" requirements.
        List<ConstructionIP> cips = townHall.getConstructionsInProgress();
        if (cips != null) {
            for (ConstructionIP cip : cips) {
                if (cip == null) continue;
                BuildingLocation bl = cip.getBuildingLocation();
                BuildingPlan plan = (bl != null) ? bl.getPlan() : null;
                if (plan == null || plan.resCost == null || plan.resCost.isEmpty()) {
                    continue;
                }
                accumulatePlanConstructionNeeds(townHall, plan, needs, allocated);
            }
        }

        return needs;
    }

    /**
     * Returns TOTAL required items for wall projects (based on BuildingProject list),
     * like your /villageneeds "Combined wall requirements (projects)" section.
     * <p>
     * Again, values are "total required"; caller subtracts what’s already in chests.
     */
    public static Map<InvItem, Integer> computeWallRequirements(Building townHall) {
        Map<InvItem, Integer> needs = new LinkedHashMap<>();
        if (townHall == null) return needs;

        List<BuildingProject> projects = townHall.getFlatProjectList();
        if (projects == null || projects.isEmpty()) {
            return needs;
        }

        Map<InvItem, Integer> allocated = new HashMap<>();

        for (BuildingProject project : projects) {
            if (project == null) continue;

            BuildingPlan initial = project.getPlan(0, 0);
            BuildingPlan next = project.getNextBuildingPlan(false);

            boolean initialWall = initial != null && initial.isWallSegment;
            boolean nextWall = next != null && next.isWallSegment;

            // same heuristic as your /villageneeds: consider wall projects only
            if (!initialWall && !nextWall) {
                continue;
            }

            BuildingPlan planForNeeds;
            if (nextWall) {
                planForNeeds = next;
            } else {
                planForNeeds = initial;
            }

            if (planForNeeds == null || planForNeeds.resCost == null || planForNeeds.resCost.isEmpty()) {
                continue;
            }

            accumulatePlanConstructionNeeds(townHall, planForNeeds, needs, allocated);
        }

        return needs;
    }

    // A wandering worker's gift to a village's construction needs, loaded or virtual alike: works on
    // Millénaire's in-memory building state only, no block access.
    public static void donateToConstruction(Building townHall) {
        if (townHall == null) return;

        Map<InvItem, Integer> reqMap = computeActiveConstructionRequirements(townHall);
        Map<InvItem, Integer> wallReq = computeWallRequirements(townHall);
        for (Map.Entry<InvItem, Integer> e : wallReq.entrySet()) {
            reqMap.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        if (reqMap.isEmpty()) return;

        int maxDistinctItems = Math.max(1, TravellersConfig.MERCHANT.merchantMaxDistinctItems);
        double giveFraction = TravellersConfig.MERCHANT.merchantGiveFraction > 0
                ? TravellersConfig.MERCHANT.merchantGiveFraction : 1.0 / 12.0;
        int minBulkAmount = Math.max(1, TravellersConfig.MERCHANT.merchantMinBulkAmount);
        int minSmallAmount = Math.max(1, TravellersConfig.MERCHANT.merchantMinSmallAmount);

        int donationsDone = 0;
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

            int maxStack = new ItemStack(item, 1, meta).getMaxStackSize();
            boolean smallStack = maxStack <= 16;
            int slice = Math.max(1, (int) Math.floor(totalReq * giveFraction));
            int minAmount = smallStack ? minSmallAmount : minBulkAmount;
            int toGive = Math.min(missing, Math.max(slice, minAmount));
            if (toGive <= 0) continue;

            int stored = townHall.storeGoods(item, meta, toGive);
            if (stored > 0) {
                donationsDone++;
                MillLog.major(townHall, "[Travellers|VirtualDonation] donated " + stored + "x "
                        + ii.getName() + " (meta " + meta + ") to " + townHall.getGameBuildingName()
                        + " at " + townHall.getPos() + " (missing=" + missing + ")");
            }
        }

        if (donationsDone == 0) {
            MillLog.major(townHall, "[Travellers|VirtualDonation] nothing to donate at "
                    + townHall.getGameBuildingName() + " " + townHall.getPos());
        }
    }

    /**
     * For a given plan, compute what is still missing in storage
     * based purely on resCost and what we’ve already allocated
     * for previous plans.
     * <p>
     * `needs` gets TOTAL required per InvItem (like your /villageneeds dump),
     * `allocated` is used to not double-count goods already “claimed” by earlier plans.
     */
    public static void accumulatePlanConstructionNeeds(
            Building townHall,
            BuildingPlan plan,
            Map<InvItem, Integer> needs,
            Map<InvItem, Integer> allocated
    ) {
        if (plan == null || plan.resCost == null || plan.resCost.isEmpty()) {
            return;
        }

        for (Map.Entry<InvItem, Integer> e : plan.resCost.entrySet()) {
            InvItem key = e.getKey();
            int required = e.getValue();
            if (required <= 0 || key == null || key.getItem() == null) {
                continue;
            }

            int totalHave = townHall.countGoods(key.getItem(), key.meta);
            int alreadyAllocated = allocated.getOrDefault(key, 0);

            // what’s still “available” in chests after previous plans took their share
            int usableForThisPlan = Math.max(totalHave - alreadyAllocated, 0);
            int missingForThisPlan = Math.max(required - usableForThisPlan, 0);

            // `needs` tracks TOTAL required across plans (just like /villageneeds' totalReq)
            needs.merge(key, required, Integer::sum);

            // mark what we consider used by this plan
            int usedNow = Math.min(required, usableForThisPlan);
            if (usedNow > 0) {
                allocated.put(key, alreadyAllocated + usedNow);
            }
        }
    }
}


