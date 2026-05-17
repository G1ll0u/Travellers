package com.jubitus.travellers.server.net.message.formations;

import com.jubitus.travellers.client.gui.formation.GuiWorkerFormation;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgOpenWorkerFormationGui implements IMessage {

    private int workerEntityId;
    private NBTTagCompound scopeTag;
    private NBTTagCompound settingsTag;
    private String scopeLabel;
    private int followerCount;

    public MsgOpenWorkerFormationGui() {
    }

    public MsgOpenWorkerFormationGui(int workerEntityId,
                                     WorkerFormationScopeResolver.OpenScope openScope,
                                     WorkerFormationSettings settings) {
        this.workerEntityId = workerEntityId;
        this.scopeTag = openScope.scope.toNbt();
        this.settingsTag = settings.toNbt();
        this.scopeLabel = openScope.label;
        this.followerCount = openScope.followerCount;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.workerEntityId = buf.readInt();
        this.scopeTag = ByteBufUtils.readTag(buf);
        this.settingsTag = ByteBufUtils.readTag(buf);
        this.scopeLabel = ByteBufUtils.readUTF8String(buf);
        this.followerCount = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerEntityId);
        ByteBufUtils.writeTag(buf, scopeTag);
        ByteBufUtils.writeTag(buf, settingsTag);
        ByteBufUtils.writeUTF8String(buf, scopeLabel == null ? "" : scopeLabel);
        buf.writeInt(followerCount);
    }

    public static class Handler implements IMessageHandler<MsgOpenWorkerFormationGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenWorkerFormationGui msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                Minecraft mc = Minecraft.getMinecraft();
                EntityPlayer player = mc.player;
                World world = mc.world;

                if (player == null || world == null) {
                    return;
                }

                Entity e = world.getEntityByID(msg.workerEntityId);
                if (!(e instanceof EntityTravellerWorker)) {
                    return;
                }

                WorkerFormationScopeKey scope = WorkerFormationScopeKey.fromNbt(msg.scopeTag);
                WorkerFormationSettings settings = WorkerFormationSettings.fromNbt(msg.settingsTag);

                mc.displayGuiScreen(new GuiWorkerFormation(
                        player,
                        (EntityTravellerWorker) e,
                        scope,
                        msg.scopeLabel,
                        msg.followerCount,
                        settings
                ));
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgOpenWorkerFormationGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenWorkerFormationGui message, MessageContext ctx) {
            return null;
        }
    }
}