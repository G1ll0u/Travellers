package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
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
                    player.sendMessage(new TextComponentString(TextFormatting.RED + "Not your worker."));
                    return;
                }

                TravellerHomeManager.HomeType homeType = TravellerHomeManager.HomeType.fromOrdinal(msg.type);

                if (homeType == TravellerHomeManager.HomeType.BARRACKS) {
                    handleBarracksHome(msg, player, w);
                } else {
                    handleChestHome(msg, player, w);
                }
            });
            return null;
        }

        private void handleChestHome(MsgWorkerSetHome msg, EntityPlayerMP player, EntityTravellerWorker w) {
            TravellerHomeManager mgr = TravellerHomeManager.get(player.world);
            TravellerHomeManager.HomeChest hc = mgr.findHome(player.getUniqueID(), player.world.provider.getDimension(), msg.pos);
            if (hc == null) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "That home no longer exists."));
                return;
            }

            w.setAssignedHome(hc.pos, hc.dimension, hc.name, TravellerHomeManager.HomeType.CHEST);

            TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), player);
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Home assigned: " + hc.name));
        }

        private void handleBarracksHome(MsgWorkerSetHome msg, EntityPlayerMP player, EntityTravellerWorker w) {
            Building barracks = MillControlledVillageHelper.findBarracksAtPos(player.world, player, msg.pos);
            if (barracks == null) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "Barracks not found or not in your village."));
                return;
            }

            com.jubitus.travellers.utils.world.mill.BarracksSlotManager bsm =
                    com.jubitus.travellers.utils.world.mill.BarracksSlotManager.get(player.world);
            int dim = player.world.provider.getDimension();

            // Allow reassignment to the same barracks (idempotent)
            boolean alreadyHere = bsm.isAssignedTo(w.getUniqueID(), msg.pos);
            if (!alreadyHere && bsm.getFreeSlots(player.getUniqueID(), msg.pos, dim) <= 0) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "This barracks is full!"));
                return;
            }

            String name = barracks.location != null && barracks.location.getIBuildingPlan() != null
                    ? barracks.location.getIBuildingPlan().getNativeName()
                    : "Barracks";

            bsm.assignWorker(player.getUniqueID(), msg.pos, dim, name, w.getUniqueID());
            w.setAssignedHome(msg.pos, dim, name, TravellerHomeManager.HomeType.BARRACKS);

            TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), player);
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Barracks assigned: " + name));
        }
    }
}
