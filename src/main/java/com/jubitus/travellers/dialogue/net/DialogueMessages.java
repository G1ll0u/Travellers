package com.jubitus.travellers.dialogue.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class DialogueMessages {

    private DialogueMessages() {
    }

    public static void register() {
        toClient(MsgOpenDialogueSetupGui.class, () -> new MsgOpenDialogueSetupGui.Handler());
        toClient(MsgOpenTravellerDialogue.class, () -> new MsgOpenTravellerDialogue.Handler());
        toServer(MsgDialogueToolQuery.class, MsgDialogueToolQuery.Handler.class);
        toClient(MsgDialogueToolResult.class, () -> new MsgDialogueToolResult.Handler());
        toServer(MsgSubmitDialogueMemory.class, MsgSubmitDialogueMemory.Handler.class);
    }
}
