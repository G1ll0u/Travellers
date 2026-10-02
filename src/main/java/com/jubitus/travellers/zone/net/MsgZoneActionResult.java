package com.jubitus.travellers.zone.net;

import com.jubitus.travellers.zone.gui.GuiPlayerZonePrompt;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server -> client: the result of a zone creation/deletion attempt (success closes the prompt,
 * failure shows the i18n-keyed reason on it). */
public class MsgZoneActionResult implements IMessage {

    private boolean success;
    private String i18nKey;

    public MsgZoneActionResult() {}

    public MsgZoneActionResult(boolean success, String i18nKey) {
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

    public static class ClientHandler implements IMessageHandler<MsgZoneActionResult, IMessage> {
        @Override
        public IMessage onMessage(MsgZoneActionResult msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (msg.success) {
                    if (Minecraft.getMinecraft().currentScreen instanceof GuiPlayerZonePrompt) {
                        Minecraft.getMinecraft().player.closeScreen();
                    }
                } else {
                    String text = I18n.format(msg.i18nKey);
                    if (Minecraft.getMinecraft().currentScreen instanceof GuiPlayerZonePrompt) {
                        ((GuiPlayerZonePrompt) Minecraft.getMinecraft().currentScreen).setError(text);
                    }
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgZoneActionResult, IMessage> {
        @Override
        public IMessage onMessage(MsgZoneActionResult msg, MessageContext ctx) { return null; }
    }
}
