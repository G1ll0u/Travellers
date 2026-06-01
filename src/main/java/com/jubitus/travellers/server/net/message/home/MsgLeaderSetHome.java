package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualWorkerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualWorkerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.millenaire.common.village.Building;

import java.util.UUID;

public class MsgLeaderSetHome implements IMessage {

    private UUID leaderUUID;
    private BlockPos pos;
    private int typeOrdinal;

    public MsgLeaderSetHome() {}

    public MsgLeaderSetHome(UUID leaderUUID, BlockPos pos, TravellerHomeManager.HomeType type) {
        this.leaderUUID = leaderUUID;
        this.pos = pos;
        this.typeOrdinal = type == null ? 0 : type.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        leaderUUID = new UUID(msb, lsb);
        pos = BlockPos.fromLong(buf.readLong());
        typeOrdinal = buf.readUnsignedByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(leaderUUID.getMostSignificantBits());
        buf.writeLong(leaderUUID.getLeastSignificantBits());
        buf.writeLong(pos.toLong());
        buf.writeByte(typeOrdinal);
    }

    public static class Handler implements IMessageHandler<MsgLeaderSetHome, IMessage> {
        @Override
        public IMessage onMessage(MsgLeaderSetHome msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                net.minecraft.server.MinecraftServer srv = player.getServer();
                if (srv == null) return;

                UUID ownerId = player.getUniqueID();
                TravellerHomeManager.HomeType homeType = TravellerHomeManager.HomeType.fromOrdinal(msg.typeOrdinal);
                String name = resolveHomeName(player.world, player, msg.pos, homeType);
                int dim = player.world.provider.getDimension();

                // For barracks: check slot availability before assigning
                if (homeType == TravellerHomeManager.HomeType.BARRACKS) {
                    com.jubitus.travellers.utils.world.mill.BarracksSlotManager bsm =
                            com.jubitus.travellers.utils.world.mill.BarracksSlotManager.get(player.world);
                    boolean alreadyHere = bsm.isAssignedTo(msg.leaderUUID, msg.pos);
                    if (!alreadyHere && bsm.getFreeSlots(ownerId, msg.pos, dim) <= 0) {
                        player.sendMessage(new net.minecraft.util.text.TextComponentString(
                                net.minecraft.util.text.TextFormatting.RED + "This barracks is full!"));
                        return;
                    }
                }

                // Try real entity first
                for (WorldServer ws : srv.worlds) {
                    net.minecraft.entity.Entity e = ws.getEntityFromUuid(msg.leaderUUID);
                    if (e instanceof EntityTravellerWorker) {
                        EntityTravellerWorker worker = (EntityTravellerWorker) e;
                        if (!worker.isHiredFollower() || !ownerId.equals(worker.getFollowOwnerId())) return;
                        if (homeType == TravellerHomeManager.HomeType.BARRACKS) {
                            com.jubitus.travellers.utils.world.mill.BarracksSlotManager.get(player.world)
                                    .assignWorker(ownerId, msg.pos, dim, name, worker.getUniqueID());
                        }
                        worker.setAssignedHome(msg.pos, dim, name, homeType);
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(worker), player);
                        return;
                    }
                }

                // Try virtual record
                WorldServer overworld = srv.worlds[0];
                VirtualWorkerManager vwm = VirtualWorkerManager.get(overworld);
                VirtualWorkerRecord r = vwm.getRecord(msg.leaderUUID);
                if (r == null || !ownerId.equals(r.followOwnerUUID)) return;

                if (homeType == TravellerHomeManager.HomeType.BARRACKS) {
                    com.jubitus.travellers.utils.world.mill.BarracksSlotManager.get(player.world)
                            .assignWorker(ownerId, msg.pos, dim, name, r.uuid);
                }

                EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
                temp.readFromNBT(r.entityNbt);
                temp.setUniqueId(r.uuid);
                temp.setAssignedHome(msg.pos, dim, name, homeType);

                NBTTagCompound updatedNbt = new NBTTagCompound();
                temp.writeToNBT(updatedNbt);
                r.entityNbt = updatedNbt;
                vwm.markDirty();
            });
            return null;
        }

        private String resolveHomeName(net.minecraft.world.World world, EntityPlayerMP player,
                                        BlockPos pos, TravellerHomeManager.HomeType type) {
            if (type == TravellerHomeManager.HomeType.BARRACKS) {
                Building b = MillControlledVillageHelper.findBarracksAtPos(world, player, pos);
                if (b != null && b.location != null && b.location.getIBuildingPlan() != null) {
                    return b.location.getIBuildingPlan().getNativeName();
                }
                return "Barracks";
            }
            TravellerHomeManager mgr = TravellerHomeManager.get(world);
            TravellerHomeManager.HomeChest hc = mgr.findHome(player.getUniqueID(), world.provider.getDimension(), pos);
            return hc != null ? hc.name : "";
        }
    }
}
