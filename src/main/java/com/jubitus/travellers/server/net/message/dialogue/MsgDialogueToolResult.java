package com.jubitus.travellers.server.net.message.dialogue;

import com.jubitus.travellers.client.dialogue.DialogueToolQueryDispatcher;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server -> client reply to {@link MsgDialogueToolQuery}, correlated by {@code requestId}. */
public class MsgDialogueToolResult implements IMessage {

    private int requestId;
    private String resultText;
    private boolean error;

    public MsgDialogueToolResult() {
    }

    public MsgDialogueToolResult(int requestId, String resultText, boolean error) {
        this.requestId = requestId;
        this.resultText = resultText;
        this.error = error;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.requestId = buf.readInt();
        this.resultText = ByteBufUtils.readUTF8String(buf);
        this.error = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(requestId);
        ByteBufUtils.writeUTF8String(buf, resultText == null ? "" : resultText);
        buf.writeBoolean(error);
    }

    public static class Handler implements IMessageHandler<MsgDialogueToolResult, IMessage> {
        @Override
        public IMessage onMessage(MsgDialogueToolResult msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    DialogueToolQueryDispatcher.onResult(msg.requestId, msg.resultText, msg.error));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgDialogueToolResult, IMessage> {
        @Override
        public IMessage onMessage(MsgDialogueToolResult message, MessageContext ctx) {
            return null;
        }
    }
}
