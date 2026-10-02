package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Objects;
import java.util.UUID;

public class MsgUpdateFormationByScope implements IMessage {

    private NBTTagCompound scopeTag;
    private NBTTagCompound settingsTag;

    public MsgUpdateFormationByScope() {}

    public MsgUpdateFormationByScope(WorkerFormationScopeKey scope, WorkerFormationSettings settings) {
        this.scopeTag = scope.toNbt();
        this.settingsTag = settings.toNbt();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        scopeTag = ByteBufUtils.readTag(buf);
        settingsTag = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeTag(buf, scopeTag);
        ByteBufUtils.writeTag(buf, settingsTag);
    }

    public static class Handler implements IMessageHandler<MsgUpdateFormationByScope, IMessage> {
        @Override
        public IMessage onMessage(MsgUpdateFormationByScope msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                WorkerFormationScopeKey scope = WorkerFormationScopeKey.fromNbt(msg.scopeTag);
                WorkerFormationSettings settings = WorkerFormationSettings.fromNbt(msg.settingsTag);

                if (!canEdit(scope, player)) return;
                WorkerFormationSavedData.get(player.world).setSettings(scope, settings);
            });
            return null;
        }

        private boolean canEdit(WorkerFormationScopeKey scope, EntityPlayerMP player) {
            UUID playerId = player.getUniqueID();
            if (scope.getType() == WorkerFormationScopeKey.Type.PLAYER) {
                return playerId.equals(scope.getId());
            }
            MinecraftServer server = player.getServer();
            if (server == null) return false;
            return WorkerLookup.findOwned(server, playerId, scope.getId()) != null;
        }
    }
}
