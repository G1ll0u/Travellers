package com.jubitus.travellers.battle;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.camp.CampsiteRecord;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
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
 * (cross-faction, declared owner hostility, raider-vs-hired engagement delay), whole-group pull-in
 * (loaded and virtual members alike), reinforcement joins for ongoing battles, and battle merging.
 * Transient — all persisted state lives in VirtualBattleManager.
 */
class BattleEncounterScanner {

    private static final int CELL_SIZE = 64;
    private static final String KEY_RAIDER = BattleRecord.RAIDER;
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
        List<EntityTraveller> loadedFree = mgr.getBattleCount() > 0 || !cells.isEmpty()
                ? freeLoadedUnits(world, mgr) : new ArrayList<>();

        // Reinforcements: nearby compatible/hostile bystanders, then the rest of every group already fighting
        for (BattleRecord b : iterableToList(mgr.getBattlesView())) {
            if (b.dimension != dim) continue;
            for (VirtualTravellerRecord rec : recordsNear(cells, b.anchor.getX(), b.anchor.getZ(), cfg.battleJoinRadius)) {
                tryJoin(world, mgr, b, rec, now, cfg);
            }
            pullInGroups(world, vtm, mgr, b, cells, loadedFree, now, cfg);
        }

        if (!anyRaider && !anyHostileOwner) {
            mergeBattles(world, vtm, mgr);
            return;
        }

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
                            startBattle(world, vtm, mgr, cells, loadedFree, r, s, now, cfg);
                        }
                    }
                }
            }
        }

        mergeBattles(world, vtm, mgr);
    }

    // -------------------------------------------------------------------------
    // Battle creation / joining
    // -------------------------------------------------------------------------

    private void startBattle(WorldServer world, VirtualTravellerManager vtm, VirtualBattleManager mgr,
                             Map<Long, List<VirtualTravellerRecord>> cells, List<EntityTraveller> loadedFree,
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
        battle.addEnemies(groupKey(a), groupKey(b));
        mgr.addToBattle(world, battle, a, groupKey(a), isWorkerGroupLeader(a), now);
        mgr.addToBattle(world, battle, b, groupKey(b), isWorkerGroupLeader(b), now);

        // Pull in both parties' groups (and any other bystanders resolvable by hostility)
        for (VirtualTravellerRecord rec : recordsNear(cells, center.getX(), center.getZ(), cfg.battleJoinRadius)) {
            tryJoin(world, mgr, battle, rec, now, cfg);
        }
        pullInGroups(world, vtm, mgr, battle, cells, loadedFree, now, cfg);
    }

    // -------------------------------------------------------------------------
    // Whole-group pull-in
    // -------------------------------------------------------------------------

    /** One alive battle pawn (or candidate) with its group identity and position. */
    private static final class Member {
        final BattleGroupLink link;
        final double x, z;

        Member(BattleGroupLink link, double x, double z) {
            this.link = link;
            this.x = x;
            this.z = z;
        }
    }

    /** Loaded units in this world that are in no battle yet. */
    private static List<EntityTraveller> freeLoadedUnits(WorldServer world, VirtualBattleManager mgr) {
        List<EntityTraveller> out = new ArrayList<>();
        for (Entity e : world.loadedEntityList) {
            if (e instanceof EntityTraveller && !e.isDead && !mgr.isParticipant(e.getUniqueID())) {
                out.add((EntityTraveller) e);
            }
        }
        return out;
    }

    /** The battle's alive unit pawns (players excluded: they belong to no marching group). */
    private static List<Member> membersOf(WorldServer world, VirtualTravellerManager vtm, BattleRecord b) {
        List<Member> out = new ArrayList<>();
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive || p.isPlayer) continue;
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            if (rec != null) {
                out.add(new Member(BattleGroupLink.of(rec), rec.posX, rec.posZ));
                continue;
            }
            Entity e = world.getEntityFromUuid(p.uuid);
            if (e instanceof EntityTraveller && !e.isDead) {
                out.add(new Member(BattleGroupLink.of((EntityTraveller) e), e.posX, e.posZ));
            }
        }
        return out;
    }

    /**
     * Enrols, on its group's side, every unit of a group already fighting here that stands within the join
     * radius of a fellow member (chained, so a column is pulled in whole). Members farther away stay out until
     * they come close.
     */
    private void pullInGroups(WorldServer world, VirtualTravellerManager vtm, VirtualBattleManager mgr, BattleRecord b,
                              Map<Long, List<VirtualTravellerRecord>> cells, List<EntityTraveller> loadedFree,
                              long now, TravellersConfig.BattleEvents cfg) {
        List<Member> members = membersOf(world, vtm, b);
        if (members.isEmpty()) return;
        int reach = b.zoneRadius + cfg.battleJoinRadius;
        double joinSq = (double) cfg.battleJoinRadius * cfg.battleJoinRadius;

        List<Object> candidates = new ArrayList<>();
        for (VirtualTravellerRecord rec : recordsNear(cells, b.anchor.getX(), b.anchor.getZ(), reach)) {
            if (rec.battleId != null || !mgr.mayJoin(rec.uuid, now)) continue;
            if (rec.campSiteId != null && !cfg.campAssaults && !rec.campSiteId.equals(b.campAnchorId)) continue;
            candidates.add(rec);
        }
        double reachSq = (double) reach * reach;
        for (EntityTraveller e : loadedFree) {
            double dx = e.posX - b.anchor.getX();
            double dz = e.posZ - b.anchor.getZ();
            if (dx * dx + dz * dz > reachSq || mgr.isParticipant(e.getUniqueID()) || !mgr.mayJoin(e.getUniqueID(), now)) continue;
            candidates.add(e);
        }

        boolean grew = true;
        while (grew && !candidates.isEmpty()) {
            grew = false;
            for (Iterator<Object> it = candidates.iterator(); it.hasNext(); ) {
                Object c = it.next();
                VirtualTravellerRecord rec = c instanceof VirtualTravellerRecord ? (VirtualTravellerRecord) c : null;
                EntityTraveller ent = rec == null ? (EntityTraveller) c : null;
                BattleGroupLink link = rec != null ? BattleGroupLink.of(rec) : BattleGroupLink.of(ent);
                double cx = rec != null ? rec.posX : ent.posX;
                double cz = rec != null ? rec.posZ : ent.posZ;
                Member mate = null;
                for (Member m : members) {
                    if (!link.linkedTo(m.link)) continue;
                    double dx = cx - m.x;
                    double dz = cz - m.z;
                    if (dx * dx + dz * dz <= joinSq) {
                        mate = m;
                        break;
                    }
                }
                if (mate == null) continue;
                it.remove();
                String key = rec != null ? groupKey(rec) : VirtualBattleManager.loadedGroupKey(ent);
                if (rec != null) mgr.addToBattle(world, b, rec, key, isWorkerGroupLeader(rec), now);
                else mgr.addLoadedParticipant(b, ent, key);
                members.add(new Member(link, cx, cz));
                grew = true;
            }
        }
    }

    /** Adds a bystander record to an ongoing battle if its faction already fights there or is hostile to one that does. */
    private void tryJoin(WorldServer world, VirtualBattleManager mgr, BattleRecord b,
                         VirtualTravellerRecord rec, long now, TravellersConfig.BattleEvents cfg) {
        if (rec.battleId != null) return;
        if (rec.campSiteId != null && !cfg.campAssaults && !rec.campSiteId.equals(b.campAnchorId)) return;

        String key = groupKey(rec);
        if (!b.hasFaction(key)) {
            Set<String> hostile = new HashSet<>();
            for (BattleParticipant p : b.participants.values()) {
                if (p.alive && !hostile.contains(p.groupKey) && hostileKeys(world, p.groupKey, key, rec, now, cfg, b.id)) {
                    hostile.add(p.groupKey);
                }
            }
            if (hostile.isEmpty()) return; // neutral: stays out
            for (String h : hostile) b.addEnemies(h, key);
        }
        mgr.addToBattle(world, b, rec, key, isWorkerGroupLeader(rec), now);
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
        com.jubitus.travellers.combat.PlayerEnemyRelations rel = com.jubitus.travellers.combat.PlayerEnemyRelations.get(world);
        Set<UUID> da = declarationsFor(world, ownerA, recA);
        if (da != null && da.contains(ownerB) && !rel.isOfflineProtected(world, ownerB)) return true;
        Set<UUID> db = declarationsFor(world, ownerB, recB);
        return db != null && db.contains(ownerA) && !rel.isOfflineProtected(world, ownerA);
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

    /** Two battles become one when their zones overlap or a group fights in both. */
    private void mergeBattles(WorldServer world, VirtualTravellerManager vtm, VirtualBattleManager mgr) {
        int dim = world.provider.getDimension();
        List<BattleRecord> list = new ArrayList<>();
        for (BattleRecord b : mgr.getBattlesView()) {
            if (b.dimension == dim) list.add(b);
        }
        if (list.size() < 2) return;
        List<List<Member>> members = new ArrayList<>();
        for (BattleRecord b : list) members.add(membersOf(world, vtm, b));
        for (int i = 0; i < list.size(); i++) {
            BattleRecord a = list.get(i);
            if (!mgr.isRunning(a)) continue;
            for (int j = i + 1; j < list.size(); j++) {
                if (!mgr.isRunning(a)) break;
                BattleRecord b = list.get(j);
                if (!mgr.isRunning(b)) continue;
                double dx = a.anchor.getX() - b.anchor.getX();
                double dz = a.anchor.getZ() - b.anchor.getZ();
                double touch = a.zoneRadius + b.zoneRadius;
                if (dx * dx + dz * dz > touch * touch && !shareGroup(members.get(i), members.get(j))) continue;
                BattleRecord dst = a.participants.size() >= b.participants.size() ? a : b;
                mgr.mergeBattles(world, dst, dst == a ? b : a, vtm);
            }
        }
    }

    private static boolean shareGroup(List<Member> a, List<Member> b) {
        for (Member x : a) {
            for (Member y : b) {
                if (x.link.linkedTo(y.link)) return true;
            }
        }
        return false;
    }

    private static List<BattleRecord> iterableToList(Iterable<BattleRecord> it) {
        List<BattleRecord> out = new ArrayList<>();
        for (BattleRecord b : it) out.add(b);
        return out;
    }
}
