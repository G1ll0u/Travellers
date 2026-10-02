package com.jubitus.travellers.worker.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class WorkerMessages {

    private WorkerMessages() {
    }

    public static void register() {
        toServer(MsgWorkerSetDestination.class, MsgWorkerSetDestination.Handler.class);
        toServer(MsgWorkerRequestVillages.class, MsgWorkerRequestVillages.Handler.class);
        toClient(MsgWorkerVillageList.class, MsgWorkerVillageList.Handler::new);
        toClient(MsgSyncWorkerHireState.class, MsgSyncWorkerHireState.Handler::new);
        toServer(MsgHireWorker.class, MsgHireWorker.Handler.class);
        toServer(MsgRecruitWorkers.class, MsgRecruitWorkers.Handler.class);
        toServer(MsgRecruitStartSelectedVillages.class, MsgRecruitStartSelectedVillages.Handler.class);
        toServer(MsgOpenWorkerSubGui.class, MsgOpenWorkerSubGui.Handler.class);
        toServer(MsgWorkerRequestGoList.class, MsgWorkerRequestGoList.Handler.class);
        toServer(MsgWorkerGoTo.class, MsgWorkerGoTo.Handler.class);
        toServer(MsgWorkerGoToPos.class, MsgWorkerGoToPos.Handler.class);
        toClient(MsgOpenClientWorkerGui.class, MsgOpenClientWorkerGui.Handler::new);
        toServer(MsgRequestServerCosts.class, MsgRequestServerCosts.Handler.class);
        toClient(MsgSyncServerCosts.class, MsgSyncServerCosts.Handler::new);
        toClient(MsgWorkerGoList.class, MsgWorkerGoList.ClientHandler::new);
    }
}
