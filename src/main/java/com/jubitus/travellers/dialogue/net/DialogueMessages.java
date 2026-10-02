package com.jubitus.travellers.dialogue.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class DialogueMessages {

    private DialogueMessages() {
    }

    public static void register() {
        toClient(MsgOpenDialogueSetupGui.class, MsgOpenDialogueSetupGui.Handler::new);
        toClient(MsgOpenTravellerDialogue.class, MsgOpenTravellerDialogue.Handler::new);
        toServer(MsgDialogueToolQuery.class, MsgDialogueToolQuery.Handler.class);
        toClient(MsgDialogueToolResult.class, MsgDialogueToolResult.Handler::new);
        toServer(MsgSubmitDialogueMemory.class, MsgSubmitDialogueMemory.Handler.class);
    }
}
