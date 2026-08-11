package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.server.group.WorkerGroupService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MsgGroupCreate implements IMessage {

    public enum Action { CREATE_GROUP, COME_BACK, ASSIGN_TO_LEADER }

    private Action action;
    private List<UUID> workerUUIDs = new ArrayList<>();
    @Nullable private UUID targetLeaderUUID; // used for ASSIGN_TO_LEADER

    public MsgGroupCreate() {}

    public MsgGroupCreate(Action action, List<UUID> uuids, @Nullable UUID targetLeaderUUID) {
        this.action = action;
        this.workerUUIDs = new ArrayList<>(uuids);
        this.targetLeaderUUID = targetLeaderUUID;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int actionOrd = buf.readByte();
        action = Action.values()[actionOrd < Action.values().length ? actionOrd : 0];
        int n = buf.readInt();
        workerUUIDs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            workerUUIDs.add(new UUID(buf.readLong(), buf.readLong()));
        }
        boolean hasLeader = buf.readBoolean();
        if (hasLeader) targetLeaderUUID = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action == null ? 0 : action.ordinal());
        buf.writeInt(workerUUIDs.size());
        for (UUID u : workerUUIDs) {
            buf.writeLong(u.getMostSignificantBits());
            buf.writeLong(u.getLeastSignificantBits());
        }
        buf.writeBoolean(targetLeaderUUID != null);
        if (targetLeaderUUID != null) {
            buf.writeLong(targetLeaderUUID.getMostSignificantBits());
            buf.writeLong(targetLeaderUUID.getLeastSignificantBits());
        }
    }

    public static class Handler implements IMessageHandler<MsgGroupCreate, IMessage> {
        @Override
        public IMessage onMessage(MsgGroupCreate msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (msg.workerUUIDs.isEmpty() || msg.action == null) return;
                switch (msg.action) {
                    case COME_BACK:
                        WorkerGroupService.comeBackMembers(player, msg.workerUUIDs);
                        break;
                    case ASSIGN_TO_LEADER:
                        WorkerGroupService.assignToLeader(player, msg.workerUUIDs, msg.targetLeaderUUID);
                        break;
                    case CREATE_GROUP:
                        WorkerGroupService.createGroup(player, msg.workerUUIDs);
                        break;
                }
            });
            return null;
        }
    }
}
