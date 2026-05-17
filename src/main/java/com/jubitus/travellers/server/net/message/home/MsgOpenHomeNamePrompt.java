package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.client.gui.home.GuiHomeNamePrompt;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgOpenHomeNamePrompt implements IMessage {

    private BlockPos pos;

    public MsgOpenHomeNamePrompt() {
    }

    public MsgOpenHomeNamePrompt(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
    }

    public static class ClientHandler implements IMessageHandler<MsgOpenHomeNamePrompt, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenHomeNamePrompt msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                Minecraft mc = Minecraft.getMinecraft();
                EntityPlayer player = mc.player;
                if (player == null) return;

                mc.displayGuiScreen(new GuiHomeNamePrompt(player, msg.pos));
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgOpenHomeNamePrompt, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenHomeNamePrompt msg, MessageContext ctx) {
            return null;
        }
    }


}