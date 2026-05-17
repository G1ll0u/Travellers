package com.jubitus.travellers.server.net.message.marker;

import com.jubitus.travellers.client.render.marker.WorkerMarkStyle;
import com.jubitus.travellers.server.marker.WorkerOwnerVisualsSavedData;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.*;

public class MsgSaveWorkerOwnerVisuals implements IMessage {

    private int color;
    private String styleId;

    public MsgSaveWorkerOwnerVisuals() {
    }

    public MsgSaveWorkerOwnerVisuals(int color, String styleId) {
        this.color = color & 0xFFFFFF;
        this.styleId = WorkerMarkStyle.byId(styleId).id;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.color = buf.readInt() & 0xFFFFFF;
        this.styleId = WorkerMarkStyle.byId(ByteBufUtils.readUTF8String(buf)).id;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(color & 0xFFFFFF);
        ByteBufUtils.writeUTF8String(buf, WorkerMarkStyle.byId(styleId).id);
    }

    public static class Handler implements IMessageHandler<MsgSaveWorkerOwnerVisuals, IMessage> {
        @Override
        public IMessage onMessage(MsgSaveWorkerOwnerVisuals msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                String safeStyle = WorkerMarkStyle.byId(msg.styleId).id;
                int safeColor = msg.color & 0xFFFFFF;

                WorkerOwnerVisualsSavedData data = WorkerOwnerVisualsSavedData.get(player.world);
                data.setFor(player.getUniqueID(), safeColor, safeStyle);
                WorkerOwnerVisualsSavedData.applyToLoadedWorkers(
                        player.getServer(),
                        player.getUniqueID(),
                        safeColor,
                        safeStyle
                );

                player.sendStatusMessage(
                        new TextComponentTranslation("travellers.worker.mark.saved"),
                        true
                );
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgSaveWorkerOwnerVisuals, IMessage> {
        @Override
        public IMessage onMessage(MsgSaveWorkerOwnerVisuals message, MessageContext ctx) {
            return null;
        }
    }
}