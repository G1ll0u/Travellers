package com.jubitus.travellers.server.dialogue.tools;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

import java.util.HashMap;
import java.util.Map;

/**
 * Single lookup from a tool name (as sent by the client in {@code MsgDialogueToolQuery}) to its
 * server-side {@link DialogueToolHandler} — the one place a new tool needs registering (see the
 * tools/memory plan's G-2: adding a third tool later should be mechanical, not a redesign).
 */
public final class DialogueToolRegistry {

    private static final Map<String, DialogueToolHandler> HANDLERS = new HashMap<>();

    static {
        HANDLERS.put("village_relation", new VillageRelationToolHandler());
        HANDLERS.put("recall_memory", new MemoryRecallToolHandler());
    }

    private DialogueToolRegistry() {
    }

    /** Never null - an unknown tool name (a model hallucinating a call, a version mismatch) reads back as text the model can voice in character rather than an error. */
    public static String dispatch(String toolName, EntityTraveller traveller, EntityPlayer player, NBTTagCompound args) {
        DialogueToolHandler handler = HANDLERS.get(toolName);
        if (handler == null) return "That is not something you know how to find out.";
        return handler.handle(traveller, player, args);
    }
}
