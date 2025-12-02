package com.jubitus.traveller.traveller.utils.debug;

import com.jubitus.traveller.traveller.entityAI.EntityTravellerWorker;
import com.jubitus.traveller.traveller.utils.villages.MillenaireVillageDirectory;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.io.File;

public class MsgWorkerSetDestination implements IMessage {

    private int entityId;
    private BlockPos targetCenter;
    private String label; // for chat

    public MsgWorkerSetDestination() {}

    public MsgWorkerSetDestination(int entityId, BlockPos targetCenter, String label) {
        this.entityId = entityId;
        this.targetCenter = targetCenter;
        this.label = label;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        this.targetCenter = new BlockPos(x, y, z);
        this.label = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeInt(targetCenter.getX());
        buf.writeInt(targetCenter.getY());
        buf.writeInt(targetCenter.getZ());
        ByteBufUtils.writeUTF8String(buf, label);
    }

    // ---------- server handler ----------

    public static class Handler implements IMessageHandler<MsgWorkerSetDestination, IMessage> {

        @Override
        public IMessage onMessage(MsgWorkerSetDestination msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(msg, player));
            return null;
        }

        private void handle(MsgWorkerSetDestination msg, EntityPlayerMP player) {
            World world = player.world;
            Entity e = world.getEntityByID(msg.entityId);
            if (!(e instanceof EntityTravellerWorker)) return;

            EntityTravellerWorker worker = (EntityTravellerWorker) e;

            if (worker.getDistanceSq(player) > 64.0D) {
                return; // too far
            }

            BlockPos villageCenter = msg.targetCenter;

            worker.startVillageDeliveryJob(player, villageCenter);

            player.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + "Alright, I'm heading to " +
                            TextFormatting.GOLD + msg.label +
                            TextFormatting.WHITE + "!"
            ));
        }
    }

    // client no-op
    public static class NoopHandler implements IMessageHandler<MsgWorkerSetDestination, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerSetDestination message, MessageContext ctx) {
            return null;
        }
    }
}
