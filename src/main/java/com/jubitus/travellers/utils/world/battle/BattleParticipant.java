package com.jubitus.travellers.utils.world.battle;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.UUID;

/** One pawn in a virtual battle. Persisted inside its BattleRecord. */
public class BattleParticipant {

    /** Prefix for a hired-worker faction/coalition groupKey ("OWNER:<uuid>"). Shared so loaded-encounter
     *  detection (VirtualBattleManager) and offscreen detection (BattleEncounterScanner) never diverge. */
    public static final String OWNER_KEY_PREFIX = "OWNER:";

    public final UUID uuid;
    /** 0 = side A, 1 = side B. */
    public int side;
    /** Faction/coalition key: "RAIDER", "CIVIL", or "OWNER:<uuid>" for hired workers. */
    public String groupKey = "CIVIL";
    @Nullable public UUID targetUuid;
    /** Ticks remaining until the next attack lands (counts down by elapsed sim ticks). */
    public int attackTimer;
    public boolean alive = true;
    /** Hired-worker group leader — targeted last and shielded by followers (survival bias). */
    public boolean isGroupLeader;
    /** Transient: true while the pawn is materialized (physical AI owns it). Revalidated each pass. */
    public transient boolean loaded;
    /** Transient: elapsed sim ticks spent unable to close on a target outside DEFEND/HOLD tether.
     *  Mirrors EntityTraveller's stuckTargetTicks; past the threshold the pawn individually leaves
     *  its battle instead of waiting for the whole fight to resolve. Not persisted, like `loaded`. */
    public transient int tetherStuckTicks;

    public BattleParticipant(UUID uuid) {
        this.uuid = uuid;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("UUID", uuid.toString());
        tag.setInteger("Side", side);
        tag.setString("Group", groupKey);
        if (targetUuid != null) tag.setString("Target", targetUuid.toString());
        tag.setInteger("Timer", attackTimer);
        tag.setBoolean("Alive", alive);
        if (isGroupLeader) tag.setBoolean("Leader", true);
        return tag;
    }

    public static BattleParticipant fromNbt(NBTTagCompound tag) {
        BattleParticipant p = new BattleParticipant(UUID.fromString(tag.getString("UUID")));
        p.side = tag.getInteger("Side");
        p.groupKey = tag.hasKey("Group") ? tag.getString("Group") : "CIVIL";
        if (tag.hasKey("Target")) p.targetUuid = UUID.fromString(tag.getString("Target"));
        p.attackTimer = tag.getInteger("Timer");
        p.alive = !tag.hasKey("Alive") || tag.getBoolean("Alive");
        p.isGroupLeader = tag.getBoolean("Leader");
        return p;
    }
}
