package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.millenaire.common.village.Building;

public class MsgWorkerSetHome implements IMessage {
    private int workerId;
    private BlockPos pos;
    private int type; // HomeType ordinal

    public MsgWorkerSetHome() {
    }

    public MsgWorkerSetHome(int workerId, BlockPos pos, TravellerHomeManager.HomeType type) {
        this.workerId = workerId;
        this.pos = pos;
        this.type = type == null ? 0 : type.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        pos = BlockPos.fromLong(buf.readLong());
        type = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeLong(pos.toLong());
        buf.writeByte(type);
    }

    public static class Handler implements IMessageHandler<MsgWorkerSetHome, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerSetHome msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                Entity e = player.world.getEntityByID(msg.workerId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker w = (EntityTravellerWorker) e;

                if (!w.isHiredFollower() || w.getFollowOwnerId() == null || !w.getFollowOwnerId().equals(player.getUniqueID())) {
                    NotificationService.notify(player, NotificationDefs.HOME_NOT_YOUR_WORKER);
                    return;
                }

                TravellerHomeManager.HomeType homeType = TravellerHomeManager.HomeType.fromOrdinal(msg.type);

                if (homeType == TravellerHomeManager.HomeType.BARRACKS) {
                    handleBarracksHome(msg, player, w);
                } else if (homeType == TravellerHomeManager.HomeType.LONE_BARRACK) {
                    handleLoneBarrackHome(msg, player, w);
                }
            });
            return null;
        }

        private void handleBarracksHome(MsgWorkerSetHome msg, EntityPlayerMP player, EntityTravellerWorker w) {
            Building barracks = MillControlledVillageHelper.findBarracksAtPos(player.world, player, msg.pos);
            if (barracks == null) {
                NotificationService.notify(player, NotificationDefs.HOME_BARRACKS_NOT_FOUND);
                return;
            }

            com.jubitus.travellers.utils.world.mill.BarracksSlotManager bsm =
                    com.jubitus.travellers.utils.world.mill.BarracksSlotManager.get(player.world);
            int dim = player.world.provider.getDimension();

            // Allow reassignment to the same barracks (idempotent)
            boolean alreadyHere = bsm.isAssignedTo(w.getUniqueID(), msg.pos);
            if (!alreadyHere && bsm.getFreeSlots(player.getUniqueID(), msg.pos, dim) <= 0) {
                NotificationService.notify(player, NotificationDefs.HOME_BARRACKS_FULL);
                return;
            }

            String name = barracks.location != null && barracks.location.getIBuildingPlan() != null
                    ? barracks.location.getIBuildingPlan().getNativeName()
                    : "Barracks";

            bsm.assignWorker(player.getUniqueID(), msg.pos, dim, name, w.getUniqueID());
            w.setAssignedHome(msg.pos, dim, name, TravellerHomeManager.HomeType.BARRACKS);

            TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), player);
            NotificationService.notify(player, NotificationDefs.HOME_BARRACKS_ASSIGNED, name);
        }

        private void handleLoneBarrackHome(MsgWorkerSetHome msg, EntityPlayerMP player, EntityTravellerWorker w) {
            LoneBarrackManager lbMgr = LoneBarrackManager.get(player.world);
            LoneBarrackRecord found = null;
            for (LoneBarrackRecord r : lbMgr.getByOwner(player.getUniqueID())) {
                if (msg.pos.equals(r.center)) {
                    found = r;
                    break;
                }
            }
            if (found == null) {
                NotificationService.notify(player, NotificationDefs.HOME_LONE_BARRACK_NOT_FOUND);
                return;
            }

            lbMgr.refreshCoffers(player.world, player.getUniqueID(), found.center);
            int dim = player.world.provider.getDimension();
            w.setAssignedHome(found.center, dim, found.name, TravellerHomeManager.HomeType.LONE_BARRACK);

            TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), player);
            NotificationService.notify(player, NotificationDefs.HOME_LONE_BARRACK_ASSIGNED, found.name);
        }
    }
}
