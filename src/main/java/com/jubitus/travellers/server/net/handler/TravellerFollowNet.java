package com.jubitus.travellers.server.net.handler;

import com.jubitus.travellers.server.map.stream.MsgMapCampUpdate;
import com.jubitus.travellers.server.map.stream.MsgMapPopulationUpdate;
import com.jubitus.travellers.server.map.stream.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.stream.MsgOpenPopulationMap;
import com.jubitus.travellers.server.map.stream.MsgSubscribeCampMap;
import com.jubitus.travellers.server.map.stream.MsgSubscribePopulationMap;
import com.jubitus.travellers.server.map.stream.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.message.MsgOpenClientWorkerGui;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.formations.MsgOpenFormationHubGui;
import com.jubitus.travellers.server.net.message.formations.MsgRequestFormationHub;
import com.jubitus.travellers.server.net.message.formations.MsgUpdateFormationByScope;
import com.jubitus.travellers.server.net.message.formations.MsgUpdateWorkerFormationSettings;
import com.jubitus.travellers.server.net.message.group.MsgGroupCreate;
import com.jubitus.travellers.server.net.message.group.MsgGroupFollow;
import com.jubitus.travellers.server.net.message.group.MsgGroupMerge;
import com.jubitus.travellers.server.net.message.group.MsgLeaderGoTo;
import com.jubitus.travellers.server.net.message.group.MsgSetLeaderFollow;
import com.jubitus.travellers.server.net.message.home.*;
import com.jubitus.travellers.server.net.message.home.MsgCreatePlayerZone;
import com.jubitus.travellers.server.net.message.home.MsgDeletePlayerZone;
import com.jubitus.travellers.server.net.message.home.MsgZoneActionResult;
import com.jubitus.travellers.server.net.message.home.MsgOpenZonePrompt;
import com.jubitus.travellers.server.net.message.home.MsgRequestWorkerZoneData;
import com.jubitus.travellers.server.net.message.home.MsgAssignWorkerDuty;
import com.jubitus.travellers.server.net.message.home.MsgUnassignWorkerDuty;
import com.jubitus.travellers.server.net.message.home.MsgWorkerZoneData;
import com.jubitus.travellers.server.net.message.leadfollow.MsgClearEscortLeader;
import com.jubitus.travellers.server.net.message.leadfollow.MsgMakeWorkerLeader;
import com.jubitus.travellers.server.net.message.leadfollow.MsgSyncLeaderFollowerCount;
import com.jubitus.travellers.server.net.message.leadfollow.MsgToggleFollow;
import com.jubitus.travellers.server.net.message.leadfollow.MsgToggleWorkerFollow;
import com.jubitus.travellers.server.net.message.map.*;
import com.jubitus.travellers.server.net.message.marker.MsgOwnerVisualsSync;
import com.jubitus.travellers.server.net.message.marker.MsgSaveWorkerOwnerVisuals;
import com.jubitus.travellers.server.net.message.workergo.*;
import com.jubitus.travellers.server.net.message.workerplayer.*;
import com.jubitus.travellers.server.net.message.recruit.MsgRecruitStartSelectedVillages;
import com.jubitus.travellers.server.net.message.recruit.MsgRecruitWorkers;
import com.jubitus.travellers.server.net.message.choir.MsgWorkerChoirToggle;
import com.jubitus.travellers.server.net.message.relations.MsgSetVillageEnemy;
import com.jubitus.travellers.server.net.message.relations.MsgWorkerRequestVillageRelations;
import com.jubitus.travellers.server.net.message.relations.MsgWorkerVillageRelationsList;
import com.jubitus.jubiconfig.network.MsgOpenConfigGui;
import com.jubitus.jubiconfig.network.MsgSaveConfigGui;
import com.jubitus.travellers.server.net.message.sounds.MsgChoirSoundDone;
import com.jubitus.travellers.server.net.message.sounds.MsgChoirSingerMute;
import com.jubitus.travellers.server.net.message.sounds.MsgPlayTravellerVoice;
import com.jubitus.travellers.server.net.message.sounds.MsgStopTravellerVoice;
import com.jubitus.travellers.server.net.message.villageshomes.MsgWorkerRequestVillages;
import com.jubitus.travellers.server.net.message.villageshomes.MsgWorkerVillageList;
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
    private static int id = 0;

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
        CHANNEL.registerMessage(MsgHireWorker.Handler.class, MsgHireWorker.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgToggleWorkerFollow.Handler.class, MsgToggleWorkerFollow.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgMakeWorkerLeader.Handler.class, MsgMakeWorkerLeader.class, id++, Side.SERVER);
        registerClientOnly(MsgSyncLeaderFollowerCount.class,
                isClient ? new MsgSyncLeaderFollowerCount.Handler() : new MsgSyncLeaderFollowerCount.NoopHandler());
        registerClientOnly(
                com.jubitus.travellers.server.net.message.debug.MsgTraceHud.class,
                isClient ? new com.jubitus.travellers.server.net.message.debug.MsgTraceHud.Handler()
                        : new com.jubitus.travellers.server.net.message.debug.MsgTraceHud.NoopHandler());
        CHANNEL.registerMessage(MsgRecruitWorkers.Handler.class, MsgRecruitWorkers.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgRecruitStartSelectedVillages.Handler.class,
                MsgRecruitStartSelectedVillages.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgClearEscortLeader.Handler.class, MsgClearEscortLeader.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgSetWorkerHostile.Handler.class, MsgSetWorkerHostile.class, id++, Side.SERVER);
        CHANNEL.registerMessage(
                com.jubitus.travellers.server.net.message.workerplayer.MsgRequestPlayerHostileList.Handler.class,
                com.jubitus.travellers.server.net.message.workerplayer.MsgRequestPlayerHostileList.class,
                id++, Side.SERVER);
        CHANNEL.registerMessage(MsgOpenWorkerSubGui.Handler.class,
                MsgOpenWorkerSubGui.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgWorkerGoHome.Handler.class, MsgWorkerGoHome.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgWorkerRequestGoList.Handler.class, MsgWorkerRequestGoList.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgWorkerGoTo.Handler.class, MsgWorkerGoTo.class, id++, Side.SERVER);
        CHANNEL.registerMessage(
                MsgWorkerGoToPos.Handler.class,
                MsgWorkerGoToPos.class,
                id++,
                Side.SERVER
        );
// client -> server: subscribe/unsubscribe map stream
        CHANNEL.registerMessage(MsgSubscribeWorkerMap.Handler.class, MsgSubscribeWorkerMap.class, id++, Side.SERVER);

// server -> client: worker positions updates
        registerClientOnly(MsgMapWorkersUpdate.class,
                isClient ? new MsgMapWorkersUpdate.ClientHandler() : new MsgMapWorkersUpdate.NoopHandler());

        registerClientOnly(
                MsgOpenClientWorkerGui.class,
                isClient ? new MsgOpenClientWorkerGui.Handler() : new MsgOpenClientWorkerGui.NoopHandler()
        );

        registerClientOnly(
                com.jubitus.travellers.server.net.message.dialogue.MsgOpenDialogueSetupGui.class,
                isClient ? new com.jubitus.travellers.server.net.message.dialogue.MsgOpenDialogueSetupGui.Handler()
                        : new com.jubitus.travellers.server.net.message.dialogue.MsgOpenDialogueSetupGui.NoopHandler()
        );

        registerClientOnly(
                com.jubitus.travellers.server.net.message.dialogue.MsgOpenTravellerDialogue.class,
                isClient ? new com.jubitus.travellers.server.net.message.dialogue.MsgOpenTravellerDialogue.Handler()
                        : new com.jubitus.travellers.server.net.message.dialogue.MsgOpenTravellerDialogue.NoopHandler()
        );

        // ---- Map discovery: server -> client ----
        registerClientOnly(MsgMapDiscoverTile.class,
                isClient ? new MsgMapDiscoverTile.ClientHandler() : new MsgMapDiscoverTile.NoopHandler());

        registerClientOnly(MsgMapDiscoverySync.class,
                isClient ? new MsgMapDiscoverySync.ClientHandler() : new MsgMapDiscoverySync.NoopHandler());

        CHANNEL.registerMessage(
                MsgSaveWorkerOwnerVisuals.Handler.class,
                MsgSaveWorkerOwnerVisuals.class,
                id++,
                Side.SERVER
        );
        registerClientOnly(MsgOwnerVisualsSync.class,
                isClient ? new MsgOwnerVisualsSync.Handler() : new MsgOwnerVisualsSync.NoopHandler());
        CHANNEL.registerMessage(MsgRequestMapDiscovery.Handler.class,
                MsgRequestMapDiscovery.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgRequestServerCosts.Handler.class, MsgRequestServerCosts.class, id++, Side.SERVER);

        registerClientOnly(
                MsgSyncServerCosts.class,
                isClient ? new MsgSyncServerCosts.Handler() : new MsgSyncServerCosts.NoopHandler()
        );
        // client -> server: tell server current map view (center/range)
        CHANNEL.registerMessage(MsgWorkerMapView.Handler.class, MsgWorkerMapView.class, id++, Side.SERVER);
// server -> client
        registerClientOnly(MsgWorkerGoList.class,
                isClient ? new MsgWorkerGoList.ClientHandler() : new MsgWorkerGoList.NoopHandler());

        // server -> client (client-only handlers, use Noop on dedicated server)
        registerClientOnly(MsgSyncWorkerHome.class,
                isClient ? new MsgSyncWorkerHome.ClientHandler() : new MsgSyncWorkerHome.NoopHandler());
        CHANNEL.registerMessage(MsgRequestMapTiles.Handler.class,
                MsgRequestMapTiles.class, id++, Side.SERVER);
        registerClientOnly(MsgMapTileData.class,
                isClient ? new MsgMapTileData.ClientHandler() : new MsgMapTileData.NoopHandler());

        // Debug: scanned-terrain overlay on GuiTravellerPopulationMap
        CHANNEL.registerMessage(
                com.jubitus.travellers.server.net.message.map.MsgTerrainDebugRequest.Handler.class,
                com.jubitus.travellers.server.net.message.map.MsgTerrainDebugRequest.class, id++, Side.SERVER);
        registerClientOnly(com.jubitus.travellers.server.net.message.map.MsgTerrainDebugMap.class,
                isClient ? new com.jubitus.travellers.server.net.message.map.MsgTerrainDebugMap.ClientHandler()
                         : new com.jubitus.travellers.server.net.message.map.MsgTerrainDebugMap.NoopHandler());
        // Debug: one unit's itinerary drawn on GuiTravellerPopulationMap
        CHANNEL.registerMessage(
                com.jubitus.travellers.server.net.message.map.MsgItineraryDebugRequest.Handler.class,
                com.jubitus.travellers.server.net.message.map.MsgItineraryDebugRequest.class, id++, Side.SERVER);
        registerClientOnly(com.jubitus.travellers.server.net.message.map.MsgItineraryDebugMap.class,
                isClient ? new com.jubitus.travellers.server.net.message.map.MsgItineraryDebugMap.ClientHandler()
                         : new com.jubitus.travellers.server.net.message.map.MsgItineraryDebugMap.NoopHandler());
        CHANNEL.registerMessage(MsgWorkerRequestVillageRelations.Handler.class,
                MsgWorkerRequestVillageRelations.class, id++, Side.SERVER);

        registerClientOnly(
                MsgWorkerVillageRelationsList.class,
                isClient ? new MsgWorkerVillageRelationsList.Handler() : new MsgWorkerVillageRelationsList.NoopHandler()
        );
        CHANNEL.registerMessage(MsgSetVillageEnemy.Handler.class,
                MsgSetVillageEnemy.class, id++, Side.SERVER);

        registerClientOnly(
                MsgOpenZonePrompt.class,
                isClient ? new MsgOpenZonePrompt.ClientHandler() : new MsgOpenZonePrompt.NoopHandler()
        );
        CHANNEL.registerMessage(MsgWorkerGetEquipment.Handler.class, MsgWorkerGetEquipment.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgCreatePlayerZone.Handler.class, MsgCreatePlayerZone.class, id++, Side.SERVER);
        registerClientOnly(MsgZoneActionResult.class,
                isClient ? new MsgZoneActionResult.ClientHandler() : new MsgZoneActionResult.NoopHandler());
        CHANNEL.registerMessage(MsgDeletePlayerZone.Handler.class, MsgDeletePlayerZone.class, id++, Side.SERVER);

        // Barracks-assignment GUI
        CHANNEL.registerMessage(MsgRequestWorkerBarracksData.Handler.class,
                MsgRequestWorkerBarracksData.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgAssignWorkerBarrack.Handler.class,
                MsgAssignWorkerBarrack.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgUnassignWorkerBarrack.Handler.class,
                MsgUnassignWorkerBarrack.class, id++, Side.SERVER);
        registerClientOnly(MsgWorkerBarracksData.class,
                isClient ? new MsgWorkerBarracksData.ClientHandler() : new MsgWorkerBarracksData.NoopHandler());

        // Duty-assignment GUI (Stable zones) - separate wire format from the Barracks one above.
        CHANNEL.registerMessage(MsgRequestWorkerZoneData.Handler.class,
                MsgRequestWorkerZoneData.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgAssignWorkerDuty.Handler.class,
                MsgAssignWorkerDuty.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgUnassignWorkerDuty.Handler.class,
                MsgUnassignWorkerDuty.class, id++, Side.SERVER);
        registerClientOnly(MsgWorkerZoneData.class,
                isClient ? new MsgWorkerZoneData.ClientHandler() : new MsgWorkerZoneData.NoopHandler());

        registered = true;

        CHANNEL.registerMessage(
                MsgUpdateWorkerFormationSettings.Handler.class,
                MsgUpdateWorkerFormationSettings.class,
                id++,
                Side.SERVER
        );

        CHANNEL.registerMessage(MsgWorkerChoirToggle.Handler.class,
                MsgWorkerChoirToggle.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgChoirSoundDone.Handler.class,
                MsgChoirSoundDone.class, id++, Side.SERVER);
        registerClientOnly(MsgChoirSingerMute.class, isClient ? new MsgChoirSingerMute.Handler() : new MsgChoirSingerMute.NoopHandler());

        CHANNEL.registerMessage(MsgGroupCreate.Handler.class, MsgGroupCreate.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgGroupMerge.Handler.class, MsgGroupMerge.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgGroupFollow.Handler.class, MsgGroupFollow.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgSetLeaderFollow.Handler.class, MsgSetLeaderFollow.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgLeaderGoTo.Handler.class, MsgLeaderGoTo.class, id++, Side.SERVER);
        CHANNEL.registerMessage(MsgUpdateFormationByScope.Handler.class, MsgUpdateFormationByScope.class, id++, Side.SERVER);
        registerClientOnly(MsgOpenFormationHubGui.class,
                isClient ? new MsgOpenFormationHubGui.Handler() : new MsgOpenFormationHubGui.NoopHandler());
        CHANNEL.registerMessage(MsgRequestFormationHub.Handler.class, MsgRequestFormationHub.class, id++, Side.SERVER);

        // Config GUI messages
        registerClientOnly(MsgOpenConfigGui.class,
                isClient ? new MsgOpenConfigGui.Handler() : new MsgOpenConfigGui.NoopHandler());
        CHANNEL.registerMessage(MsgSaveConfigGui.Handler.class, MsgSaveConfigGui.class, id++, Side.SERVER);

        // Formation hub: Get Equipment
        CHANNEL.registerMessage(MsgRequestEquipDests.Handler.class, MsgRequestEquipDests.class, id++, Side.SERVER);
        registerClientOnly(MsgEquipDestsResponse.class,
                isClient ? new MsgEquipDestsResponse.ClientHandler() : new MsgEquipDestsResponse.NoopHandler());
        CHANNEL.registerMessage(MsgLeaderGetEquip.Handler.class, MsgLeaderGetEquip.class, id++, Side.SERVER);

        // Population map (travellers/raiders/workers, loaded + virtual, world overview)
        registerClientOnly(MsgOpenPopulationMap.class,
                isClient ? new MsgOpenPopulationMap.Handler() : new MsgOpenPopulationMap.NoopHandler());
        CHANNEL.registerMessage(MsgSubscribePopulationMap.Handler.class, MsgSubscribePopulationMap.class, id++, Side.SERVER);
        registerClientOnly(MsgMapPopulationUpdate.class,
                isClient ? new MsgMapPopulationUpdate.ClientHandler() : new MsgMapPopulationUpdate.NoopHandler());

        // Camp debug overlay (firepits + shelter slots, loaded + virtual), same map GUI
        CHANNEL.registerMessage(MsgSubscribeCampMap.Handler.class, MsgSubscribeCampMap.class, id++, Side.SERVER);
        registerClientOnly(MsgMapCampUpdate.class,
                isClient ? new MsgMapCampUpdate.ClientHandler() : new MsgMapCampUpdate.NoopHandler());

        // Virtual battle markers (subscription piggybacks on MsgSubscribePopulationMap)
        registerClientOnly(com.jubitus.travellers.server.map.stream.MsgMapBattleUpdate.class,
                isClient ? new com.jubitus.travellers.server.map.stream.MsgMapBattleUpdate.ClientHandler()
                         : new com.jubitus.travellers.server.map.stream.MsgMapBattleUpdate.NoopHandler());

        // Foreign (not-my-own) unit vision markers (subscription piggybacks on MsgSubscribeWorkerMap)
        registerClientOnly(com.jubitus.travellers.server.map.stream.MsgMapForeignUnitsUpdate.class,
                isClient ? new com.jubitus.travellers.server.map.stream.MsgMapForeignUnitsUpdate.ClientHandler()
                         : new com.jubitus.travellers.server.map.stream.MsgMapForeignUnitsUpdate.NoopHandler());

        // Debug: teleport player to a clicked map position
        CHANNEL.registerMessage(
                com.jubitus.travellers.server.net.message.map.MsgTeleportToPos.Handler.class,
                com.jubitus.travellers.server.net.message.map.MsgTeleportToPos.class, id++, Side.SERVER);

        // Debug: spawn a virtual traveller/worker at a clicked map position
        CHANNEL.registerMessage(
                com.jubitus.travellers.server.net.message.map.MsgSpawnEntityAtPos.Handler.class,
                com.jubitus.travellers.server.net.message.map.MsgSpawnEntityAtPos.class, id++, Side.SERVER);

        // On-screen notification toasts
        registerClientOnly(com.jubitus.travellers.server.net.message.notify.MsgShowNotification.class,
                isClient ? new com.jubitus.travellers.server.net.message.notify.MsgShowNotification.Handler()
                         : new com.jubitus.travellers.server.net.message.notify.MsgShowNotification.NoopHandler());

        // Notification history panel (GuiWorkerFormationHub)
        CHANNEL.registerMessage(
                com.jubitus.travellers.server.net.message.notify.MsgRequestJournalHistory.Handler.class,
                com.jubitus.travellers.server.net.message.notify.MsgRequestJournalHistory.class, id++, Side.SERVER);
        registerClientOnly(com.jubitus.travellers.server.net.message.notify.MsgJournalHistoryData.class,
                isClient ? new com.jubitus.travellers.server.net.message.notify.MsgJournalHistoryData.ClientHandler()
                         : new com.jubitus.travellers.server.net.message.notify.MsgJournalHistoryData.NoopHandler());
        registerClientOnly(com.jubitus.travellers.server.net.message.notify.MsgJournalEntryAdded.class,
                isClient ? new com.jubitus.travellers.server.net.message.notify.MsgJournalEntryAdded.ClientHandler()
                         : new com.jubitus.travellers.server.net.message.notify.MsgJournalEntryAdded.NoopHandler());

        // Dialogue tool calling (Phase 3): client -> server query, server -> client result
        CHANNEL.registerMessage(
                com.jubitus.travellers.server.net.message.dialogue.MsgDialogueToolQuery.Handler.class,
                com.jubitus.travellers.server.net.message.dialogue.MsgDialogueToolQuery.class, id++, Side.SERVER);
        registerClientOnly(
                com.jubitus.travellers.server.net.message.dialogue.MsgDialogueToolResult.class,
                isClient ? new com.jubitus.travellers.server.net.message.dialogue.MsgDialogueToolResult.Handler()
                        : new com.jubitus.travellers.server.net.message.dialogue.MsgDialogueToolResult.NoopHandler());

        // Dialogue memory (Phase 3): client -> server, fire-and-forget end-of-conversation fact submission
        CHANNEL.registerMessage(
                com.jubitus.travellers.server.net.message.dialogue.MsgSubmitDialogueMemory.Handler.class,
                com.jubitus.travellers.server.net.message.dialogue.MsgSubmitDialogueMemory.class, id++, Side.SERVER);
        // Debug: Millénaire buildings as the mod reads them, drawn in the world
        registerClientOnly(com.jubitus.travellers.server.net.message.debug.MsgVillageDebugView.class,
                isClient ? new com.jubitus.travellers.server.net.message.debug.MsgVillageDebugView.Handler()
                        : new com.jubitus.travellers.server.net.message.debug.MsgVillageDebugView.NoopHandler());
    }


    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerBoth(Class<? extends IMessage> msgType,
                                     IMessageHandler<?, ?> clientHandler,
                                     IMessageHandler<?, ?> serverHandler) {
        int id = TravellerFollowNet.id++;
        CHANNEL.registerMessage((IMessageHandler) clientHandler, (Class) msgType, id, Side.CLIENT);
        CHANNEL.registerMessage((IMessageHandler) serverHandler, (Class) msgType, id, Side.SERVER);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerClientOnly(Class<? extends IMessage> msgType,
                                           IMessageHandler<?, ?> clientHandler) {
        int id = TravellerFollowNet.id++;
        CHANNEL.registerMessage((IMessageHandler) clientHandler, (Class) msgType, id, Side.CLIENT);
    }

    public static void sendToPlayer(net.minecraft.entity.player.EntityPlayer player, IMessage msg) {
        if (player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            CHANNEL.sendTo(msg, (net.minecraft.entity.player.EntityPlayerMP) player);
        }
    }


}



