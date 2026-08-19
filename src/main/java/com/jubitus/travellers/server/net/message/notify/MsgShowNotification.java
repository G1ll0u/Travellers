package com.jubitus.travellers.server.net.message.notify;

import com.jubitus.travellers.client.notify.ClientNotificationCache;
import com.jubitus.travellers.client.notify.LocationFormat;
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

    public MsgShowNotification() {
    }

    public MsgShowNotification(NotificationType type, String i18nKey, String[] args, long worldTime) {
        this(type, i18nKey, args, worldTime, null, 0);
    }

    public MsgShowNotification(NotificationType type, String i18nKey, String[] args, long worldTime,
                                @Nullable BlockPos loc, int dimension) {
        this.typeOrdinal = type.ordinal();
        this.i18nKey = i18nKey;
        this.args = args;
        this.worldTime = worldTime;
        this.hasLocation = loc != null;
        if (loc != null) {
            this.locX = loc.getX();
            this.locY = loc.getY();
            this.locZ = loc.getZ();
            this.locDim = dimension;
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
    }

    public static class Handler implements IMessageHandler<MsgShowNotification, IMessage> {
        @Override
        public IMessage onMessage(MsgShowNotification msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                NotificationType type = NotificationType.values()[msg.typeOrdinal];
                String text = I18n.format(msg.i18nKey, (Object[]) msg.args);
                if (msg.hasLocation) {
                    String suffix = LocationFormat.describe(new BlockPos(msg.locX, msg.locY, msg.locZ), msg.locDim);
                    if (suffix != null) text = text + ", " + suffix;
                }
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
