package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.mill.MillControlledVillageHelper;
import com.jubitus.travellers.world.mill.MillVillageIndex;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class MsgRequestEquipDests implements IMessage {

    public MsgRequestEquipDests() {}

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestEquipDests, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestEquipDests msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                List<MsgEquipDestsResponse.EquipDestInfo> dests = new ArrayList<>();

                if (TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled) {
                    for (BlockPos pos : MillControlledVillageHelper.getControlledVillageCenters(player.world, player)) {
                        String name = MillVillageIndex.nameForApprox(player.world, pos);
                        dests.add(new MsgEquipDestsResponse.EquipDestInfo(pos,
                                com.jubitus.travellers.world.mill.EquipTargetKind.CONTROLLED_VILLAGE, name));
                    }
                }

                PlayerZoneManager lbMgr = PlayerZoneManager.get(player.world);
                for (PlayerZoneRecord r : lbMgr.getByOwnerAndType(player.getUniqueID(),
                        com.jubitus.travellers.world.zone.ZoneType.BARRACK)) {
                    String name = (r.name != null && !r.name.isEmpty()) ? r.name : "Barrack";
                    dests.add(new MsgEquipDestsResponse.EquipDestInfo(r.center,
                            com.jubitus.travellers.world.mill.EquipTargetKind.LONE_BARRACK, name));
                }
                for (PlayerZoneRecord r : lbMgr.getByOwnerAndType(player.getUniqueID(),
                        com.jubitus.travellers.world.zone.ZoneType.STABLE)) {
                    String name = (r.name != null && !r.name.isEmpty()) ? r.name : "Stable";
                    dests.add(new MsgEquipDestsResponse.EquipDestInfo(r.center,
                            com.jubitus.travellers.world.mill.EquipTargetKind.STABLE, name));
                }

                TravellerFollowNet.CHANNEL.sendTo(new MsgEquipDestsResponse(dests), player);
            });
            return null;
        }
    }
}
