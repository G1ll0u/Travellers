package com.jubitus.travellers.village.net;

import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.worldmap.KnownVillages;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.village.TravellerVillageRelations;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.*;

import java.util.UUID;

public class MsgSetVillageEnemy implements IMessage {

    private int x, y, z;
    private boolean enemy;

    public MsgSetVillageEnemy() {}

    public MsgSetVillageEnemy(BlockPos center, boolean enemy) {
        this.x = center.getX();
        this.y = center.getY();
        this.z = center.getZ();
        this.enemy = enemy;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        enemy = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
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

            UUID pid = player.getUniqueID();
            BlockPos center = new BlockPos(msg.x, msg.y, msg.z);

            // Peace is always allowed (clears stale entries); war only on a real village the player knows.
            if (msg.enemy && (MillSettlements.at(ws, center) == null || !KnownVillages.knows(ws, pid, center))) return;

            TravellerVillageRelations rel = TravellerVillageRelations.get(ws);
            rel.setEnemy(pid, ws.provider.getDimension(), center, msg.enemy);

            // send refreshed list to client
            MsgWorkerVillageRelationsList reply =
                    MsgWorkerRequestVillageRelations.Handler.buildList(ws, player.getPosition(), pid);
            TravellersNetwork.sendToPlayer(player, reply);
        }
    }
}