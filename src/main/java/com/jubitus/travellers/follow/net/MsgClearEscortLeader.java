package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Collections;
import java.util.UUID;

public class MsgClearEscortLeader implements IMessage {

    private int entityId;

    public MsgClearEscortLeader() {
    }

    public MsgClearEscortLeader(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
    }

    public static class Handler implements IMessageHandler<MsgClearEscortLeader, IMessage> {
        @Override
        public IMessage onMessage(MsgClearEscortLeader msg, MessageContext ctx) {
            // Always run world/entity changes on server thread
            ctx.getServerHandler().player.getServerWorld().addScheduledTask(() -> {
                EntityPlayerMP sender = ctx.getServerHandler().player;
                World world = sender.world;

                Entity e = world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTravellerWorker)) return;

                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // Security: only the owner can change this
                UUID owner = w.getFollowOwnerId();
                if (owner == null || !owner.equals(sender.getUniqueID())) return;

                // Only do something if actually following a leader
                if (!w.hasEscortLeader()) return;

                // Routed through WorkerGroupService (not a direct escortLeaderUUID mutation here) so
                // this single-worker "stop escorting, follow me instead" order gets the exact same
                // treatment as every other group/leader reassignment: busy guard, virtual formation
                // state reset (formationOrdinal/goal), immediate goal refresh, and a live Hub push.
                WorkerGroupService.comeBackMembers(sender, Collections.singletonList(w.getUniqueID()));
            });

            return null;
        }
    }
}
