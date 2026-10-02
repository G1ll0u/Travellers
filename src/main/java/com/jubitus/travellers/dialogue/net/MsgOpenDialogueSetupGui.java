package com.jubitus.travellers.dialogue.net;

import com.jubitus.travellers.dialogue.gui.GuiDialogueSetup;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server → client, no payload: opens {@link GuiDialogueSetup} standalone (no parent screen to
 * return to). Currently reached only via {@code /travellersdebug dialogue setup} — a temporary
 * entry point wired in before Part C of the dialogue plan builds the real one (the right-click
 * dialogue GUI's "not configured yet" fallback button). Remove the command hook, not this message,
 * once that exists — the message itself is a reasonable permanent way to reach the settings screen
 * without an NPC nearby.
 */
public class MsgOpenDialogueSetupGui implements IMessage {

    public MsgOpenDialogueSetupGui() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
    }

    @Override
    public void toBytes(ByteBuf buf) {
    }

    public static class Handler implements IMessageHandler<MsgOpenDialogueSetupGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenDialogueSetupGui msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    Minecraft.getMinecraft().displayGuiScreen(new GuiDialogueSetup(null)));
            return null;
        }
    }
}
