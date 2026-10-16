package com.jubitus.travellers.follow;

import com.jubitus.travellers.battle.VirtualCombatProfile;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A traveller/raider group outlives its leader: the best-equipped member (armour + weapon) takes the lead
 * and the leader's trip, and every other member follows it. Hired-worker groups: {@code WorkerGroupService}.
 */
public final class GroupSuccession {

    private GroupSuccession() {
    }

    /** A loaded leader is dying: its trip is still readable. No-op when nobody follows it. */
    public static void onLeaderDied(WorldServer world, EntityTraveller leader) {
        if (leader instanceof EntityTravellerWorker || leader.getGroupLeaderUUID() != null) return;
        List<BlockPos> stops = leader.getTravelRoute();
        int index = leader.getVillageRouteIndex();
        if (stops.isEmpty() || index < 0 || index >= stops.size()) {
            BlockPos target = leader.getTravelCommon().getTargetVillage();
            stops = target != null ? Collections.singletonList(target) : Collections.emptyList();
            index = 0;
        }
        elect(world, leader.getUniqueID(), stops, index);
    }

    /** A virtual leader is dying (offscreen battle): its record is still present. */
    public static void onLeaderDied(WorldServer world, VirtualTravellerRecord leader) {
        if ("WORKER".equals(leader.entityClass) || leader.groupLeaderUUID != null) return;
        elect(world, leader.uuid, leader.tripStops, leader.tripIndex);
    }

    /**
     * Fallback for a follower that finds its leader gone without a death hook (killed by a command, record
     * removed): elects a successor, who picks a new destination. Returns the caller's leader afterwards (null when
     * the caller itself now leads).
     */
    @Nullable
    public static UUID onLeaderMissing(WorldServer world, UUID goneLeaderId, UUID callerId) {
        // Alive in another dimension: not gone, the caller just unlinks as before.
        if (world.getMinecraftServer() != null) {
            for (WorldServer ws : world.getMinecraftServer().worlds) {
                net.minecraft.entity.Entity e = ws.getEntityFromUuid(goneLeaderId);
                if (e != null && !e.isDead) return null;
            }
        }
        UUID successor = elect(world, goneLeaderId, Collections.emptyList(), -1);
        return successor == null || successor.equals(callerId) ? null : successor;
    }

    /** Elects and applies; returns the new leader, or null when the group had no member left. */
    @Nullable
    private static UUID elect(WorldServer world, UUID goneLeaderId, List<BlockPos> tripStops, int tripIndex) {
        Map<UUID, EntityTraveller> loaded = new HashMap<>();
        for (EntityTraveller t : world.getEntities(EntityTraveller.class, e -> e != null && !e.isDead
                && !(e instanceof EntityTravellerWorker) && goneLeaderId.equals(e.getGroupLeaderUUID()))) {
            loaded.put(t.getUniqueID(), t);
        }
        Map<UUID, VirtualTravellerRecord> virtual = new HashMap<>();
        int dim = world.provider.getDimension();
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(world).getRecordsView()) {
            if (r.dimension == dim && !"WORKER".equals(r.entityClass) && goneLeaderId.equals(r.groupLeaderUUID)) {
                virtual.put(r.uuid, r);
            }
        }
        if (loaded.isEmpty() && virtual.isEmpty()) return null;

        Map<UUID, Double> scores = new HashMap<>();
        for (EntityTraveller t : loaded.values()) scores.put(t.getUniqueID(), gearScore(t));
        for (VirtualTravellerRecord r : virtual.values()) scores.put(r.uuid, gearScore(r));
        UUID successor = pickSuccessor(scores);

        boolean hasTrip = tripIndex >= 0 && tripIndex < tripStops.size();
        EntityTraveller newLeader = loaded.get(successor);
        if (newLeader != null) {
            // Catchup toward the dead leader would wipe the route below when it ends: stop it first.
            if (newLeader.isCatchupTravel()) newLeader.catchup.stop();
            newLeader.setGroupLeaderUUID(null);
            // Without the leader's trip, a follower's own one is the stale copy from its spawn: pick afresh.
            if (hasTrip) newLeader.restoreVirtualRoute(tripStops, tripIndex);
            else newLeader.getTravelCommon().clearRoute(true);
            newLeader.getItineraryState().clear();
            TravellerTracer.event(newLeader, "follow", "group succession: now leads the group of " + goneLeaderId);
        } else {
            VirtualTravellerRecord r = virtual.get(successor);
            r.groupLeaderUUID = null;
            r.setTrip(hasTrip ? tripStops : Collections.emptyList(), tripIndex);
            r.goal = r.currentTripStop();
            r.itinerary.clear();
        }
        for (EntityTraveller t : loaded.values()) {
            if (t != newLeader) t.setGroupLeaderUUID(successor);
        }
        for (VirtualTravellerRecord r : virtual.values()) {
            if (!r.uuid.equals(successor)) r.groupLeaderUUID = successor;
        }
        VirtualTravellerManager.get(world).markDirty();
        return successor;
    }

    /** Armour + melee damage, read the same way for a loaded unit (its NBT) and a virtual one. */
    public static double gearScore(EntityTraveller unit) {
        NBTTagCompound nbt = new NBTTagCompound();
        unit.writeToNBT(nbt);
        String entityClass = unit instanceof EntityTravellerWorker ? "WORKER"
                : unit instanceof EntityTravellerRaider ? "RAIDER" : "TRAVELLER";
        return gearScore(nbt, entityClass);
    }

    public static double gearScore(VirtualTravellerRecord r) {
        return r.entityNbt == null ? 0.0D : gearScore(r.entityNbt, r.entityClass);
    }

    private static double gearScore(NBTTagCompound entityNbt, String entityClass) {
        VirtualCombatProfile p = VirtualCombatProfile.fromEntityNbt(entityNbt, entityClass);
        return p.armor + p.toughness + p.meleeDamage;
    }

    /** Highest score wins; ties go to the smallest UUID so every caller elects the same unit. */
    public static UUID pickSuccessor(Map<UUID, Double> scores) {
        List<UUID> ids = new ArrayList<>(scores.keySet());
        Collections.sort(ids);
        UUID best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (UUID id : ids) {
            double s = scores.get(id);
            if (s > bestScore) {
                bestScore = s;
                best = id;
            }
        }
        return best;
    }
}
