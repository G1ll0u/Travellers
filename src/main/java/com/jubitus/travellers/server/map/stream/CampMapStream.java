package com.jubitus.travellers.server.map.stream;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.world.build.BuildStep;
import com.jubitus.travellers.world.camp.CampBuildCoordinator;
import com.jubitus.travellers.world.camp.CampOccupant;
import com.jubitus.travellers.world.camp.CampsiteManager;
import com.jubitus.travellers.world.camp.CampsiteRecord;
import com.jubitus.travellers.world.camp.ShelterSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feeds the population map GUI with debug info for every campsite (firepit + shelter slots), so a
 * player can inspect camp state world-wide, loaded or not. Mirrors {@link PopulationMapStream}
 * (same subscribe/tick/view-range pattern) but stays a separate stream/subscriber-set so camp data
 * can be toggled independently of population data.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class CampMapStream {

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

    private static final int UPDATE_EVERY_TICKS = 20;
    private static int tickCounter = 0;

    // Safety ceiling only - see MapStreamUtil. Each campsite contributes 1 firepit + N slot entries,
    // so this must comfortably cover thousands of camps; if ever exceeded, the nearest are kept.
    private static final int MAX_ENTRIES_PER_PACKET = 20000;
    private static final int DEFAULT_RANGE_BLOCKS = 4096;

    private static final Random RAND = new Random();

    public static void subscribe(UUID playerId) {
        if (playerId != null) SUBSCRIBERS.add(playerId);
    }

    public static void unsubscribe(UUID playerId) {
        if (playerId != null) SUBSCRIBERS.remove(playerId);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        unsubscribe(e.player.getUniqueID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter % UPDATE_EVERY_TICKS != 0) return;

        if (SUBSCRIBERS.isEmpty()) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        List<MsgMapCampUpdate.Entry> all = collectAll(server);

        for (UUID playerId : new ArrayList<>(SUBSCRIBERS)) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
            if (player == null) continue;

            int[] view = WorkerMapStream.getView(playerId);
            int viewDim = (view != null) ? view[0] : player.dimension;
            int cx = (view != null) ? view[1] : (int) Math.floor(player.posX);
            int cz = (view != null) ? view[2] : (int) Math.floor(player.posZ);
            int range = (view != null) ? view[3] : DEFAULT_RANGE_BLOCKS;
            long rangeSq = (long) range * (long) range;

            List<MsgMapCampUpdate.Entry> visible = MapStreamUtil.nearestWithinRange(
                    all, viewDim, en -> en.dim, en -> en.pos, cx, cz, rangeSq, MAX_ENTRIES_PER_PACKET);

            if (visible.isEmpty()) {
                com.jubitus.travellers.server.net.handler.TravellerFollowNet.sendToPlayer(player,
                        new MsgMapCampUpdate(Collections.emptyList()));
            } else {
                com.jubitus.travellers.server.net.handler.TravellerFollowNet.sendToPlayer(player,
                        new MsgMapCampUpdate(visible));
            }
        }
    }

    private static List<MsgMapCampUpdate.Entry> collectAll(MinecraftServer server) {
        List<MsgMapCampUpdate.Entry> out = new ArrayList<>();

        for (WorldServer ws : server.worlds) {
            int dim = ws.provider.getDimension();
            for (CampsiteRecord rec : CampsiteManager.get(ws).getAll()) {
                out.add(firepitEntry(dim, rec));
                for (int i = 0; i < rec.slots.size(); i++) {
                    out.add(slotEntry(ws, dim, rec, rec.slots.get(i), i));
                }
            }
        }

        return out;
    }

    private static MsgMapCampUpdate.Entry firepitEntry(int dim, CampsiteRecord rec) {
        MsgMapCampUpdate.Entry e = new MsgMapCampUpdate.Entry();
        e.dim = dim;
        e.pos = rec.firepitPos;
        e.kind = MsgMapCampUpdate.ObjKind.FIREPIT;
        e.firepitBuilt = rec.firepitBuilt;
        e.firepitLit = rec.firepitLit;

        String faction = rec.faction();
        StringBuilder sb = new StringBuilder();
        sb.append("Campsite ").append(shortId(rec.id)).append('\n');
        sb.append("Firepit: ").append(rec.firepitBuilt ? (rec.firepitLit ? "built, lit" : "built, unlit") : "not built").append('\n');
        sb.append("Occupants: ").append(rec.occupants.size()).append('/').append(rec.slots.size()).append(" slots");
        if (faction != null) sb.append(" (").append(faction).append(')');
        if (rec.emptySinceTick >= 0) sb.append("\nEmpty since tick ").append(rec.emptySinceTick);
        e.label = sb.toString();
        return e;
    }

    private static MsgMapCampUpdate.Entry slotEntry(WorldServer ws, int dim, CampsiteRecord rec, ShelterSlot slot, int index) {
        MsgMapCampUpdate.Entry e = new MsgMapCampUpdate.Entry();
        e.dim = dim;
        e.pos = slot.center();
        e.kind = MsgMapCampUpdate.ObjKind.SLOT;

        StringBuilder sb = new StringBuilder();
        sb.append("Shelter slot #").append(index);

        if (slot.disabled) {
            e.slotState = MsgMapCampUpdate.SlotState.DISABLED;
            sb.append("\nDISABLED (terrain conflict)");
            e.label = sb.toString();
            return e;
        }

        CampOccupant occ = rec.occupants.get(index);
        if (occ == null) {
            e.slotState = MsgMapCampUpdate.SlotState.EMPTY;
            sb.append("\nEmpty");
            e.label = sb.toString();
            return e;
        }

        switch (occ.buildState) {
            case BUILDING: e.slotState = MsgMapCampUpdate.SlotState.BUILDING; break;
            case TEARING_DOWN: e.slotState = MsgMapCampUpdate.SlotState.TEARING_DOWN; break;
            default: e.slotState = MsgMapCampUpdate.SlotState.BUILT; break;
        }

        Entity liveEntity = ws.getEntityFromUuid(occ.travellerUUID);
        e.occupantVirtual = (liveEntity == null);

        List<BuildStep> plan = CampBuildCoordinator.ensurePlan(occ, slot, RAND);
        int planSize = plan != null ? plan.size() : -1;

        sb.append('\n').append(occ.entityClass).append(" (").append(occ.cultureId).append(')');
        sb.append('\n').append(e.occupantVirtual ? "virtual" : "loaded");
        sb.append('\n').append(occ.buildState);
        if (planSize > 0) {
            int pct = Math.round(100f * occ.buildProgress / planSize);
            sb.append(": ").append(occ.buildProgress).append('/').append(planSize).append(" (").append(pct).append("%)");
        } else if (planSize < 0) {
            sb.append(": template unresolved (frozen)");
        }
        if (occ.shelterTemplateName != null) sb.append("\nTemplate: ").append(occ.shelterTemplateName);
        if (occ.bedPos != null) sb.append("\nHas bed");

        e.label = sb.toString();
        return e;
    }

    private static String shortId(UUID id) {
        String s = id.toString();
        return s.substring(0, Math.min(8, s.length()));
    }
}
