package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.worker.SpawnGroup;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.village.MillMoney;
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
    // The group size and price the player saw; the server refuses a hire if either changed since.
    private int groupSize;
    private int price;

    public MsgHireWorker() {
    }

    public MsgHireWorker(EntityTravellerWorker worker) {
        this.entityId = worker.getEntityId();
        this.groupSize = worker.clientHireGroupSize;
        this.price = worker.clientHirePrice;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.groupSize = buf.readInt();
        this.price = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeInt(groupSize);
        buf.writeInt(price);
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
                    // INVARIANT: a worker carrying the owner's money is never dismissed with it.
                    worker.purse.handTo(player);
                    if (worker.purse.get() > 0) {
                        NotificationService.notify(player, NotificationDefs.WORKER_PURSE_NO_ROOM);
                        return;
                    }
                    worker.dismissFollower();
                    NotificationService.notify(player, NotificationDefs.DISMISS_FOLLOWER);

                    // sync to client
                    TravellersNetwork.CHANNEL.sendTo(
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

                // The whole merchant group or nobody. A group that changed since the price was shown is
                // re-priced on the player's screen instead of charged.
                SpawnGroup group = SpawnGroup.of(player.getServerWorld(), worker);
                int costInDeniers = group.hirePrice();
                if (group.size() != msg.groupSize || costInDeniers != msg.price) {
                    TravellersNetwork.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                    return;
                }
                int playerMoneyBefore = MillMoney.countPlayerMoney(player);

                if (playerMoneyBefore < costInDeniers) {
                    NotificationService.notify(player, NotificationDefs.HIRE_NOT_ENOUGH_MONEY,
                            MillMoney.format(costInDeniers));
                    MillMoney.syncPlayerInventory(player);
                    return;
                }

                if (!MillMoney.trySpendPlayerMoney(player, costInDeniers)) {
                    NotificationService.notify(player, NotificationDefs.HIRE_PAYMENT_FAILED);
                    return;
                }

                group.hireAll(player.getServerWorld(), player);


                TravellersNetwork.CHANNEL.sendTo(
                        new MsgSyncWorkerHireState(worker),
                        player
                );
            });
            return null;
        }
    }


}
