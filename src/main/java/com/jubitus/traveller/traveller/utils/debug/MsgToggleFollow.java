package com.jubitus.traveller.traveller.utils.debug;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgToggleFollow implements net.minecraftforge.fml.common.network.simpleimpl.IMessage {
    public int targetId;  // <-- the Traveller's entity ID

    public MsgToggleFollow() {
    } // required

    public MsgToggleFollow(int targetId) {
        this.targetId = targetId;
    }

    @Override
    public void fromBytes(io.netty.buffer.ByteBuf buf) {
        targetId = buf.readInt();
    }

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {
        buf.writeInt(targetId);
    }

    // client handler
    public static class Handler implements net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler<MsgToggleFollow, net.minecraftforge.fml.common.network.simpleimpl.IMessage> {
        @Override
        public net.minecraftforge.fml.common.network.simpleimpl.IMessage onMessage(MsgToggleFollow msg,
                                                                                   net.minecraftforge.fml.common.network.simpleimpl.MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (msg.targetId < 0) {
                    FollowTravellerClient.disable();
                } else {
                    FollowTravellerClient.toggleFollow(msg.targetId);
                }
            });

            return null;
        }
    }

    // server no-op handler (exists only so the server knows the discriminator)
    public static class NoopHandler implements IMessageHandler<MsgToggleFollow, IMessage> {
        @Override
        public IMessage onMessage(MsgToggleFollow msg, MessageContext ctx) {
            return null;
        }
    }

}


