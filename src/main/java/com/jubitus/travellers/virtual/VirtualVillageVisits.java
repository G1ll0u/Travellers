package com.jubitus.travellers.virtual;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/** Village stays of offscreen records (see VillageVisitState; the loaded side is EntityAIVillageVisit). */
final class VirtualVillageVisits {

    private final VirtualTravellerManager manager;
    private final Map<UUID, VirtualTravellerRecord> records;

    VirtualVillageVisits(VirtualTravellerManager manager, Map<UUID, VirtualTravellerRecord> records) {
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
        if (!visitsEnabledFor(r)) return false;
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        double dx = stop.getX() + 0.5 - r.posX;
        double dz = stop.getZ() + 0.5 - r.posZ;
        double d2 = dx * dx + dz * dz;
        int reach = Math.max(cfg.arrivalRadius, cfg.loneBuildingArrivalRadius);
        if (d2 > (double) reach * reach) return false;
        if (r.entityNbt != null && r.entityNbt.hasKey("VisitedStop")
                && BlockPos.fromLong(r.entityNbt.getLong("VisitedStop")).equals(stop)) return false;
        com.jubitus.travellers.village.VillageVisitIndex.Settlement s =
                com.jubitus.travellers.village.VillageVisitIndex.get(world, stop);
        if (s == null) return false;
        int arrive = s.lone ? cfg.loneBuildingArrivalRadius : cfg.arrivalRadius;
        if (d2 > (double) arrive * arrive) return false;

        int min = s.lone ? cfg.loneBuildingHaltMinSeconds : cfg.stayMinSeconds;
        int max = Math.max(min, s.lone ? cfg.loneBuildingHaltMaxSeconds : cfg.stayMaxSeconds);
        com.jubitus.travellers.village.visit.VillageVisitState st =
                new com.jubitus.travellers.village.visit.VillageVisitState(stop, 20 * (min + world.rand.nextInt(max - min + 1)), false);
        startStay(world, r, st, s, now);
        return true;
    }

    private void startStay(WorldServer world, VirtualTravellerRecord r, com.jubitus.travellers.village.visit.VillageVisitState st,
                           com.jubitus.travellers.village.VillageVisitIndex.Settlement s, long now) {
        if (r.mounted && r.mountNbt != null && TravellersConfig.MOUNTS.hitchMountInVillages
                && (r.mountMode == null || "MOUNTED".equals(r.mountMode))) {
            // Tied where the grounds begin, on the side it came from, like the loaded hitch.
            st.hitchPos = edgePostFor(world, s, r.posX, r.posY, r.posZ);
            r.mountMode = MountMode.HITCHED.name();
            if (r.entityNbt != null) r.entityNbt.setLong("VillageHitchStop", st.stop.toLong());
        }
        r.visit = st;
        r.visitClockTick = now;
        pickVirtualSpot(world, r, s, now);
        manager.markDirty();
    }

    /** The point on the settlement's edge between its centre and (x, z), clear of any building. */
    private static BlockPos edgePostFor(WorldServer world, com.jubitus.travellers.village.VillageVisitIndex.Settlement s,
                                        double x, double y, double z) {
        double cx = s.center.getX() + 0.5, cz = s.center.getZ() + 0.5;
        double dx = x - cx, dz = z - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) {
            dx = 1.0;
            len = 1.0;
        }
        double edge = Math.max(s.radius, TravellersConfig.VILLAGE_VISIT.arrivalRadius);
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
        com.jubitus.travellers.village.visit.VillageVisitState st = r.visit;
        com.jubitus.travellers.village.VillageVisitIndex.Settlement s =
                com.jubitus.travellers.village.VillageVisitIndex.get(world, st.stop);
        if (s == null) {
            endVirtualVisit(world, r, now);
            return;
        }
        // Capped: a paused simulation (empty server) must not end every stay in one step.
        long elapsed = r.visitClockTick < 0 ? 0 : Math.min(200L, Math.max(0, now - r.visitClockTick));
        r.visitClockTick = now;
        if (st.asFollower) {
            if (!st.isLeaving() && leaderVisitStop(world, r.groupLeaderUUID) == null) st.remainingTicks = 0;
        } else if (!st.isLeaving()) {
            st.remainingTicks -= (int) Math.min(Integer.MAX_VALUE, elapsed);
        }

        if (!st.isLeaving()) {
            if (now >= st.nextStepTick) pickVirtualSpot(world, r, s, now);
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
                                       com.jubitus.travellers.village.visit.VillageVisitState st, long now) {
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
        com.jubitus.travellers.village.visit.VillageVisitState st = r.visit;
        r.visit = null;
        r.visitClockTick = -1L;
        com.jubitus.travellers.village.visit.VisitOccupancy.release(world, r.uuid);
        if ("HITCHED".equals(r.mountMode)) r.mountMode = MountMode.MOUNTED.name();
        if (r.entityNbt != null) r.entityNbt.removeTag("VillageHitchStop");
        manager.markDirty();
        // A follower's own trip isn't what brought it here; it simply goes back to following.
        if (st == null || st.asFollower) return;
        if ("WORKER".equals(r.entityClass)) manager.tryVirtualDonate(world, r);
        manager.advanceTrip(world, r, now);
    }

    /** Draws the record's next spot in the village, the same way the loaded visit draws its targets. */
    private void pickVirtualSpot(WorldServer world, VirtualTravellerRecord r,
                                 com.jubitus.travellers.village.VillageVisitIndex.Settlement s, long now) {
        com.jubitus.travellers.village.visit.VillageVisitState st = r.visit;
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
        if (!s.buildings.isEmpty() && wBuilding > 0) {
            for (com.jubitus.travellers.village.VillageVisitIndex.Target t : s.buildings) {
                addSpot(world, r, s, t.entrance, t.point, wBuilding / s.buildings.size(), spots, faces, weights);
            }
        }
        if (!s.panelGroups.isEmpty() && wPanel > 0) {
            for (java.util.List<com.jubitus.travellers.village.VillageVisitIndex.Target> g : s.panelGroups) {
                com.jubitus.travellers.village.VillageVisitIndex.Target t = g.get(rng.nextInt(g.size()));
                addSpot(world, r, s, t.entrance, t.point, wPanel / s.panelGroups.size(), spots, faces, weights);
            }
        }
        if (!s.entrances.isEmpty() && (wStroll > 0 || spots.isEmpty())) {
            double w = Math.max(wStroll, 1.0) / s.entrances.size();
            for (BlockPos e : s.entrances) addSpot(world, r, s, e, null, w, spots, faces, weights);
        }
        if (spots.isEmpty()) {
            st.spot = s.center;
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
        com.jubitus.travellers.village.visit.VisitOccupancy.claim(world, r.uuid, st.spot, step * 2);
    }

    private static void addSpot(WorldServer world, VirtualTravellerRecord r,
                                com.jubitus.travellers.village.VillageVisitIndex.Settlement s,
                                @Nullable BlockPos entrance, @Nullable BlockPos point, double weight,
                                java.util.List<BlockPos> spots, java.util.List<BlockPos> faces, java.util.List<Double> weights) {
        // Offscreen visitors stand outside: at the entrance, never at an indoor point.
        BlockPos at = entrance;
        if (at == null && point != null && com.jubitus.travellers.village.MillFootprints.at(world,
                point.getX() + 0.5, point.getY(), point.getZ() + 0.5, 0) == null) at = point;
        if (at == null || weight <= 0) return;
        double crowd = com.jubitus.travellers.village.visit.VisitOccupancy.crowdFactor(world, at, 4.0, r.uuid,
                TravellersConfig.VILLAGE_VISIT.crowdPenalty);
        spots.add(at);
        faces.add(point != null ? point : null);
        weights.add(weight * crowd);
    }

    /** The village a group leader (loaded or offscreen) is staying in right now, or null. */
    @Nullable
    private BlockPos leaderVisitStop(WorldServer world, @Nullable UUID leaderId) {
        if (leaderId == null) return null;
        VirtualTravellerRecord leaderRec = records.get(leaderId);
        if (leaderRec != null) {
            com.jubitus.travellers.village.visit.VillageVisitState lv = leaderRec.visit;
            return lv != null && !lv.isLeaving() && !lv.asFollower ? lv.stop : null;
        }
        Entity live = world.getEntityFromUuid(leaderId);
        if (!(live instanceof EntityTraveller) || live.isDead) return null;
        com.jubitus.travellers.village.visit.EntityAIVillageVisit v = ((EntityTraveller) live).getVillageVisitAI();
        return v != null && v.isStaying() ? v.getVisitStop() : null;
    }

    /** A group follower record inside the village its leader stays in visits on its own until the leader leaves. */
    boolean tryStartFollower(WorldServer world, VirtualTravellerRecord r, long now) {
        if (!TravellersConfig.VILLAGE_VISIT.followersDisperse || !visitsEnabledFor(r)) return false;
        BlockPos stop = leaderVisitStop(world, r.groupLeaderUUID);
        if (stop == null) return false;
        com.jubitus.travellers.village.VillageVisitIndex.Settlement s =
                com.jubitus.travellers.village.VillageVisitIndex.get(world, stop);
        if (s == null) return false;
        double dx = s.center.getX() + 0.5 - r.posX;
        double dz = s.center.getZ() + 0.5 - r.posZ;
        double rad = Math.max(s.radius, TravellersConfig.VILLAGE_VISIT.arrivalRadius);
        if (dx * dx + dz * dz > rad * rad) return false;
        startStay(world, r, new com.jubitus.travellers.village.visit.VillageVisitState(stop, Integer.MAX_VALUE / 2, true), s, now);
        return true;
    }
}
