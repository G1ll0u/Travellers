package com.jubitus.travellers.virtual;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.stay.EntityAIStay;
import com.jubitus.travellers.stay.StayOccupancy;
import com.jubitus.travellers.stay.StayPlace;
import com.jubitus.travellers.stay.StayState;
import com.jubitus.travellers.village.VillageVisitIndex;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/** Stays of offscreen records (see StayState; the loaded side is EntityAIStay). */
final class VirtualStays {

    private final VirtualTravellerManager manager;
    private final Map<UUID, VirtualTravellerRecord> records;

    VirtualStays(VirtualTravellerManager manager, Map<UUID, VirtualTravellerRecord> records) {
        this.manager = manager;
        this.records = records;
    }

    private static boolean visitsEnabledFor(VirtualTravellerRecord r) {
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        if ("RAIDER".equals(r.entityClass)) return cfg.enabledRaiders;
        if ("WORKER".equals(r.entityClass)) return cfg.enabledWorkers && !r.isHiredFollower;
        return cfg.enabledTravellers;
    }

    /** Starts a stay once the record is inside the stop's arrival radius, like the loaded arrival. */
    boolean tryStart(WorldServer world, VirtualTravellerRecord r, BlockPos stop, long now) {
        if (!visitsEnabledFor(r) || com.jubitus.travellers.raid.RaidWorldData.isRaider(world, r.uuid)) return false;
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        double dx = stop.getX() + 0.5 - r.posX;
        double dz = stop.getZ() + 0.5 - r.posZ;
        double d2 = dx * dx + dz * dz;
        int reach = Math.max(cfg.arrivalRadius, cfg.loneBuildingArrivalRadius);
        if (d2 > (double) reach * reach) return false;
        if (r.entityNbt != null && r.entityNbt.hasKey("VisitedStop")
                && BlockPos.fromLong(r.entityNbt.getLong("VisitedStop")).equals(stop)) return false;
        StayPlace place = StayPlace.at(world, stop);
        if (place == null) return false;
        int arrive = place.arrivalRadius();
        if (d2 > (double) arrive * arrive) return false;

        startStay(world, r, new StayState(stop, place.rollStayTicks(world.rand), false), place, now);
        return true;
    }

    private void startStay(WorldServer world, VirtualTravellerRecord r, StayState st, StayPlace place, long now) {
        if (place.settlement() != null) {
            com.jubitus.travellers.debug.profile.MovementProfiler.villageVisit(world, r.uuid, st.stop, place.isLone(), r.entityClass,
                    r.cultureId, st.asFollower, r.mounted, true);
        }
        if (r.mounted && r.mountNbt != null && TravellersConfig.MOUNTS.hitchMountInVillages
                && (r.mountMode == null || "MOUNTED".equals(r.mountMode))) {
            // Tied where the grounds begin, on the side it came from, like the loaded hitch.
            st.hitchPos = edgePostFor(world, place, r.posX, r.posY, r.posZ);
            r.mountMode = MountMode.HITCHED.name();
            if (r.entityNbt != null) r.entityNbt.setLong("VillageHitchStop", st.stop.toLong());
        }
        r.visit = st;
        r.visitClockTick = now;
        pickVirtualSpot(world, r, place, now);
        manager.markDirty();
    }

    /** The point on the place's edge between its centre and (x, z), clear of any building. */
    private static BlockPos edgePostFor(WorldServer world, StayPlace place, double x, double y, double z) {
        double cx = place.center.getX() + 0.5, cz = place.center.getZ() + 0.5;
        double dx = x - cx, dz = z - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) {
            dx = 1.0;
            len = 1.0;
        }
        double edge = place.radius;
        for (int extra = 0; extra <= 8; extra += 2) {
            double px = cx + dx / len * (edge + extra), pz = cz + dz / len * (edge + extra);
            if (!com.jubitus.travellers.village.MillFootprints.isForbiddenLanding(world, px, y, pz)) {
                return new BlockPos(px, y, pz);
            }
        }
        return new BlockPos(cx + dx / len * edge, y, cz + dz / len * edge);
    }

    /**
     * The offscreen stay: every few seconds the record moves on to another spot of the village (the
     * entrance of a building it visits or of a street it strolls, quieter ones preferred, a little
     * apart from the others), walked there by advanceVirtual. At the end it goes back to its horse's
     * post, a worker leaves its gift, and the trip moves on. A follower's stay lasts its leader's.
     */
    void tick(WorldServer world, VirtualTravellerRecord r, long now) {
        StayState st = r.visit;
        StayPlace place = StayPlace.at(world, st.stop);
        if (place == null) {
            endVirtualVisit(world, r, now);
            return;
        }
        // Capped: a paused simulation (empty server) must not end every stay in one step.
        long elapsed = r.visitClockTick < 0 ? 0 : Math.min(200L, Math.max(0, now - r.visitClockTick));
        r.visitClockTick = now;
        if (st.asFollower) {
            if (!st.isLeaving() && leaderStayStop(world, r.groupLeaderUUID) == null) st.remainingTicks = 0;
        } else if (!st.isLeaving()) {
            st.remainingTicks -= (int) Math.min(Integer.MAX_VALUE, elapsed);
        }

        if (!st.isLeaving()) {
            if (now >= st.nextStepTick) pickVirtualSpot(world, r, place, now);
            return;
        }

        // Leaving: back to the horse first.
        st.spot = null;
        st.facing = null;
        if (st.hitchPos != null) {
            r.goal = st.hitchPos;
            double dx = st.hitchPos.getX() + 0.5 - r.posX;
            double dz = st.hitchPos.getZ() + 0.5 - r.posZ;
            if (dx * dx + dz * dz > 4.0) return;
            if (st.horseAtPost && !reclaimHorseAtPost(world, r, st, now)) return;
        }
        endVirtualVisit(world, r, now);
    }

    /**
     * The horse is still standing at its post (its rider went offscreen while it was there). It is
     * never made to vanish in view: the record waits here until the post's chunk unloads (the horse
     * then becomes the record's data) or the record itself reappears here. Past the timeout it leaves
     * without it - the bond stays, and the horse joins it back as data when its chunk is next loaded.
     */
    private boolean reclaimHorseAtPost(WorldServer world, VirtualTravellerRecord r,
                                       StayState st, long now) {
        if (r.mountNbt != null) {
            st.horseAtPost = false;
            return true;
        }
        if (st.waitingForHorseSince < 0) st.waitingForHorseSince = now;
        boolean timedOut = now - st.waitingForHorseSince >= TravellersConfig.VILLAGE_VISIT.hitchReclaimTimeoutSeconds * 20L;
        UUID horseId = r.entityNbt != null && r.entityNbt.hasUniqueId("MountUUID") ? r.entityNbt.getUniqueId("MountUUID") : null;
        Entity horse = horseId == null ? null : world.getEntityFromUuid(horseId);
        if (horse == null || horse.isDead) {
            if (!timedOut) return false;
            st.horseAtPost = false;
            return true;
        }
        // Frozen ground is still drawn but nothing there moves: a last resort, after the timeout.
        if (timedOut && VirtualTravellerManager.isFrozenByVanillaAt(world, horse.posX, horse.posZ)
                && horse instanceof net.minecraft.entity.passive.EntityHorse
                && ((net.minecraft.entity.passive.EntityHorse) horse).getHealth() > 0.0F) {
            NBTTagCompound nbt = com.jubitus.travellers.horse.HorseSnapshots.capture((net.minecraft.entity.passive.EntityHorse) horse);
            horse.setDead();
            r.mountNbt = nbt;
            st.horseAtPost = false;
            return true;
        }
        return false;
    }

    private void endVirtualVisit(WorldServer world, VirtualTravellerRecord r, long now) {
        StayState st = r.visit;
        drop(world, r);
        // A follower's own trip isn't what brought it here; it simply goes back to following.
        if (st == null || st.asFollower) return;
        if ("WORKER".equals(r.entityClass)) manager.tryVirtualDonate(world, r);
        manager.advanceTrip(world, r, now);
    }

    /** Ends the stay without its departure effects (gift, next trip stop). */
    void drop(WorldServer world, VirtualTravellerRecord r) {
        r.visit = null;
        r.visitClockTick = -1L;
        StayOccupancy.release(world, r.uuid);
        if ("HITCHED".equals(r.mountMode)) r.mountMode = MountMode.MOUNTED.name();
        if (r.entityNbt != null) r.entityNbt.removeTag("VillageHitchStop");
        manager.markDirty();
    }

    /** Draws the record's next spot in the village, the same way the loaded visit draws its targets. */
    private void pickVirtualSpot(WorldServer world, VirtualTravellerRecord r, StayPlace place, long now) {
        VillageVisitIndex.Settlement s = place.settlement();
        StayState st = r.visit;
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        java.util.Random rng = world.rand;
        int step = cfg.virtualStepSeconds * 20;
        st.nextStepTick = now + step / 2 + rng.nextInt(step + 1);

        boolean streetsOnly = "RAIDER".equals(r.entityClass) && !cfg.raidersVisitBuildings;
        java.util.List<BlockPos> spots = new java.util.ArrayList<>();
        java.util.List<BlockPos> faces = new java.util.ArrayList<>();
        java.util.List<Double> weights = new java.util.ArrayList<>();
        double wStroll = Math.max(0, cfg.weightStroll);
        double wBuilding = streetsOnly ? 0 : Math.max(0, cfg.weightBuilding);
        double wPanel = streetsOnly || !cfg.visitPanels ? 0 : Math.max(0, cfg.weightPanel);
        if (s != null && !s.buildings.isEmpty() && wBuilding > 0) {
            for (VillageVisitIndex.Target t : s.buildings) {
                addSpot(world, r, t.entrance, t.point, wBuilding / s.buildings.size(), spots, faces, weights);
            }
        }
        if (s != null && !s.panelGroups.isEmpty() && wPanel > 0) {
            for (java.util.List<VillageVisitIndex.Target> g : s.panelGroups) {
                VillageVisitIndex.Target t = g.get(rng.nextInt(g.size()));
                addSpot(world, r, t.entrance, t.point, wPanel / s.panelGroups.size(), spots, faces, weights);
            }
        }
        java.util.List<BlockPos> anchors = place.anchors();
        if (!anchors.isEmpty() && (wStroll > 0 || spots.isEmpty())) {
            double w = Math.max(wStroll, 1.0) / anchors.size();
            for (BlockPos e : anchors) addSpot(world, r, e, null, w, spots, faces, weights);
        }
        if (spots.isEmpty()) {
            st.spot = place.center;
            st.facing = null;
            r.goal = st.spot;
            return;
        }
        double total = 0;
        for (double w : weights) total += w;
        double roll = rng.nextDouble() * total;
        int pick = spots.size() - 1;
        for (int i = 0; i < spots.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) {
                pick = i;
                break;
            }
        }
        // A little apart from anyone else standing at the same entrance.
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double radius = 1.5 + rng.nextDouble() * 1.5;
        BlockPos base = spots.get(pick);
        st.spot = new BlockPos(base.getX() + Math.cos(angle) * radius, base.getY(), base.getZ() + Math.sin(angle) * radius);
        st.facing = faces.get(pick);
        r.goal = st.spot;
        StayOccupancy.claim(world, r.uuid, st.spot, step * 2);
    }

    private static void addSpot(WorldServer world, VirtualTravellerRecord r,
                                @Nullable BlockPos entrance, @Nullable BlockPos point, double weight,
                                java.util.List<BlockPos> spots, java.util.List<BlockPos> faces, java.util.List<Double> weights) {
        // Offscreen visitors stand outside: at the entrance, never at an indoor point.
        BlockPos at = entrance;
        if (at == null && point != null && com.jubitus.travellers.village.MillFootprints.at(world,
                point.getX() + 0.5, point.getY(), point.getZ() + 0.5, 0) == null) at = point;
        if (at == null || weight <= 0) return;
        double crowd = StayOccupancy.crowdFactor(world, at, 4.0, r.uuid,
                TravellersConfig.VILLAGE_VISIT.crowdPenalty);
        spots.add(at);
        faces.add(point != null ? point : null);
        weights.add(weight * crowd);
    }

    /** The village a group leader (loaded or offscreen) is staying in right now, or null. */
    @Nullable
    private BlockPos leaderStayStop(WorldServer world, @Nullable UUID leaderId) {
        if (leaderId == null) return null;
        VirtualTravellerRecord leaderRec = records.get(leaderId);
        if (leaderRec != null) {
            StayState lv = leaderRec.visit;
            return lv != null && !lv.isLeaving() && !lv.asFollower ? lv.stop : null;
        }
        Entity live = world.getEntityFromUuid(leaderId);
        if (!(live instanceof EntityTraveller) || live.isDead) return null;
        EntityAIStay v = ((EntityTraveller) live).getStayAI();
        return v != null && v.isStaying() ? v.getStayStop() : null;
    }

    /** A group follower record inside the village its leader stays in visits on its own until the leader leaves. */
    boolean tryStartFollower(WorldServer world, VirtualTravellerRecord r, long now) {
        if (!TravellersConfig.VILLAGE_VISIT.followersDisperse || !visitsEnabledFor(r)
                || com.jubitus.travellers.raid.RaidWorldData.isRaider(world, r.uuid)) return false;
        BlockPos stop = leaderStayStop(world, r.groupLeaderUUID);
        if (stop == null) return false;
        StayPlace place = StayPlace.at(world, stop);
        if (place == null || !place.contains(r.posX, r.posZ)) return false;
        startStay(world, r, new StayState(stop, Integer.MAX_VALUE / 2, true), place, now);
        return true;
    }
}
