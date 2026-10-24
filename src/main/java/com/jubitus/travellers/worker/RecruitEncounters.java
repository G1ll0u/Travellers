package com.jubitus.travellers.worker;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.profile.MovementProfiler;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.UnitWorth;
import com.jubitus.travellers.unit.VirtualizationGate;
import com.jubitus.travellers.unit.WorkerPurse;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.village.VillageVisitIndex;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A recruiter meeting the people around it, loaded or offscreen alike (positions only): on the road a
 * small circle around it, while it waits in a village the whole village. Each unit is asked once per
 * tour; a group answers as one.
 */
public final class RecruitEncounters {

    private static final double ROAD_RADIUS = 24.0D;
    /** How far a road recruit's notice looks for a village to name the place after. */
    private static final int NAMED_PLACE_RADIUS = 3000;

    private RecruitEncounters() {
    }

    /** Regularly, from RecruitMission (loaded) and VirtualWorkerMissions (offscreen); each unit is asked once, so the rate only decides who is noticed. */
    public static void tick(WorldServer ws, RecruitTour tour, WorkerPurse purse, UUID recruiterId, @Nullable UUID ownerId,
                            double x, double z) {
        tour.forgetDead(id -> whereAlive(ws, id));
        if (ownerId == null || tour.isBack() || tour.isComplete() || purse.get() <= 0) return;
        EntityPlayerMP owner = ws.getMinecraftServer().getPlayerList().getPlayerByUUID(ownerId);
        if (owner == null) return;

        double cx = x, cz = z, radius = ROAD_RADIUS;
        boolean inVillage = false;
        RecruitPlan.Stop stop = tour.currentStop();
        if (tour.isStaying() && stop != null) {
            VillageVisitIndex.Settlement s = VillageVisitIndex.get(ws, stop.center);
            if (s != null) {
                cx = s.center.getX() + 0.5D;
                cz = s.center.getZ() + 0.5D;
                radius = Math.max(ROAD_RADIUS, s.radius);
                inVillage = true;
            }
        }
        double rSq = radius * radius;

        // Collected first: a join changes both the record map and the world's entities.
        List<SpawnGroup> met = new ArrayList<>();
        int dim = ws.provider.getDimension();
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(ws).getRecordsView()) {
            if (r.dimension != dim || tour.wasAsked(r.uuid) || !RecruitCultures.isCandidate(ws, r)) continue;
            if (distSq(cx, cz, r.posX, r.posZ) <= rSq) met.add(SpawnGroup.of(ws, r));
        }
        AxisAlignedBB box = new AxisAlignedBB(cx - radius, 0.0D, cz - radius, cx + radius, 256.0D, cz + radius);
        for (EntityTraveller e : ws.getEntitiesWithinAABB(EntityTraveller.class, box)) {
            if (tour.wasAsked(e.getUniqueID()) || !RecruitCultures.isCandidate(ws, e)) continue;
            if (distSq(cx, cz, e.posX, e.posZ) <= rSq) met.add(SpawnGroup.of(ws, e));
        }
        for (SpawnGroup g : met) {
            if (tour.isComplete()) return;
            ask(ws, tour, purse, g, owner, recruiterId, inVillage);
        }
    }

    private static void ask(WorldServer ws, RecruitTour tour, WorkerPurse purse, SpawnGroup g, EntityPlayerMP owner, UUID recruiterId,
                            boolean inVillage) {
        List<UUID> ids = new ArrayList<>();
        Map<String, Integer> byCulture = new HashMap<>();
        String leadCulture = null;
        // Busy right now (fighting, mid-air, aboard): not asked yet, it may come by again.
        for (EntityTraveller e : g.loaded) {
            if (e.isInCombat() || e.getAttackTarget() != null || !VirtualizationGate.mayVirtualize(e)) return;
            ids.add(e.getUniqueID());
            byCulture.merge(e.getCultureId(), 1, Integer::sum);
            if (leadCulture == null) leadCulture = e.getCultureId();
        }
        for (VirtualTravellerRecord r : g.virtual) {
            if (r.battleId != null) return;
            ids.add(r.uuid);
            byCulture.merge(r.cultureId, 1, Integer::sum);
            if (leadCulture == null) leadCulture = r.cultureId;
        }
        if (ids.isEmpty() || ids.stream().anyMatch(tour::wasAsked)) return;
        ids.forEach(tour::markAsked);

        for (Map.Entry<String, Integer> e : byCulture.entrySet()) {
            if (e.getKey() == null || tour.missing(e.getKey()) < e.getValue()
                    || RecruitCultures.verdict(ws, owner.getUniqueID(), e.getKey()) != RecruitCultures.Verdict.OK) {
                MovementProfiler.recruitAsk(e.getKey() == null || tour.missing(e.getKey()) == 0 ? "culture" : "quota");
                return;
            }
        }
        if (g.hirePrice() > purse.get()) {
            MovementProfiler.recruitAsk("purse");
            return;
        }
        int chance = RecruitCultures.chancePercent(ws, owner.getUniqueID(), leadCulture);
        if (!inVillage) chance = chance * TravellersConfig.RECRUITMENT.recruitRoadChancePercent / 100;
        if (ws.rand.nextInt(100) >= chance) {
            MovementProfiler.recruitAsk(inVillage ? "refused in village" : "refused on road");
            return;
        }

        List<UUID> joined = join(ws, tour, purse, g, owner, inVillage);
        if (joined.isEmpty()) return;
        MovementProfiler.recruitAsk(inVillage ? "joined in village" : "joined on road");
        if (!inVillage) tour.log().joinedOnRoad(groupPos(g), joined.size());
        WorkerGroupService.joinRecruiter(owner, joined, recruiterId);
        BlockPos at = groupPos(g);
        MillSettlements.Settlement place = inVillage ? MillSettlements.at(ws, tour.currentStop().center)
                : MillSettlements.nearestVillage(ws, at, NAMED_PLACE_RADIUS);
        String placeName = place != null ? place.displayName() : at.getX() + "/" + at.getZ();
        NotificationService.notifyAt(owner, inVillage ? NotificationDefs.RECRUIT_WORKERS_RECRUITED : NotificationDefs.RECRUIT_WORKERS_RECRUITED_ROAD,
                new NoticePlace(at, ws.provider.getDimension(), recruiterId), String.valueOf(joined.size()), placeName);
    }

    /** Each member becomes a hired worker of the owner, paid from the purse; returns who actually joined. */
    private static List<UUID> join(WorldServer ws, RecruitTour tour, WorkerPurse purse, SpawnGroup g, EntityPlayerMP owner,
                                   boolean inVillage) {
        VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
        long now = ws.getTotalWorldTime();
        List<UUID> joined = new ArrayList<>();
        for (EntityTraveller e : g.loaded) {
            int price = UnitWorth.hirePrice(e);
            String culture = e.getCultureId();
            UUID id = e.getUniqueID();
            BlockPos at = e.getPosition();
            if (e instanceof EntityTravellerWorker) {
                e.setGroupLeaderUUID(null);
                ((EntityTravellerWorker) e).hireAsFollower(owner);
            } else {
                // A traveller changes kind through its record: the hand-off already carries its horse and
                // every piece of state, and it reappears as a worker right away.
                if (!mgr.virtualizeTraveller(ws, e, now)) continue;
                VirtualTravellerRecord r = mgr.getRecord(id);
                if (r == null) continue;
                mgr.recruitRecord(ws, r, owner);
                mgr.materializeSoon(id);
            }
            purse.spend(price);
            tour.addRecruit(id, culture, at, inVillage, price);
            joined.add(id);
        }
        for (VirtualTravellerRecord r : g.virtual) {
            int price = UnitWorth.hirePrice(r);
            mgr.recruitRecord(ws, r, owner);
            purse.spend(price);
            tour.addRecruit(r.uuid, r.cultureId, new BlockPos(r.posX, r.posY, r.posZ), inVillage, price);
            joined.add(r.uuid);
        }
        return joined;
    }

    private static BlockPos groupPos(SpawnGroup g) {
        return g.loaded.isEmpty() ? new BlockPos(g.virtual.get(0).posX, g.virtual.get(0).posY, g.virtual.get(0).posZ)
                : g.loaded.get(0).getPosition();
    }

    // Loaded or offscreen in any world; a body mid hand-off is dead but its record already exists.
    @Nullable
    private static BlockPos whereAlive(WorldServer ws, UUID id) {
        for (WorldServer w : ws.getMinecraftServer().worlds) {
            VirtualTravellerRecord r = VirtualTravellerManager.get(w).getRecord(id);
            if (r != null) return new BlockPos(r.posX, r.posY, r.posZ);
            net.minecraft.entity.Entity e = w.getEntityFromUuid(id);
            if (e != null && !e.isDead) return e.getPosition();
        }
        return null;
    }

    private static double distSq(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }
}
