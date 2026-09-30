package com.jubitus.travellers.debug.village;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mixins.MixinResManagerPointsAccessor;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.debug.MsgVillageDebugView;
import com.jubitus.travellers.world.mill.MillFootprints;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.buildingmanagers.ResManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Streams the Millénaire buildings around each player who turned the debug view on, as the mod
 * itself reads them (footprints, tags, visit points, panels), so it can be checked against the world. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class VillageDebugView {

    private static final int UPDATE_EVERY_TICKS = 40;
    private static final Set<UUID> VIEWERS = ConcurrentHashMap.newKeySet();
    private static int tickCounter = 0;

    private VillageDebugView() {
    }

    public static void show(EntityPlayerMP player) {
        VIEWERS.add(player.getUniqueID());
        send(player);
    }

    public static void hide(EntityPlayerMP player) {
        VIEWERS.remove(player.getUniqueID());
        TravellerFollowNet.sendToPlayer(player, new MsgVillageDebugView());
    }

    public static boolean isShown(EntityPlayerMP player) {
        return VIEWERS.contains(player.getUniqueID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        VIEWERS.remove(e.player.getUniqueID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || VIEWERS.isEmpty()) return;
        if (++tickCounter % UPDATE_EVERY_TICKS != 0) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        for (UUID id : new ArrayList<>(VIEWERS)) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(id);
            if (player != null) send(player);
        }
    }

    private static void send(EntityPlayerMP player) {
        MsgVillageDebugView msg = new MsgVillageDebugView();
        msg.active = true;
        msg.labelDistance = TravellersConfig.VILLAGE_VISIT.debugLabelDistance;
        for (MillFootprints.Footprint f : MillFootprints.near(player.world, player.posX, player.posZ,
                TravellersConfig.VILLAGE_VISIT.debugViewRadius)) {
            msg.buildings.add(describe(f));
        }
        TravellerFollowNet.sendToPlayer(player, msg);
    }

    private static MsgVillageDebugView.Building describe(MillFootprints.Footprint f) {
        Building b = f.building;
        MsgVillageDebugView.Building out = new MsgVillageDebugView.Building();
        out.minX = f.minX;
        out.minY = f.minY;
        out.minZ = f.minZ;
        out.maxX = f.maxX;
        out.maxY = f.maxY;
        out.maxZ = f.maxZ;
        out.planKey = f.planKey();
        out.townHall = b.isTownhall;
        out.name = safe(() -> b.getNativeBuildingName());
        out.tags = safe(() -> String.join(",", b.getTags()));
        if (b.isTownhall) out.village = safe(() -> b.getVillageQualifiedName());

        ResManager res = b.getResManager();
        if (res != null) {
            MixinResManagerPointsAccessor raw = (MixinResManagerPointsAccessor) res;
            addPoint(out, MsgVillageDebugView.POINT_ENTRANCE, raw.travellers$getPathStartPos());
            addPoint(out, MsgVillageDebugView.POINT_LEISURE, raw.travellers$getLeasurePos());
            addPoint(out, MsgVillageDebugView.POINT_SELLING, raw.travellers$getSellingPos());
            addPoint(out, MsgVillageDebugView.POINT_BED, raw.travellers$getSleepingPos());
            if (res.signs != null) {
                for (Point p : res.signs) addPoint(out, MsgVillageDebugView.POINT_PANEL, p);
            }
        }
        return out;
    }

    private static void addPoint(MsgVillageDebugView.Building out, byte kind, @Nullable Point p) {
        if (p == null) return;
        out.points.add(new int[]{kind, p.getiX(), p.getiY(), p.getiZ()});
    }

    private interface StringSource {
        String get();
    }

    private static String safe(StringSource s) {
        try {
            String v = s.get();
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }
}
