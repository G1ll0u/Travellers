package com.jubitus.travellers.dialogue.client;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Which kind of local AI server the player runs; picks the wire format and the default port. */
@SideOnly(Side.CLIENT)
public enum LlmServerType {
    OLLAMA("Ollama", 11434, OllamaProtocol.INSTANCE),
    /** llama.cpp's llama-server, and any other server speaking the OpenAI chat API (LM Studio, koboldcpp...). */
    OPENAI_COMPATIBLE("llama.cpp / OpenAI-compatible", 8080, OpenAiProtocol.INSTANCE);

    /** Shown to the player as-is: product names, never translated. */
    public final String displayName;
    public final int defaultPort;
    final LlmProtocol protocol;

    LlmServerType(String displayName, int defaultPort, LlmProtocol protocol) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.protocol = protocol;
    }

    public LlmServerType next() {
        LlmServerType[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Ollama loads models on demand; an OpenAI-compatible server already holds its model from launch. */
    public boolean supportsPreload() {
        return this == OLLAMA;
    }

    /** Only Ollama takes the context size per request; llama.cpp fixes it when the server starts. */
    public boolean supportsContextSize() {
        return this == OLLAMA;
    }
}
