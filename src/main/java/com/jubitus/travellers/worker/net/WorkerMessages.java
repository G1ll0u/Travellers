package com.jubitus.travellers.worker.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class WorkerMessages {

    private WorkerMessages() {
    }

    public static void register() {
        toServer(MsgWorkerSetDestination.class, MsgWorkerSetDestination.Handler.class);
        toServer(MsgWorkerRequestVillages.class, MsgWorkerRequestVillages.Handler.class);
        toClient(MsgWorkerVillageList.class, () -> new MsgWorkerVillageList.Handler());
        toClient(MsgSyncWorkerHireState.class, () -> new MsgSyncWorkerHireState.Handler());
        toServer(MsgHireWorker.class, MsgHireWorker.Handler.class);
        toServer(MsgRecruitWorkers.class, MsgRecruitWorkers.Handler.class);
        toServer(MsgRecruitStartSelectedVillages.class, MsgRecruitStartSelectedVillages.Handler.class);
        toServer(MsgOpenWorkerSubGui.class, MsgOpenWorkerSubGui.Handler.class);
        toServer(MsgWorkerRequestGoList.class, MsgWorkerRequestGoList.Handler.class);
        toServer(MsgWorkerGoTo.class, MsgWorkerGoTo.Handler.class);
        toServer(MsgWorkerGoToPos.class, MsgWorkerGoToPos.Handler.class);
        toClient(MsgOpenClientWorkerGui.class, () -> new MsgOpenClientWorkerGui.Handler());
        toServer(MsgRequestServerCosts.class, MsgRequestServerCosts.Handler.class);
        toClient(MsgSyncServerCosts.class, () -> new MsgSyncServerCosts.Handler());
        toClient(MsgWorkerGoList.class, () -> new MsgWorkerGoList.ClientHandler());
        toServer(MsgRecruitPreviewRequest.class, MsgRecruitPreviewRequest.Handler.class);
        toClient(MsgRecruitPreview.class, () -> new MsgRecruitPreview.Handler());
    }
}
