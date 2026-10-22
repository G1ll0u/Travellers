package com.jubitus.travellers.battle;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.UUID;

/** One pawn in a virtual battle. Persisted inside its BattleRecord. */
public class BattleParticipant {

    /** Prefix for a hired-worker faction/coalition groupKey ("OWNER:<uuid>"). Shared so loaded-encounter
     *  detection (VirtualBattleManager) and offscreen detection (BattleEncounterScanner) never diverge. */
    public static final String OWNER_KEY_PREFIX = "OWNER:";

    public final UUID uuid;
    /** Faction/coalition key: "RAIDER", "CIVIL", or "OWNER:<uuid>" for hired workers. */
    public String groupKey = "CIVIL";
    @Nullable public UUID targetUuid;
    /** Ticks remaining until the next attack lands (counts down by elapsed sim ticks). */
    public int attackTimer;
    public boolean alive = true;
    /** Hired-worker group leader — targeted last and shielded by followers (survival bias). */
    public boolean isGroupLeader;
    /** A player fighting beside their workers: counted, never simulated nor targeted by virtual pawns. */
    public boolean isPlayer;
    /** Transient: true while the pawn is materialized (physical AI owns it). Revalidated each pass. */
    public transient boolean loaded;
    /** Transient: loaded, yet in no world right now (its chunk unloaded, server restarting). Still standing. */
    public transient boolean absent;
    /** Transient: last position seen loaded (diagnostics of a pawn gone absent). */
    public transient double lastX, lastZ;
    /** Transient: world tick it went absent; past a minute it leaves the battle (not fallen). */
    public transient long absentSince;
    /** Transient: elapsed sim ticks spent unable to close on a target outside DEFEND/HOLD tether.
     *  Mirrors EntityTraveller's stuckTargetTicks; past the threshold the pawn individually leaves
     *  its battle instead of waiting for the whole fight to resolve. Not persisted, like `loaded`. */
    public transient int tetherStuckTicks;

    /** Transient: elapsed sim ticks without a blow given or taken, a target closed in on, or (loaded) a target held.
     *  Past the threshold the pawn leaves the battle so no fight can pin a unit indefinitely. */
    public transient int noProgressTicks;
    /** Transient: distance to the current target at the previous sim step (-1 = none), to detect closing in. */
    public transient double lastTargetDist = -1;

    /** Transient: world tick the current target was adopted (0 after a reload: the lock is simply already over). */
    public transient long targetSinceTick;

    /** Transient: how the pawn moves between sim steps; set by each step, applied by every movement pass. */
    public transient MoveIntent move = MoveIntent.STAND;
    /** Transient: the point a {@link MoveIntent#TO_POINT} pawn walks to. */
    public transient double moveX, moveZ;
    /** Transient: how close the pawn stops to its target or point. */
    public transient double moveStopAt;
    /** Transient: its pace, in percent of a walking player. */
    public transient double movePercent;
    /** Transient: world tick its battle movement starts at: the offscreen step it made before joining already covers the ticks before. */
    public transient long movesFromTick;

    public enum MoveIntent { STAND, TO_TARGET, TO_POINT }

    public BattleParticipant(UUID uuid) {
        this.uuid = uuid;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("UUID", uuid.toString());
        tag.setString("Group", groupKey);
        if (targetUuid != null) tag.setString("Target", targetUuid.toString());
        tag.setInteger("Timer", attackTimer);
        tag.setBoolean("Alive", alive);
        if (isGroupLeader) tag.setBoolean("Leader", true);
        if (isPlayer) tag.setBoolean("Player", true);
        return tag;
    }

    public static BattleParticipant fromNbt(NBTTagCompound tag) {
        BattleParticipant p = new BattleParticipant(UUID.fromString(tag.getString("UUID")));
        p.groupKey = tag.hasKey("Group") ? tag.getString("Group") : "CIVIL";
        if (tag.hasKey("Target")) p.targetUuid = UUID.fromString(tag.getString("Target"));
        p.attackTimer = tag.getInteger("Timer");
        p.alive = !tag.hasKey("Alive") || tag.getBoolean("Alive");
        p.isGroupLeader = tag.getBoolean("Leader");
        p.isPlayer = tag.getBoolean("Player");
        return p;
    }
}
