package com.jubitus.travellers.server.net.message.relations;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.data.TravellerVillageRelations;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.*;

import java.util.UUID;

public class MsgSetVillageEnemy implements IMessage {

    private int workerId;
    private int x, y, z;
    private boolean enemy;

    public MsgSetVillageEnemy() {}

    public MsgSetVillageEnemy(int workerId, BlockPos center, boolean enemy) {
        this.workerId = workerId;
        this.x = center.getX();
        this.y = center.getY();
        this.z = center.getZ();
        this.enemy = enemy;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        enemy = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(enemy);
    }

    public static class Handler implements IMessageHandler<MsgSetVillageEnemy, IMessage> {
        @Override
        public IMessage onMessage(MsgSetVillageEnemy msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(msg, player));
            return null;
        }

        private void handle(MsgSetVillageEnemy msg, EntityPlayerMP player) {
            WorldServer ws = player.getServerWorld();
            Entity e = ws.getEntityByID(msg.workerId);
            if (!(e instanceof EntityTravellerWorker)) return;

            EntityTravellerWorker worker = (EntityTravellerWorker) e;

            if (worker.getDistanceSq(player) > 64.0D) return;
            if (worker.isHiredFollower() && !player.getUniqueID().equals(worker.getFollowOwnerId())) return;

            UUID pid = player.getUniqueID();
            BlockPos center = new BlockPos(msg.x, msg.y, msg.z);

            TravellerVillageRelations rel = TravellerVillageRelations.get(ws);
            rel.setEnemy(pid, ws.provider.getDimension(), center, msg.enemy);

            // send refreshed list to client
            MsgWorkerVillageRelationsList reply =
                    MsgWorkerRequestVillageRelations.Handler.buildList(ws, worker, pid);
            TravellerFollowNet.sendToPlayer(player, reply);
        }
    }
}