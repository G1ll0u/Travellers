package com.jubitus.travellers.follow;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.follow.net.MsgPlayerFollowState;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.notify.NotificationDef;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of "the player follows a unit hands-off": who follows whom, and when a session ends.
 * The walking itself is client-side (input injection); the server only validates, ends the session
 * on loss of the target and, later, teleports (boat, rescue).
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class PlayerFollowSessions {

    private static final double MAX_START_DISTANCE = 64.0D;
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final int HUNGRY_FOOD_LEVEL = 6;

    private static final class Session {
        final UUID target;
        final PlayerFollowTransport.Progress progress = new PlayerFollowTransport.Progress();

        Session(UUID target) {
            this.target = target;
        }
    }

    private static final Map<UUID, Session> SESSION_BY_PLAYER = new HashMap<>();

    private PlayerFollowSessions() {
    }

    /** Starts (or replaces) the player's session; ignored when the entity is not a followable unit in reach. */
    public static void start(EntityPlayerMP player, int entityId) {
        Entity e = player.world.getEntityByID(entityId);
        if (!isFollowable(e) || e.world != player.world || player.getDistanceSq(e) > MAX_START_DISTANCE * MAX_START_DISTANCE) {
            return;
        }
        SESSION_BY_PLAYER.put(player.getUniqueID(), new Session(e.getUniqueID()));
        TravellersNetwork.sendToPlayer(player, new MsgPlayerFollowState(e.getEntityId()));
    }

    public static void stop(EntityPlayerMP player) {
        stop(player, null);
    }

    private static void stop(EntityPlayerMP player, @Nullable NotificationDef reason) {
        if (SESSION_BY_PLAYER.remove(player.getUniqueID()) != null) {
            TravellersNetwork.sendToPlayer(player, new MsgPlayerFollowState(MsgPlayerFollowState.NONE));
            if (reason != null) NotificationService.notify(player, reason);
        }
    }

    public static boolean isFollowing(UUID playerId) {
        return SESSION_BY_PLAYER.containsKey(playerId);
    }

    @Nullable
    public static UUID targetOf(UUID playerId) {
        Session s = SESSION_BY_PLAYER.get(playerId);
        return s == null ? null : s.target;
    }

    // Raiders are hostile: never an escort target.
    private static boolean isFollowable(@Nullable Entity e) {
        return e instanceof EntityTraveller && !(e instanceof EntityTravellerRaider) && !e.isDead;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSION_BY_PLAYER.isEmpty()) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.getTickCounter() % CHECK_INTERVAL_TICKS != 0) return;

        Iterator<Map.Entry<UUID, Session>> it = SESSION_BY_PLAYER.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            Entity target = player.getServerWorld().getEntityFromUuid(entry.getValue().target);
            NotificationDef stopReason = stopReason(player, target);
            if (stopReason != null) {
                it.remove();
                TravellersNetwork.sendToPlayer(player, new MsgPlayerFollowState(MsgPlayerFollowState.NONE));
                NotificationService.notify(player, stopReason);
            } else {
                PlayerFollowTransport.tick(player, target, entry.getValue().progress, server.getTickCounter());
            }
        }
    }

    /** Why the hands-off walk must end now, or null to go on. */
    @Nullable
    private static NotificationDef stopReason(EntityPlayerMP player, @Nullable Entity target) {
        if (!isFollowable(target)) return NotificationDefs.FOLLOW_TARGET_LOST;
        if (((EntityTraveller) target).isInCombat()) return NotificationDefs.FOLLOW_STOPPED_COMBAT;
        // Below this vanilla forbids sprinting, so the player could no longer keep up.
        if (player.getFoodStats().getFoodLevel() <= HUNGRY_FOOD_LEVEL) return NotificationDefs.FOLLOW_STOPPED_HUNGRY;
        return null;
    }

    @SubscribeEvent
    public static void onPlayerDamaged(LivingDamageEvent event) {
        if (event.getAmount() <= 0f || !(event.getEntityLiving() instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityLiving();
        if (SESSION_BY_PLAYER.containsKey(player.getUniqueID())) stop(player, NotificationDefs.FOLLOW_STOPPED_HURT);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSION_BY_PLAYER.remove(event.player.getUniqueID());
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP) stop((EntityPlayerMP) event.player);
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof EntityPlayerMP) stop((EntityPlayerMP) event.getEntityLiving());
    }
}
