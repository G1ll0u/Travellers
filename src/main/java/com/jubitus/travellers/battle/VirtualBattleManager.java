package com.jubitus.travellers.battle;

import com.jubitus.travellers.unit.UnitSpeed;
import com.jubitus.travellers.worldmap.stream.MapStrikeLog;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.combat.AttackTargetLimiter;
import com.jubitus.travellers.combat.TargetLockPolicy;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import com.jubitus.travellers.unit.equipment.TravellerEquipmentManager;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.BattleNotifications;
import com.jubitus.travellers.notify.BattleSummary;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.notify.VanillaDeathMessage;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.notify.WorkerLifecycleNotifications;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.virtual.VirtualFormationSimulator;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.combat.TravellerCombatLogic;
import com.jubitus.travellers.combat.TravellerItemDamage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.CombatRules;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Per-dimension catalog and simulator of virtual battles. Follows the camp-manager pattern:
 * logical state is the source of truth, persisted in NBT, ticked from TravellerChunkManager.
 * The sim only resolves virtual-vs-virtual damage; materialized participants fight via the
 * normal loaded AI and are tracked here by UUID only. A virtual pawn chasing a loaded foe walks
 * up to it until it materializes, so no blow ever crosses the loaded/virtual line.
 */
public class VirtualBattleManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersVirtualBattles";

    private static final int LOOT_RECONCILE_INTERVAL = 20;
    private static final int MAX_LOOT_DEPOSITS_PER_PASS = 4;
    private static final int MAX_PENDING_LOOT = 512;
    // Clamp elapsed sim ticks so a long pause (restart, lag) can't burst hundreds of attacks
    private static final int MAX_STEP_TICKS = 200;
    private static final double LEADER_SHIELD_RADIUS_SQ = 8.0 * 8.0;
    // Elapsed sim ticks a DEFEND/HOLD pawn can spend leashed away from its target before it gives up
    // and leaves its battle individually. Mirrors EntityTraveller's stuckTargetTicks threshold (80).
    private static final int TETHER_DISENGAGE_TICKS = 80;

    // Elapsed sim ticks a pawn may go without any progress before it leaves its battle (30 s).
    private static final int NO_PROGRESS_DISENGAGE_TICKS = 600;
    // After leaving a battle without a result, a pawn cannot be pulled into a new one for this long (60 s).
    private static final int REJOIN_COOLDOWN_TICKS = 1200;
    private static final int ORPHAN_SWEEP_INTERVAL = 200;
    // How long an ended battle keeps its map marker, showing the result (60 s).
    private static final int ENDED_MARKER_TICKS = 1200;
    /** A shown battle ends after this long without any blow. */
    private static final int LULL_END_TICKS = 600;
    /** A battle with no blow yet (sides still closing in) ends after this long. */
    private static final int APPROACH_END_TICKS = 1200;
    /** A fight starting this soon after a battle ended at the same place continues that battle. */
    private static final int REOPEN_TICKS = 1200;
    /** The shown position follows the blows only once they drift this far from it. */
    private static final double ANCHOR_DRIFT = 48.0;
    /** An owner's "henchmen fallen" toast is updated at most this often during a battle. */
    private static final int FALLEN_BEAT_TICKS = 200;

    private final Map<UUID, BattleRecord> battles = new LinkedHashMap<>();
    // Transient: battles that just ended, kept for their map marker only
    private final List<BattleRecord> recentlyEnded = new ArrayList<>();
    // Transient: pawn uuid -> world tick before which it may not be (re)added to a battle
    private final Map<UUID, Long> rejoinBlockedUntil = new HashMap<>();
    private long lastOrphanSweepTick = -1;
    private final List<PendingLootDrop> pendingLoot = new ArrayList<>();

    // Transient: pawn uuid -> battle id (rebuilt on load)
    private final Map<UUID, UUID> participantIndex = new HashMap<>();
    private final BattleEncounterScanner scanner = new BattleEncounterScanner();
    private long lastDetectTick = -1;
    private long lastSimPassTick = -1;
    private long lastLootTick = -1;
    private int simCursor = 0;

    public VirtualBattleManager(String name) {
        super(name);
    }

    public VirtualBattleManager() {
        super(DATA_NAME);
    }

    public static VirtualBattleManager get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        VirtualBattleManager mgr = (VirtualBattleManager) storage.getOrLoadData(VirtualBattleManager.class, DATA_NAME);
        if (mgr == null) {
            mgr = new VirtualBattleManager(DATA_NAME);
            storage.setData(DATA_NAME, mgr);
        }
        return mgr;
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public void tick(WorldServer world, long now) {
        TravellersConfig.BattleEvents cfg = TravellersConfig.BATTLE_EVENTS;

        // Peaceful blocks all combat, virtual battles included - any battle already in progress
        // when the world flips to peaceful is dissolved immediately, same as the feature-toggle-off
        // case right below, rather than being left to play out.
        if (!cfg.enableVirtualBattles || TravellersDerivedConfig.isPeacefulBlocked(world)) {
            if (!battles.isEmpty()) releaseAllBattles(world);
        } else {
            if (now - lastDetectTick >= cfg.detectionIntervalTicks) {
                lastDetectTick = now;
                scanner.run(world, VirtualTravellerManager.get(world), this, now);
            }
            if (now - lastSimPassTick >= cfg.battleSimIntervalTicks) {
                lastSimPassTick = now;
                simPass(world, now);
            }
        }

        if (now - lastOrphanSweepTick >= ORPHAN_SWEEP_INTERVAL) {
            lastOrphanSweepTick = now;
            sweepOrphanBattleIds(world, now);
        }

        if (now - lastLootTick >= LOOT_RECONCILE_INTERVAL) {
            lastLootTick = now;
            reconcileLoot(world, now);
        }
    }

    // -------------------------------------------------------------------------
    // Public API (called by VirtualTravellerManager / entity hooks / streams)
    // -------------------------------------------------------------------------

    public boolean isParticipant(UUID uuid) {
        return participantIndex.containsKey(uuid);
    }

    @Nullable
    public BattleRecord battleOf(UUID uuid) {
        UUID id = participantIndex.get(uuid);
        return id != null ? battles.get(id) : null;
    }

    public Iterable<BattleRecord> getBattlesView() {
        return battles.values();
    }

    /** Battles that ended less than a minute ago, for the map's result markers. */
    public List<BattleRecord> getRecentlyEnded(long now) {
        recentlyEnded.removeIf(b -> now - b.endTick > ENDED_MARKER_TICKS);
        return recentlyEnded;
    }

    public int getBattleCount() {
        return battles.size();
    }

    public int getPendingLootCount() {
        return pendingLoot.size();
    }

    /** Debug: force-end a battle, releasing all survivors. Forced ends never pillage. */
    public void debugEndBattle(WorldServer world, BattleRecord b) {
        endBattle(world, VirtualTravellerManager.get(world), b, false);
    }

    /** Restores battleId on a freshly created record for a pawn that re-virtualized mid-battle. */
    public void restoreBattleIdIfParticipant(VirtualTravellerRecord rec) {
        UUID id = participantIndex.get(rec.uuid);
        if (id == null) return;
        BattleRecord b = battles.get(id);
        BattleParticipant p = b != null ? b.participants.get(rec.uuid) : null;
        if (p != null && p.alive) {
            rec.battleId = id;
            p.loaded = false;
        }
    }

    /** Sets an initial attack target on a pawn that materialized mid-battle. */
    public void onParticipantMaterialized(WorldServer world, UUID uuid, EntityTraveller entity) {
        BattleRecord b = battleOf(uuid);
        if (b == null) return;
        BattleParticipant self = b.participants.get(uuid);
        if (self == null || !self.alive) return;
        self.loaded = true;

        EntityTraveller nearest = null;
        double best = Double.MAX_VALUE;
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive || p.side == self.side) continue;
            Entity e = world.getEntityFromUuid(p.uuid);
            if (!(e instanceof EntityTraveller) || e.isDead) continue;
            double d = entity.getDistanceSq(e);
            if (d < best) {
                best = d;
                nearest = (EntityTraveller) e;
            }
        }
        if (nearest != null) {
            entity.setAttackTarget(nearest);
        }
    }

    /** Marks a participant dead after its loaded entity died (vanilla drops apply, no virtual loot). */
    public void onParticipantDeath(WorldServer world, EntityTraveller entity, @Nullable DamageSource cause) {
        UUID uuid = entity.getUniqueID();
        UUID id = participantIndex.remove(uuid);
        if (id == null) return;
        BattleRecord b = battles.get(id);
        if (b == null) return;
        BattleParticipant p = b.participants.get(uuid);
        if (p != null) p.alive = false;

        if (entity instanceof EntityTravellerWorker) {
            EntityTravellerWorker w =
                    (EntityTravellerWorker) entity;
            UUID owner = w.getFollowOwnerUUID();
            if (p != null && w.isHiredFollower() && owner != null && shouldNotifyDeath(b, owner)) {
                notifyFallen(world, b, p, owner, entity.getName());
            }
        }
        markDirty();
    }

    // -------------------------------------------------------------------------
    // Battle membership (called by the scanner)
    // -------------------------------------------------------------------------

    /** False while the pawn is cooling down after leaving a battle without a result. */
    boolean mayJoin(UUID uuid, long now) {
        Long until = rejoinBlockedUntil.get(uuid);
        if (until == null) return true;
        if (now >= until) {
            rejoinBlockedUntil.remove(uuid);
            return true;
        }
        return false;
    }

    private void blockRejoin(UUID uuid, long now) {
        rejoinBlockedUntil.put(uuid, now + REJOIN_COOLDOWN_TICKS);
    }

    /** Safety net: a record whose battle (or membership in it) no longer exists must not stay frozen. */
    private void sweepOrphanBattleIds(WorldServer world, long now) {
        VirtualTravellerManager vtm = VirtualTravellerManager.get(world);
        int dim = world.provider.getDimension();
        List<VirtualTravellerRecord> orphans = null;
        for (VirtualTravellerRecord r : vtm.getAllRecords()) {
            if (r.battleId == null || r.dimension != dim) continue;
            BattleRecord b = battles.get(r.battleId);
            BattleParticipant p = b != null ? b.participants.get(r.uuid) : null;
            if (p != null && p.alive) continue;
            if (orphans == null) orphans = new ArrayList<>();
            orphans.add(r);
        }
        if (orphans == null) return;
        for (VirtualTravellerRecord r : orphans) {
            r.battleId = null;
            participantIndex.remove(r.uuid);
            vtm.forceRefreshGoal(world, r, now);
        }
        markDirty();
    }

    BattleRecord createBattle(WorldServer world, BlockPos center, long now, @Nullable UUID campAnchorId) {
        BattleRecord reopened = reopenAt(world, center, now);
        if (reopened != null) return reopened;
        BattleRecord b = new BattleRecord(UUID.randomUUID(), world.provider.getDimension(), center, now);
        b.campAnchorId = campAnchorId;
        battles.put(b.id, b);
        markDirty();
        return b;
    }

    /**
     * A fight breaking out where a battle ended less than a minute ago continues it: same id (so the same
     * map marker and journal row), its fallen kept, its survivors enrolled afresh as they come back.
     */
    @Nullable
    private BattleRecord reopenAt(WorldServer world, BlockPos center, long now) {
        int dim = world.provider.getDimension();
        for (BattleRecord b : new ArrayList<>(recentlyEnded)) {
            if (b.dimension != dim || now - b.endTick > REOPEN_TICKS) continue;
            double dx = b.center.getX() - center.getX();
            double dz = b.center.getZ() - center.getZ();
            if (dx * dx + dz * dz > (double) b.zoneRadius * b.zoneRadius) continue;
            recentlyEnded.remove(b);
            b.participants.values().removeIf(p -> p.alive);
            b.endTick = -1;
            b.winnerSide = -1;
            b.lastBlowTick = now;
            b.lastSimTick = now;
            b.notifiedOwners.clear();
            battles.put(b.id, b);
            markDirty();
            return b;
        }
        return null;
    }

    void addToBattle(WorldServer world, BattleRecord b, VirtualTravellerRecord rec, int side,
                     String groupKey, boolean isGroupLeader, long now) {
        if (rec.battleId != null || b.participants.containsKey(rec.uuid) || !mayJoin(rec.uuid, now)) return;
        BattleParticipant p = new BattleParticipant(rec.uuid);
        p.side = side;
        p.groupKey = groupKey;
        p.isGroupLeader = isGroupLeader;
        p.attackTimer = (rec.uuid.hashCode() & 7); // small stagger so hits don't sync up
        b.participants.put(p.uuid, p);
        rec.battleId = b.id;
        participantIndex.put(rec.uuid, b.id);

        checkNotifyOwners(world, b);
        markDirty();
    }

    /** Loaded-only pawn joining/starting a battle: no VirtualTravellerRecord backs it, just a UUID. */
    void addLoadedParticipant(BattleRecord b, EntityTraveller entity, int side, String groupKey) {
        if (b.participants.containsKey(entity.getUniqueID())) return;
        BattleParticipant p = new BattleParticipant(entity.getUniqueID());
        p.side = side;
        p.groupKey = groupKey;
        p.loaded = true;
        p.attackTimer = (entity.getUniqueID().hashCode() & 7);
        b.participants.put(p.uuid, p);
        participantIndex.put(p.uuid, b.id);
    }

    /**
     * Entry point for a fight that starts (and may stay) entirely in loaded chunks — the offscreen
     * {@link BattleEncounterScanner} can never see it since it only scans {@link VirtualTravellerRecord}s.
     * Called from {@code EntityTraveller.setAttackTarget} once a hostile target is actually accepted.
     * Every such fight is tracked, hired workers or not, so the map shows the same battles near the player as offscreen.
     */
    public void onLoadedEngagement(WorldServer world, EntityTraveller attacker, EntityTraveller target) {
        TravellersConfig.BattleEvents cfg = TravellersConfig.BATTLE_EVENTS;
        if (!cfg.enableVirtualBattles || !cfg.detectLoadedEncounters) return;

        BattleRecord b = battleOf(attacker.getUniqueID());
        if (b == null) b = battleOf(target.getUniqueID());

        String aKey = loadedGroupKey(attacker);
        String tKey = loadedGroupKey(target);
        if (aKey.equals(tKey)) return; // same faction, shouldn't happen but stay safe

        if (b == null) {
            b = createBattle(world, attacker.getPosition(), world.getTotalWorldTime(), null);
        }

        int aSide = b.sideOfGroup(aKey);
        int tSide = b.sideOfGroup(tKey);
        if (aSide < 0 && tSide < 0) {
            aSide = 0;
            tSide = 1;
        } else if (aSide < 0) {
            aSide = 1 - tSide;
        } else if (tSide < 0) {
            tSide = 1 - aSide;
        }
        addLoadedParticipant(b, attacker, aSide, aKey);
        addLoadedParticipant(b, target, tSide, tKey);

        checkNotifyOwners(world, b);
        markDirty();
    }

    /**
     * A loaded blow between two fighters (units or players). It keeps their battle alive and shown; a player
     * striking or struck by a battle participant joins their workers' side.
     */
    public void onLoadedBlow(WorldServer world, Entity attacker, Entity victim) {
        BattleRecord b = battleOf(attacker.getUniqueID());
        if (b == null) b = battleOf(victim.getUniqueID());
        if (b == null) return;
        long now = world.getTotalWorldTime();
        b.recordBlow((attacker.posX + victim.posX) / 2, (attacker.posZ + victim.posZ) / 2, now);
        if (attacker instanceof EntityPlayer) joinPlayer(b, (EntityPlayer) attacker, victim.getUniqueID());
        if (victim instanceof EntityPlayer) joinPlayer(b, (EntityPlayer) victim, attacker.getUniqueID());
        checkNotifyOwners(world, b);
        markDirty();
    }

    private void joinPlayer(BattleRecord b, EntityPlayer player, UUID foeId) {
        if (player.isCreative() || player.isSpectator() || b.participants.containsKey(player.getUniqueID())) return;
        BattleParticipant foe = b.participants.get(foeId);
        if (foe == null || !foe.alive) return;
        String key = BattleParticipant.OWNER_KEY_PREFIX + player.getUniqueID();
        int side = b.sideOfGroup(key);
        if (side < 0) side = 1 - foe.side;
        if (side == foe.side) return;
        BattleParticipant p = new BattleParticipant(player.getUniqueID());
        p.side = side;
        p.groupKey = key;
        p.loaded = true;
        p.isPlayer = true;
        b.participants.put(p.uuid, p);
        participantIndex.put(p.uuid, b.id);
    }

    /** A player who fell in a battle counts among its fallen. */
    public void onPlayerDeath(EntityPlayer player) {
        UUID id = participantIndex.remove(player.getUniqueID());
        BattleRecord b = id != null ? battles.get(id) : null;
        BattleParticipant p = b != null ? b.participants.get(player.getUniqueID()) : null;
        if (p != null) p.alive = false;
        markDirty();
    }

    /** Mirrors BattleEncounterScanner.groupKey for VirtualTravellerRecords, kept in sync so the two
     *  detection paths (offscreen scan / loaded engagement) never disagree on faction identity. */
    static String loadedGroupKey(EntityTraveller e) {
        if (e instanceof EntityTravellerRaider) return "RAIDER";
        if (e instanceof EntityTravellerWorker) {
            EntityTravellerWorker w =
                    (EntityTravellerWorker) e;
            if (w.isHiredFollower() && w.getFollowOwnerUUID() != null) {
                return BattleParticipant.OWNER_KEY_PREFIX + w.getFollowOwnerUUID();
            }
        }
        return "CIVIL";
    }

    /**
     * Re-evaluates every hired-worker owner present in {@code b} and notifies any not-yet-notified
     * owner whose fight has crossed the configured significance threshold (enemy count + enemy/worker
     * ratio, re-checked on every join so reinforcements can push a previously-ignored skirmish over
     * the bar). Idempotent via {@link BattleRecord#notifiedOwners}.
     */
    private void checkNotifyOwners(WorldServer world, BattleRecord b) {
        if (!TravellersConfig.BATTLE_EVENTS.notifyOwnerOnBattleStart || b.firstBlowTick < 0) return;
        Set<UUID> owners = new HashSet<>();
        for (BattleParticipant p : b.participants.values()) {
            if (p.groupKey.startsWith(BattleParticipant.OWNER_KEY_PREFIX)) {
                owners.add(UUID.fromString(p.groupKey.substring(BattleParticipant.OWNER_KEY_PREFIX.length())));
            }
        }
        for (UUID owner : owners) {
            if (b.notifiedOwners.contains(owner) || !meetsNotifySignificance(b, owner)) continue;
            if (!b.notifiedOwners.add(owner)) continue;
            BattleNotifications.started(world.getMinecraftServer(), owner, b.id, battlePlace(b),
                    countsFor(b, owner, world.getTotalWorldTime()));
        }
    }

    /** Enemy headcount in the fight vs. this owner's own participant headcount in the same fight. */
    private boolean meetsNotifySignificance(BattleRecord b, UUID owner) {
        TravellersConfig.BattleEvents cfg = TravellersConfig.BATTLE_EVENTS;
        String key = BattleParticipant.OWNER_KEY_PREFIX + owner;
        int ownSide = b.sideOfGroup(key);
        if (ownSide < 0) return false;
        int ownCount = 0;
        int enemyCount = 0;
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive) continue;
            if (p.groupKey.equals(key)) ownCount++;
            else if (p.side != ownSide) enemyCount++;
        }
        return enemyCount >= cfg.notifyMinEnemyCount && enemyCount >= ownCount * cfg.notifyMinEnemyToWorkerRatio;
    }

    private boolean shouldNotifyDeath(BattleRecord b, UUID owner) {
        TravellersConfig.BattleEvents cfg = TravellersConfig.BATTLE_EVENTS;
        if (!cfg.notifyOwnerOnDeath) return false;
        return cfg.notifyDeathAlwaysNotifies || meetsNotifySignificance(b, owner);
    }

    /** Headcounts of the battle seen from this owner's side, for the journal row. */
    private static BattleSummary.Counts countsFor(BattleRecord b, UUID owner, long now) {
        int ownSide = b.sideOfGroup(BattleParticipant.OWNER_KEY_PREFIX + owner);
        int[] standing = new int[2], fallen = new int[2];
        int raiders = 0, travellers = 0, henchmen = 0;
        for (BattleParticipant p : b.participants.values()) {
            int side = p.side == 1 ? 1 : 0;
            if (p.alive) standing[side]++;
            else fallen[side]++;
            if (p.side == ownSide) continue;
            if ("RAIDER".equals(p.groupKey)) raiders++;
            else if (p.groupKey.startsWith(BattleParticipant.OWNER_KEY_PREFIX)) henchmen++;
            else travellers++;
        }
        int own = ownSide == 1 ? 1 : 0;
        int seconds = b.firstBlowTick < 0 ? 0 : (int) (Math.max(0, now - b.firstBlowTick) / 20);
        return new BattleSummary.Counts(standing[own], fallen[own], standing[1 - own], fallen[1 - own],
                raiders, travellers, henchmen, seconds);
    }

    private static NoticePlace battlePlace(BattleRecord b) {
        return new NoticePlace(b.anchor, b.dimension, null);
    }

    /**
     * A hired worker fell: its owner's battle story gets the casualty and the owner will hear the result.
     * Call after {@code ZoneAssignments.onWorkerDied}, which decides whether it comes back.
     */
    private void notifyFallen(WorldServer world, BattleRecord b, BattleParticipant victim, UUID owner, String name) {
        b.notifiedOwners.add(owner);
        if (world.getMinecraftServer() == null) return;
        b.pendingFallen.computeIfAbsent(owner, k -> new ArrayList<>()).add(new BattleSummary.CasualtyEntry(name, true, 0f,
                WorkerLifecycleNotifications.respawnOutlook(world.getMinecraftServer(), victim.uuid)));
        flushFallen(world, b, false);
    }

    /** Sends each owner's gathered casualties, at most once per {@link #FALLEN_BEAT_TICKS} unless forced. */
    private void flushFallen(WorldServer world, BattleRecord b, boolean force) {
        if (b.pendingFallen.isEmpty()) return;
        long now = world.getTotalWorldTime();
        for (java.util.Iterator<Map.Entry<UUID, List<BattleSummary.CasualtyEntry>>> it = b.pendingFallen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, List<BattleSummary.CasualtyEntry>> e = it.next();
            Long last = b.lastFallenBeat.get(e.getKey());
            if (!force && last != null && now - last < FALLEN_BEAT_TICKS) continue;
            BattleNotifications.workersFell(world.getMinecraftServer(), e.getKey(), b.id, battlePlace(b), e.getValue(),
                    countsFor(b, e.getKey(), now));
            b.lastFallenBeat.put(e.getKey(), now);
            it.remove();
        }
    }

    /** One result per owner told about this battle: won (no foe left), lost (all of the owner's side fell), else broken off. */
    private void notifyOutcomes(WorldServer world, BattleRecord b) {
        for (UUID owner : b.notifiedOwners) {
            String key = BattleParticipant.OWNER_KEY_PREFIX + owner;
            int side = b.sideOfGroup(key);
            int fallen = 0;
            for (BattleParticipant p : b.participants.values()) {
                if (!p.alive && p.groupKey.equals(key)) fallen++;
            }
            BattleNotifications.Outcome outcome = BattleNotifications.Outcome.BROKEN_OFF;
            if (side >= 0) {
                int own = b.countAlive(side);
                int foes = b.countAlive(1 - side);
                if (foes == 0 && own > 0) outcome = BattleNotifications.Outcome.WON;
                else if (own == 0 && foes > 0 && fallen > 0) outcome = BattleNotifications.Outcome.LOST;
            }
            BattleNotifications.ended(world.getMinecraftServer(), owner, b.id, battlePlace(b), outcome, fallen,
                    countsFor(b, owner, world.getTotalWorldTime()));
        }
    }

    private static String resolveParticipantName(WorldServer world, VirtualTravellerManager vtm, UUID uuid) {
        VirtualTravellerRecord rec = vtm.getRecord(uuid);
        if (rec != null) {
            if (rec.name != null && !rec.name.isEmpty()) return rec.name;
            // Travellers/raiders never populate rec.name (only workers do), but their entityNbt
            // always carries the CustomName set at onInitialSpawn - use it before giving up.
            if (rec.entityNbt != null && rec.entityNbt.hasKey("CustomName", 8)) {
                String n = rec.entityNbt.getString("CustomName");
                if (n != null && !n.isEmpty()) return n;
            }
        }
        Entity e = world.getEntityFromUuid(uuid);
        if (e != null) {
            String n = e.getName();
            if (n != null && !n.isEmpty()) return n;
        }
        return "Unknown";
    }

    /** Moves every participant of {@code src} into {@code dst} (side mapping decided by caller). */
    void mergeBattles(WorldServer world, BattleRecord dst, BattleRecord src, Map<UUID, Integer> sideMapping,
                      VirtualTravellerManager vtm) {
        flushFallen(world, src, true);
        for (BattleParticipant p : src.participants.values()) {
            Integer side = sideMapping.get(p.uuid);
            if (side == null) continue;
            p.side = side;
            dst.participants.put(p.uuid, p);
            participantIndex.put(p.uuid, dst.id);
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            if (rec != null) rec.battleId = dst.id;
        }
        for (UUID owner : src.notifiedOwners) {
            BattleNotifications.merged(world.getMinecraftServer(), owner, src.id, dst.id);
        }
        dst.notifiedOwners.addAll(src.notifiedOwners);
        dst.startTick = Math.min(dst.startTick, src.startTick);
        if (dst.firstBlowTick < 0 || (src.firstBlowTick >= 0 && src.firstBlowTick < dst.firstBlowTick)) {
            dst.firstBlowTick = src.firstBlowTick;
            if (dst.lastBlowTick < 0) dst.anchor = src.anchor;
        }
        dst.lastBlowTick = Math.max(dst.lastBlowTick, src.lastBlowTick);
        for (int i = 0; i < src.lootPool.tagCount(); i++) {
            dst.lootPool.appendTag(src.lootPool.getCompoundTagAt(i));
        }
        src.lootPool = new NBTTagList();
        battles.remove(src.id);
        markDirty();
    }

    // -------------------------------------------------------------------------
    // Simulation
    // -------------------------------------------------------------------------

    private void simPass(WorldServer world, long now) {
        if (battles.isEmpty()) return;
        int dim = world.provider.getDimension();
        List<BattleRecord> list = new ArrayList<>();
        for (BattleRecord b : battles.values()) {
            if (b.dimension == dim) list.add(b);
        }
        if (list.isEmpty()) return;

        VirtualTravellerManager vtm = VirtualTravellerManager.get(world);
        int budget = Math.min(TravellersConfig.BATTLE_EVENTS.maxBattlesSimPerTick, list.size());
        for (int i = 0; i < budget; i++) {
            BattleRecord b = list.get((simCursor + i) % list.size());
            stepBattle(world, vtm, b, now);
        }
        simCursor = (simCursor + budget) % Math.max(1, list.size());
        markDirty();
    }

    private void stepBattle(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, long now) {
        int elapsed = (int) Math.min(Math.max(0, now - b.lastSimTick), MAX_STEP_TICKS);
        b.lastSimTick = now;
        if (elapsed <= 0) return;

        // Refresh loaded flags; drop pawns that vanished entirely (debug clears etc.)
        List<BattleParticipant> gone = null;
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive) continue;
            if (p.isPlayer) {
                // A player who logged out or changed dimension leaves the battle; they did not fall.
                if (world.getPlayerEntityByUUID(p.uuid) == null) {
                    if (gone == null) gone = new ArrayList<>();
                    gone.add(p);
                }
                continue;
            }
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            p.loaded = rec == null;
            if (rec == null && world.getEntityFromUuid(p.uuid) == null) {
                p.alive = false;
                participantIndex.remove(p.uuid);
            }
        }
        if (gone != null) {
            for (BattleParticipant p : gone) {
                b.participants.remove(p.uuid);
                participantIndex.remove(p.uuid);
            }
        }

        if (checkBattleEnd(world, vtm, b, now)) return;

        // Melee pressure per target (mirrors the loaded AttackTargetLimiter)
        Map<UUID, Integer> meleePerTarget = new HashMap<>();
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive || p.loaded || p.targetUuid == null) continue;
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            if (rec == null || rec.getCombatProfile().isArcher) continue;
            meleePerTarget.merge(p.targetUuid, 1, Integer::sum);
        }

        double meleeReach = TravellersConfig.BATTLE_EVENTS.meleeReach;
        List<BattleParticipant> toDisengage = null;
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive || p.loaded) continue;
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            if (rec == null) continue;
            p.noProgressTicks += elapsed;

            BattleParticipant target = resolveTarget(world, vtm, b, p, meleePerTarget, now);
            if (target == null) {
                // No reachable enemy: drift toward the fight
                moveToward(rec, b.center.getX() + 0.5, b.center.getZ() + 0.5, elapsed, 2.0, UnitSpeed.travelPercent());
                p.tetherStuckTicks = 0;
                p.lastTargetDist = -1;
                if (p.noProgressTicks > NO_PROGRESS_DISENGAGE_TICKS) {
                    if (toDisengage == null) toDisengage = new ArrayList<>();
                    toDisengage.add(p);
                }
                continue;
            }
            VirtualTravellerRecord trec = vtm.getRecord(target.uuid);
            EntityTraveller foe = trec == null ? loadedFoe(world, target.uuid) : null;
            if (trec == null && foe == null) {
                p.targetUuid = null;
                continue;
            }

            VirtualCombatProfile prof = rec.getCombatProfile();
            double tx = trec != null ? trec.posX : foe.posX;
            double tz = trec != null ? trec.posZ : foe.posZ;
            double dx = tx - rec.posX;
            double dz = tz - rec.posZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            rec.rotYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            if (p.lastTargetDist >= 0 && dist < p.lastTargetDist - 0.01) p.noProgressTicks = 0;
            p.lastTargetDist = dist;

            // INVARIANT: a virtual pawn never strikes a loaded foe (an invisible blow); it walks up to it and materializes.
            if (trec != null && prof.isArcher && dist >= prof.rangedMinDist && dist <= prof.rangedMaxDist) {
                strike(world, vtm, b, p, rec, target, trec, prof.rangedDamage, prof.rangedCooldownTicks,
                        false, elapsed, now);
                p.tetherStuckTicks = 0;
            } else if (trec != null && dist <= meleeReach) {
                strike(world, vtm, b, p, rec, target, trec, prof.meleeDamage, prof.attackCooldownTicks,
                        true, elapsed, now);
                p.tetherStuckTicks = 0;
            } else if (VirtualFormationSimulator.withinTether(world, vtm, rec, tx, tz, now)) {
                // An archer only keeps its distance while the target is beyond bow range; closer than the
                // bow's minimum it closes in and fights in melee, so two archers can never freeze apart.
                double stopAt = trec != null && prof.isArcher && dist > prof.rangedMaxDist
                        ? Math.max(prof.rangedMinDist, prof.rangedMaxDist * 0.8) : meleeReach * 0.75;
                moveToward(rec, tx, tz, elapsed, stopAt, UnitSpeed.attackPercent());
                p.tetherStuckTicks = 0;
            } else {
                // DEFEND/HOLD: this target strayed outside the leash - fall back to the anchor
                // (formation slot / leader position) instead of chasing, mirroring
                // EntityAIAttackMeleeCustom's chase abort. Long enough stuck like this and the pawn
                // leaves the fight individually, the offscreen counterpart of EntityTraveller's
                // generic stuck-target self-clear.
                if (rec.goal != null) {
                    moveToward(rec, rec.goal.getX() + 0.5, rec.goal.getZ() + 0.5, elapsed, 0.5, UnitSpeed.travelPercent());
                }
                p.tetherStuckTicks += elapsed;
                if (p.tetherStuckTicks > TETHER_DISENGAGE_TICKS) {
                    if (toDisengage == null) toDisengage = new ArrayList<>();
                    toDisengage.add(p);
                    continue;
                }
            }
            if (p.noProgressTicks > NO_PROGRESS_DISENGAGE_TICKS) {
                if (toDisengage == null) toDisengage = new ArrayList<>();
                toDisengage.add(p);
            }
        }

        // Loaded fighters stay counted while the battle lasts; one with no target that has walked far off leaves it.
        double leaveDistSq = (double) TravellersConfig.BATTLE_EVENTS.engagementRadius
                * TravellersConfig.BATTLE_EVENTS.engagementRadius;
        List<BattleParticipant> toRelease = null;
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive || !p.loaded || p.isPlayer) continue;
            Entity e = world.getEntityFromUuid(p.uuid);
            if (!(e instanceof EntityTraveller) || e.isDead) continue;
            EntityTraveller le = (EntityTraveller) e;
            if (le.getAttackTarget() != null && !le.getAttackTarget().isDead) continue;
            double dx = le.posX - (b.center.getX() + 0.5);
            double dz = le.posZ - (b.center.getZ() + 0.5);
            if (dx * dx + dz * dz > leaveDistSq) {
                if (toRelease == null) toRelease = new ArrayList<>();
                toRelease.add(p);
            }
        }
        if (toRelease != null) {
            for (BattleParticipant p : toRelease) {
                b.participants.remove(p.uuid);
                participantIndex.remove(p.uuid);
            }
        }
        if (toDisengage != null) {
            for (BattleParticipant p : toDisengage) {
                disengage(world, vtm, b, p, now);
            }
        }
        updateZone(world, vtm, b);
        flushFallen(world, b, false);
        checkNotifyOwners(world, b);
    }

    /** Recentres the battle on its pawns (camp assaults stay on the camp), sizes its zone, moves the anchor if the blows drifted. */
    private void updateZone(WorldServer world, VirtualTravellerManager vtm, BattleRecord b) {
        List<double[]> positions = new ArrayList<>();
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive) continue;
            double[] pos = p.isPlayer ? playerPosition(world, p.uuid) : foePosition(world, vtm, p.uuid);
            if (pos != null) positions.add(pos);
        }
        if (positions.isEmpty()) return;
        if (b.campAnchorId == null) {
            double sx = 0, sz = 0;
            for (double[] pos : positions) {
                sx += pos[0];
                sz += pos[1];
            }
            b.center = new BlockPos((int) Math.floor(sx / positions.size()), b.center.getY(),
                    (int) Math.floor(sz / positions.size()));
        }
        double far = 0;
        for (double[] pos : positions) {
            double dx = pos[0] - (b.center.getX() + 0.5);
            double dz = pos[1] - (b.center.getZ() + 0.5);
            far = Math.max(far, dx * dx + dz * dz);
        }
        b.zoneRadius = Math.max(BattleRecord.MIN_ZONE_RADIUS, (int) Math.ceil(Math.sqrt(far)));
        if (b.campAnchorId != null) {
            b.anchor = b.center;
        } else if (b.firstBlowTick < 0) {
            b.anchor = b.center;
        } else {
            double dx = b.blowX - (b.anchor.getX() + 0.5);
            double dz = b.blowZ - (b.anchor.getZ() + 0.5);
            if (dx * dx + dz * dz > ANCHOR_DRIFT * ANCHOR_DRIFT) {
                b.anchor = new BlockPos(Math.floor(b.blowX), b.center.getY(), Math.floor(b.blowZ));
            }
        }
    }

    @Nullable
    private static double[] playerPosition(WorldServer world, UUID uuid) {
        EntityPlayer pl = world.getPlayerEntityByUUID(uuid);
        return pl != null ? new double[]{pl.posX, pl.posZ} : null;
    }

    /**
     * A single DEFEND/HOLD pawn gives up on this battle without dying: leaves the participant list,
     * resumes its normal goal (mission/formation/follow-owner) right away, same as a survivor at
     * {@link #endBattle}. No loot changes hands - this is a withdrawal, not a kill. If this empties
     * one side, {@link #checkBattleEnd} concludes the fight normally on the battle's next sim pass.
     */
    private void disengage(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, BattleParticipant p, long now) {
        b.participants.remove(p.uuid);
        participantIndex.remove(p.uuid);
        blockRejoin(p.uuid, now);
        VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
        if (rec != null) {
            rec.battleId = null;
            vtm.forceRefreshGoal(world, rec, now);
        }
        markDirty();
    }

    /** A loaded enemy a virtual pawn can still strike, or null. */
    @Nullable
    private static EntityTraveller loadedFoe(WorldServer world, UUID uuid) {
        Entity e = world.getEntityFromUuid(uuid);
        if (!(e instanceof EntityTraveller) || e.isDead) return null;
        return ((EntityTraveller) e).getHealth() > 0f ? (EntityTraveller) e : null;
    }

    /** XZ position of an enemy, virtual or loaded; null when it is neither. */
    @Nullable
    private static double[] foePosition(WorldServer world, VirtualTravellerManager vtm, UUID uuid) {
        VirtualTravellerRecord rec = vtm.getRecord(uuid);
        if (rec != null) return new double[]{rec.posX, rec.posZ};
        EntityTraveller le = loadedFoe(world, uuid);
        return le != null ? new double[]{le.posX, le.posZ} : null;
    }

    /**
     * Same rules as loaded combat: a valid target is kept for the lock duration (TargetLockPolicy), after
     * which a strictly better-scored one (nearer, less contested) may take over; the melee cap is the
     * loaded AttackTargetLimiter formula, with the battle's age as the fight duration.
     */
    @Nullable
    private BattleParticipant resolveTarget(WorldServer world, VirtualTravellerManager vtm, BattleRecord b,
                                            BattleParticipant p, Map<UUID, Integer> meleePerTarget, long now) {
        BattleParticipant current = null;
        if (p.targetUuid != null) {
            BattleParticipant t = b.participants.get(p.targetUuid);
            if (t != null && t.alive && t.side != p.side && foePosition(world, vtm, t.uuid) != null) current = t;
            else p.targetUuid = null;
        }
        if (current != null && now - p.targetSinceTick < TargetLockPolicy.LOCK_TICKS) return current;

        VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
        if (rec == null) return current;
        boolean archer = rec.getCombatProfile().isArcher;
        boolean leaderBias = TravellersConfig.BATTLE_EVENTS.leaderSurvivalBias;
        int meleeCap = AttackTargetLimiter.capFor(now - b.startTick, 0);

        BattleParticipant best = null;
        long bestScore = Long.MAX_VALUE;
        long currentScore = Long.MAX_VALUE;
        for (BattleParticipant t : b.participants.values()) {
            if (!t.alive || t.side == p.side || t.isPlayer) continue;
            double[] pos = foePosition(world, vtm, t.uuid);
            if (pos == null) continue;
            double dx = pos[0] - rec.posX;
            double dz = pos[1] - rec.posZ;
            long distSq = (long) (dx * dx + dz * dz);
            // The current target already counts in meleePerTarget: it is not "full" for its own attacker.
            int others = meleePerTarget.getOrDefault(t.uuid, 0) - (t == current ? 1 : 0);
            boolean full = !archer && others >= meleeCap;
            boolean shielded = leaderBias && t.isGroupLeader;
            long score = distSq + (full ? 1_000_000L : 0) + (shielded ? 4_000_000L : 0);
            if (t == current) currentScore = score;
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best == null || best == current || (current != null && bestScore >= currentScore)) {
            if (current != null) p.targetSinceTick = now; // re-evaluated and kept: next look in a full lock
            return current;
        }
        if (current != null && !archer) meleePerTarget.merge(current.uuid, -1, Integer::sum);
        p.targetUuid = best.uuid;
        p.targetSinceTick = now;
        if (!archer) meleePerTarget.merge(best.uuid, 1, Integer::sum);
        return best;
    }

    /** One virtual pawn's attacks over the elapsed ticks against a virtual record. */
    private void strike(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, BattleParticipant attacker,
                        VirtualTravellerRecord attackerRec, BattleParticipant victim, VirtualTravellerRecord victimRec,
                        float rawDamage, int cooldown, boolean melee, int elapsed, long now) {
        attacker.attackTimer -= elapsed;
        int guard = 0;
        while (attacker.attackTimer <= 0 && victim.alive && guard++ < 8) {
            // The timer is <= 0 here: it says how long ago inside this step the strike really fell.
            long struck = Math.max(now - elapsed, now + attacker.attackTimer);
            attacker.attackTimer += Math.max(1, cooldown);
            if (MapStrikeLog.isActive()) {
                MapStrikeLog.record(melee ? MapStrikeLog.MELEE : MapStrikeLog.ARROW, attackerRec.dimension, struck,
                        attackerRec.uuid, attackerRec.posX, attackerRec.posZ, victimRec.posX, victimRec.posZ);
            }
            applyDamage(world, vtm, b, attacker, victim, victimRec, rawDamage, now);
            if (melee) VirtualItemWear.wearWeapon(attackerRec);
            VirtualItemWear.markCombat(attackerRec, now);
        }
        if (attacker.attackTimer < 0) attacker.attackTimer = 0;
    }

    private void applyDamage(WorldServer world, VirtualTravellerManager vtm, BattleRecord b,
                             BattleParticipant attacker, BattleParticipant victim, VirtualTravellerRecord victimRec,
                             float rawDamage, long now) {
        VirtualTravellerRecord attackerRec = vtm.getRecord(attacker.uuid);
        double ax = attackerRec != null ? attackerRec.posX : victimRec.posX;
        double az = attackerRec != null ? attackerRec.posZ : victimRec.posZ;
        b.recordBlow((ax + victimRec.posX) / 2, (az + victimRec.posZ) / 2, now);
        // Leader survival bias: a shielding follower nearby takes the hit instead
        if (victim.isGroupLeader && TravellersConfig.BATTLE_EVENTS.leaderSurvivalBias) {
            BattleParticipant shield = null;
            VirtualTravellerRecord shieldRec = null;
            double best = LEADER_SHIELD_RADIUS_SQ;
            for (BattleParticipant q : b.participants.values()) {
                if (!q.alive || q.isGroupLeader || q.side != victim.side
                        || !q.groupKey.equals(victim.groupKey)) continue;
                VirtualTravellerRecord qr = vtm.getRecord(q.uuid);
                if (qr == null) continue;
                double dx = qr.posX - victimRec.posX;
                double dz = qr.posZ - victimRec.posZ;
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    shield = q;
                    shieldRec = qr;
                }
            }
            if (shield != null) {
                victim = shield;
                victimRec = shieldRec;
            }
        }

        VirtualCombatProfile prof = victimRec.getCombatProfile();
        float scaled = (float) (rawDamage * TravellersConfig.BATTLE_EVENTS.virtualDamageMultiplier);
        if (prof.hasShield && world.rand.nextFloat() < virtualBlockChance()) {
            attacker.noProgressTicks = 0;
            victim.noProgressTicks = 0;
            VirtualItemWear.wearShield(victimRec, scaled);
            VirtualItemWear.markCombat(victimRec, now);
            return;
        }
        float dmg = CombatRules.getDamageAfterAbsorb(scaled, prof.armor, prof.toughness);
        victimRec.health = victimRec.getHealthResolved() - dmg;
        attacker.noProgressTicks = 0;
        victim.noProgressTicks = 0;
        if (victimRec.health <= 0f) {
            killVirtual(world, vtm, b, attacker, victim, victimRec, now);
        } else {
            // INVARIANT: no wear after a possibly lethal hit - the dropped loot is read from this NBT.
            VirtualItemWear.wearArmor(victimRec, scaled);
            VirtualItemWear.markCombat(victimRec, now);
        }
    }

    /** Parry duty cycle (raised / raised + rest) halved: only attacks from the front are blocked. */
    private static float virtualBlockChance() {
        float up = TravellersConfig.EQUIPMENT.shieldRaisedSeconds;
        float rest = TravellersConfig.EQUIPMENT.shieldRestSeconds;
        return 0.5f * up / Math.max(0.01f, up + rest);
    }

    private void killVirtual(WorldServer world, VirtualTravellerManager vtm, BattleRecord b,
                             BattleParticipant attacker, BattleParticipant victim, VirtualTravellerRecord rec, long now) {
        victim.alive = false;
        participantIndex.remove(victim.uuid);

        if (lootEnabledFor(rec)) {
            // Hired/non-hired workers drop everything, no roll — mirrors EntityTravellerWorker's
            // physical death (dropInventoryFull). Travellers/raiders use the same per-item chances
            // as the physical vanilla drop roll, for virtual/physical parity.
            boolean guaranteedFull = "WORKER".equals(rec.entityClass);
            NBTTagList items = collectLootItems(rec.entityNbt, guaranteedFull, world.rand);
            for (int i = 0; i < items.tagCount(); i++) {
                b.lootPool.appendTag(items.getCompoundTagAt(i));
            }
        }

        if (rec.campSiteId != null) {
            CampsiteManager.get(world).releaseOccupant(rec.uuid);
        }

        // Same bed rule as a loaded death: back beside its bed later, or the bed is freed.
        if (rec.isHiredFollower && "WORKER".equals(rec.entityClass) && rec.entityNbt != null
                && world.getMinecraftServer() != null) {
            NBTTagCompound nbt = rec.entityNbt.copy();
            com.jubitus.travellers.unit.EntityTravellerWorker.stripInventoryNbt(nbt);
            com.jubitus.travellers.zone.ZoneAssignments.onWorkerDied(world.getMinecraftServer(), rec.uuid,
                    rec.followOwnerUUID, com.jubitus.travellers.zone.ZoneRef.read(nbt), nbt);
        }

        // After the bed rule, so the notice can say whether the worker comes back.
        if (rec.isHiredFollower && rec.followOwnerUUID != null && shouldNotifyDeath(b, rec.followOwnerUUID)) {
            notifyFallen(world, b, victim, rec.followOwnerUUID, rec.name == null ? "" : rec.name);
        }

        // A virtually-dying rider's mount has no physical presence to leave abandoned offscreen (no
        // chunk to spawn a loose horse into without force-loading it) - its NBT is simply dropped with
        // the record, same as a loaded rider's mount is left as an ordinary loose horse where it died.

        com.jubitus.travellers.follow.GroupSuccession.onLeaderDied(world, rec);
        if (rec.isHiredFollower && world.getMinecraftServer() != null) {
            com.jubitus.travellers.follow.group.WorkerGroupService.onLeaderDied(world.getMinecraftServer(),
                    rec.followOwnerUUID, rec.uuid, com.jubitus.travellers.follow.group.WorkerGroupService.captureOrder(rec));
        }
        com.jubitus.travellers.debug.autotest.AutotestInvariants.onVirtualDeath(rec.uuid);
        vtm.removeRecord(world, rec.uuid);
        markDirty();
    }

    /** Which per-type loot toggle gates a victim's gear entering the battle's loot pool. */
    private static boolean lootEnabledFor(VirtualTravellerRecord rec) {
        switch (rec.entityClass) {
            case "RAIDER":
                return TravellersConfig.BATTLE_EVENTS.dropLootRaiders;
            case "WORKER":
                return rec.isHiredFollower
                        ? TravellersConfig.BATTLE_EVENTS.dropLootHiredWorkers
                        : TravellersConfig.BATTLE_EVENTS.dropLootWorkers;
            default:
                return TravellersConfig.BATTLE_EVENTS.dropLootTravellers;
        }
    }

    /**
     * Armor + hand items + backpack contents, skipping ghost display copies. Workers ({@code
     * guaranteedFull}) drop everything; travellers/raiders roll the same per-item chances as the
     * physical death drop ({@code TravellersConfig.EQUIPMENT}), for virtual/physical parity.
     */
    private static NBTTagList collectLootItems(NBTTagCompound entityNbt, boolean guaranteedFull, Random rand) {
        NBTTagList out = new NBTTagList();
        if (entityNbt == null) return out;
        float equipChance = TravellersConfig.EQUIPMENT.equipmentDropChance;
        appendStacks(out, entityNbt.getTagList("ArmorItems", 10), guaranteedFull, equipChance, rand);
        appendStacks(out, entityNbt.getTagList("HandItems", 10), guaranteedFull, equipChance, rand);
        if (entityNbt.hasKey("TravellerInv", 10)) {
            NBTTagList backpack = entityNbt.getCompoundTag("TravellerInv").getTagList("Items", 10);
            if (guaranteedFull) {
                appendStacks(out, backpack, true, 1f, rand);
            } else {
                appendBackpackStacksLimited(out, backpack, rand);
            }
        }
        return out;
    }

    private static void appendStacks(NBTTagList out, NBTTagList src, boolean guaranteedFull, float chance, Random rand) {
        for (int i = 0; i < src.tagCount(); i++) {
            NBTTagCompound tag = src.getCompoundTagAt(i);
            ItemStack s = new ItemStack(tag);
            if (s.isEmpty() || TravellerEquipmentManager.isGhostItem(s)) continue;
            // Player-gifted gear is always returned, mirroring TravellerEquipmentManager.dropPlayerGiftEquipment.
            if (guaranteedFull || isPlayerGiftStack(tag) || rand.nextFloat() < chance) out.appendTag(tag.copy());
        }
    }

    /** Mirrors TravellerEquipmentManager#dropBackpackLoot: shuffled, capped, per-item chance. */
    private static void appendBackpackStacksLimited(NBTTagList out, NBTTagList src, Random rand) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < src.tagCount(); i++) order.add(i);
        Collections.shuffle(order, rand);

        float chance = TravellersConfig.EQUIPMENT.backpackDropChance;
        int max = TravellersConfig.EQUIPMENT.backpackMaxDrops;
        int dropped = 0;
        for (int idx : order) {
            NBTTagCompound tag = src.getCompoundTagAt(idx);
            ItemStack s = new ItemStack(tag);
            if (s.isEmpty() || TravellerEquipmentManager.isGhostItem(s)) continue;
            if (isPlayerGiftStack(tag)) {           // always returned, doesn't count against the cap
                out.appendTag(tag.copy());
                continue;
            }
            if (dropped >= max) continue;
            if (rand.nextFloat() < chance) {
                out.appendTag(tag.copy());
                dropped++;
            }
        }
    }

    private static boolean isPlayerGiftStack(NBTTagCompound stackNbt) {
        if (!stackNbt.hasKey("tag", 10)) return false;
        return stackNbt.getCompoundTag("tag").getBoolean(TravellerEquipmentManager.PLAYER_GIFT_TAG);
    }

    private boolean checkBattleEnd(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, long now) {
        int aliveA = b.countAlive(0);
        int aliveB = b.countAlive(1);
        boolean cleared = aliveA == 0 || aliveB == 0;
        boolean quiet = b.firstBlowTick < 0
                ? now - b.startTick > APPROACH_END_TICKS
                : now - b.lastBlowTick > LULL_END_TICKS;
        boolean timeout = now - b.startTick > TravellersConfig.BATTLE_EVENTS.maxBattleDurationTicks;
        if (!cleared && !quiet && !timeout) return false;
        endBattle(world, vtm, b, cleared);
        return true;
    }

    /**
     * @param allowPillage false forces the whole loot pool straight to the ground (timeout, draw,
     *                      or a forced/debug end never counts as a clean victory).
     */
    private void endBattle(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, boolean allowPillage) {
        long now = world.getTotalWorldTime();
        for (BattleParticipant p : b.participants.values()) {
            participantIndex.remove(p.uuid);
            if (!p.alive) continue;
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            if (rec == null) continue;
            rec.battleId = null;
            // A fight that ended without a winner must not restart on the very next detection pass.
            if (!allowPillage) blockRejoin(p.uuid, now);
            // Leader gone without a death hook: elect a successor so survivors don't stand forever
            if (rec.groupLeaderUUID != null && vtm.getRecord(rec.groupLeaderUUID) == null
                    && world.getEntityFromUuid(rec.groupLeaderUUID) == null) {
                rec.groupLeaderUUID = com.jubitus.travellers.follow.GroupSuccession.onLeaderMissing(world,
                        rec.groupLeaderUUID, rec.uuid);
            }
            if (rec.escortLeaderUUID != null && vtm.getRecord(rec.escortLeaderUUID) == null
                    && world.getEntityFromUuid(rec.escortLeaderUUID) == null && world.getMinecraftServer() != null) {
                // null afterwards = this survivor now leads, or follows its owner
                rec.escortLeaderUUID = com.jubitus.travellers.follow.group.WorkerGroupService.onLeaderMissing(
                        world.getMinecraftServer(), rec.followOwnerUUID, rec.escortLeaderUUID, rec.uuid);
            }
            // Recompute the formation goal right away instead of leaving it stale (frozen since the
            // battle started, per refreshAllGoals' battleId gate) for up to a full goal-refresh cycle
            // - a scattered survivor's return-to-formation starts the instant the fight ends.
            vtm.forceRefreshGoal(world, rec, now);
        }
        flushFallen(world, b, true);
        notifyOutcomes(world, b);
        distributeLootPool(world, vtm, b, allowPillage);
        battles.remove(b.id);
        int aliveA = b.countAlive(0);
        int aliveB = b.countAlive(1);
        b.winnerSide = aliveA > 0 && aliveB == 0 ? 0 : aliveB > 0 && aliveA == 0 ? 1 : -1;
        b.endTick = now;
        getRecentlyEnded(now);
        recentlyEnded.add(b);
        markDirty();
    }

    private void releaseAllBattles(WorldServer world) {
        VirtualTravellerManager vtm = VirtualTravellerManager.get(world);
        for (BattleRecord b : new ArrayList<>(battles.values())) {
            endBattle(world, vtm, b, false);
        }
    }

    /**
     * Clean win (one side wiped, the other not, no timeout): surviving virtual winners get first
     * pick at upgrading their equipment from the pool; whatever is left (and all of the pool on a
     * timeout/draw/forced end) becomes a deferred ground drop at the battle's center.
     */
    private void distributeLootPool(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, boolean allowPillage) {
        if (b.lootPool.tagCount() == 0) return;

        if (allowPillage && TravellersConfig.BATTLE_EVENTS.battleLootingEnabled) {
            boolean aliveA = b.countAlive(0) > 0;
            boolean aliveB = b.countAlive(1) > 0;
            if (aliveA != aliveB) {
                pillageForSurvivors(world, vtm, b, aliveA ? 0 : 1);
            }
        }

        if (b.lootPool.tagCount() > 0) {
            pendingLoot.add(new PendingLootDrop(b.dimension, b.center.getX() + 0.5, b.center.getZ() + 0.5,
                    b.center.getY(), b.lootPool, world.getTotalWorldTime(), "Battlefield"));
            if (pendingLoot.size() > MAX_PENDING_LOOT) pendingLoot.remove(0);
        }
        b.lootPool = new NBTTagList();
    }

    /** Only still-virtual survivors (never materialized pawns, which keep their physical AI/inventory) pillage. */
    private void pillageForSurvivors(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, int winnerSide) {
        for (BattleParticipant p : b.participants.values()) {
            if (b.lootPool.tagCount() == 0) break;
            if (p.side != winnerSide || !p.alive || p.loaded) continue;
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            if (rec == null) continue;
            b.lootPool = pillageOne(world, rec, b.lootPool);
        }
    }

    /** Mirrors VirtualTravellerManager.tryVirtualEquipExchange: temp unspawned entity, mutate, re-serialize.
     *  Returns the remaining (unclaimed + displaced) pool. */
    private NBTTagList pillageOne(WorldServer world, VirtualTravellerRecord rec, NBTTagList pool) {
        EntityTraveller temp = createTempEntity(world, rec.entityClass);
        temp.readFromNBT(rec.entityNbt);
        temp.setUniqueId(rec.uuid);

        NBTTagList remaining = new NBTTagList();
        boolean changed = false;
        for (int i = 0; i < pool.tagCount(); i++) {
            NBTTagCompound stackTag = pool.getCompoundTagAt(i);
            ItemStack candidate = new ItemStack(stackTag);
            if (candidate.isEmpty()) continue;
            ItemStack displaced = tryEquipUpgrade(temp, candidate);
            if (displaced == candidate) {
                remaining.appendTag(stackTag);
            } else {
                changed = true;
                if (!displaced.isEmpty()) {
                    NBTTagCompound dTag = new NBTTagCompound();
                    displaced.writeToNBT(dTag);
                    remaining.appendTag(dTag);
                }
            }
        }

        if (changed) {
            NBTTagCompound updated = new NBTTagCompound();
            temp.writeToNBT(updated);
            rec.entityNbt = updated;
            rec.invalidateNbtCaches();
        }
        return remaining;
    }

    /** Returns the candidate stack unchanged if not claimed, otherwise the displaced old stack (possibly empty). */
    private static ItemStack tryEquipUpgrade(EntityTraveller temp, ItemStack candidate) {
        if (temp.equipment.isWeapon(candidate) && temp.equipment.isWeaponUpgrade(candidate)) {
            ItemStack old = temp.getHeldItemMainhand();
            temp.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, candidate);
            return old;
        }
        if (TravellerEquipmentManager.isShield(candidate) && temp.equipment.wantsShield(candidate)) {
            ItemStack old = temp.getHeldItemOffhand();
            temp.setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot.OFFHAND, candidate);
            return old;
        }
        if (candidate.getItem() instanceof net.minecraft.item.ItemArmor) {
            net.minecraft.inventory.EntityEquipmentSlot slot =
                    ((net.minecraft.item.ItemArmor) candidate.getItem()).armorType;
            if (temp.equipment.isArmorForSlot(candidate, slot) && temp.equipment.isArmorUpgrade(candidate, slot)) {
                ItemStack old = temp.getItemStackFromSlot(slot);
                temp.setItemStackToSlot(slot, candidate);
                return old;
            }
        }
        return candidate;
    }

    private static EntityTraveller createTempEntity(WorldServer world, String entityClass) {
        if ("WORKER".equals(entityClass)) {
            return new EntityTravellerWorker(world);
        }
        if ("RAIDER".equals(entityClass)) {
            return new EntityTravellerRaider(world);
        }
        return new EntityTraveller(world);
    }

    private static void moveToward(VirtualTravellerRecord rec, double tx, double tz,
                                   int elapsedTicks, double stopAt, double percent) {
        double dx = tx - rec.posX;
        double dz = tz - rec.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= stopAt || dist < 0.01) return;
        double step = Math.min(UnitSpeed.blocksPerTick(percent) * elapsedTicks, dist - stopAt);
        rec.posX += (dx / dist) * step;
        rec.posZ += (dz / dist) * step;
    }

    // -------------------------------------------------------------------------
    // Loot reconcile (deferred drops materialize when a player is near)
    // -------------------------------------------------------------------------

    private void reconcileLoot(WorldServer world, long now) {
        if (pendingLoot.isEmpty()) return;
        int expiry = TravellersConfig.BATTLE_EVENTS.lootExpiryTicks;
        double range = world.getMinecraftServer().getPlayerList().getViewDistance() * 16.0;
        int dim = world.provider.getDimension();
        int deposited = 0;
        boolean changed = false;

        Iterator<PendingLootDrop> it = pendingLoot.iterator();
        while (it.hasNext()) {
            PendingLootDrop drop = it.next();
            if (drop.dimension != dim) continue;
            if (expiry > 0 && now - drop.createdTick > expiry) {
                it.remove();
                changed = true;
                continue;
            }
            if (deposited >= MAX_LOOT_DEPOSITS_PER_PASS) break;
            if (!isPlayerNear(world, drop.x, drop.z, range)) continue;
            BlockPos probe = new BlockPos((int) drop.x, 64, (int) drop.z);
            if (!world.isBlockLoaded(probe)) continue;

            depositLoot(world, drop);
            it.remove();
            changed = true;
            deposited++;
        }
        if (changed) markDirty();
    }

    private static void depositLoot(WorldServer world, PendingLootDrop drop) {
        int bx = (int) Math.floor(drop.x);
        int bz = (int) Math.floor(drop.z);
        int y = surfaceYIgnoringFoliage(world, bx, bz);
        for (int i = 0; i < drop.items.tagCount(); i++) {
            ItemStack s = new ItemStack(drop.items.getCompoundTagAt(i));
            if (s.isEmpty()) continue;
            double ox = (world.rand.nextDouble() - 0.5) * 1.5;
            double oz = (world.rand.nextDouble() - 0.5) * 1.5;
            EntityItem ei = new EntityItem(world, drop.x + ox, y + 0.5, drop.z + oz, s);
            ei.setDefaultPickupDelay();
            world.spawnEntity(ei);
        }
    }

    /** Ground level at (x,z) for loot: the real ground under trees, or the water surface. */
    private static int surfaceYIgnoringFoliage(WorldServer world, int x, int z) {
        int y = ColumnGround.terrain(world, x, z, true);
        return y != ColumnGround.NONE ? y : world.getHeight(x, z);
    }

    private static boolean isPlayerNear(WorldServer world, double x, double z, double range) {
        double r2 = range * range;
        for (EntityPlayerMP p : world.getMinecraftServer().getPlayerList().getPlayers()) {
            if (p.world != world) continue;
            double dx = x - p.posX;
            double dz = z - p.posZ;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        battles.clear();
        pendingLoot.clear();
        participantIndex.clear();
        NBTTagList list = nbt.getTagList("Battles", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            try {
                BattleRecord b = BattleRecord.fromNbt(list.getCompoundTagAt(i));
                battles.put(b.id, b);
                for (BattleParticipant p : b.participants.values()) {
                    if (p.alive) participantIndex.put(p.uuid, b.id);
                }
            } catch (Throwable ignored) {
            }
        }
        NBTTagList loot = nbt.getTagList("PendingLoot", 10);
        for (int i = 0; i < loot.tagCount(); i++) {
            try {
                pendingLoot.add(PendingLootDrop.fromNbt(loot.getCompoundTagAt(i)));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (BattleRecord b : battles.values()) {
            list.appendTag(b.toNbt());
        }
        nbt.setTag("Battles", list);
        NBTTagList loot = new NBTTagList();
        for (PendingLootDrop d : pendingLoot) {
            loot.appendTag(d.toNbt());
        }
        nbt.setTag("PendingLoot", loot);
        return nbt;
    }
}
