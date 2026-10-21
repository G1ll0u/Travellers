package com.jubitus.travellers.raid;

import com.jubitus.travellers.battle.VirtualCombatProfile;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.spawn.TravellerSoftSpawner;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Builds a raid's plan, then puts its waves on the ground one at a time. */
final class RaidLauncher {

    /** Warbands fan out this many degrees either side of their wave's direction. */
    private static final double FAN_DEGREES = 35.0;
    /** A wave's warbands are spread over this many blocks of extra distance, so they arrive one after another. */
    private static final int ARRIVAL_DEPTH = 80;
    private static final int PLACEMENT_ATTEMPTS = 6;
    /** Spawns closer than the server view distance are vetoed, so raids always appear at least this far past it. */
    private static final int VIEW_MARGIN_BLOCKS = 16;

    private RaidLauncher() {
    }

    static final class Start {
        final RaidPlan plan;
        final double baseAngle;

        Start(RaidPlan plan, double baseAngle) {
            this.plan = plan;
            this.baseAngle = baseAngle;
        }
    }

    static final class WaveResult {
        final int created;
        @Nullable final NoticePlace place;
        final double angle;

        WaveResult(int created, @Nullable NoticePlace place, double angle) {
            this.created = created;
            this.place = place;
            this.angle = angle;
        }
    }

    /** The raid's plan and base direction; null when no allowed culture has a village in the world. */
    @Nullable
    static Start plan(WorldServer ws, EntityPlayerMP player, Random rng) {
        Map<String, MillSettlements.Settlement> nearest = nearestAllowedByCulture(ws, player.posX, player.posZ);
        if (nearest.isEmpty()) return null;
        List<String> cultures = new ArrayList<>(nearest.keySet());
        String primary = cultures.remove(rng.nextInt(cultures.size()));

        int total = RaidStrength.raiderCount(ownedWorkersStrength(ws, player),
                TravellersConfig.RAIDS.difficultyScale, TravellersConfig.RAIDS.maxRaiders);
        RaidPlan plan = RaidPlan.build(total, primary, cultures, settings(), rng);
        BlockPos home = nearest.get(primary).center;
        return new Start(plan, Math.atan2(home.getZ() - player.posZ, home.getX() - player.posX));
    }

    /** Puts one wave on the ground, shrunk to the server-wide cap; created = 0 when nothing could be placed. */
    static WaveResult launchWave(WorldServer ws, EntityPlayerMP player, RaidWorldData data, UUID raidId,
                                 int index, RaidPlan.Wave wave, double baseAngle) {
        double waveAngle = baseAngle + Math.toRadians(wave.offsetDegrees);
        int total = Math.min(wave.size, TravellersConfig.RAIDS.maxActiveRaidRaiders - aliveRaidRaiders(ws, data));
        MillSettlements.Settlement home = homeFor(ws, player, wave.culture);
        if (total <= 0 || home == null) return new WaveResult(0, null, waveAngle);

        Random rng = ws.rand;
        VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
        BlockPos goal = player.getPosition();
        int distance = spawnDistance(ws);
        RaidGear gear = RaidGear.of(wave.kind);
        Consumer<EntityTraveller> outfit = t -> gear.outfit(t, CultureRegistry.get(t.getCultureId()), rng);
        boolean mounted = wave.kind == RaidPlan.WaveKind.CAVALRY;

        int created = 0;
        NoticePlace firstPlace = null;
        for (int size : RaidComposition.splitGroups(total, rng)) {
            for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
                double angle = waveAngle + Math.toRadians((rng.nextDouble() * 2.0 - 1.0) * FAN_DEGREES);
                double dist = distance + rng.nextInt(ARRIVAL_DEPTH);
                BlockPos ring = new BlockPos(player.posX + Math.cos(angle) * dist, 0,
                        player.posZ + Math.sin(angle) * dist);
                List<UUID> ids = TravellerSoftSpawner.spawnRaidGroup(ws, mgr, ring, home.cultureKey,
                        home.center, goal, size, outfit, mounted);
                if (ids.isEmpty()) continue;
                for (UUID id : ids) data.addMember(raidId, index, id);
                created += ids.size();
                if (firstPlace == null) firstPlace = new NoticePlace(ring, ws.provider.getDimension(), ids.get(0));
                break;
            }
        }
        return new WaveResult(created, firstPlace, waveAngle);
    }

    private static RaidPlan.Settings settings() {
        TravellersConfig.Raids c = TravellersConfig.RAIDS;
        RaidPlan.Settings s = new RaidPlan.Settings();
        s.minWaves = c.minWaves;
        s.maxWaves = c.maxWaves;
        s.maxRaiders = c.maxRaiders;
        s.approachWeights = new int[]{c.oneFrontWeight, c.twoFrontsWeight, c.encircleWeight};
        s.kindWeights = new int[]{c.hordeWeight, c.eliteWeight, c.cavalryWeight};
        s.cavalryAllowed = TravellersConfig.MOUNTS.enabled;
        s.mixedCulturePercent = c.mixedCultureChance;
        return s;
    }

    private static int spawnDistance(WorldServer ws) {
        int beyondView = ws.getMinecraftServer().getPlayerList().getViewDistance() * 16 + VIEW_MARGIN_BLOCKS;
        return Math.max(TravellersConfig.RAIDS.spawnDistance, beyondView);
    }

    /** Raid raiders of every player still standing, loaded or virtual. */
    private static int aliveRaidRaiders(WorldServer ws, RaidWorldData data) {
        VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);
        int n = 0;
        for (UUID id : data.allMembers()) {
            Entity e = ws.getEntityFromUuid(id);
            if (e != null ? e.isEntityAlive() : mgr.getRecord(id) != null) n++;
        }
        return n;
    }

    /** The nearest village of the wave's culture, or of any allowed culture if that one is gone. */
    @Nullable
    private static MillSettlements.Settlement homeFor(WorldServer ws, EntityPlayerMP player, String culture) {
        Map<String, MillSettlements.Settlement> nearest = nearestAllowedByCulture(ws, player.posX, player.posZ);
        MillSettlements.Settlement home = nearest.get(culture);
        if (home != null || nearest.isEmpty()) return home;
        return nearest.values().iterator().next();
    }

    /** Per allowed culture present in the world, its village nearest to the point. */
    private static Map<String, MillSettlements.Settlement> nearestAllowedByCulture(WorldServer ws, double x, double z) {
        Set<String> allowed = new HashSet<>();
        for (String c : TravellersConfig.RAIDS.allowedCultures) {
            if (c != null && !c.trim().isEmpty()) allowed.add(c.trim().toLowerCase(Locale.ROOT));
        }
        Map<String, MillSettlements.Settlement> nearestByCulture = new HashMap<>();
        for (MillSettlements.Settlement s : MillSettlements.villages(ws)) {
            if (s.cultureKey.isEmpty()) continue;
            if (!allowed.isEmpty() && !allowed.contains(s.cultureKey.toLowerCase(Locale.ROOT))) continue;
            MillSettlements.Settlement best = nearestByCulture.get(s.cultureKey);
            if (best == null || distSq(s, x, z) < distSq(best, x, z)) nearestByCulture.put(s.cultureKey, s);
        }
        return nearestByCulture;
    }

    private static double distSq(MillSettlements.Settlement s, double x, double z) {
        double dx = s.center.getX() - x;
        double dz = s.center.getZ() - z;
        return dx * dx + dz * dz;
    }

    /** Sum over the player's hired workers near them (or all, radius 0), loaded or virtual, relative to a bare worker. */
    private static double ownedWorkersStrength(WorldServer ws, EntityPlayerMP player) {
        UUID owner = player.getUniqueID();
        int radius = TravellersConfig.RAIDS.strengthRadius;
        double r2 = (double) radius * radius;
        VirtualCombatProfile bare = VirtualCombatProfile.fromEntityNbt(new NBTTagCompound(), "WORKER");
        double reference = RaidStrength.power(damageOf(bare), bare.maxHealth, bare.armor);
        double sum = 0.0;
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(ws).getAllRecords()) {
            if (!"WORKER".equals(r.entityClass) || !r.isHiredFollower || !owner.equals(r.followOwnerUUID)) continue;
            if (radius > 0 && horizontalDistSq(r.posX, r.posZ, player) > r2) continue;
            VirtualCombatProfile p = r.getCombatProfile();
            sum += RaidStrength.relativePower(RaidStrength.power(damageOf(p), p.maxHealth, p.armor), reference);
        }
        for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class,
                x -> x != null && x.isEntityAlive() && x.isHiredFollower() && owner.equals(x.getFollowOwnerId()))) {
            if (radius > 0 && horizontalDistSq(w.posX, w.posZ, player) > r2) continue;
            VirtualCombatProfile p = VirtualCombatProfile.fromEntityNbt(w.writeToNBT(new NBTTagCompound()), "WORKER");
            sum += RaidStrength.relativePower(RaidStrength.power(damageOf(p), p.maxHealth, p.armor), reference);
        }
        return sum;
    }

    private static double horizontalDistSq(double x, double z, EntityPlayerMP p) {
        double dx = x - p.posX;
        double dz = z - p.posZ;
        return dx * dx + dz * dz;
    }

    private static double damageOf(VirtualCombatProfile p) {
        return p.isArcher ? Math.max(p.meleeDamage, p.rangedDamage) : p.meleeDamage;
    }
}
