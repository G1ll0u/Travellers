package com.jubitus.travellers.client.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jubitus.travellers.server.dialogue.DialogueContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tools offered to the model when the player has opted into tool calling (see
 * {@code docs/npc-dialogue-tools-memory-plan.md} decisions G-1/G-2, Task H2). Adding a tool later is
 * one entry here plus its server-side {@code DialogueToolHandler} registration - the "small,
 * mechanical addition" G-2 asks for, nothing else needs touching.
 */
@SideOnly(Side.CLIENT)
public final class DialogueToolCatalog {

    private DialogueToolCatalog() {
    }

    /**
     * Empty when the player hasn't opted in (see {@link DialogueClientSettings#isToolsEnabled()}) - no
     * "tools" field is sent at all in that case. {@code recall_memory} is additionally gated on
     * {@link DialogueContext#memoryEnabled} - the server-authoritative memory switch (M-6) - so a
     * player never gets offered a tool the server can't actually back.
     */
    public static List<OllamaClient.ToolDefinition> currentTools(DialogueContext context) {
        if (!DialogueClientSettings.isToolsEnabled()) return Collections.emptyList();
        List<OllamaClient.ToolDefinition> tools = new ArrayList<>();
        tools.add(villageRelationTool());
        if (context != null && context.memoryEnabled) tools.add(recallMemoryTool());
        return tools;
    }

    private static OllamaClient.ToolDefinition recallMemoryTool() {
        JsonObject topic = new JsonObject();
        topic.addProperty("type", "string");
        topic.addProperty("description",
                "The subject to try to recall, as phrased in conversation (e.g. \"the player's family\", \"past visits\")");

        JsonObject properties = new JsonObject();
        properties.add("topic", topic);

        JsonArray required = new JsonArray();
        required.add("topic");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);

        return new OllamaClient.ToolDefinition("recall_memory",
                "Try to recall an older memory about the person you're talking to, from a past conversation.",
                parameters);
    }

    private static OllamaClient.ToolDefinition villageRelationTool() {
        JsonObject villageA = new JsonObject();
        villageA.addProperty("type", "string");
        villageA.addProperty("description", "Name of the first village");
        JsonObject villageB = new JsonObject();
        villageB.addProperty("type", "string");
        villageB.addProperty("description", "Name of the second village");

        JsonObject properties = new JsonObject();
        properties.add("villageA", villageA);
        properties.add("villageB", villageB);

        JsonArray required = new JsonArray();
        required.add("villageA");
        required.add("villageB");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);

        return new OllamaClient.ToolDefinition("village_relation",
                "Find out whether two named villages are at war, at peace, or allied with each other.",
                parameters);
    }
}
