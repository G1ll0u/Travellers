package com.jubitus.travellers.server.net.message.formations;

import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgUpdateWorkerFormationSettings implements IMessage {

    private int workerEntityId;
    private NBTTagCompound scopeTag;
    private NBTTagCompound settingsTag;

    public MsgUpdateWorkerFormationSettings() {
    }

    public MsgUpdateWorkerFormationSettings(int workerEntityId,
                                            WorkerFormationScopeKey scope,
                                            WorkerFormationSettings settings) {
        this.workerEntityId = workerEntityId;
        this.scopeTag = scope.toNbt();
        this.settingsTag = settings.toNbt();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.workerEntityId = buf.readInt();
        this.scopeTag = ByteBufUtils.readTag(buf);
        this.settingsTag = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerEntityId);
        ByteBufUtils.writeTag(buf, scopeTag);
        ByteBufUtils.writeTag(buf, settingsTag);
    }

    public static class Handler implements IMessageHandler<MsgUpdateWorkerFormationSettings, IMessage> {
        @Override
        public IMessage onMessage(MsgUpdateWorkerFormationSettings msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                World world = player.world;
                Entity e = world.getEntityByID(msg.workerEntityId);
                if (!(e instanceof EntityTravellerWorker)) {
                    return;
                }

                EntityTravellerWorker worker = (EntityTravellerWorker) e;
                WorkerFormationScopeKey scope = WorkerFormationScopeKey.fromNbt(msg.scopeTag);
                WorkerFormationSettings settings = WorkerFormationSettings.fromNbt(msg.settingsTag);

                if (!WorkerFormationScopeResolver.canPlayerEditScope(worker, player, scope)) {
                    return;
                }

                WorkerFormationSavedData.get(world).setSettings(scope, settings);
            });
            return null;
        }
    }
}