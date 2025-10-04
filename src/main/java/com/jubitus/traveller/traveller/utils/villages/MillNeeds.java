package com.jubitus.traveller.traveller.utils.villages;


import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.item.TradeGood;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.ConstructionIP;
import org.millenaire.common.village.VillagerRecord;

public final class MillNeeds {

    /**
     * Convenience: compute needs for all candidates and return only the deficits (>0).
     * If you want “what should my traveller bring here?”, pass (false,false,false) so
     * trade reservations use reservedQuantity (not full target).
     */
    public static java.util.List<ItemNeed> deficitsFor(Building th,
                                                       boolean forConstruction, boolean forExport, boolean forShop) {
        java.util.List<ItemNeed> out = new java.util.ArrayList<>();
        for (InvItem ii : candidateItems(th)) {
            ItemNeed n = statusFor(th, ii, forConstruction, forExport, forShop);
            if (n.neededTotal > 0) out.add(n);
        }
        // optional: sort by biggest shortage first
        out.sort((a, b) -> Integer.compare(b.neededTotal, a.neededTotal));
        return out;
    }

    /**
     * Union of items we should consider: all construction needs (goal + CIPs) + all trade goods.
     */
    public static java.util.Set<InvItem> candidateItems(Building th) {
        java.util.Set<InvItem> set = new java.util.HashSet<>();

        // Current goal
        BuildingPlan project = th.getCurrentGoalBuildingPlan();
        if (project != null) set.addAll(project.resCost.keySet());

        // All constructions in progress
        for (ConstructionIP cip : th.getConstructionsInProgress()) {
            if (cip.getBuildingLocation() != null && cip.getBuildingLocation().getPlan() != null) {
                set.addAll(cip.getBuildingLocation().getPlan().resCost.keySet());
            }
        }

        // Trade goods (things with TradeGood entries)
        for (InvItem ii : th.culture.getInvItemsWithTradeGoods()) {
            set.add(ii);
        }
        return set;
    }

    /**
     * Full status for one InvItem. You choose the context flags depending on your use case:
     * - forConstruction: true if you’re trying to allocate for builders right now
     * - forExport: true if you’re evaluating what can be exported
     * - forShop: true if you’re evaluating shop sales availability
     */
    public static ItemNeed statusFor(Building th, InvItem ii,
                                     boolean forConstruction, boolean forExport, boolean forShop) {

        // 1) On-hand (sum of all resManager chests)
        int onHand = th.countGoods(ii.getItem(), ii.meta);

        // 2) Construction reservations (CIPs + current goal if not handled), minus builder-held
        int reservedConstruction = 0;
        boolean projectHandled = false;
        BuildingPlan currentGoal = th.getCurrentGoalBuildingPlan();

        for (ConstructionIP cip : th.getConstructionsInProgress()) {
            if (cip.getBuildingLocation() == null) continue;
            BuildingPlan plan = cip.getBuildingLocation().getPlan();
            if (plan == null) continue;

            for (InvItem key : plan.resCost.keySet()) {
                if (key.matches(ii)) {
                    int need = plan.resCost.get(key);
                    int builderHas = (cip.getBuilder() != null) ? cip.getBuilder().countInv(key) : 0;
                    int shortfall = Math.max(need - builderHas, 0);
                    reservedConstruction += shortfall;
                }
            }
            if (plan == currentGoal) projectHandled = true;
        }
        if (!projectHandled && currentGoal != null) {
            for (InvItem key : currentGoal.resCost.keySet()) {
                if (key.matches(ii)) {
                    reservedConstruction += currentGoal.resCost.get(key);
                }
            }
        }

        // 3) Trade reservations (reservedQuantity or targetQuantity)
        int reservedTrade = 0;
        TradeGood tg = th.culture.getTradeGood(ii);
        if (tg != null) {
            reservedTrade = forExport ? tg.targetQuantity : tg.reservedQuantity;
        }

        // 4) Upkeep reservations (requiredFoodAndGoods for villagers housed in this TH)
        int reservedUpkeep = 0;
        for (VillagerRecord vr : th.getVillagerRecords().values()) {
            if (vr.getHousePos() != null && vr.getHousePos().equals(th.getPos()) && vr.getType() != null) {
                for (InvItem req : vr.getType().requiredFoodAndGoods.keySet()) {
                    if (ii.matches(req)) {
                        reservedUpkeep += vr.getType().requiredFoodAndGoods.get(req);
                    }
                }
            }
        }

        // 5) “Free now” (same semantics as nbGoodAvailable; clamps to 0)
        int freeNow = th.nbGoodAvailable(ii, forConstruction, forExport, forShop);

        // 6) Needed for current goal only (sum over matching metas)
        int neededForGoal = 0;
        if (currentGoal != null) {
            for (InvItem key : currentGoal.resCost.keySet()) {
                if (key.matches(ii)) {
                    neededForGoal += currentGoal.resCost.get(key);
                }
            }
        }

        // 7) Trade target
        int tradeTarget = (tg != null) ? tg.targetQuantity : 0;

        // 8) “Needed total” (Millénaire’s own nbGoodNeeded = goal + tradeTarget − onHand, clamped)
        int meta = ii.meta;
        int neededTotal = th.nbGoodNeeded(ii.getItem(), meta);

        return new ItemNeed(ii, onHand, reservedConstruction, reservedTrade, reservedUpkeep,
                freeNow, neededForGoal, tradeTarget, neededTotal);
    }

    public static final class ItemNeed {
        public final InvItem item;
        public final int onHand;
        public final int reservedConstruction;
        public final int reservedTrade;
        public final int reservedUpkeep;
        public final int freeNow;       // available now for the chosen context
        public final int neededForGoal; // current goal plan requirement
        public final int tradeTarget;   // culture targetQuantity
        public final int neededTotal;   // nbGoodNeeded(item, meta)

        ItemNeed(InvItem item, int onHand, int reservedConstruction, int reservedTrade,
                 int reservedUpkeep, int freeNow, int neededForGoal, int tradeTarget, int neededTotal) {
            this.item = item;
            this.onHand = onHand;
            this.reservedConstruction = reservedConstruction;
            this.reservedTrade = reservedTrade;
            this.reservedUpkeep = reservedUpkeep;
            this.freeNow = freeNow;
            this.neededForGoal = neededForGoal;
            this.tradeTarget = tradeTarget;
            this.neededTotal = neededTotal;
        }
    }
}

