package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
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

                // --- Case 3: not hired yet -> try to hire (cost 2a = 2*64 deniers) ---
                int costInDeniers = 2 * 64;
                int playerMoney = MillCommonUtilities.countMoney(player.inventory);
                if (playerMoney < costInDeniers) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.RED + "You don't have enough money (2a needed)."
                    ));
                    return;
                }

                // Pay
                MillCommonUtilities.changeMoney(player.inventory, -costInDeniers, player);

                // Hire (default: followOrderActive = true as in your hireAsFollower)
                worker.hireAsFollower(player);
                player.sendMessage(new TextComponentString(
                        TextFormatting.GREEN + "The travellers is now hired by you."
                ));

                // sync to client
                TravellerFollowNet.CHANNEL.sendTo(
                        new MsgSyncWorkerHireState(worker),
                        player
                );
            });
            return null;
        }
    }


}
