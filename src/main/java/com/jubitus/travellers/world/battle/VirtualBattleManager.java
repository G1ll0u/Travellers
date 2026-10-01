package com.jubitus.travellers.world.battle;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import com.jubitus.travellers.entity.equipment.TravellerEquipmentManager;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.BattleSummary;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.notify.VanillaDeathMessage;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.notify.WorkerLifecycleNotifications;
import com.jubitus.travellers.world.camp.CampsiteManager;
import com.jubitus.travellers.world.virtualization.VirtualFormationSimulator;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
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
 * normal loaded AI and are tracked here by UUID only.
 */
public class VirtualBattleManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersVirtualBattles";

    // Same walking speed as VirtualTravellerManager's travel sim, scaled live off the
    // current TravellersConfig.SPEED.movementSpeed the same way (see currentSimStepBpt()).
    private static final double VIRTUAL_SPEED_BPT_BASELINE = 4.3 / 20.0;
    private static final double MOVEMENT_SPEED_BASELINE = 0.52;

    private static double currentVirtualSpeedBpt() {
        return VIRTUAL_SPEED_BPT_BASELINE
                * (TravellersConfig.SPEED.movementSpeed / MOVEMENT_SPEED_BASELINE);
    }
    private static final int LOOT_RECONCILE_INTERVAL = 20;
    private static final int MAX_LOOT_DEPOSITS_PER_PASS = 4;
    private static final int MAX_PENDING_LOOT = 512;
    // Clamp elapsed sim ticks so a long pause (restart, lag) can't burst hundreds of attacks
    private static final int MAX_STEP_TICKS = 200;
    private static final double LEADER_SHIELD_RADIUS_SQ = 8.0 * 8.0;
    // Elapsed sim ticks a DEFEND/HOLD pawn can spend leashed away from its target before it gives up
    // and leaves its battle individually. Mirrors EntityTraveller's stuckTargetTicks threshold (80).
    private static final int TETHER_DISENGAGE_TICKS = 80;

    private final Map<UUID, BattleRecord> battles = new LinkedHashMap<>();
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
                String name = entity.getName();
                VirtualTravellerManager vtm = VirtualTravellerManager.get(world);
                WorkerLifecycleNotifications.died(world.getMinecraftServer(), owner, uuid, name,
                        NoticePlace.of(entity), VanillaDeathMessage.from(entity, cause),
                        buildDeathSummary(world, vtm, b, p, name, entity.getPosition()));
            }
        }
        markDirty();
    }

    // -------------------------------------------------------------------------
    // Battle membership (called by the scanner)
    // -------------------------------------------------------------------------

    BattleRecord createBattle(WorldServer world, BlockPos center, long now, @Nullable UUID campAnchorId) {
        BattleRecord b = new BattleRecord(UUID.randomUUID(), world.provider.getDimension(), center, now);
        b.campAnchorId = campAnchorId;
        battles.put(b.id, b);
        markDirty();
        return b;
    }

    void addToBattle(WorldServer world, BattleRecord b, VirtualTravellerRecord rec, int side,
                     String groupKey, boolean isGroupLeader, long now) {
        if (rec.battleId != null || b.participants.containsKey(rec.uuid)) return;
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
    private void addLoadedParticipant(BattleRecord b, EntityTraveller entity, int side, String groupKey) {
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
     * Only tracked when a hired worker is on one side — that's the only case anyone needs notifying about.
     */
    public void onLoadedEngagement(WorldServer world, EntityTraveller attacker, EntityTraveller target) {
        TravellersConfig.BattleEvents cfg = TravellersConfig.BATTLE_EVENTS;
        if (!cfg.enableVirtualBattles || !cfg.detectLoadedEncounters) return;
        if (!involvesHiredWorker(attacker, target)) return;

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

    private static boolean involvesHiredWorker(EntityTraveller a, EntityTraveller b) {
        return (a instanceof EntityTravellerWorker
                        && ((EntityTravellerWorker) a).isHiredFollower())
                || (b instanceof EntityTravellerWorker
                        && ((EntityTravellerWorker) b).isHiredFollower());
    }

    /** Mirrors BattleEncounterScanner.groupKey for VirtualTravellerRecords, kept in sync so the two
     *  detection paths (offscreen scan / loaded engagement) never disagree on faction identity. */
    private static String loadedGroupKey(EntityTraveller e) {
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
        if (!TravellersConfig.BATTLE_EVENTS.notifyOwnerOnBattleStart) return;
        VirtualTravellerManager vtm = VirtualTravellerManager.get(world);
        Set<UUID> owners = new HashSet<>();
        for (BattleParticipant p : b.participants.values()) {
            if (p.groupKey.startsWith(BattleParticipant.OWNER_KEY_PREFIX)) {
                owners.add(UUID.fromString(p.groupKey.substring(BattleParticipant.OWNER_KEY_PREFIX.length())));
            }
        }
        for (UUID owner : owners) {
            if (b.notifiedOwners.contains(owner) || !meetsNotifySignificance(b, owner)) continue;
            if (!b.notifiedOwners.add(owner)) continue;
            NotificationService.notifyEventOffline(world.getMinecraftServer(), owner,
                    NotificationDefs.BATTLE_UNDER_ATTACK, buildStartSummary(world, vtm, b, owner));
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

    private BattleSummary buildStartSummary(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, UUID owner) {
        String key = BattleParticipant.OWNER_KEY_PREFIX + owner;
        int ownSide = b.sideOfGroup(key);
        List<String> attackers = new ArrayList<>();
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive || p.side == ownSide) continue;
            attackers.add(resolveParticipantName(world, vtm, p.uuid));
        }
        return new BattleSummary(b.center, b.dimension, Collections.emptyList(), attackers);
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
    void mergeBattles(BattleRecord dst, BattleRecord src, Map<UUID, Integer> sideMapping,
                      VirtualTravellerManager vtm) {
        for (BattleParticipant p : src.participants.values()) {
            Integer side = sideMapping.get(p.uuid);
            if (side == null) continue;
            p.side = side;
            dst.participants.put(p.uuid, p);
            participantIndex.put(p.uuid, dst.id);
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            if (rec != null) rec.battleId = dst.id;
        }
        dst.notifiedOwners.addAll(src.notifiedOwners);
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
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive) continue;
            VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
            p.loaded = rec == null;
            if (rec == null && world.getEntityFromUuid(p.uuid) == null) {
                p.alive = false;
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

            BattleParticipant target = resolveTarget(vtm, b, p, meleePerTarget);
            if (target == null) {
                // No virtual enemy reachable (all loaded or none left): drift toward the fight
                moveToward(rec, b.center.getX() + 0.5, b.center.getZ() + 0.5, elapsed, 2.0);
                p.tetherStuckTicks = 0;
                continue;
            }
            VirtualTravellerRecord trec = vtm.getRecord(target.uuid);
            if (trec == null) continue;

            VirtualCombatProfile prof = rec.getCombatProfile();
            double dx = trec.posX - rec.posX;
            double dz = trec.posZ - rec.posZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            rec.rotYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;

            if (prof.isArcher && dist >= prof.rangedMinDist && dist <= prof.rangedMaxDist) {
                fireAttacks(world, vtm, b, p, target, trec,
                        prof.rangedDamage, prof.rangedCooldownTicks, elapsed, now);
                p.tetherStuckTicks = 0;
            } else if (dist <= meleeReach) {
                fireAttacks(world, vtm, b, p, target, trec,
                        prof.meleeDamage, prof.attackCooldownTicks, elapsed, now);
                p.tetherStuckTicks = 0;
            } else if (VirtualFormationSimulator.withinTether(world, vtm, rec, trec.posX, trec.posZ, now)) {
                double stopAt = prof.isArcher ? Math.max(prof.rangedMinDist,
                        prof.rangedMaxDist * 0.8) : meleeReach * 0.75;
                moveToward(rec, trec.posX, trec.posZ, elapsed, stopAt);
                p.tetherStuckTicks = 0;
            } else {
                // DEFEND/HOLD: this target strayed outside the leash - fall back to the anchor
                // (formation slot / leader position) instead of chasing, mirroring
                // EntityAIAttackMeleeCustom's chase abort. Long enough stuck like this and the pawn
                // leaves the fight individually, the offscreen counterpart of EntityTraveller's
                // generic stuck-target self-clear (there is no per-unit "abandon this battle" path
                // otherwise - only the whole BattleRecord ending via wipe/timeout).
                if (rec.goal != null) {
                    moveToward(rec, rec.goal.getX() + 0.5, rec.goal.getZ() + 0.5, elapsed, 0.5);
                }
                p.tetherStuckTicks += elapsed;
                if (p.tetherStuckTicks > TETHER_DISENGAGE_TICKS) {
                    if (toDisengage == null) toDisengage = new ArrayList<>();
                    toDisengage.add(p);
                }
            }
        }
        if (toDisengage != null) {
            for (BattleParticipant p : toDisengage) {
                disengage(world, vtm, b, p, now);
            }
        }

        // Keep the marker centered on the actual fight (camp assaults stay camp-anchored)
        if (b.campAnchorId == null) {
            double sx = 0, sz = 0;
            int n = 0;
            for (BattleParticipant p : b.participants.values()) {
                if (!p.alive || p.loaded) continue;
                VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
                if (rec == null) continue;
                sx += rec.posX;
                sz += rec.posZ;
                n++;
            }
            if (n > 0) b.center = new BlockPos((int) (sx / n), b.center.getY(), (int) (sz / n));
        }
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
        VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
        if (rec != null) {
            rec.battleId = null;
            vtm.forceRefreshGoal(world, rec, now);
        }
        markDirty();
    }

    /** Current target if still valid (alive, enemy, virtual), else a fresh pick. */
    @Nullable
    private BattleParticipant resolveTarget(VirtualTravellerManager vtm, BattleRecord b,
                                            BattleParticipant p, Map<UUID, Integer> meleePerTarget) {
        if (p.targetUuid != null) {
            BattleParticipant t = b.participants.get(p.targetUuid);
            if (t != null && t.alive && t.side != p.side && vtm.getRecord(t.uuid) != null) return t;
            p.targetUuid = null;
        }

        VirtualTravellerRecord rec = vtm.getRecord(p.uuid);
        if (rec == null) return null;
        boolean archer = rec.getCombatProfile().isArcher;
        boolean leaderBias = TravellersConfig.BATTLE_EVENTS.leaderSurvivalBias;
        int meleeCap = TravellersConfig.BATTLE_EVENTS.maxMeleePerTarget;

        BattleParticipant best = null;
        long bestScore = Long.MAX_VALUE;
        for (BattleParticipant t : b.participants.values()) {
            if (!t.alive || t.side == p.side) continue;
            VirtualTravellerRecord trec = vtm.getRecord(t.uuid);
            if (trec == null) continue;
            double dx = trec.posX - rec.posX;
            double dz = trec.posZ - rec.posZ;
            long distSq = (long) (dx * dx + dz * dz);
            boolean full = !archer && meleePerTarget.getOrDefault(t.uuid, 0) >= meleeCap;
            boolean shielded = leaderBias && t.isGroupLeader;
            long score = distSq + (full ? 1_000_000L : 0) + (shielded ? 4_000_000L : 0);
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best != null) {
            p.targetUuid = best.uuid;
            if (!archer) meleePerTarget.merge(best.uuid, 1, Integer::sum);
        }
        return best;
    }

    private void fireAttacks(WorldServer world, VirtualTravellerManager vtm, BattleRecord b,
                             BattleParticipant attacker, BattleParticipant victim,
                             VirtualTravellerRecord victimRec, float rawDamage, int cooldown,
                             int elapsed, long now) {
        attacker.attackTimer -= elapsed;
        int guard = 0;
        while (attacker.attackTimer <= 0 && victim.alive && guard++ < 8) {
            attacker.attackTimer += Math.max(1, cooldown);
            applyDamage(world, vtm, b, attacker, victim, victimRec, rawDamage, now);
        }
        if (attacker.attackTimer < 0) attacker.attackTimer = 0;
    }

    private void applyDamage(WorldServer world, VirtualTravellerManager vtm, BattleRecord b,
                             BattleParticipant attacker, BattleParticipant victim, VirtualTravellerRecord victimRec,
                             float rawDamage, long now) {
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
        float dmg = CombatRules.getDamageAfterAbsorb(scaled, prof.armor, prof.toughness);
        victimRec.health = victimRec.getHealthResolved() - dmg;
        if (victimRec.health <= 0f) {
            killVirtual(world, vtm, b, attacker, victim, victimRec, now);
        }
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
            com.jubitus.travellers.entity.travellers.EntityTravellerWorker.stripInventoryNbt(nbt);
            com.jubitus.travellers.world.zone.ZoneAssignments.onWorkerDied(world.getMinecraftServer(), rec.uuid,
                    rec.followOwnerUUID, com.jubitus.travellers.world.zone.ZoneRef.read(nbt), nbt);
        }

        // After the bed rule, so the notice can say whether the worker comes back.
        if (rec.isHiredFollower && rec.followOwnerUUID != null && shouldNotifyDeath(b, rec.followOwnerUUID)) {
            String name = rec.name == null ? "" : rec.name;
            String attackerName = resolveParticipantName(world, vtm, attacker.uuid);
            BlockPos pos = new BlockPos((int) rec.posX, (int) rec.posY, (int) rec.posZ);
            WorkerLifecycleNotifications.died(world.getMinecraftServer(), rec.followOwnerUUID, rec.uuid, name,
                    new NoticePlace(pos, rec.dimension, rec.uuid),
                    VanillaDeathMessage.genericSlainBy(name, attackerName),
                    buildDeathSummary(world, vtm, b, victim, name, pos));
        }

        // A virtually-dying rider's mount has no physical presence to leave abandoned offscreen (no
        // chunk to spawn a loose horse into without force-loading it) - its NBT is simply dropped with
        // the record, same as a loaded rider's mount is left as an ordinary loose horse where it died.

        vtm.removeRecord(world, rec.uuid);
        markDirty();
    }

    private BattleSummary buildDeathSummary(WorldServer world, VirtualTravellerManager vtm, BattleRecord b,
                                             BattleParticipant victim, String name, BlockPos pos) {
        List<BattleSummary.CasualtyEntry> casualties = new ArrayList<>();
        casualties.add(new BattleSummary.CasualtyEntry(name, true, 0f));

        List<String> attackers = new ArrayList<>();
        for (BattleParticipant p : b.participants.values()) {
            if (p.side == victim.side || !p.alive) continue;
            attackers.add(resolveParticipantName(world, vtm, p.uuid));
        }

        return new BattleSummary(pos, b.dimension, casualties, attackers);
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
            if (s.isEmpty() || isGhostStack(tag)) continue;
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
            if (s.isEmpty() || isGhostStack(tag)) continue;
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

    private static boolean isGhostStack(NBTTagCompound stackNbt) {
        if (!stackNbt.hasKey("tag", 10)) return false;
        NBTTagCompound tag = stackNbt.getCompoundTag("tag");
        return tag.getBoolean(TravellerEquipmentManager.GHOST_SWORD_TAG)
                || tag.getBoolean(TravellerEquipmentManager.GHOST_BOW_TAG)
                || tag.getBoolean(TravellerEquipmentManager.GHOST_TORCH_TAG)
                || tag.getBoolean(TravellerEquipmentManager.GHOST_BLOCK_TAG)
                || tag.getBoolean(TravellerEquipmentManager.GHOST_ROD_TAG);
    }

    private static boolean isPlayerGiftStack(NBTTagCompound stackNbt) {
        if (!stackNbt.hasKey("tag", 10)) return false;
        return stackNbt.getCompoundTag("tag").getBoolean(TravellerEquipmentManager.PLAYER_GIFT_TAG);
    }

    private boolean checkBattleEnd(WorldServer world, VirtualTravellerManager vtm, BattleRecord b, long now) {
        int aliveA = b.countAlive(0);
        int aliveB = b.countAlive(1);
        boolean timeout = now - b.startTick > TravellersConfig.BATTLE_EVENTS.maxBattleDurationTicks;
        if (aliveA > 0 && aliveB > 0 && !timeout) return false;
        endBattle(world, vtm, b, !timeout);
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
            // Dead leader: unlink so survivors resume travel instead of standing forever
            if (rec.groupLeaderUUID != null && vtm.getRecord(rec.groupLeaderUUID) == null
                    && world.getEntityFromUuid(rec.groupLeaderUUID) == null) {
                rec.groupLeaderUUID = null;
                rec.itinerary.clear(); // forces a fresh itinerary
            }
            if (rec.escortLeaderUUID != null && vtm.getRecord(rec.escortLeaderUUID) == null
                    && world.getEntityFromUuid(rec.escortLeaderUUID) == null) {
                rec.escortLeaderUUID = null; // falls back to owner-follow
            }
            // Recompute the formation goal right away instead of leaving it stale (frozen since the
            // battle started, per refreshAllGoals' battleId gate) for up to a full goal-refresh cycle
            // - a scattered survivor's return-to-formation starts the instant the fight ends.
            vtm.forceRefreshGoal(world, rec, now);
        }
        distributeLootPool(world, vtm, b, allowPillage);
        battles.remove(b.id);
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
                                   int elapsedTicks, double stopAt) {
        double dx = tx - rec.posX;
        double dz = tz - rec.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= stopAt || dist < 0.01) return;
        double step = Math.min(currentVirtualSpeedBpt() * elapsedTicks, dist - stopAt);
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

    /**
     * Ground level at (x,z), scanning down through air, foliage, leaves and logs so loot
     * never lands on a tree canopy.
     */
    private static int surfaceYIgnoringFoliage(WorldServer world, int x, int z) {
        int y = Math.min(world.getActualHeight() - 1, world.getHeight(x, z) + 8);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (y > 1) {
            pos.setPos(x, y, z);
            net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
            net.minecraft.block.material.Material mat = state.getMaterial();
            boolean passThrough = mat == net.minecraft.block.material.Material.AIR
                    || mat == net.minecraft.block.material.Material.LEAVES
                    || mat == net.minecraft.block.material.Material.PLANTS
                    || mat == net.minecraft.block.material.Material.VINE
                    || mat == net.minecraft.block.material.Material.WOOD;
            if (!passThrough && (mat.blocksMovement() || mat.isLiquid())) return y + 1;
            y--;
        }
        return world.getHeight(x, z);
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
