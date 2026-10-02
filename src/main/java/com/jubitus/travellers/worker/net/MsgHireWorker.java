package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.net.TravellerFollowNet;
import com.jubitus.travellers.village.TravellerMoney;
import com.jubitus.travellers.village.MillSettlements;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
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
                    NotificationService.notify(player, NotificationDefs.DISMISS_FOLLOWER);

                    // sync to client
                    TravellerFollowNet.CHANNEL.sendTo(
                            new MsgSyncWorkerHireState(worker),
                            player
                    );
                    return;
                }

                // --- Case 2: already hired by someone else ---
                if (hired) {
                    NotificationService.notify(player, NotificationDefs.HIRE_ALREADY_HIRED_BY_OTHER);
                    return;
                }

                // --- Case 3: not hired yet -> try to hire ---
                if (TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled
                        && TravellersConfig.CONTROLLED_VILLAGE.requireControlledVillageToHire
                        && MillSettlements.controlledBy(world, player).isEmpty()) {
                    NotificationService.notify(player, NotificationDefs.HIRE_NEEDS_VILLAGE_CHIEF);
                    return;
                }

                int costInDeniers = TravellersDerivedConfig.workerHireCostDeniersServerCache;
                int playerMoneyBefore = TravellerMoney.countPlayerMoney(player);

                if (playerMoneyBefore < costInDeniers) {
                    NotificationService.notify(player, NotificationDefs.HIRE_NOT_ENOUGH_MONEY,
                            MillCommonUtilities.getShortPrice(costInDeniers));
                    TravellerMoney.syncPlayerInventory(player);
                    return;
                }

                if (!TravellerMoney.trySpendPlayerMoney(player, costInDeniers)) {
                    NotificationService.notify(player, NotificationDefs.HIRE_PAYMENT_FAILED);
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
