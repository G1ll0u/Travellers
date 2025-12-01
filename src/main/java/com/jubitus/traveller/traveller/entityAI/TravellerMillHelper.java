package com.jubitus.traveller.traveller.entityAI;

import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.village.BuildingProject;
import org.millenaire.common.village.ConstructionIP;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TravellerMillHelper {

    private TravellerMillHelper() {}

    /**
     * Returns a map of total required resources for all constructions in progress
     * (buildings + walls) keyed by InvItem.
     *
     * Value = sum of plan.resCost over all ConstructionIP with non-null plan.
     */
    public static Map<InvItem, Integer> computeConstructionRequirements(Building townHall) {
        Map<InvItem, Integer> totalRequired = new LinkedHashMap<>();

        List<ConstructionIP> cips = townHall.getConstructionsInProgress();
        for (ConstructionIP cip : cips) {
            if (cip == null) continue;
            BuildingLocation bl = cip.getBuildingLocation();
            if (bl == null) continue;
            BuildingPlan plan = bl.getPlan();
            if (plan == null || plan.resCost == null) continue;

            for (Map.Entry<InvItem, Integer> e : plan.resCost.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                if (ii == null || ii.getItem() == null || req <= 0) continue;
                totalRequired.merge(ii, req, Integer::sum);
            }
        }

        return totalRequired;
    }

    /**
     * Active constructions only (buildings + walls that already have a ConstructionIP)
     */
    public static Map<InvItem, Integer> computeActiveConstructionRequirements(Building townHall) {
        Map<InvItem, Integer> totalRequired = new LinkedHashMap<>();

        List<ConstructionIP> cips = townHall.getConstructionsInProgress();
        for (ConstructionIP cip : cips) {
            if (cip == null) continue;
            BuildingLocation bl = cip.getBuildingLocation();
            if (bl == null) continue;
            BuildingPlan plan = bl.getPlan();
            if (plan == null || plan.resCost == null) continue;

            for (Map.Entry<InvItem, Integer> e : plan.resCost.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                if (ii == null || ii.getItem() == null || req <= 0) continue;
                totalRequired.merge(ii, req, Integer::sum);
            }
        }

        return totalRequired;
    }

    /**
     * Wall requirements based on the *next* wall plans (not only what is already in ConstructionIP).
     *
     * We approximate Millénaire’s “Resources needed for walls” by:
     *   - iterating all projects in getFlatProjectList()
     *   - taking project.getNextBuildingPlan(false)
     *   - keeping only plans where plan.isWallSegment == true
     *   - summing their resCost
     */
    public static Map<InvItem, Integer> computeWallRequirements(Building townHall) {
        Map<InvItem, Integer> totalRequired = new LinkedHashMap<>();

        List<BuildingProject> projects = townHall.getFlatProjectList();
        if (projects == null || projects.isEmpty()) {
            return totalRequired;
        }

        for (BuildingProject project : projects) {
            if (project == null) continue;

            // This is exactly how computeCurrentWallLevel() probes the next wall plan
            BuildingPlan next = project.getNextBuildingPlan(false);
            if (next == null || !next.isWallSegment || next.resCost == null) {
                continue;
            }

            for (Map.Entry<InvItem, Integer> e : next.resCost.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                if (ii == null || ii.getItem() == null || req <= 0) continue;

                totalRequired.merge(ii, req, Integer::sum);
            }
        }

        return totalRequired;
    }

    public static String formatDonationLine(InvItem ii, int totalReq, int inVillage, int missing, int donate) {
        return String.format(
                "%s (meta %d): totalReq=%d, chest=%d, missing=%d, donate=%d",
                ii.getName(), ii.meta, totalReq, inVillage, missing, donate
        );
    }
}



