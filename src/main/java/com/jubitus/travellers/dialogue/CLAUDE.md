# NPC dialogue (local LLM: Ollama or OpenAI-compatible)

Right-clicking a plain (non-hired, non-hostile) `EntityTraveller` opens a dialogue GUI answered in character by a **local AI server on the player's own machine** (Ollama or llama.cpp). Code: `dialogue/` (context, memory, tools), `dialogue/client/` (LLM client, prompt), `dialogue/gui/`. Status and rationale: memory `project_npc_dialogue_system`. Raiders and workers override `processInteract()` themselves.

## Split
- **The LLM call is entirely client-side**; the server never talks to the AI server. `LlmClient` owns connections, threads and errors (plain `HttpURLConnection`, callbacks marshalled to the client thread); each server type's wire format is one `LlmProtocol` (`OllamaProtocol`, `OpenAiProtocol`) — a new server type is one enum entry in `LlmServerType` plus one protocol.
- `TravellersConfig.DIALOGUE.enabled` is a server master switch only; `DialogueClientSettings` (host, model, tuning, detail tier, tools opt-in) is per-player and never synced.
- Sampling options are only sent when set (`ChatOptions.UNSET_*`) so Ollama's defaults apply; `think` defaults to false.
- Closing the GUI mid-request **detaches**, never cancels (cancelling would abort a model load). A real error cancels.

## Context
- The server always builds the full `DialogueContext`; the client tier (MINIMAL/STANDARD/RICH) decides what `DialoguePromptBuilder` writes. `DialogueContextProvider` is the extension seam — one provider per concern, unbudgeted (runs once per click).
- **Grounding**: never raw coordinates or raw numbers — bucketed relative offsets and words. Item display names are the one exception. The prompt says "I don't know" rather than invent.
- Village distance wording scales with the world's own village spacing (Millénaire config, computed server-side).
- Culture flavor text always reads the `"en"` entry (`MixinCultureLoadedLanguagesAccessor`) — `getCultureString` follows the server language.
- Recent travel history reuses `TravelCommon.recentDestinations`, excluding the current destination.
- Occupation and travel motive are deterministic and code-generated (`JobLabelFormatter`, `TravelMotive` rolled once at spawn), never LLM-generated.
- Prompt wording never shows a raw culture id: use `DialogueContext.cultureName(id)` (English adjective, filled server-side by the last provider).
- Shared prompt instructions (no `*action*` narration, humor licence, answer in the player's language stated early and late) apply to every personality.

## Memory and tools
- NPC memory is **global per traveller** (`TravellerMemoryStore`, off by default), persisted through entity NBT only (no record plumbing needed). Only write path: end-of-conversation extraction (`MsgSubmitDialogueMemory`), never retried. "Have we met" is per player and always on (`markMet` in `processInteract`).
- Memory extraction keeps lasting facts about the person only (identity, traits, promises, debts, insults); zero facts is the normal outcome.
- Tool calling is a client-local opt-in. Ollama: a tool call is detected from `message.tool_calls` on every streamed line, never `done_reason`; OpenAI format: calls arrive in fragments, assembled until `finish_reason`. Tool round-trip messages are ephemeral, never persisted. Adding a tool = one entry in `DialogueToolRegistry` + one in `DialogueToolCatalog`.

## Personalities
- Character sheets: `assets/travellers/personalities/<lang>/<id>.md` (Temperament / How they speak / Quirks / Comic mechanism). A personality may have no sound folder.
- Writing guide for sheets: `docs/npc-dialogue-personality-brief.md`.
