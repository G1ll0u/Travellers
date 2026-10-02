package com.jubitus.travellers.follow.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class FollowMessages {

    private FollowMessages() {
    }

    public static void register() {
        toServer(MsgToggleWorkerFollow.class, MsgToggleWorkerFollow.Handler.class);
        toServer(MsgMakeWorkerLeader.class, MsgMakeWorkerLeader.Handler.class);
        toClient(MsgSyncLeaderFollowerCount.class, () -> new MsgSyncLeaderFollowerCount.Handler());
        toServer(MsgClearEscortLeader.class, MsgClearEscortLeader.Handler.class);
        toServer(MsgUpdateWorkerFormationSettings.class, MsgUpdateWorkerFormationSettings.Handler.class);
        toServer(MsgGroupCreate.class, MsgGroupCreate.Handler.class);
        toServer(MsgGroupMerge.class, MsgGroupMerge.Handler.class);
        toServer(MsgGroupFollow.class, MsgGroupFollow.Handler.class);
        toServer(MsgSetLeaderFollow.class, MsgSetLeaderFollow.Handler.class);
        toServer(MsgLeaderGoTo.class, MsgLeaderGoTo.Handler.class);
        toServer(MsgUpdateFormationByScope.class, MsgUpdateFormationByScope.Handler.class);
        toClient(MsgOpenFormationHubGui.class, () -> new MsgOpenFormationHubGui.Handler());
        toServer(MsgRequestFormationHub.class, MsgRequestFormationHub.Handler.class);
    }
}
