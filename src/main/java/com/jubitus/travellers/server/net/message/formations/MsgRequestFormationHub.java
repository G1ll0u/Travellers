package com.jubitus.travellers.server.net.message.formations;

import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MsgRequestFormationHub implements IMessage {

    public MsgRequestFormationHub() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestFormationHub, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestFormationHub msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() ->
                    com.jubitus.travellers.server.group.WorkerGroupService.sendHub(player, false));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgRequestFormationHub, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestFormationHub msg, MessageContext ctx) { return null; }
    }
}
