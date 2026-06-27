package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.client.gui.home.GuiWorkerLoneBarrackPrompt;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgLoneBarrackResult implements IMessage {

    private boolean success;
    private String i18nKey;

    public MsgLoneBarrackResult() {}

    public MsgLoneBarrackResult(boolean success, String i18nKey) {
        this.success = success;
        this.i18nKey = i18nKey;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        success = buf.readBoolean();
        i18nKey = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(success);
        ByteBufUtils.writeUTF8String(buf, i18nKey == null ? "" : i18nKey);
    }

    public static class ClientHandler implements IMessageHandler<MsgLoneBarrackResult, IMessage> {
        @Override
        public IMessage onMessage(MsgLoneBarrackResult msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (msg.success) {
                    if (Minecraft.getMinecraft().currentScreen instanceof GuiWorkerLoneBarrackPrompt) {
                        Minecraft.getMinecraft().player.closeScreen();
                    }
                } else {
                    String text = I18n.format(msg.i18nKey);
                    if (Minecraft.getMinecraft().currentScreen instanceof GuiWorkerLoneBarrackPrompt) {
                        ((GuiWorkerLoneBarrackPrompt) Minecraft.getMinecraft().currentScreen).setError(text);
                    }
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgLoneBarrackResult, IMessage> {
        @Override
        public IMessage onMessage(MsgLoneBarrackResult msg, MessageContext ctx) { return null; }
    }
}
