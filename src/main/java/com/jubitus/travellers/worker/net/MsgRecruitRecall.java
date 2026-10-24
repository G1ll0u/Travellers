package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/** Formation Hub → server: call a recruiter back from its tour, loaded or offscreen. */
public class MsgRecruitRecall implements IMessage {

    private UUID workerId;

    public MsgRecruitRecall() {
    }

    public MsgRecruitRecall(UUID workerId) {
        this.workerId = workerId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(workerId.getMostSignificantBits());
        buf.writeLong(workerId.getLeastSignificantBits());
    }

    public static class Handler implements IMessageHandler<MsgRecruitRecall, IMessage> {
        @Override
        public IMessage onMessage(MsgRecruitRecall msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(player, msg.workerId));
            return null;
        }

        private void handle(EntityPlayerMP player, UUID id) {
            WorkerLookup.Owned o = WorkerLookup.findOwned(player.getServer(), player.getUniqueID(), id);
            if (o == null) return;
            String name;
            if (o.live != null) {
                if (!o.live.recruit.isOnMission()) return;
                o.live.recruit.recall();
                name = o.live.getName();
            } else {
                WorldServer ws = DimensionManager.getWorld(o.record.dimension);
                if (ws == null || o.record.recruitTour == null) return;
                VirtualTravellerManager.get(ws).recallRecruiter(ws, o.record);
                name = o.record.name;
            }
            NotificationService.notify(player, NotificationDefs.RECRUIT_RECALLED, name);
            WorkerGroupService.sendHub(player, true);
        }
    }
}
