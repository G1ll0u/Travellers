package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes, per player, the positions of their own vision sources — their alive hired workers
 * (loaded + virtual) plus, if {@code TravellersConfig.WORLDMAP.playerIsVisionSource} is on, the
 * player's own position — that reveal foreign entities/battles on the player worldmap and
 * formation hub. Shared by {@link ForeignUnitMapStream} and {@link BattleMapStream} so both apply
 * the same rule.
 */
final class PlayerUnitVisionSource {

    private PlayerUnitVisionSource() {}

    static final class Pos {
        final int dim;
        final double x, z;
        Pos(int dim, double x, double z) { this.dim = dim; this.x = x; this.z = z; }
    }

    static Map<UUID, List<Pos>> computeOwnUnitPositions(MinecraftServer server) {
        Map<UUID, List<Pos>> out = new HashMap<>();

        if (TravellersConfig.WORLDMAP.playerIsVisionSource) {
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                out.computeIfAbsent(player.getUniqueID(), k -> new ArrayList<>())
                        .add(new Pos(player.dimension, player.posX, player.posZ));
            }
        }

        for (WorldServer ws : server.worlds) {
            int dim = ws.provider.getDimension();
            for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class,
                    e -> e != null && e.isEntityAlive())) {
                UUID owner = w.getFollowOwnerId();
                if (owner == null || !w.isHiredFollower()) continue;
                out.computeIfAbsent(owner, k -> new ArrayList<>())
                        .add(new Pos(dim, w.posX, w.posZ));
            }
        }

        WorldServer overworld = server.getWorld(0);
        if (overworld != null) {
            for (VirtualTravellerRecord vr : VirtualTravellerManager.get(overworld).getRecordsView()) {
                if (!vr.isHiredFollower || vr.followOwnerUUID == null) continue;
                out.computeIfAbsent(vr.followOwnerUUID, k -> new ArrayList<>())
                        .add(new Pos(vr.dimension, vr.posX, vr.posZ));
            }
        }

        return out;
    }

    /** True if (dim,x,z) is within radius of any of the player's own unit positions. */
    static boolean isVisible(List<Pos> ownPositions, int dim, double x, double z, double radius) {
        if (ownPositions == null) return false;
        double r2 = radius * radius;
        for (Pos p : ownPositions) {
            if (p.dim != dim) continue;
            double dx = p.x - x;
            double dz = p.z - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }
}
