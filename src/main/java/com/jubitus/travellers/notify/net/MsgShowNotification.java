package com.jubitus.travellers.notify.net;

import com.jubitus.travellers.notify.client.ClientNotificationCache;
import com.jubitus.travellers.notify.client.LocationFormat;
import com.jubitus.travellers.notify.client.NoticeText;
import com.jubitus.travellers.notify.NoticeLine;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.notify.NotificationType;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;

public class MsgShowNotification implements IMessage {
    public int typeOrdinal;
    public String i18nKey;
    public String[] args;
    public long worldTime;
    public boolean hasLocation;
    public int locX, locY, locZ, locDim;
    @Nullable
    public NoticeLine detail;

    public MsgShowNotification() {
    }

    public MsgShowNotification(NotificationType type, String i18nKey, String[] args, long worldTime,
                                @Nullable NoticePlace place, @Nullable NoticeLine detail) {
        this.detail = detail;
        this.typeOrdinal = type.ordinal();
        this.i18nKey = i18nKey;
        this.args = args;
        this.worldTime = worldTime;
        this.hasLocation = place != null;
        if (place != null) {
            this.locX = place.pos.getX();
            this.locY = place.pos.getY();
            this.locZ = place.pos.getZ();
            this.locDim = place.dimension;
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        typeOrdinal = buf.readInt();
        i18nKey = ByteBufUtils.readUTF8String(buf);
        worldTime = buf.readLong();
        hasLocation = buf.readBoolean();
        if (hasLocation) {
            locX = buf.readInt();
            locY = buf.readInt();
            locZ = buf.readInt();
            locDim = buf.readInt();
        }
        int argCount = buf.readInt();
        args = new String[argCount];
        for (int i = 0; i < argCount; i++) {
            args[i] = ByteBufUtils.readUTF8String(buf);
        }
        detail = buf.readBoolean() ? NoticeLine.read(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(typeOrdinal);
        ByteBufUtils.writeUTF8String(buf, i18nKey);
        buf.writeLong(worldTime);
        buf.writeBoolean(hasLocation);
        if (hasLocation) {
            buf.writeInt(locX);
            buf.writeInt(locY);
            buf.writeInt(locZ);
            buf.writeInt(locDim);
        }
        buf.writeInt(args.length);
        for (String arg : args) {
            ByteBufUtils.writeUTF8String(buf, arg);
        }
        buf.writeBoolean(detail != null);
        if (detail != null) detail.write(buf);
    }

    public static class Handler implements IMessageHandler<MsgShowNotification, IMessage> {
        @Override
        public IMessage onMessage(MsgShowNotification msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                NotificationType type = NotificationType.values()[msg.typeOrdinal];
                NoticePlace place = msg.hasLocation
                        ? new NoticePlace(new BlockPos(msg.locX, msg.locY, msg.locZ), msg.locDim, null) : null;
                String text = NoticeText.format(msg.i18nKey, msg.args, place, msg.detail);
                ClientNotificationCache.push(type, text, msg.worldTime);
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgShowNotification, IMessage> {
        @Override
        public IMessage onMessage(MsgShowNotification msg, MessageContext ctx) {
            return null;
        }
    }
}
