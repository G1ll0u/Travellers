package com.jubitus.travellers.utils.net.message.chunks;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.utils.debug.TravellerChunkSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketTravellerChunksRequest implements IMessage {

    public int radiusBlocks;

    public PacketTravellerChunksRequest() {
    }

    public PacketTravellerChunksRequest(int radiusBlocks) {
        this.radiusBlocks = radiusBlocks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        radiusBlocks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(radiusBlocks);
    }

    public static class Handler implements IMessageHandler<PacketTravellerChunksRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketTravellerChunksRequest message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                WorldServer world = player.getServerWorld();
                BlockPos center = player.getPosition();

                TravellerChunkSnapshot snapshot =
                        TravellerChunkSnapshot.create(world, center, message.radiusBlocks);

                Travellers.NETWORK.sendTo(
                        new PacketTravellerChunks(snapshot),
                        player
                );
            });

            return null; // no reply packet
        }
    }
}


