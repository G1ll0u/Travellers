package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.client.gui.home.GuiWorkerLoneBarrackPrompt;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MsgOpenLoneBarrackPrompt implements IMessage {

    private int x, y, z;
    private List<ExistingBarrack> existing = new ArrayList<>();

    public MsgOpenLoneBarrackPrompt() {}

    public MsgOpenLoneBarrackPrompt(BlockPos center, List<ExistingBarrack> existing) {
        this.x = center.getX();
        this.y = center.getY();
        this.z = center.getZ();
        this.existing = existing != null ? existing : Collections.emptyList();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        int n = buf.readUnsignedShort();
        existing = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int cx = buf.readInt();
            int cz = buf.readInt();
            int hw = buf.readShort();
            int hd = buf.readShort();
            String name = ByteBufUtils.readUTF8String(buf);
            existing.add(new ExistingBarrack(cx, cz, hw, hd, name));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeShort(existing.size());
        for (ExistingBarrack e : existing) {
            buf.writeInt(e.cx);
            buf.writeInt(e.cz);
            buf.writeShort(e.halfWidth);
            buf.writeShort(e.halfDepth);
            ByteBufUtils.writeUTF8String(buf, e.name != null ? e.name : "");
        }
    }

    public static class ExistingBarrack {
        public final int cx, cz, halfWidth, halfDepth;
        public final String name;

        public ExistingBarrack(int cx, int cz, int halfWidth, int halfDepth, String name) {
            this.cx = cx;
            this.cz = cz;
            this.halfWidth = halfWidth;
            this.halfDepth = halfDepth;
            this.name = name;
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgOpenLoneBarrackPrompt, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenLoneBarrackPrompt msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                EntityPlayer player = Minecraft.getMinecraft().player;
                if (player == null) return;
                Minecraft.getMinecraft().displayGuiScreen(
                    new GuiWorkerLoneBarrackPrompt(player, new BlockPos(msg.x, msg.y, msg.z), msg.existing));
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgOpenLoneBarrackPrompt, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenLoneBarrackPrompt msg, MessageContext ctx) { return null; }
    }
}
