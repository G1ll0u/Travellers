package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.follow.gui.GuiTravellerInteractChoice;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server -> client: the player right-clicked a traveller; offer "Talk" or "Follow him". */
public class MsgOpenFollowChoice implements IMessage {

    private int entityId;

    public MsgOpenFollowChoice() {
    }

    public MsgOpenFollowChoice(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static class Handler implements IMessageHandler<MsgOpenFollowChoice, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenFollowChoice msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    Minecraft.getMinecraft().displayGuiScreen(new GuiTravellerInteractChoice(msg.entityId)));
            return null;
        }
    }
}
