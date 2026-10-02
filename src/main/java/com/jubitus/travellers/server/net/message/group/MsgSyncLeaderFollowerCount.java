package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server -&gt; client: the authoritative (loaded + virtual) follower count for a leader worker, for
 * display in {@code GuiWorkerHub}'s "Lead" button. Deliberately separate from
 * {@code EntityTravellerWorker.LEADER_FOLLOWER_COUNT_DM} (a synced DataParameter): that field is
 * loaded-follower-only by design (it drives the leader's own local idle AI / armband texture, which
 * only care about physically-present followers) and must stay that way - see
 * {@code WorkerGroupService.sendHub}'s "unified is-a-leader definition" for why a loaded-only count
 * is wrong for anything player-facing. This message carries the same union count {@code sendHub}
 * computes for the Formation Hub, so the two screens never disagree.
 */
public class MsgSyncLeaderFollowerCount implements IMessage {

    private int entityId;
    private int count;

    public MsgSyncLeaderFollowerCount() {
    }

    public MsgSyncLeaderFollowerCount(int entityId, int count) {
        this.entityId = entityId;
        this.count = count;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.count = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeInt(count);
    }

    public static class Handler implements IMessageHandler<MsgSyncLeaderFollowerCount, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncLeaderFollowerCount msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                World world = Minecraft.getMinecraft().world;
                if (world == null) return;
                Entity e = world.getEntityByID(msg.entityId);
                if (e instanceof EntityTravellerWorker) {
                    ((EntityTravellerWorker) e).setClientAuthoritativeLeaderFollowerCount(msg.count);
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgSyncLeaderFollowerCount, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncLeaderFollowerCount msg, MessageContext ctx) {
            return null;
        }
    }
}
