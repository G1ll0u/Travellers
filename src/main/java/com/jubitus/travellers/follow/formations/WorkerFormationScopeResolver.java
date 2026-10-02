package com.jubitus.travellers.follow.formations;

import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class WorkerFormationScopeResolver {

    private WorkerFormationScopeResolver() {
    }

    @Nullable
    public static WorkerFormationScopeKey resolveRuntimeScope(@Nullable EntityLivingBase leader) {
        if (leader == null) {
            return null;
        }

        if (leader instanceof EntityPlayer) {
            return new WorkerFormationScopeKey(
                    WorkerFormationScopeKey.Type.PLAYER,
                    leader.getUniqueID()
            );
        }

        if (leader instanceof EntityTravellerWorker) {
            return new WorkerFormationScopeKey(
                    WorkerFormationScopeKey.Type.WORKER,
                    leader.getUniqueID()
            );
        }

        return null;
    }

    @Nullable
    public static OpenScope resolveOpenScope(EntityTravellerWorker openedWorker, EntityPlayer player) {
        if (openedWorker == null || player == null) {
            return null;
        }

        if (!openedWorker.isHiredFollower()) {
            return null;
        }

        UUID playerId = player.getUniqueID();
        if (!playerId.equals(openedWorker.getFollowOwnerId())) {
            return null;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }

        int ledCount = countFollowersForWorkerLeader(server, playerId, openedWorker.getUniqueID());
        if (ledCount > 0) {
            return new OpenScope(
                    new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, openedWorker.getUniqueID()),
                    openedWorker.getName(),
                    ledCount
            );
        }

        UUID escortLeaderId = openedWorker.getEscortLeaderUUID();
        if (escortLeaderId != null) {
            EntityTravellerWorker escortLeader = WorkerLookup.findLoaded(server, escortLeaderId);
            String label = escortLeader != null ? escortLeader.getName() : escortLeaderId.toString();

            return new OpenScope(
                    new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, escortLeaderId),
                    label,
                    countFollowersForWorkerLeader(server, playerId, escortLeaderId)
            );
        }

        return new OpenScope(
                new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.PLAYER, playerId),
                player.getName(),
                countDirectFollowersForPlayer(server, playerId)
        );
    }

    public static boolean canPlayerEditScope(EntityTravellerWorker openedWorker,
                                             EntityPlayer player,
                                             WorkerFormationScopeKey scope) {
        if (openedWorker == null || player == null || scope == null) {
            return false;
        }

        if (!openedWorker.isHiredFollower()) {
            return false;
        }

        UUID playerId = player.getUniqueID();
        if (!playerId.equals(openedWorker.getFollowOwnerId())) {
            return false;
        }

        if (scope.getType() == WorkerFormationScopeKey.Type.PLAYER) {
            return playerId.equals(scope.getId());
        }

        if (scope.getType() == WorkerFormationScopeKey.Type.WORKER) {
            if (openedWorker.getUniqueID().equals(scope.getId())) {
                return true;
            }

            UUID escortLeaderId = openedWorker.getEscortLeaderUUID();
            if (escortLeaderId != null && escortLeaderId.equals(scope.getId())) {
                return true;
            }

            EntityLivingBase effective = openedWorker.getEffectiveFollowTarget();
            return effective instanceof EntityTravellerWorker && effective.getUniqueID().equals(scope.getId());
        }

        return false;
    }

    public static int countFollowersForWorkerLeader(MinecraftServer server, UUID ownerId, UUID leaderId) {
        int count = 0;

        for (WorldServer ws : server.worlds) {
            List<EntityTravellerWorker> list = ws.getEntities(EntityTravellerWorker.class, w ->
                    w != null
                            && !w.isDead
                            && w.isHiredFollower()
                            && Objects.equals(ownerId, w.getFollowOwnerId())
                            && leaderId.equals(w.getEscortLeaderUUID())
            );
            count += list.size();
        }

        return count;
    }

    public static int countDirectFollowersForPlayer(MinecraftServer server, UUID playerId) {
        int count = 0;

        for (WorldServer ws : server.worlds) {
            List<EntityTravellerWorker> list = ws.getEntities(EntityTravellerWorker.class, w ->
                    w != null
                            && !w.isDead
                            && w.isHiredFollower()
                            && playerId.equals(w.getFollowOwnerId())
                            && w.getEscortLeaderUUID() == null
            );
            count += list.size();
        }

        return count;
    }

    public static final class OpenScope {
        public final WorkerFormationScopeKey scope;
        public final String label;
        public final int followerCount;

        public OpenScope(WorkerFormationScopeKey scope, String label, int followerCount) {
            this.scope = scope;
            this.label = label == null ? "" : label;
            this.followerCount = Math.max(0, followerCount);
        }
    }
}