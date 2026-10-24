package com.jubitus.travellers.virtual;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VirtualTravellerRecord {

    public final UUID uuid;
    public double posX;
    public double posY;
    public double posZ;
    public float rotYaw;
    public final int dimension;

    @Nullable public BlockPos goal;

    // Quick-access for goal refresh
    public boolean isHiredFollower;
    @Nullable public UUID followOwnerUUID;
    public boolean isCatchupMode;
    @Nullable public UUID catchupTargetUUID;

    // Worker mode name (IDLE / TO_VILLAGE / RETURNING / RECRUITING)
    public String workerMode = "IDLE";

    // True when a Go order is active (IDLE mode but heading to a specific location)
    public boolean goOrderActive;

    // Mirrors EntityTravellerWorker.followOrderActive: false = Follow OFF, worker stays put
    public boolean followOrderActive = true;

    // Non-null when this worker is following another worker as escort (not just the player)
    @Nullable public UUID escortLeaderUUID;

    // Destination used for forced materialization when the worker arrives
    @Nullable public BlockPos jobGoal;

    // Duty zone assignment (independent of Home/workerMode - see the Stable zone system).
    // Deliberately not folded into workerMode/goal so Home and Duty stay independent axes offscreen
    // too, matching the loaded EntityTravellerWorker's separate field pair.
    @Nullable public String dutyKind;
    @Nullable public BlockPos dutyZoneCenter;

    // Personal self-travel in progress (Go Home / Go To Duty), mirrored from the loaded worker so the
    // trip keeps advancing offscreen and survives a virtualize -> materialize round-trip mid-route.
    // personalTravelGoal is the concrete destination; it is cleared (and the flags with it) the tick
    // the record arrives, see VirtualTravellerManager.refreshGoal.
    public boolean goingHome;
    public boolean goingToDuty;
    @Nullable public BlockPos personalTravelGoal;

    // Full entity state for restoration (non-final so it can be updated after virtual delivery)
    public NBTTagCompound entityNbt;

    // Home village, used to count virtual workers per village in the spawner
    @Nullable public BlockPos homeVillage;

    // --- Regionalized destination choice (Plan B) ---------------------------------
    // Immutable identity mirrored from entity NBT at virtualize; the ring
    // round-trip as-is (see the traveller-state checklist in CLAUDE.md).
    @Nullable public String cultureId;              // entity NBT "CultureId"
    public long[] recentDestinations = new long[0]; // ring of last N chosen dests, packed XZ, newest first
    public float wanderlust;                        // mirror of TravelCommon.wanderlust
    public float exploreChance;                     // mirror of TravelCommon.exploreChance

    /** Populates the Plan B identity mirrors from the frozen entity NBT. */
    public void captureIdentityFromNbt() {
        if (entityNbt == null) return;
        if (entityNbt.hasKey("CultureId")) cultureId = entityNbt.getString("CultureId");
        if (entityNbt.hasKey("Wanderlust")) wanderlust = entityNbt.getFloat("Wanderlust");
        if (entityNbt.hasKey("ExploreChance")) exploreChance = entityNbt.getFloat("ExploreChance");
        if (entityNbt.hasKey("RecentDests", 11)) {
            recentDestinations = com.jubitus.travellers.travel.destination
                    .RecentDestinationRing.fromIntPairs(entityNbt.getIntArray("RecentDests"));
        }
    }

    /** Records a freshly chosen destination on the ring and mirrors it into entityNbt for materialize. */
    public void noteChosenDestination(BlockPos dest, int maxN) {
        recentDestinations = com.jubitus.travellers.travel.destination
                .RecentDestinationRing.push(recentDestinations, dest, maxN);
        if (entityNbt != null) {
            entityNbt.setIntArray("RecentDests", com.jubitus.travellers.travel.destination
                    .RecentDestinationRing.toIntPairs(recentDestinations));
        }
    }

    public long virtualizedTick;
    public long lastGoalRefreshTick;

    // Position at the moment of virtualization — never updated during virtual movement.
    public double virtualizedPosX;
    public double virtualizedPosY;
    public double virtualizedPosZ;

    // Last world tick the offscreen simulation actually moved this record. Memory-only on purpose:
    // after a restart every record reads as "has not moved", which is the conservative answer for
    // the anchored-formation hold below (a group nobody has moved stays exactly where it was left).
    public long lastVirtualMoveTick = Long.MIN_VALUE;

    // Stable formation slot index within the escort leader's group, assigned once by
    // VirtualFormationSimulator (mirrors FollowFormationCoordinator's persistentOrdinals for the
    // loaded path) and kept for the worker's lifetime. -1 = unassigned (new worker, or a record
    // loaded from a save written before this field existed).
    public int formationOrdinal = -1;

    // Entity class discriminator — determines which entity class to reconstruct at materialization.
    // "WORKER" (default), "TRAVELLER", or "RAIDER"
    public String entityClass = "WORKER";

    // Trip: the ordered village stops (mirror of TravelCommon.route/routeIndex). tripIndex = current
    // stop, -1 = no trip. Distinct from the itinerary, which is the path polyline of the current leg.
    public List<BlockPos> tripStops = new ArrayList<>();
    public int tripIndex = -1;

    // Current leg's itinerary + cursor, handed over as-is to/from the loaded entity.
    public com.jubitus.travellers.pathing.itinerary.ItineraryState itinerary =
            new com.jubitus.travellers.pathing.itinerary.ItineraryState();

    @Nullable
    public BlockPos currentTripStop() {
        return tripIndex >= 0 && tripIndex < tripStops.size() ? tripStops.get(tripIndex) : null;
    }

    @Nullable
    public BlockPos finalTripStop() {
        return tripStops.isEmpty() ? null : tripStops.get(tripStops.size() - 1);
    }

    public void setTrip(List<BlockPos> stops, int index) {
        tripStops = new ArrayList<>(stops);
        tripIndex = stops.isEmpty() ? -1 : Math.max(0, Math.min(index, stops.size() - 1));
    }

    // Transient diagnostic only (ROUTING.debug): last tick advanceVirtual logged this record's
    // position-trail deviation from its route chord. Never persisted.
    public transient long lastTrailLogTick;

    // Non-null for traveller/raider group followers and merchant escorts (unhired workers): the leader they track.
    @Nullable public UUID groupLeaderUUID;

    // Rolled once at spawn, group-atomic exactly like `mounted` below (the leader rolls, every
    // follower copies its result) - a group's shape must not change depending on materialization
    // order. Stores WorkerFormationStyle.name(); applied to the entity unconditionally at
    // materialize (see VirtualTravellerManager.materializeEntity), since entityNbt alone only
    // carries a style for an entity that was already real at some point (TravellerSoftSpawner's
    // virtual-only group creation never had one).
    @Nullable public String groupFormationStyle;

    // Rolled once at spawn (group-atomic for traveller/raider groups: the leader rolls, every
    // follower copies its result). Consumed at materialization to bond a real horse via
    // TravellerMountSupport.bondNewMount — materialize never re-rolls, so a group stays uniformly
    // mounted or on-foot no matter when each member happens to materialize. Also drives the offscreen
    // speed bonus (advanceVirtual) once the record has actually gone virtual while mounted.
    public boolean mounted;

    // Full NBT of the bonded horse, captured when a mounted entity virtualizes (the horse itself is
    // removed from the world - not left behind, not despawned as an ordinary horse). Non-null only
    // after that has happened at least once; materialize reconstructs the exact same horse (same
    // UUID, same tame/saddle/attributes/age/color) from this instead of bonding a fresh one.
    @Nullable public NBTTagCompound mountNbt;

    // Whether the rider was in the saddle, leading its horse or leaving it hitched when it went
    // virtual (MountMode.name()), so materialize puts the pair back exactly as the player last saw
    // them instead of always dropping the rider into the saddle.
    @Nullable public String mountMode;
    /** The current village stay, moved here from the entity at virtualize (see VillageVisitState). */
    @Nullable public com.jubitus.travellers.village.visit.VillageVisitState visit;
    /** A hired worker's recruiting tour, moved here at virtualize (authoritative over entityNbt). */
    @Nullable public com.jubitus.travellers.worker.RecruitTour recruitTour;

    // Camp occupation cache (P3). The campsite occupant is the source of truth; these are re-derived
    // on virtualize. When set, the entity halts its route and sits at its slot.
    @Nullable public UUID campSiteId;
    public int campSlotIndex = -1;
    // Blocks re-claiming a camp right after leaving one (prevents daytime loitering). Persisted so a
    // server restart mid-cooldown can't be used to instantly re-claim a camp just left.
    public long campCooldownUntil = 0L;

    // Cached display data for the worldmap (set at virtualization)
    public String name = "";
    public int ownerColor = 0xFFFFFF;

    // Current health. -1 = unknown (old record): resolved lazily from entityNbt / profile max.
    public float health = -1f;
    // Mirrors EntityAIAutoEat.recoverToFull: once healing starts below the threshold, commit to
    // healing all the way to max HP rather than stopping as soon as it crosses back above it.
    public boolean recoveringToFull = false;

    // Non-null while this record is fighting in a virtual battle — normal goal refresh,
    // movement, job arrivals and camp claims are all suspended.
    @Nullable public UUID battleId;

    // Transient cache derived from entityNbt (never persisted)
    private transient com.jubitus.travellers.battle.VirtualCombatProfile combatProfile;

    /** Not saved: last world tick the offscreen village stay was advanced (see tickVirtualVisit). */
    public long visitClockTick = -1L;

    /** Combat stats derived once from the frozen entity NBT (recomputed if entityNbt is replaced). */
    public com.jubitus.travellers.battle.VirtualCombatProfile getCombatProfile() {
        if (combatProfile == null) {
            combatProfile = com.jubitus.travellers.battle.VirtualCombatProfile
                    .fromEntityNbt(entityNbt, entityClass);
        }
        return combatProfile;
    }

    /** Invalidates transient caches after entityNbt is rewritten (virtual missions, equip swaps). */
    public void invalidateNbtCaches() {
        combatProfile = null;
    }

    /** Current health, resolving unknown (-1) from entityNbt or the profile max. */
    public float getHealthResolved() {
        if (health < 0f) {
            float fromNbt = entityNbt != null ? entityNbt.getFloat("Health") : 0f;
            health = fromNbt > 0f ? fromNbt : getCombatProfile().maxHealth;
        }
        return health;
    }

    /** Owner UUIDs this hired worker's owner has declared hostile (account-wide, see PlayerEnemyRelations). */
    public java.util.Set<UUID> getDeclaredHostileOwners(net.minecraft.world.WorldServer world) {
        if (followOwnerUUID == null) return java.util.Collections.emptySet();
        return com.jubitus.travellers.combat.PlayerEnemyRelations.get(world).getHostiles(followOwnerUUID);
    }

    public VirtualTravellerRecord(UUID uuid, double posX, double posY, double posZ, float rotYaw,
                               int dimension, NBTTagCompound entityNbt) {
        this.uuid = uuid;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.rotYaw = rotYaw;
        this.dimension = dimension;
        this.entityNbt = entityNbt;
        this.virtualizedPosX = posX;
        this.virtualizedPosY = posY;
        this.virtualizedPosZ = posZ;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("UUID", uuid.toString());
        tag.setDouble("PX", posX);
        tag.setDouble("PY", posY);
        tag.setDouble("PZ", posZ);
        tag.setFloat("Yaw", rotYaw);
        tag.setInteger("Dim", dimension);
        tag.setTag("EntityData", entityNbt);
        tag.setBoolean("Hired", isHiredFollower);
        if (followOwnerUUID != null) tag.setString("OwnerUUID", followOwnerUUID.toString());
        tag.setBoolean("Catchup", isCatchupMode);
        if (catchupTargetUUID != null) tag.setString("CatchupUUID", catchupTargetUUID.toString());
        tag.setString("Mode", workerMode);
        tag.setBoolean("GoOrder", goOrderActive);
        tag.setBoolean("FollowOrder", followOrderActive);
        if (escortLeaderUUID != null) tag.setString("EscortLeader", escortLeaderUUID.toString());
        if (goal != null) {
            tag.setInteger("GX", goal.getX());
            tag.setInteger("GY", goal.getY());
            tag.setInteger("GZ", goal.getZ());
        }
        if (jobGoal != null) {
            tag.setInteger("JGX", jobGoal.getX());
            tag.setInteger("JGY", jobGoal.getY());
            tag.setInteger("JGZ", jobGoal.getZ());
        }
        if (homeVillage != null) {
            tag.setInteger("HVX", homeVillage.getX());
            tag.setInteger("HVZ", homeVillage.getZ());
        }
        if (dutyKind != null) tag.setString("DutyKind", dutyKind);
        if (dutyZoneCenter != null) {
            tag.setInteger("DZX", dutyZoneCenter.getX());
            tag.setInteger("DZY", dutyZoneCenter.getY());
            tag.setInteger("DZZ", dutyZoneCenter.getZ());
        }
        if (goingHome) tag.setBoolean("GoHome", true);
        if (goingToDuty) tag.setBoolean("GoDuty", true);
        if (personalTravelGoal != null) {
            tag.setInteger("PTX", personalTravelGoal.getX());
            tag.setInteger("PTY", personalTravelGoal.getY());
            tag.setInteger("PTZ", personalTravelGoal.getZ());
        }
        tag.setLong("VT", virtualizedTick);
        tag.setDouble("VPX", virtualizedPosX);
        tag.setDouble("VPY", virtualizedPosY);
        tag.setDouble("VPZ", virtualizedPosZ);
        tag.setString("Name", name != null ? name : "");
        tag.setInteger("OwnerColor", ownerColor);
        if (formationOrdinal >= 0) tag.setInteger("FormOrd", formationOrdinal);
        tag.setString("EC", entityClass);
        if (health >= 0f) tag.setFloat("HP", health);
        if (recoveringToFull) tag.setBoolean("RecFull", true);
        if (battleId != null) tag.setString("BattleId", battleId.toString());
        if (groupLeaderUUID != null) tag.setString("GroupLeader", groupLeaderUUID.toString());
        if (groupFormationStyle != null) tag.setString("GroupFormationStyle", groupFormationStyle);
        if (mounted) tag.setBoolean("Mounted", true);
        if (mountNbt != null) tag.setTag("MountData", mountNbt);
        if (mountMode != null) tag.setString("MountMode", mountMode);
        if (visit != null) tag.setTag("Visit", visit.write());
        if (recruitTour != null) tag.setTag("RecruitTour", recruitTour.write());
        if (campSiteId != null) {
            tag.setString("CampSite", campSiteId.toString());
            tag.setInteger("CampSlot", campSlotIndex);
        }
        if (campCooldownUntil > 0L) tag.setLong("CampCooldown", campCooldownUntil);
        if (cultureId != null) tag.setString("Cult", cultureId);
        if (recentDestinations.length > 0) {
            tag.setIntArray("RcD", com.jubitus.travellers.travel.destination
                    .RecentDestinationRing.toIntPairs(recentDestinations));
        }
        if (wanderlust != 0f) tag.setFloat("Wl", wanderlust);
        if (exploreChance != 0f) tag.setFloat("ExC", exploreChance);
        if (!tripStops.isEmpty()) {
            int[] trip = new int[tripStops.size() * 3];
            for (int i = 0; i < tripStops.size(); i++) {
                trip[i * 3] = tripStops.get(i).getX();
                trip[i * 3 + 1] = tripStops.get(i).getY();
                trip[i * 3 + 2] = tripStops.get(i).getZ();
            }
            tag.setIntArray("Trip", trip);
            tag.setInteger("TI", tripIndex);
        }
        itinerary.writeNbt(tag, "Itin");
        return tag;
    }

    public static VirtualTravellerRecord fromNbt(NBTTagCompound tag) {
        UUID uuid = UUID.fromString(tag.getString("UUID"));
        double px = tag.getDouble("PX");
        double py = tag.getDouble("PY");
        double pz = tag.getDouble("PZ");
        float yaw = tag.getFloat("Yaw");
        int dim = tag.getInteger("Dim");
        NBTTagCompound entityData = tag.getCompoundTag("EntityData");

        VirtualTravellerRecord r = new VirtualTravellerRecord(uuid, px, py, pz, yaw, dim, entityData);
        r.isHiredFollower = tag.getBoolean("Hired");
        if (tag.hasKey("OwnerUUID")) r.followOwnerUUID = UUID.fromString(tag.getString("OwnerUUID"));
        // Backwards-compat: old records didn't save Hired/OwnerUUID at the record level.
        // Fall back to the entity NBT which has always stored these fields.
        if (!r.isHiredFollower && entityData != null) {
            r.isHiredFollower = entityData.getBoolean("HiredFollower");
        }
        if (r.followOwnerUUID == null && entityData != null && entityData.hasKey("FollowOwnerIdLeast")) {
            r.followOwnerUUID = entityData.getUniqueId("FollowOwnerId");
        }
        r.isCatchupMode = tag.getBoolean("Catchup");
        if (tag.hasKey("CatchupUUID")) r.catchupTargetUUID = UUID.fromString(tag.getString("CatchupUUID"));
        r.workerMode = tag.hasKey("Mode") ? tag.getString("Mode") : "IDLE";
        r.goOrderActive = tag.getBoolean("GoOrder");
        r.followOrderActive = !tag.hasKey("FollowOrder") || tag.getBoolean("FollowOrder");
        if (tag.hasKey("EscortLeader")) r.escortLeaderUUID = UUID.fromString(tag.getString("EscortLeader"));
        if (tag.hasKey("GX")) r.goal = new BlockPos(tag.getInteger("GX"), tag.getInteger("GY"), tag.getInteger("GZ"));
        if (tag.hasKey("JGX")) r.jobGoal = new BlockPos(tag.getInteger("JGX"), tag.getInteger("JGY"), tag.getInteger("JGZ"));
        if (tag.hasKey("HVX")) r.homeVillage = new BlockPos(tag.getInteger("HVX"), 0, tag.getInteger("HVZ"));
        if (tag.hasKey("DutyKind")) r.dutyKind = tag.getString("DutyKind");
        if (tag.hasKey("DZX")) r.dutyZoneCenter = new BlockPos(tag.getInteger("DZX"), tag.getInteger("DZY"), tag.getInteger("DZZ"));
        // Fall back to the frozen entity NBT for records written before these record-level fields existed.
        r.goingHome = tag.getBoolean("GoHome") || (entityData != null && entityData.getBoolean("GoingHome"));
        r.goingToDuty = tag.getBoolean("GoDuty") || (entityData != null && entityData.getBoolean("GoingToDuty"));
        if (tag.hasKey("PTX")) {
            r.personalTravelGoal = new BlockPos(tag.getInteger("PTX"), tag.getInteger("PTY"), tag.getInteger("PTZ"));
        } else if (r.goingToDuty && r.dutyZoneCenter != null) {
            r.personalTravelGoal = r.dutyZoneCenter;
        } else if (r.goingHome && entityData != null && com.jubitus.travellers.zone.ZoneRef.read(entityData) != null) {
            r.personalTravelGoal = com.jubitus.travellers.zone.ZoneRef.read(entityData).center;
        }
        r.virtualizedTick = tag.getLong("VT");
        // Backward-compat: if not saved yet, treat as not moved (use current pos as origin)
        r.virtualizedPosX = tag.hasKey("VPX") ? tag.getDouble("VPX") : px;
        r.virtualizedPosY = tag.hasKey("VPY") ? tag.getDouble("VPY") : py;
        r.virtualizedPosZ = tag.hasKey("VPZ") ? tag.getDouble("VPZ") : pz;
        r.name = tag.hasKey("Name") ? tag.getString("Name") : "";
        r.ownerColor = tag.hasKey("OwnerColor") ? tag.getInteger("OwnerColor") : 0xFFFFFF;
        r.formationOrdinal = tag.hasKey("FormOrd") ? tag.getInteger("FormOrd") : -1;
        r.entityClass = tag.hasKey("EC") ? tag.getString("EC") : "WORKER";
        r.cultureId = tag.hasKey("Cult") ? tag.getString("Cult") : null;
        if (tag.hasKey("RcD", 11)) {
            r.recentDestinations = com.jubitus.travellers.travel.destination
                    .RecentDestinationRing.fromIntPairs(tag.getIntArray("RcD"));
        }
        r.wanderlust = tag.getFloat("Wl");
        r.exploreChance = tag.getFloat("ExC");
        // Back-compat / self-heal: an older record has none of the above but its frozen entity NBT does.
        if (r.cultureId == null) r.captureIdentityFromNbt();
        r.health = tag.hasKey("HP") ? tag.getFloat("HP") : -1f;
        r.recoveringToFull = tag.getBoolean("RecFull");
        if (tag.hasKey("BattleId")) r.battleId = UUID.fromString(tag.getString("BattleId"));
        if (tag.hasKey("GroupLeader")) r.groupLeaderUUID = UUID.fromString(tag.getString("GroupLeader"));
        if (tag.hasKey("GroupFormationStyle")) r.groupFormationStyle = tag.getString("GroupFormationStyle");
        r.mounted = tag.getBoolean("Mounted");
        if (tag.hasKey("MountData")) r.mountNbt = tag.getCompoundTag("MountData");
        if (tag.hasKey("MountMode")) r.mountMode = tag.getString("MountMode");
        r.visit = com.jubitus.travellers.village.visit.VillageVisitState.read(tag.hasKey("Visit") ? tag.getCompoundTag("Visit") : null);
        r.recruitTour = com.jubitus.travellers.worker.RecruitTour.read(tag.hasKey("RecruitTour") ? tag.getCompoundTag("RecruitTour") : null);
        if (tag.hasKey("CampSite")) {
            r.campSiteId = UUID.fromString(tag.getString("CampSite"));
            r.campSlotIndex = tag.getInteger("CampSlot");
        }
        r.campCooldownUntil = tag.getLong("CampCooldown");
        int[] trip = tag.getIntArray("Trip");
        if (trip.length >= 3 && trip.length % 3 == 0) {
            List<BlockPos> stops = new ArrayList<>();
            for (int i = 0; i < trip.length; i += 3) stops.add(new BlockPos(trip[i], trip[i + 1], trip[i + 2]));
            r.setTrip(stops, tag.getInteger("TI"));
        } else if (tag.hasKey("TR", 9)) {
            // Older save: the in-flight route is dropped, only its destination is kept.
            NBTTagList routeList = tag.getTagList("TR", 11);
            if (routeList.tagCount() > 0) {
                int[] arr = routeList.getIntArrayAt(routeList.tagCount() - 1);
                if (arr.length == 3) {
                    r.setTrip(java.util.Collections.singletonList(new BlockPos(arr[0], arr[1], arr[2])), 0);
                }
            }
        }
        r.itinerary.readNbt(tag, "Itin");
        return r;
    }
}
