package com.jubitus.travellers.battle;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Marching-group identity of one unit, loaded or virtual, so a battle enrols a whole group as soon as
 * one member fights. Two units are linked when they share a group key or one is the other's leader.
 */
final class BattleGroupLink {

    final UUID uuid;
    @Nullable final UUID leader;
    final String key;

    BattleGroupLink(UUID uuid, @Nullable UUID leader, String key) {
        this.uuid = uuid;
        this.leader = leader;
        this.key = key;
    }

    boolean linkedTo(BattleGroupLink o) {
        return key.equals(o.key) || uuid.equals(o.leader) || o.uuid.equals(leader);
    }

    static BattleGroupLink of(VirtualTravellerRecord r) {
        if (r.isHiredFollower && r.followOwnerUUID != null) {
            return hired(r.uuid, r.escortLeaderUUID, r.followOrderActive, r.followOwnerUUID);
        }
        return plain(r.uuid, r.groupLeaderUUID);
    }

    static BattleGroupLink of(EntityTraveller e) {
        if (e instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) e;
            if (w.isHiredFollower() && w.getFollowOwnerUUID() != null) {
                return hired(w.getUniqueID(), w.getEscortLeaderUUID(), w.isFollowOrderActive(), w.getFollowOwnerUUID());
            }
        }
        return plain(e.getUniqueID(), e.getGroupLeaderUUID());
    }

    /** A hired worker: its escort group, or the owner's own escort when it follows the owner directly. */
    static BattleGroupLink hired(UUID uuid, @Nullable UUID escortLeader, boolean followsOrder, UUID owner) {
        if (escortLeader != null) return new BattleGroupLink(uuid, escortLeader, "E:" + escortLeader);
        return new BattleGroupLink(uuid, null, followsOrder ? "O:" + owner : "E:" + uuid);
    }

    /** A traveller, raider or roaming worker: its spawn group, led by {@code groupLeader}. */
    static BattleGroupLink plain(UUID uuid, @Nullable UUID groupLeader) {
        return new BattleGroupLink(uuid, groupLeader, "G:" + (groupLeader != null ? groupLeader : uuid));
    }
}
