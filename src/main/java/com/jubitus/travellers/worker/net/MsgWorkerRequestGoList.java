package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.village.net.SettlementMapInfo;
import com.jubitus.travellers.zone.ZoneType;
import net.minecraft.world.WorldServer;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MsgWorkerRequestGoList implements IMessage {
    private int workerId;

    public MsgWorkerRequestGoList() {}
    public MsgWorkerRequestGoList(int workerId) { this.workerId = workerId; }

    @Override public void fromBytes(ByteBuf buf) { workerId = buf.readInt(); }
    @Override public void toBytes(ByteBuf buf) { buf.writeInt(workerId); }

    public static class Handler implements IMessageHandler<MsgWorkerRequestGoList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerRequestGoList msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                int responseId;
                if (msg.workerId == -1) {
                    responseId = -1;
                } else {
                    EntityTravellerWorker w = WorkerLookup.ownedById(player, msg.workerId);
                    if (w == null) return;
                    responseId = w.getEntityId();
                }

                List<MsgWorkerGoList.Entry> out = placesFor(player);
                TravellersNetwork.CHANNEL.sendTo(new MsgWorkerGoList(responseId, out), player);
            });

            return null;
        }
    }

    /**
     * Every place the player can send henchmen to, nearest first: villages, lone buildings, own Barracks and Stables.
     * The one list both the screens and the Go order read, so an order can only target a real place.
     */
    public static List<MsgWorkerGoList.Entry> placesFor(EntityPlayerMP player) {
        List<MsgWorkerGoList.Entry> out = new ArrayList<>();
        BlockPos origin = player.getPosition();
        WorldServer ws = player.getServerWorld();

        java.util.Set<BlockPos> controlledVillages = TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled
                ? MillSettlements.controlledBy(ws, player)
                : java.util.Collections.emptySet();

        for (MillSettlements.Settlement s : MillSettlements.all(ws)) {
            MsgWorkerGoList.Kind kind = !s.isVillage ? MsgWorkerGoList.Kind.LONE_BUILDING
                    : controlledVillages.contains(s.center) ? MsgWorkerGoList.Kind.CONTROLLED_VILLAGE
                    : MsgWorkerGoList.Kind.VILLAGE;
            MsgWorkerGoList.Entry e = MsgWorkerGoList.Entry.of(kind, s.center, s.displayName(),
                    EntityTravellerWorker.dist2D(origin, s.center));
            e.settlement = SettlementMapInfo.of(ws, player, s);
            out.add(e);
        }

        java.util.Map<BlockPos, com.jubitus.travellers.follow.net.FormationZoneInfo> zones =
                com.jubitus.travellers.follow.group.WorkerGroupService.zoneInfos(player.getServer(), player);
        PlayerZoneManager zoneMgr = PlayerZoneManager.get(ws);
        addZones(out, zoneMgr, player, ZoneType.BARRACK, MsgWorkerGoList.Kind.HOME, "Barrack", zones);
        addZones(out, zoneMgr, player, ZoneType.STABLE, MsgWorkerGoList.Kind.STABLE, "Stable", zones);

        out.sort(Comparator.comparingInt(a -> a.distance));
        return out;
    }

    private static void addZones(List<MsgWorkerGoList.Entry> out, PlayerZoneManager mgr, EntityPlayerMP player, ZoneType type,
                                 MsgWorkerGoList.Kind kind, String fallbackName,
                                 java.util.Map<BlockPos, com.jubitus.travellers.follow.net.FormationZoneInfo> zones) {
        for (PlayerZoneRecord r : mgr.getByOwnerAndType(player.getUniqueID(), type)) {
            String label = r.name != null && !r.name.isEmpty() ? r.name : fallbackName;
            MsgWorkerGoList.Entry e = MsgWorkerGoList.Entry.of(kind, r.center, label,
                    EntityTravellerWorker.dist2D(player.getPosition(), r.center));
            e.zone = zones.get(r.center);
            out.add(e);
        }
    }
}
