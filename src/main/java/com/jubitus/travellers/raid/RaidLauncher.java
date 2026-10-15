package com.jubitus.travellers.raid;

import com.jubitus.travellers.battle.VirtualCombatProfile;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.spawn.TravellerSoftSpawner;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Builds a raid: how many raiders, of which culture, and where the warbands appear. */
final class RaidLauncher {

    /** Warbands fan out this many degrees either side of the direction of their home village. */
    private static final double FAN_DEGREES = 35.0;
    /** Warbands are spread over this many blocks of extra distance, so they arrive one after another. */
    private static final int ARRIVAL_DEPTH = 80;
    private static final int PLACEMENT_ATTEMPTS = 6;

    private RaidLauncher() {
    }

    /** @return how many raiders were created (0 = nothing could be placed, no raid) */
    static int launch(WorldServer ws, EntityPlayerMP player, RaidWorldData data, UUID raidId) {
        Random rng = ws.rand;
        MillSettlements.Settlement home = pickHomeVillage(ws, player, rng);
        if (home == null) return 0;

        int total = RaidStrength.raiderCount(ownedWorkersStrength(ws, player.getUniqueID()),
                TravellersConfig.RAIDS.difficultyScale, TravellersConfig.RAIDS.maxRaiders);
        VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
        BlockPos goal = player.getPosition();
        double baseAngle = Math.atan2(home.center.getZ() - player.posZ, home.center.getX() - player.posX);

        int created = 0;
        NoticePlace firstPlace = null;
        for (int size : RaidComposition.splitGroups(total, rng)) {
            for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
                double angle = baseAngle + Math.toRadians((rng.nextDouble() * 2.0 - 1.0) * FAN_DEGREES);
                double dist = TravellersConfig.RAIDS.spawnDistance + rng.nextInt(ARRIVAL_DEPTH);
                BlockPos ring = new BlockPos(player.posX + Math.cos(angle) * dist, 0,
                        player.posZ + Math.sin(angle) * dist);
                List<UUID> ids = TravellerSoftSpawner.spawnRaidGroup(ws, mgr, ring, home.cultureKey,
                        home.center, goal, size);
                if (ids.isEmpty()) continue;
                for (UUID id : ids) data.addMember(raidId, id);
                created += ids.size();
                if (firstPlace == null) firstPlace = new NoticePlace(ring, ws.provider.getDimension(), ids.get(0));
                break;
            }
        }
        if (created > 0) {
            NotificationService.notifyOfflineAt(ws.getMinecraftServer(), player.getUniqueID(),
                    NotificationDefs.RAID_INCOMING, firstPlace, null);
        }
        return created;
    }

    /** The nearest village of a culture picked among those the player allows; null when none exists. */
    private static MillSettlements.Settlement pickHomeVillage(WorldServer ws, EntityPlayerMP player, Random rng) {
        Set<String> allowed = new HashSet<>();
        for (String c : TravellersConfig.RAIDS.allowedCultures) {
            if (c != null && !c.trim().isEmpty()) allowed.add(c.trim().toLowerCase(Locale.ROOT));
        }
        Map<String, MillSettlements.Settlement> nearestByCulture = new HashMap<>();
        for (MillSettlements.Settlement s : MillSettlements.villages(ws)) {
            if (s.cultureKey.isEmpty()) continue;
            if (!allowed.isEmpty() && !allowed.contains(s.cultureKey.toLowerCase(Locale.ROOT))) continue;
            MillSettlements.Settlement best = nearestByCulture.get(s.cultureKey);
            if (best == null || distSq(s, player) < distSq(best, player)) nearestByCulture.put(s.cultureKey, s);
        }
        if (nearestByCulture.isEmpty()) return null;
        List<MillSettlements.Settlement> options = new ArrayList<>(nearestByCulture.values());
        return options.get(rng.nextInt(options.size()));
    }

    private static double distSq(MillSettlements.Settlement s, EntityPlayerMP p) {
        double dx = s.center.getX() - p.posX;
        double dz = s.center.getZ() - p.posZ;
        return dx * dx + dz * dz;
    }

    /** Sum over the player's hired workers, loaded or virtual, of their power relative to a bare worker. */
    private static double ownedWorkersStrength(WorldServer ws, UUID owner) {
        VirtualCombatProfile bare = VirtualCombatProfile.fromEntityNbt(new NBTTagCompound(), "WORKER");
        double reference = RaidStrength.power(damageOf(bare), bare.maxHealth, bare.armor);
        double sum = 0.0;
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(ws).getAllRecords()) {
            if (!"WORKER".equals(r.entityClass) || !r.isHiredFollower || !owner.equals(r.followOwnerUUID)) continue;
            VirtualCombatProfile p = r.getCombatProfile();
            sum += RaidStrength.relativePower(RaidStrength.power(damageOf(p), p.maxHealth, p.armor), reference);
        }
        for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class,
                x -> x != null && x.isEntityAlive() && x.isHiredFollower() && owner.equals(x.getFollowOwnerId()))) {
            VirtualCombatProfile p = VirtualCombatProfile.fromEntityNbt(w.writeToNBT(new NBTTagCompound()), "WORKER");
            sum += RaidStrength.relativePower(RaidStrength.power(damageOf(p), p.maxHealth, p.armor), reference);
        }
        return sum;
    }

    private static double damageOf(VirtualCombatProfile p) {
        return p.isArcher ? Math.max(p.meleeDamage, p.rangedDamage) : p.meleeDamage;
    }
}
