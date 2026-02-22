package com.jubitus.travellers.utils.net;

import com.jubitus.travellers.utils.net.message.*;
import com.jubitus.travellers.utils.net.message.home.*;
import com.jubitus.travellers.utils.net.message.leadfollow.*;
import com.jubitus.travellers.utils.net.message.map.*;
import com.jubitus.travellers.utils.net.message.recruit.MsgRecruitStartSelectedVillages;
import com.jubitus.travellers.utils.net.message.recruit.MsgRecruitWorkers;
import com.jubitus.travellers.utils.net.message.sounds.MsgPlayTravellerVoice;
import com.jubitus.travellers.utils.net.message.sounds.MsgStopTravellerVoice;
import com.jubitus.travellers.utils.net.message.villageshomes.MsgWorkerRequestVillages;
import com.jubitus.travellers.utils.net.message.villageshomes.MsgWorkerVillageList;
import com.jubitus.travellers.utils.net.message.workergo.*;
import com.jubitus.travellers.utils.net.message.workerplayer.*;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class TravellerFollowNet {
    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel("travfollow");
    private static boolean registered = false;
    private static int NEXT_ID = 0;

    private TravellerFollowNet() {
    }

    public static void init() {
        if (registered) return;
        boolean isClient = FMLCommonHandler.instance().getSide().isClient();

        registerBoth(MsgToggleFollow.class, new MsgToggleFollow.Handler(), new MsgToggleFollow.NoopHandler());
        registerBoth(MsgWorkerSetDestination.class, new MsgWorkerSetDestination.NoopHandler(), new MsgWorkerSetDestination.Handler());
        registerBoth(MsgWorkerRequestVillages.class, new MsgWorkerRequestVillages.NoopHandler(), new MsgWorkerRequestVillages.Handler());

        registerClientOnly(MsgWorkerVillageList.class, isClient ? new MsgWorkerVillageList.Handler() : new MsgWorkerVillageList.NoopHandler());
        registerClientOnly(MsgPlayTravellerVoice.class, isClient ? new MsgPlayTravellerVoice.Handler() : new MsgPlayTravellerVoice.NoopHandler());
        registerClientOnly(MsgStopTravellerVoice.class, isClient ? new MsgStopTravellerVoice.Handler() : new MsgStopTravellerVoice.NoopHandler());
        registerClientOnly(MsgSyncWorkerHireState.class, isClient ? new MsgSyncWorkerHireState.Handler() : new MsgSyncWorkerHireState.NoopHandler());
        registerClientOnly(
                MsgSyncWorkerHostileList.class,
                isClient ? new MsgSyncWorkerHostileList.Handler() : new MsgSyncWorkerHostileList.NoopHandler()
        );
        CHANNEL.registerMessage(MsgHireWorker.Handler.class, MsgHireWorker.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgToggleWorkerFollow.Handler.class, MsgToggleWorkerFollow.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgMakeWorkerLeader.Handler.class, MsgMakeWorkerLeader.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgRecruitWorkers.Handler.class, MsgRecruitWorkers.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgRecruitStartSelectedVillages.Handler.class,
                MsgRecruitStartSelectedVillages.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgClearEscortLeader.Handler.class, MsgClearEscortLeader.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgSetWorkerHostile.Handler.class, MsgSetWorkerHostile.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgOpenWorkerSubGui.Handler.class,
                MsgOpenWorkerSubGui.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgWorkerSetHome.Handler.class, MsgWorkerSetHome.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgWorkerGoHome.Handler.class, MsgWorkerGoHome.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgRegisterHomeChest.Handler.class, MsgRegisterHomeChest.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgWorkerRequestHomeList.Handler.class,
                MsgWorkerRequestHomeList.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgWorkerRequestGoList.Handler.class, MsgWorkerRequestGoList.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(MsgWorkerGoTo.Handler.class, MsgWorkerGoTo.class, NEXT_ID++, Side.SERVER);
        CHANNEL.registerMessage(
                MsgWorkerGoToPos.Handler.class,
                MsgWorkerGoToPos.class,
                NEXT_ID++,
                Side.SERVER
        );

        // ---- Map discovery: server -> client ----
        registerClientOnly(MsgMapDiscoverTile.class,
                isClient ? new MsgMapDiscoverTile.ClientHandler() : new MsgMapDiscoverTile.NoopHandler());

        registerClientOnly(MsgMapDiscoverySync.class,
                isClient ? new MsgMapDiscoverySync.ClientHandler() : new MsgMapDiscoverySync.NoopHandler());

        CHANNEL.registerMessage(MsgRequestMapDiscovery.Handler.class,
                MsgRequestMapDiscovery.class, NEXT_ID++, Side.SERVER);
    CHANNEL.registerMessage(MsgRequestServerCosts.Handler.class, MsgRequestServerCosts.class, NEXT_ID++, Side.SERVER);
    CHANNEL.registerMessage(MsgSyncServerCosts.Handler.class, MsgSyncServerCosts.class, NEXT_ID++, Side.CLIENT);
// server -> client
        registerClientOnly(MsgWorkerGoList.class,
                isClient ? new MsgWorkerGoList.ClientHandler() : new MsgWorkerGoList.NoopHandler());

        // server -> client (client-only handlers, use Noop on dedicated server)
        registerClientOnly(MsgWorkerHomeList.class,
                isClient ? new MsgWorkerHomeList.ClientHandler() : new MsgWorkerHomeList.NoopHandler());

        registerClientOnly(MsgSyncWorkerHome.class,
                isClient ? new MsgSyncWorkerHome.ClientHandler() : new MsgSyncWorkerHome.NoopHandler());
        CHANNEL.registerMessage(MsgRequestMapTiles.Handler.class,
                MsgRequestMapTiles.class, NEXT_ID++, Side.SERVER);
        registerClientOnly(MsgMapTileData.class,
                isClient ? new MsgMapTileData.ClientHandler() : new MsgMapTileData.NoopHandler());
        registered = true;
    }


    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerBoth(Class<? extends IMessage> msgType,
                                     IMessageHandler<?, ?> clientHandler,
                                     IMessageHandler<?, ?> serverHandler) {
        int id = NEXT_ID++;
        CHANNEL.registerMessage((IMessageHandler) clientHandler, (Class) msgType, id, Side.CLIENT);
        CHANNEL.registerMessage((IMessageHandler) serverHandler, (Class) msgType, id, Side.SERVER);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerClientOnly(Class<? extends IMessage> msgType,
                                           IMessageHandler<?, ?> clientHandler) {
        int id = NEXT_ID++;
        CHANNEL.registerMessage((IMessageHandler) clientHandler, (Class) msgType, id, Side.CLIENT);
    }

    public static void sendToPlayer(net.minecraft.entity.player.EntityPlayer player, IMessage msg) {
        if (player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            CHANNEL.sendTo(msg, (net.minecraft.entity.player.EntityPlayerMP) player);
        }
    }


}



