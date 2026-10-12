package com.jubitus.travellers.dialogue.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jubitus.travellers.dialogue.DialogueContext;
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
     * "tools" field is sent at all in that case.
     */
    public static List<LlmClient.ToolDefinition> currentTools(DialogueContext context) {
        if (!DialogueClientSettings.isToolsEnabled()) return Collections.emptyList();
        List<LlmClient.ToolDefinition> tools = new ArrayList<>();
        tools.add(villageRelationTool());
        return tools;
    }

    private static LlmClient.ToolDefinition villageRelationTool() {
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

        return new LlmClient.ToolDefinition("village_relation",
                "Find out whether two named villages are at war, at peace, or allied with each other.",
                parameters);
    }
}
