package com.jubitus.travellers.utils.mobs;

import com.google.common.base.Optional;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;

public class PlayerHostility {
    private final EntityTravellerWorker w;

    // --- retaliation engagement (server-only runtime memory) ---
    private final java.util.Map<UUID, Long> engagedOwnersUntil = new java.util.HashMap<>();

    // --- follower mirroring (server) ---
    private int mirrorCooldown = 0;


    // Source of truth ici
    public final Set<UUID> hostilePlayers = new HashSet<>();
    private final List<String> hostileNamesClient = new ArrayList<>();

    // Per-worker local hostiles (auto-retaliation); does NOT sync to escorts
    public final Set<UUID> localHostilePlayers = new HashSet<>();


    public PlayerHostility(EntityTravellerWorker worker) {
        this.w = worker;
    }

    public Set<UUID> getHostilePlayers() {
        return hostilePlayers;
    }

    public boolean isPlayerMarkedHostile(@Nullable EntityPlayer p) {
        if (p == null) return false;
        return isOwnerEffectivelyHostile(p.getUniqueID());
    }


    public void addHostilePlayer(@Nullable UUID id) {
        if (id == null) return;
        hostilePlayers.add(id);
    }

    public void removeHostilePlayer(@Nullable UUID id) {
        if (id == null) return;
        hostilePlayers.remove(id);
    }

    public void setHostile(UUID target, boolean hostile) {
        if (target == null) return;
        if (hostile) hostilePlayers.add(target);
        else hostilePlayers.remove(target);
    }


    public Set<UUID> getEffectiveHostilePlayers() {
        // Effective = declared (leader/self) + local
        Set<UUID> out = new HashSet<>();
        EntityTravellerWorker leader = getGroupLeaderOrSelf();
        if (leader != null && leader != w) out.addAll(leader.getHostility().hostilePlayers);
        else out.addAll(hostilePlayers);

        out.addAll(localHostilePlayers);
        return out;
    }

    public List<String> getHostileNamesClient() {
        return hostileNamesClient;
    }

    public void setHostileNamesClient(List<String> names) {
        hostileNamesClient.clear();
        hostileNamesClient.addAll(names);
    }

    // ----------------------------------------------------------------

    public EntityTravellerWorker getHostileListOwner() {
        UUID leaderId = getEscortLeaderUUID();
        if (leaderId != null) {
            EntityTravellerWorker leader = resolveEscortLeader(leaderId);
            if (leader != null && !leader.isDead) return leader;
        }
        return w;
    }

    @Nullable
    public UUID getEscortLeaderUUID() {
        Optional<UUID> o = w.getDataManager().get(EntityTravellerWorker.ESCORT_LEADER_DM);
        return o.isPresent() ? o.get() : null;
    }

    @Nullable
    public EntityTravellerWorker resolveEscortLeader(UUID id) {
        if (id == null) return null;
        if (!(w.world instanceof WorldServer)) return null;
        Entity e = ((WorldServer) w.world).getEntityFromUuid(id);
        return (e instanceof EntityTravellerWorker) ? (EntityTravellerWorker) e : null;
    }

    public EntityTravellerWorker getGroupLeaderOrSelf() {
        UUID leaderId = getEscortLeaderUUID();
        if (leaderId != null) {
            EntityTravellerWorker leader = resolveEscortLeader(leaderId);
            if (leader != null && !leader.isDead) {
                return leader;
            }
        }
        // IMPORTANT: do NOT clear escortLeader here. Let worker tick logic decide.
        return w;
    }



    public void syncHostileListToEscorts() {
        if (w.world.isRemote) return;
        if (!(w.world instanceof WorldServer)) return;

        MinecraftServer server = ((WorldServer) w.world).getMinecraftServer();
        if (server == null) return;

        UUID ownerId = w.getFollowOwnerId();
        if (ownerId == null) return;

        UUID leaderId = w.getUniqueID();
        Set<UUID> src = new HashSet<>(hostilePlayers);

        for (WorldServer ws : server.worlds) {
            List<EntityTravellerWorker> escorts = ws.getEntities(EntityTravellerWorker.class, other ->
                    other != null
                            && other.isEntityAlive()
                            && other.isHiredFollower()
                            && Objects.equals(other.getFollowOwnerId(), ownerId)
                            && leaderId.equals(other.getEscortLeaderUUID())
            );

            for (EntityTravellerWorker other : escorts) {
                PlayerHostility h = other.getHostility();
                h.hostilePlayers.clear();
                h.hostilePlayers.addAll(src);
            }
        }
    }
    public void tickServer() {
        if (w.world.isRemote) return;

        // Mirror leader hostile list into local set so we persist it if leader unloads/dies
        tickMirrorLeaderHostiles();

        // Expire retaliation engagements
        cleanupEngagements();
    }

    private void tickMirrorLeaderHostiles() {
        // Only followers need mirroring
        if (!w.isHiredFollower()) return;

        UUID leaderId = getEscortLeaderUUID();
        if (leaderId == null) return;

        // every ~2s
        if (--mirrorCooldown > 0) return;
        mirrorCooldown = 40;

        EntityTravellerWorker leader = resolveEscortLeader(leaderId);
        if (leader == null || leader.isDead) return;

        // must be same owner to be meaningful
        if (!java.util.Objects.equals(leader.getFollowOwnerId(), w.getFollowOwnerId())) return;

        // Copy leader list into our local list (this is the persistence trick)
        java.util.Set<UUID> src = leader.getHostility().hostilePlayers;
        if (!hostilePlayers.equals(src)) {
            hostilePlayers.clear();
            hostilePlayers.addAll(src);
        }
    }

    // Engage enemy owner for N ticks (retaliation window)
    public void engageOwner(UUID enemyOwner, int durationTicks) {
        if (enemyOwner == null || durationTicks <= 0) return;
        long now = w.world.getTotalWorldTime();
        long until = now + durationTicks;

        Long prev = engagedOwnersUntil.get(enemyOwner);
        if (prev == null || prev < until) {
            engagedOwnersUntil.put(enemyOwner, until);
        }
    }

    // True while engaged (auto-cleans expired)
    public boolean isEngagedOwner(UUID enemyOwner) {
        if (enemyOwner == null) return false;
        Long until = engagedOwnersUntil.get(enemyOwner);
        if (until == null) return false;

        long now = w.world.getTotalWorldTime();
        if (until <= now) {
            engagedOwnersUntil.remove(enemyOwner);
            return false;
        }
        return true;
    }

    private void cleanupEngagements() {
        long now = w.world.getTotalWorldTime();
        java.util.Iterator<java.util.Map.Entry<UUID, Long>> it = engagedOwnersUntil.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() == null || e.getValue() <= now) it.remove();
        }
    }
    public boolean addLocalHostile(@Nullable UUID id) {
        if (id == null) return false;
        return localHostilePlayers.add(id);
    }

    public boolean removeLocalHostile(@Nullable UUID id) {
        if (id == null) return false;
        return localHostilePlayers.remove(id);
    }

    // "Declared war" (manual list) — does NOT include localHostilePlayers
    public boolean isOwnerDeclaredHostile(@Nullable UUID ownerId) {
        if (ownerId == null) return false;

        EntityTravellerWorker leader = getGroupLeaderOrSelf();
        if (leader != null && leader != w) {
            return leader.getHostility().hostilePlayers.contains(ownerId);
        }
        return hostilePlayers.contains(ownerId);
    }

    // Used for targeting — declared OR local
    public boolean isOwnerEffectivelyHostile(@Nullable UUID ownerId) {
        if (ownerId == null) return false;
        return isOwnerDeclaredHostile(ownerId) || localHostilePlayers.contains(ownerId);
    }

    // GUI display: union of declared + local for THIS worker
    public Set<UUID> getHostilesForGui() {
        Set<UUID> out = new HashSet<>();
        // declared list source (leader if present, else self)
        EntityTravellerWorker leader = getGroupLeaderOrSelf();
        if (leader != null && leader != w) out.addAll(leader.getHostility().hostilePlayers);
        else out.addAll(hostilePlayers);

        // plus local
        out.addAll(localHostilePlayers);
        return out;
    }

}
