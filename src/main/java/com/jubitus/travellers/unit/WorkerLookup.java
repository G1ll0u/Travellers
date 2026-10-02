package com.jubitus.travellers.unit;

import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/** Finds a worker by id: its loaded body in any world first, else its virtual record. */
public final class WorkerLookup {

    private WorkerLookup() {
    }

    @Nullable
    public static EntityTravellerWorker findLoaded(MinecraftServer server, UUID id) {
        for (WorldServer ws : server.worlds) {
            Entity e = ws.getEntityFromUuid(id);
            if (e instanceof EntityTravellerWorker && !e.isDead) return (EntityTravellerWorker) e;
        }
        return null;
    }

    @Nullable
    public static VirtualTravellerRecord findRecord(MinecraftServer server, UUID id) {
        VirtualTravellerRecord r = VirtualTravellerManager.get(server.getWorld(0)).getRecord(id);
        return r != null && "WORKER".equals(r.entityClass) ? r : null;
    }

    /** The loaded worker hired by {@code owner}; null when not loaded or not theirs. */
    @Nullable
    public static EntityTravellerWorker findLoadedOwned(MinecraftServer server, UUID owner, UUID id) {
        EntityTravellerWorker w = findLoaded(server, id);
        return w != null && isOwnedBy(w, owner) ? w : null;
    }

    /** The worker hired by {@code owner}, loaded or virtual; null when it doesn't exist or isn't theirs. */
    @Nullable
    public static Owned findOwned(MinecraftServer server, UUID owner, UUID id) {
        EntityTravellerWorker live = findLoaded(server, id);
        if (live != null) return isOwnedBy(live, owner) ? new Owned(live, null) : null;
        VirtualTravellerRecord r = findRecord(server, id);
        return r != null && r.isHiredFollower && owner.equals(r.followOwnerUUID) ? new Owned(null, r) : null;
    }

    /** A loaded worker addressed by entity id from a client screen, if hired by that player. */
    @Nullable
    public static EntityTravellerWorker ownedById(EntityPlayer player, int entityId) {
        Entity e = player.world.getEntityByID(entityId);
        if (!(e instanceof EntityTravellerWorker) || e.isDead) return null;
        EntityTravellerWorker w = (EntityTravellerWorker) e;
        return isOwnedBy(w, player.getUniqueID()) ? w : null;
    }

    public static boolean isOwnedBy(EntityTravellerWorker w, UUID owner) {
        return w.isHiredFollower() && owner.equals(w.getFollowOwnerId());
    }

    /** Exactly one of {@link #live} / {@link #record} is set. */
    public static final class Owned {
        @Nullable
        public final EntityTravellerWorker live;
        @Nullable
        public final VirtualTravellerRecord record;

        Owned(@Nullable EntityTravellerWorker live, @Nullable VirtualTravellerRecord record) {
            this.live = live;
            this.record = record;
        }
    }
}
