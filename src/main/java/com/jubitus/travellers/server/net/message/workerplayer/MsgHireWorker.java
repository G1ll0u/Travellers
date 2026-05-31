package com.jubitus.travellers.server.net.message.workerplayer;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.flooz.TravellerMoney;
import com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.util.UUID;

public class MsgHireWorker implements IMessage {
    private int entityId;

    public MsgHireWorker() {
    }

    public MsgHireWorker(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static class Handler implements IMessageHandler<MsgHireWorker, IMessage> {
        @Override
        public IMessage onMessage(MsgHireWorker msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                World world = player.world;
                Entity e = world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTravellerWorker)) return;

                EntityTravellerWorker worker = (EntityTravellerWorker) e;

                UUID ownerId = worker.getFollowOwnerId();
                boolean hired = worker.isHiredFollower();
                boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());

                // --- Case 1: already hired by THIS player -> dismiss ---
                if (hiredByMe) {
                    worker.dismissFollower();
                    player.sendMessage(new TextComponentString(
                            TextFormatting.YELLOW + "The travellers will no longer follow you."
                    ));

                    // sync to client
                    TravellerFollowNet.CHANNEL.sendTo(
                            new MsgSyncWorkerHireState(worker),
                            player
                    );
                    return;
                }

                // --- Case 2: already hired by someone else ---
                if (hired) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.RED + "This travellers is already hired by someone else."
                    ));
                    return;
                }

                // --- Case 3: not hired yet -> try to hire ---
                if (TravellersModConfig.controlledVillageFeatureEnabled
                        && TravellersModConfig.requireControlledVillageToHire
                        && !MillControlledVillageHelper.isPlayerVillageChief(world, player)) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.RED + "You must be chief of a Millenaire village to hire henchmen."
                    ));
                    return;
                }

                int costInDeniers = TravellersModConfig.workerHireCostDeniers_serverCache;
                int playerMoneyBefore = TravellerMoney.countPlayerMoney(player);

                if (playerMoneyBefore < costInDeniers) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.RED + "You don't have enough money (" +
                                    MillCommonUtilities.getShortPrice(costInDeniers) + " needed)."
                    ));
                    TravellerMoney.syncPlayerInventory(player);
                    return;
                }

                if (!TravellerMoney.trySpendPlayerMoney(player, costInDeniers)) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.RED + "Hire failed: money could not be deducted."
                    ));
                    return;
                }

                worker.hireAsFollower(player);


                TravellerFollowNet.CHANNEL.sendTo(
                        new MsgSyncWorkerHireState(worker),
                        player
                );
            });
            return null;
        }
    }


}
