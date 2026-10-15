package com.jubitus.travellers.battle;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.camp.CampsiteRecord;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic encounter detection over virtual records: coarse spatial hash, hostility rules
 * (cross-faction, declared owner hostility, raider-vs-hired engagement delay), group pull-in,
 * reinforcement joins for ongoing battles, and battle merging.
 * Transient — all persisted state lives in VirtualBattleManager.
 */
class BattleEncounterScanner {

    private static final int CELL_SIZE = 64;
    private static final String KEY_RAIDER = "RAIDER";
    private static final String KEY_CIVIL = "CIVIL";
    private static final String KEY_OWNER_PREFIX = BattleParticipant.OWNER_KEY_PREFIX;

    // Sustained-proximity tracking for the raider-vs-hired engagement delay: pairKey -> {firstSeen, lastSeen}
    private final Map<String, long[]> proximitySeen = new HashMap<>();
    // Declared hostility per owner, refreshed from hired records each pass
    private final Map<UUID, Set<UUID>> ownerDeclarations = new HashMap<>();
    // Worker-leader uuids observed this pass (some record escorts them)
    private final Set<UUID> escortLeaders = new HashSet<>();

    void run(WorldServer world, VirtualTravellerManager vtm, VirtualBattleManager mgr, long now) {
        TravellersConfig.BattleEvents cfg = TravellersConfig.BATTLE_EVENTS;
        int dim = world.provider.getDimension();

        // Build the spatial hash over records not already fighting
        Map<Long, List<VirtualTravellerRecord>> cells = new HashMap<>();
        boolean anyRaider = false;
        boolean anyHostileOwner = false;
        escortLeaders.clear();
        for (VirtualTravellerRecord r : vtm.getAllRecords()) {
            if (r.dimension != dim || r.battleId != null) continue;
            cells.computeIfAbsent(cellKey(r.posX, r.posZ), k -> new ArrayList<>()).add(r);
            if ("RAIDER".equals(r.entityClass)) anyRaider = true;
            if (r.escortLeaderUUID != null) escortLeaders.add(r.escortLeaderUUID);
            if (r.isHiredFollower && r.followOwnerUUID != null) {
                Set<UUID> declared = r.getDeclaredHostileOwners(world);
                if (!declared.isEmpty()) {
                    anyHostileOwner = true;
                    ownerDeclarations.put(r.followOwnerUUID, declared);
                }
            }
        }
        pruneProximity(now, cfg);
        if (cells.isEmpty()) return;

        // Reinforcements: pull nearby compatible/hostile groups into ongoing battles
        for (BattleRecord b : iterableToList(mgr.getBattlesView())) {
            if (b.dimension != dim) continue;
            for (VirtualTravellerRecord rec : recordsNear(cells, b.center.getX(), b.center.getZ(), cfg.battleJoinRadius)) {
                tryJoin(world, mgr, b, rec, now, cfg);
            }
        }

        if (!anyRaider && !anyHostileOwner) return;

        // New encounters: scan 3x3 neighborhoods for hostile pairs
        double engageSq = (double) cfg.engagementRadius * cfg.engagementRadius;
        for (Map.Entry<Long, List<VirtualTravellerRecord>> e : cells.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) e.getKey().longValue();
            for (VirtualTravellerRecord r : e.getValue()) {
                if (r.battleId != null) continue;
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        List<VirtualTravellerRecord> others =
                                cells.get(((long) (cx + ox) << 32) | ((cz + oz) & 0xFFFFFFFFL));
                        if (others == null) continue;
                        for (VirtualTravellerRecord s : others) {
                            if (s.battleId != null || r.battleId != null) continue;
                            if (r.uuid.compareTo(s.uuid) >= 0) continue;
                            if (!mgr.mayJoin(r.uuid, now) || !mgr.mayJoin(s.uuid, now)) continue;
                            double dx = r.posX - s.posX;
                            double dz = r.posZ - s.posZ;
                            if (dx * dx + dz * dz > engageSq) continue;
                            if (!isHostilePair(world, r, s, now, cfg)) continue;
                            startBattle(world, vtm, mgr, cells, r, s, now, cfg);
                        }
                    }
                }
            }
        }

        mergeCloseBattles(world, vtm, mgr, cfg);
    }

    // -------------------------------------------------------------------------
    // Battle creation / joining
    // -------------------------------------------------------------------------

    private void startBattle(WorldServer world, VirtualTravellerManager vtm, VirtualBattleManager mgr,
                             Map<Long, List<VirtualTravellerRecord>> cells,
                             VirtualTravellerRecord a, VirtualTravellerRecord b, long now,
                             TravellersConfig.BattleEvents cfg) {
        // Camp assault: anchor the battle at the defender's camp
        UUID campAnchor = a.campSiteId != null ? a.campSiteId : b.campSiteId;
        BlockPos center;
        if (campAnchor != null) {
            CampsiteRecord camp = CampsiteManager.get(world).getById(campAnchor);
            center = camp != null ? camp.center
                    : new BlockPos((int) ((a.posX + b.posX) / 2), (int) a.posY, (int) ((a.posZ + b.posZ) / 2));
        } else {
            center = new BlockPos((int) ((a.posX + b.posX) / 2), (int) a.posY, (int) ((a.posZ + b.posZ) / 2));
        }

        BattleRecord battle = mgr.createBattle(world, center, now, campAnchor);
        mgr.addToBattle(world, battle, a, 0, groupKey(a), isWorkerGroupLeader(a), now);
        mgr.addToBattle(world, battle, b, 1, groupKey(b), isWorkerGroupLeader(b), now);

        // Pull in both parties' groups (and any other bystanders resolvable by hostility)
        for (VirtualTravellerRecord rec : recordsNear(cells, center.getX(), center.getZ(), cfg.battleJoinRadius)) {
            tryJoin(world, mgr, battle, rec, now, cfg);
        }
    }

    /** Adds a bystander record to an ongoing battle if its group belongs to (or is hostile to) a side. */
    private void tryJoin(WorldServer world, VirtualBattleManager mgr, BattleRecord b,
                         VirtualTravellerRecord rec, long now, TravellersConfig.BattleEvents cfg) {
        if (rec.battleId != null) return;
        if (rec.campSiteId != null && !cfg.campAssaults && !rec.campSiteId.equals(b.campAnchorId)) return;

        String key = groupKey(rec);
        int side = b.sideOfGroup(key);
        if (side < 0) {
            boolean hostileTo0 = sideHostileToKey(world, b, 0, key, rec, now, cfg, b.id);
            boolean hostileTo1 = sideHostileToKey(world, b, 1, key, rec, now, cfg, b.id);
            if (hostileTo0 == hostileTo1) return; // neutral, or hostile to both: stay out
            side = hostileTo0 ? 1 : 0;
        }
        mgr.addToBattle(world, b, rec, side, key, isWorkerGroupLeader(rec), now);
    }

    private boolean sideHostileToKey(WorldServer world, BattleRecord b, int side, String key, VirtualTravellerRecord rec,
                                     long now, TravellersConfig.BattleEvents cfg, UUID battleId) {
        Set<String> seen = new HashSet<>();
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive || p.side != side || !seen.add(p.groupKey)) continue;
            if (hostileKeys(world, p.groupKey, key, rec, now, cfg, battleId)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Hostility rules
    // -------------------------------------------------------------------------

    private boolean isHostilePair(WorldServer world, VirtualTravellerRecord a, VirtualTravellerRecord b, long now,
                                  TravellersConfig.BattleEvents cfg) {
        if ((a.campSiteId != null || b.campSiteId != null) && !cfg.campAssaults) return false;
        String ka = groupKey(a);
        String kb = groupKey(b);
        if (ka.equals(kb)) return false;

        boolean aRaider = KEY_RAIDER.equals(ka);
        boolean bRaider = KEY_RAIDER.equals(kb);
        if (aRaider || bRaider) {
            VirtualTravellerRecord other = aRaider ? b : a;
            if (other.isHiredFollower) {
                if (!cfg.raidersAttackHiredWorkers) return false;
                VirtualTravellerRecord raider = aRaider ? a : b;
                return sustainedProximity(pairKey(groupRoot(raider), groupRoot(other)), now, cfg);
            }
            return true;
        }

        // Hired vs hired: declared owner hostility (either direction)
        if (ka.startsWith(KEY_OWNER_PREFIX) && kb.startsWith(KEY_OWNER_PREFIX)) {
            if (!cfg.hiredVsHiredBattles) return false;
            return ownersHostile(world, a.followOwnerUUID, b.followOwnerUUID, a, b);
        }
        return false;
    }

    /**
     * Key-level hostility for joining an existing battle. {@code rec} is the joining record
     * (provides owner declarations); raider-vs-hired uses the battle id for the proximity delay.
     */
    private boolean hostileKeys(WorldServer world, String existing, String joining, VirtualTravellerRecord rec,
                                long now, TravellersConfig.BattleEvents cfg, UUID battleId) {
        if (existing.equals(joining)) return false;
        boolean existingRaider = KEY_RAIDER.equals(existing);
        boolean joiningRaider = KEY_RAIDER.equals(joining);
        if (existingRaider || joiningRaider) {
            String other = existingRaider ? joining : existing;
            if (other.startsWith(KEY_OWNER_PREFIX)) {
                if (!cfg.raidersAttackHiredWorkers) return false;
                return sustainedProximity("battle:" + battleId + ":" + other, now, cfg);
            }
            return true;
        }
        if (existing.startsWith(KEY_OWNER_PREFIX) && joining.startsWith(KEY_OWNER_PREFIX)) {
            if (!cfg.hiredVsHiredBattles) return false;
            UUID oa = UUID.fromString(existing.substring(KEY_OWNER_PREFIX.length()));
            UUID ob = UUID.fromString(joining.substring(KEY_OWNER_PREFIX.length()));
            return ownersHostile(world, oa, ob, rec, null);
        }
        return false;
    }

    private boolean ownersHostile(WorldServer world, @Nullable UUID ownerA, @Nullable UUID ownerB,
                                  @Nullable VirtualTravellerRecord recA, @Nullable VirtualTravellerRecord recB) {
        if (ownerA == null || ownerB == null || ownerA.equals(ownerB)) return false;
        Set<UUID> da = declarationsFor(world, ownerA, recA);
        if (da != null && da.contains(ownerB)) return true;
        Set<UUID> db = declarationsFor(world, ownerB, recB);
        return db != null && db.contains(ownerA);
    }

    @Nullable
    private Set<UUID> declarationsFor(WorldServer world, UUID owner, @Nullable VirtualTravellerRecord rec) {
        if (rec != null && owner.equals(rec.followOwnerUUID)) {
            Set<UUID> declared = rec.getDeclaredHostileOwners(world);
            if (!declared.isEmpty()) ownerDeclarations.put(owner, declared);
            return declared;
        }
        return ownerDeclarations.get(owner);
    }

    /** True once the pair has stayed in detection range for the configured delay. */
    private boolean sustainedProximity(String key, long now, TravellersConfig.BattleEvents cfg) {
        if (cfg.raiderVsHiredEngageDelayTicks <= 0) return true;
        long staleAfter = 3L * Math.max(1, cfg.detectionIntervalTicks);
        long[] t = proximitySeen.get(key);
        if (t == null || now - t[1] > staleAfter) {
            proximitySeen.put(key, new long[]{now, now});
            return false;
        }
        t[1] = now;
        return now - t[0] >= cfg.raiderVsHiredEngageDelayTicks;
    }

    private void pruneProximity(long now, TravellersConfig.BattleEvents cfg) {
        if (proximitySeen.size() < 256) return;
        long staleAfter = 3L * Math.max(1, cfg.detectionIntervalTicks);
        proximitySeen.values().removeIf(t -> now - t[1] > staleAfter);
    }

    // -------------------------------------------------------------------------
    // Grouping
    // -------------------------------------------------------------------------

    static String groupKey(VirtualTravellerRecord r) {
        if ("RAIDER".equals(r.entityClass)) return KEY_RAIDER;
        if (r.isHiredFollower && r.followOwnerUUID != null) return KEY_OWNER_PREFIX + r.followOwnerUUID;
        return KEY_CIVIL;
    }

    /** Stable identity of the record's marching group, for proximity-delay pair keys. */
    private static UUID groupRoot(VirtualTravellerRecord r) {
        if (r.groupLeaderUUID != null) return r.groupLeaderUUID;
        if (r.escortLeaderUUID != null) return r.escortLeaderUUID;
        if (r.followOwnerUUID != null) return r.followOwnerUUID;
        return r.uuid;
    }

    private static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** Hired worker leading its own escort sub-group (targeted last with leader survival bias). */
    private boolean isWorkerGroupLeader(VirtualTravellerRecord r) {
        return r.isHiredFollower && escortLeaders.contains(r.uuid);
    }

    // -------------------------------------------------------------------------
    // Spatial hash helpers
    // -------------------------------------------------------------------------

    private static long cellKey(double x, double z) {
        int cx = (int) Math.floor(x / CELL_SIZE);
        int cz = (int) Math.floor(z / CELL_SIZE);
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static List<VirtualTravellerRecord> recordsNear(Map<Long, List<VirtualTravellerRecord>> cells,
                                                            int x, int z, int radius) {
        List<VirtualTravellerRecord> out = new ArrayList<>();
        int minCx = Math.floorDiv(x - radius, CELL_SIZE);
        int maxCx = Math.floorDiv(x + radius, CELL_SIZE);
        int minCz = Math.floorDiv(z - radius, CELL_SIZE);
        int maxCz = Math.floorDiv(z + radius, CELL_SIZE);
        double r2 = (double) radius * radius;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                List<VirtualTravellerRecord> cell = cells.get(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                if (cell == null) continue;
                for (VirtualTravellerRecord r : cell) {
                    double dx = r.posX - x;
                    double dz = r.posZ - z;
                    if (dx * dx + dz * dz <= r2) out.add(r);
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Merging
    // -------------------------------------------------------------------------

    private void mergeCloseBattles(WorldServer world, VirtualTravellerManager vtm,
                                   VirtualBattleManager mgr, TravellersConfig.BattleEvents cfg) {
        List<BattleRecord> list = iterableToList(mgr.getBattlesView());
        if (list.size() < 2) return;
        double mergeSq = (double) cfg.mergeBattleDistance * cfg.mergeBattleDistance;
        for (int i = 0; i < list.size(); i++) {
            BattleRecord a = list.get(i);
            for (int j = i + 1; j < list.size(); j++) {
                BattleRecord b = list.get(j);
                if (a.dimension != b.dimension) continue;
                double dx = a.center.getX() - b.center.getX();
                double dz = a.center.getZ() - b.center.getZ();
                if (dx * dx + dz * dz > mergeSq) continue;
                BattleRecord dst = a.participants.size() >= b.participants.size() ? a : b;
                BattleRecord src = dst == a ? b : a;
                Map<UUID, Integer> mapping = alignSides(dst, src);
                if (mapping != null) {
                    mgr.mergeBattles(dst, src, mapping, vtm);
                    return; // list is stale after a merge; next pass handles the rest
                }
            }
        }
    }

    /** Maps every src participant to a dst side via shared group keys, or null if unresolvable. */
    @Nullable
    private Map<UUID, Integer> alignSides(BattleRecord dst, BattleRecord src) {
        Map<String, Integer> keySides = new HashMap<>();
        for (BattleParticipant p : dst.participants.values()) {
            keySides.putIfAbsent(p.groupKey, p.side);
        }
        // Determine which src side corresponds to dst side 0 using any shared key
        Integer srcSideForDst0 = null;
        for (BattleParticipant p : src.participants.values()) {
            Integer dstSide = keySides.get(p.groupKey);
            if (dstSide != null) {
                srcSideForDst0 = dstSide == 0 ? p.side : 1 - p.side;
                break;
            }
        }
        // No shared keys: align raider sides if both battles have raiders
        if (srcSideForDst0 == null) {
            Integer dstRaiderSide = keySides.get(KEY_RAIDER);
            int srcRaiderSide = src.sideOfGroup(KEY_RAIDER);
            if (dstRaiderSide == null || srcRaiderSide < 0) return null;
            srcSideForDst0 = dstRaiderSide == 0 ? srcRaiderSide : 1 - srcRaiderSide;
        }
        Map<UUID, Integer> mapping = new HashMap<>();
        for (BattleParticipant p : src.participants.values()) {
            mapping.put(p.uuid, p.side == srcSideForDst0 ? 0 : 1);
        }
        return mapping;
    }

    private static List<BattleRecord> iterableToList(Iterable<BattleRecord> it) {
        List<BattleRecord> out = new ArrayList<>();
        for (BattleRecord b : it) out.add(b);
        return out;
    }
}
